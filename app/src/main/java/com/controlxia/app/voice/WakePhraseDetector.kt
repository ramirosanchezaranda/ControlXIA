package com.controlxia.app.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import org.vosk.Model
import org.vosk.Recognizer
import java.util.Locale

/**
 * Detecta el nombre del asistente en un flujo de audio (16 kHz, mono, PCM 16).
 * No depende de Android: el motor le pasa lo que lee del micrófono.
 *
 * Escucha con un reconocedor limitado a una gramática mínima (el nombre +
 * "[unk]" para todo lo demás), que es liviano y reconoce el nombre aunque se
 * pronuncie distinto ("Xia", "Sía", "Shía", "Chía").
 *
 * La gramática fuerza todo lo que se oye a "nombre o nada", así que el final
 * de palabras como "fantasía" o "policía" también suena a "xia". Por eso cada
 * detección pasa una segunda verificación: se re-decodifica ese tramo de audio
 * con el vocabulario completo y, si en ese instante se dijo una palabra que
 * empezó claramente antes, era el final de otra palabra y se descarta.
 */
class WakePhraseDetector(
    private val model: Model,
    private val words: List<String>,
    sensitivity: Float,
) : AutoCloseable {

    private val sens = sensitivity.coerceIn(0f, 1f).toDouble()

    /** Confianza mínima de Vosk para aceptar el nombre (más sensible = más permisivo). */
    private val minConfidence = 0.95 - 0.5 * sens

    /** Cuánto antes que el nombre puede empezar la palabra real que se oyó ahí. */
    private val tolerance = 0.05 + 0.10 * sens

    private val grammar = Recognizer(model, SAMPLE_RATE.toFloat(), grammarJson()).apply {
        setWords(true)
    }
    private val ring = AudioRing(SAMPLE_RATE * RING_SECONDS)

    /** Recibe [n] muestras nuevas; true si con ellas se confirmó el nombre. */
    fun accept(chunk: ShortArray, n: Int): Boolean {
        ring.write(chunk, n)
        if (!grammar.acceptWaveForm(chunk, n)) return false
        val hit = findHit(grammar.result) ?: return false
        return isStandaloneWord(hit)
    }

    override fun close() {
        grammar.close()
    }

    /** ["xia", "[unk]"]: el nombre (o la frase) y "cualquier otra cosa". */
    private fun grammarJson(): String =
        JsonArray(listOf(JsonPrimitive(words.joinToString(" ")), JsonPrimitive("[unk]"))).toString()

    private class Word(val text: String, val start: Double, val end: Double, val conf: Double)

    private class Hit(val start: Double, val end: Double)

    private fun parseWords(json: String): List<Word> {
        val result = (runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject)
            ?.get("result") as? JsonArray ?: return emptyList()
        return result.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            Word(
                text = (o["word"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
                start = (o["start"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null,
                end = (o["end"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null,
                conf = (o["conf"] as? JsonPrimitive)?.doubleOrNull ?: 1.0,
            )
        }
    }

    /** Busca el nombre (o la frase completa, en orden) con confianza suficiente. */
    private fun findHit(json: String): Hit? {
        val heard = parseWords(json)
        if (words.isEmpty() || heard.size < words.size) return null
        for (i in 0..heard.size - words.size) {
            val window = heard.subList(i, i + words.size)
            val matches = window.indices.all {
                window[it].text == words[it] && window[it].conf >= minConfidence
            }
            if (matches) return Hit(window.first().start, window.last().end)
        }
        return null
    }

    /**
     * Segunda verificación (ver la doc de la clase): re-decodifica el tramo con
     * el vocabulario completo y descarta si el nombre era el final de otra
     * palabra. Si el tramo ya no está en el buffer, acepta.
     */
    private fun isStandaloneWord(hit: Hit): Boolean {
        val from = ((hit.start - PRE_ROLL_S) * SAMPLE_RATE).toLong().coerceAtLeast(0L)
        val to = ((hit.end + POST_ROLL_S) * SAMPLE_RATE).toLong()
        val segment = ring.slice(from, to) ?: return true
        val offset = from.toDouble() / SAMPLE_RATE
        val wordStart = hit.start - offset
        val wordEnd = hit.end - offset
        val decoded = Recognizer(model, SAMPLE_RATE.toFloat()).use { free ->
            free.setWords(true)
            free.acceptWaveForm(segment, segment.size)
            parseWords(free.finalResult)
        }
        return decoded
            .filter { it.end > wordStart + 0.05 && it.start < wordEnd - 0.05 }
            .none { it.start < wordStart - tolerance }
    }

    /** Últimos segundos de audio, indexados por número de muestra desde el arranque. */
    private class AudioRing(private val capacity: Int) {
        private val data = ShortArray(capacity)
        private var total = 0L

        fun write(src: ShortArray, n: Int) {
            for (i in 0 until n) data[((total + i) % capacity).toInt()] = src[i]
            total += n
        }

        fun slice(from: Long, to: Long): ShortArray? {
            val end = minOf(to, total)
            if (from < total - capacity || from >= end) return null
            return ShortArray((end - from).toInt()) { data[((from + it) % capacity).toInt()] }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val RING_SECONDS = 25
        private const val PRE_ROLL_S = 1.5
        private const val POST_ROLL_S = 0.3

        /** "¡Oye, Xia!" → ["oye", "xia"]: minúsculas y sin signos; las tildes se conservan. */
        fun normalize(phrase: String): List<String> =
            phrase.lowercase(Locale("es"))
                .replace(Regex("[^\\p{L}\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
    }
}
