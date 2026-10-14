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
 * Detecta el nombre del asistente (y, mientras trabaja, palabras para
 * frenarlo como "pará") en un flujo de audio (16 kHz, mono, PCM 16).
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
 *
 * Las palabras para frenar ([Phrase.alone]) son más exigentes, porque "para"
 * es de las palabras más comunes del español y el teléfono se oye a sí mismo
 * mientras habla: cuentan solo si se dijeron solas ("¡pará!", "basta, basta"),
 * no dentro de una frase ("para mí un café", "para mandar mensajes…"), y si el
 * reconocedor libre oyó esa misma palabra.
 *
 * Para ahorrar batería, con [gated] el reconocedor solo trabaja cuando hay
 * sonido ([SpeechGate]): en silencio no decodifica nada. Al aparecer voz se
 * le pasa también el audio de un rato antes, así no se pierde el principio
 * del nombre. Como el reconocedor ya no ve todo el audio, sus tiempos se
 * traducen a los del micrófono ([toRing]).
 */
class WakePhraseDetector(
    private val model: Model,
    private val phrases: List<Phrase>,
    sensitivity: Float,
    private val gated: Boolean = true,
) : AutoCloseable {

    /** Una frase a detectar; [alone]: solo cuenta dicha sola (ver la doc de la clase). */
    class Phrase(val words: List<String>, val alone: Boolean = false)

    /**
     * Muletillas que pueden acompañar a "pará" ("pará ya", "no, pará", "basta
     * por favor"): están en la gramática para que no cuenten como otra frase.
     */
    private val fillers = if (phrases.any { it.alone }) FILLERS else emptyList()

    /** Todas las palabras de la gramática, sin tildes. */
    private val known = (phrases.flatMap { it.words } + fillers.flatMap { it.split(' ') })
        .map { plain(it) }.toSet()

    private val sens = sensitivity.coerceIn(0f, 1f).toDouble()

    /** Confianza mínima de Vosk para aceptar el nombre (más sensible = más permisivo). */
    private val minConfidence = 0.95 - 0.5 * sens

    /** Cuánto antes que el nombre puede empezar la palabra real que se oyó ahí. */
    private val tolerance = 0.05 + 0.10 * sens

    private val grammar = Recognizer(model, SAMPLE_RATE.toFloat(), grammarJson()).apply {
        setWords(true)
    }
    private val ring = AudioRing(SAMPLE_RATE * RING_SECONDS)
    private val gate = SpeechGate()

    /** Si el reconocedor está recibiendo audio ahora (la compuerta está abierta). */
    private var feeding = false

    /** Muestras que recibió el reconocedor desde que se creó. */
    private var fed = 0L

    /**
     * Tramos de audio continuo que recibió el reconocedor: dónde empieza cada
     * uno para el reconocedor y para el micrófono. Sirve para traducir tiempos.
     */
    private val segments = ArrayDeque<LongArray>()

    /** Bloques que se saltearon por silencio (para el registro de batería). */
    var skippedChunks = 0L
        private set

    /** Recibe [n] muestras nuevas; true si con ellas se confirmó alguna frase. */
    fun accept(chunk: ShortArray, n: Int): Boolean = detect(chunk, n) >= 0

    /** Una palabra de frenar oída que espera el audio de después para confirmarse. */
    private class PendingStop(val index: Int, val hit: Hit, val heard: List<Word>)

    private var pendingStop: PendingStop? = null

    /** Recibe [n] muestras nuevas; el índice de la frase confirmada, o -1. */
    fun detect(chunk: ShortArray, n: Int): Int {
        ring.write(chunk, n)
        pendingStop?.let { stop ->
            if (ring.seconds < stop.hit.end + ALONE_GAP_S + TAIL_S) return@let
            pendingStop = null
            if (isSaidAlone(stop.hit, phrases[stop.index], stop.heard)) return stop.index
            // No era "pará" solo: puede ser "Beto, para que…", que sí lo despierta.
            findWake(stop.heard)?.let { return it }
        }
        if (gated && !gate.update(chunk, n)) {
            skippedChunks++
            if (!feeding) return -1
            // Volvió el silencio: lo que quedaba a medio reconocer se cierra ahora.
            feeding = false
            return handle(grammar.finalResult)
        }
        if (!feeding) {
            feeding = true
            // Lo de un rato antes, para no perder el principio de la palabra.
            val now = ring.total - n
            val from = maxOf(now - PRE_GATE_SAMPLES, ring.total - ring.capacity, 0L)
            segments.addLast(longArrayOf(fed, from))
            while (segments.size > MAX_SEGMENTS) segments.removeFirst()
            ring.slice(from, now)?.takeIf { it.isNotEmpty() }?.let { before ->
                fed += before.size
                if (grammar.acceptWaveForm(before, before.size)) {
                    val index = handle(grammar.result)
                    if (index >= 0) return index
                }
            }
        }
        fed += n
        if (!grammar.acceptWaveForm(chunk, n)) return -1
        return handle(grammar.result)
    }

    /** Un resultado de la gramática: ¿se dijo alguna frase? */
    private fun handle(json: String): Int {
        val heard = parseWords(json, ::toRing)
        // Primero las de frenar ("Beto, pará" frena, no despierta). Se confirman
        // un rato después: la gramática avisa apenas termina la palabra, antes de
        // saber si la frase sigue ("ya basta de lluvia…").
        for (index in phrases.indices.filter { phrases[it].alone }) {
            val hit = findHit(heard, phrases[index].words) ?: continue
            pendingStop = PendingStop(index, hit, heard)
            return -1
        }
        return findWake(heard) ?: -1
    }

    /**
     * Segundos del reconocedor → segundos del micrófono. Sin compuerta son
     * iguales; con compuerta el reconocedor se saltea el silencio.
     */
    private fun toRing(seconds: Double): Double {
        if (!gated) return seconds
        val sample = (seconds * SAMPLE_RATE).toLong()
        val segment = segments.lastOrNull { it[0] <= sample } ?: return seconds
        return (segment[1] + (sample - segment[0])).toDouble() / SAMPLE_RATE
    }

    /** El nombre (u otra frase que no es de frenar) en lo que oyó la gramática. */
    private fun findWake(heard: List<Word>): Int? =
        phrases.indices.filter { !phrases[it].alone }.firstOrNull { index ->
            findHit(heard, phrases[index].words)?.let(::isStandaloneWord) == true
        }

    override fun close() {
        grammar.close()
    }

    /** ["xia", "pará", "[unk]"]: cada frase y "cualquier otra cosa". */
    private fun grammarJson(): String =
        JsonArray(
            phrases.map { JsonPrimitive(it.words.joinToString(" ")) } +
                fillers.map { JsonPrimitive(it) } + JsonPrimitive("[unk]")
        ).toString()

    private class Word(val text: String, val start: Double, val end: Double, val conf: Double)

    private class Hit(val start: Double, val end: Double)

    private fun parseWords(json: String, time: (Double) -> Double = { it }): List<Word> {
        val result = (runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject)
            ?.get("result") as? JsonArray ?: return emptyList()
        return result.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            Word(
                text = (o["word"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
                start = time((o["start"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null),
                end = time((o["end"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null),
                conf = (o["conf"] as? JsonPrimitive)?.doubleOrNull ?: 1.0,
            )
        }
    }

    /** Busca la frase completa, en orden, con confianza suficiente. */
    private fun findHit(heard: List<Word>, words: List<String>): Hit? {
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

    /**
     * Para las palabras de frenar: el reconocedor libre oyó algo parecido a esa
     * palabra (con voces reales a veces entiende "pasa" por "basta") y ninguna
     * otra palabra pegada antes o después: "para mí" no frena. Lo que la
     * gramática ya reconoció (el nombre, "ya", "por favor") no cuenta como pegado.
     * La gramática sola no alcanza: descarta lo que no conoce en vez de marcarlo.
     */
    private fun isSaidAlone(hit: Hit, phrase: Phrase, heard: List<Word>): Boolean {
        val from = ((hit.start - PRE_ROLL_S) * SAMPLE_RATE).toLong().coerceAtLeast(0L)
        val to = ((hit.end + ALONE_GAP_S + TAIL_S) * SAMPLE_RATE).toLong()
        val segment = ring.slice(from, to) ?: return false
        val offset = from.toDouble() / SAMPLE_RATE
        val start = hit.start - offset
        val end = hit.end - offset
        val decoded = Recognizer(model, SAMPLE_RATE.toFloat()).use { free ->
            free.setWords(true)
            free.acceptWaveForm(segment, segment.size)
            parseWords(free.finalResult)
        }
        val recognizedAbs = heard.filter { plain(it.text) in known }.map { it.start to it.end }
        if (speechAround(hit, recognizedAbs)) return false
        val recognized = recognizedAbs.map { (s, e) -> (s - offset) to (e - offset) }
        fun overlaps(w: Word, s: Double, e: Double) = w.end > s + 0.05 && w.start < e - 0.05
        val same = decoded.filter { overlaps(it, start, end) }
        // Una palabra más larga que la contiene: "silenciosamente", "bastante".
        if (same.any { it.start < start - LONGER_S || it.end > end + LONGER_S }) return false
        val likeness = same
            .maxOfOrNull { d -> phrase.words.maxOf { similarity(plain(d.text), plain(it)) } } ?: 0.0
        val neighbors = decoded.any { w ->
            overlaps(w, start - ALONE_GAP_S, end + ALONE_GAP_S) &&
                recognized.none { (s, e) -> overlaps(w, s, e) }
        }
        return likeness >= MIN_LIKENESS && !neighbors
    }

    /**
     * Hay voz justo antes o después de la palabra que no es algo reconocido (el
     * nombre, "ya", "por favor"): era parte de una frase. Complementa al
     * reconocedor libre, que a veces se saltea palabras dichas sin fuerza.
     */
    private fun speechAround(hit: Hit, recognized: List<Pair<Double, Double>>): Boolean {
        val word = rms(hit.start, hit.end) ?: return false
        val windows = generateSequence(hit.start - ALONE_GAP_S) { it + ENERGY_WINDOW_S }
            .takeWhile { it < hit.end + ALONE_GAP_S }
            .filter { it + ENERGY_WINDOW_S <= hit.start - 0.05 || it >= hit.end + 0.05 }
            .filter { w -> recognized.none { (s, e) -> w + ENERGY_WINDOW_S > s && w < e } }
            .toList()
        val levels = windows.mapNotNull { rms(it, it + ENERGY_WINDOW_S) }
        // Umbral relativo a la palabra y al ruido de fondo del momento.
        val floor = levels.minOrNull() ?: 0.0
        val threshold = maxOf(word * SPEECH_RATIO, floor * 3)
        return levels.any { it > threshold }
    }

    private fun rms(from: Double, to: Double): Double? {
        val samples = ring.slice((from * SAMPLE_RATE).toLong().coerceAtLeast(0L), (to * SAMPLE_RATE).toLong())
            ?: return null
        if (samples.isEmpty()) return null
        return kotlin.math.sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
    }

    /** Últimos segundos de audio, indexados por número de muestra desde el arranque. */
    private class AudioRing(val capacity: Int) {
        private val data = ShortArray(capacity)

        /** Muestras recibidas desde el arranque. */
        var total = 0L
            private set

        /** Segundos de audio recibidos desde el arranque. */
        val seconds: Double get() = total.toDouble() / SAMPLE_RATE

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

        /** Audio de antes que se le pasa al reconocedor cuando aparece voz. */
        private const val PRE_GATE_SAMPLES = SAMPLE_RATE.toLong()
        private const val MAX_SEGMENTS = 256
        private const val PRE_ROLL_S = 1.5
        private const val POST_ROLL_S = 0.3

        /** Cuánto silencio tiene que haber alrededor de "pará" para que cuente sola. */
        private const val ALONE_GAP_S = 0.6

        /**
         * Audio de más después del silencio: una palabra que empieza justo al final
         * ("basta de llu…") necesita terminar para que el reconocedor libre la oiga.
         */
        private const val TAIL_S = 0.6

        /** Ventanas para medir si hay voz alrededor, y desde qué nivel (relativo a la palabra) es voz. */
        private const val ENERGY_WINDOW_S = 0.1
        private const val SPEECH_RATIO = 0.2

        /** Qué tan parecido a "basta" tiene que ser lo que oyó el reconocedor libre. */
        private const val MIN_LIKENESS = 0.6

        /** Cuánto puede pasarse la palabra oída de los bordes de "pará" sin ser otra más larga. */
        private const val LONGER_S = 0.25

        private val FILLERS = listOf("ya", "no", "che", "bueno", "por favor")

        /** 1 = iguales, 0 = nada que ver (distancia de edición). */
        private fun similarity(a: String, b: String): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                }
                prev = cur
            }
            return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
        }

        private fun plain(word: String): String =
            java.text.Normalizer.normalize(word, java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")

        /** "¡Oye, Xia!" → ["oye", "xia"]: minúsculas y sin signos; las tildes se conservan. */
        fun normalize(phrase: String): List<String> =
            phrase.lowercase(Locale("es"))
                .replace(Regex("[^\\p{L}\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
    }
}
