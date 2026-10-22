package com.controlxia.app.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.controlxia.app.SecurePrefs
import com.controlxia.app.diagnostics.AgentLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * "Con mi voz": para lo privado con el teléfono bloqueado, decís tu
 * contraseña y la app comprueba dos cosas a la vez: que dijiste la contraseña
 * (reconocida con Vosk, sin internet) y que es tu voz (huella de voz con el
 * modelo de hablantes de Vosk, 13 MB, se baja una vez).
 *
 * La contraseña y la huella quedan cifradas en el teléfono y no salen de ahí.
 * Es más cómodo que la huella dactilar pero menos seguro: una grabación muy
 * buena de tu voz diciendo la contraseña podría engañarla. Si no te reconoce,
 * se pide la huella.
 */
object VoiceId {

    /** Lo que se escuchó: el texto y la huella de la voz (si alcanzó el audio). */
    class Sample(val text: String, val print: FloatArray?, val frames: Int)

    /** Resultado de comprobar: [ok] si coinciden la contraseña y la voz. */
    class Check(val heard: String, val passwordOk: Boolean, val similarity: Float?, val threshold: Float) {
        val ok: Boolean get() = passwordOk && (similarity ?: -1f) >= threshold
        fun describe(): String =
            "oí «$heard», contraseña ${if (passwordOk) "bien" else "mal"}, " +
                "parecido ${similarity?.let { "%.2f".format(it) } ?: "—"} (pide ${"%.2f".format(threshold)})"
    }

    // ---- Lo guardado ----

    private fun prefs(context: Context) = SecurePrefs.get(context, "voice_id")

    fun isEnrolled(context: Context): Boolean =
        password(context) != null && prefs(context).getString(KEY_PRINT, null)?.let { VoicePrint.decode(it) } != null

    fun password(context: Context): String? = prefs(context).getString(KEY_PASSWORD, null)?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        verifiedAt = 0L
    }

    /** Guarda la contraseña (ya en palabras del modelo) y la huella promedio de [prints]. */
    fun save(context: Context, password: String, prints: List<FloatArray>) {
        prefs(context).edit()
            .putString(KEY_PASSWORD, password)
            .putString(KEY_PRINT, VoicePrint.encode(VoicePrint.average(prints)))
            .putFloat(KEY_THRESHOLD, VoicePrint.threshold(prints))
            .apply()
        AgentLog.add("VOZ guardada (umbral ${"%.2f".format(VoicePrint.threshold(prints))})")
    }

    // ---- Comprobar ----

    @Volatile
    private var verifiedAt = 0L

    /** Te reconoció hace poco (no se vuelve a pedir en cada paso de una charla). */
    fun verifiedRecently(): Boolean = verifiedAt != 0L && SystemClock.elapsedRealtime() - verifiedAt < VALID_MS

    /** Escucha la contraseña y la compara con lo guardado. null si no hay nada guardado o falla el micrófono. */
    suspend fun verify(context: Context): Check? {
        val password = password(context) ?: return null
        val saved = prefs(context).getString(KEY_PRINT, null)?.let { VoicePrint.decode(it) } ?: return null
        val threshold = prefs(context).getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)
        val sample = listen(context, password) ?: return null
        val check = Check(
            heard = sample.text,
            passwordOk = sample.text == password,
            similarity = sample.print?.let { VoicePrint.similarity(it, saved) },
            threshold = threshold,
        )
        AgentLog.add("VOZ ${check.describe()}")
        if (check.ok) verifiedAt = SystemClock.elapsedRealtime()
        return check
    }

    /**
     * La contraseña en palabras que el modelo de voz conoce ("Girasol azul" →
     * "girasol azul"), o null si alguna no está (hay que elegir otra).
     */
    suspend fun resolvePassword(context: Context, text: String): String? = withContext(Dispatchers.IO) {
        val words = text.trim()
        if (words.split(Regex("\\s+")).size !in 2..6) return@withContext null
        VoskModelStore.enter()
        try {
            val model = runCatching { VoskModelStore.get(context) }.getOrNull() ?: return@withContext null
            WakePhraseResolver.resolve(words) { VoskModelStore.hasWord(model, it) }?.joinToString(" ")
        } finally {
            VoskModelStore.exit()
        }
    }

    /**
     * Graba hasta [MAX_LISTEN_MS] mientras decís la contraseña ([password], en
     * palabras del modelo) y devuelve qué entendió y la huella de la voz.
     */
    @SuppressLint("MissingPermission") // se pide antes de ofrecer esta opción
    suspend fun listen(context: Context, password: String): Sample? = withContext(Dispatchers.IO) {
        val spkDir = speakerDir(context)
        if (!isSpeakerModelReady(context)) return@withContext null
        VoskModelStore.enter()
        var recorder: AudioRecord? = null
        try {
            val model = VoskModelStore.get(context)
            val grammar = JsonArray(listOf(JsonPrimitive(password), JsonPrimitive("[unk]"))).toString()
            SpeakerModel(spkDir.absolutePath).use { speaker ->
                Recognizer(model, SAMPLE_RATE.toFloat(), grammar).use { recognizer ->
                    recognizer.setSpeakerModel(speaker)
                    val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    recorder = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(minBuffer, SAMPLE_RATE),
                    ).takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: return@withContext null
                    val chunk = ShortArray(SAMPLE_RATE / 10)
                    recorder!!.startRecording()
                    val gate = SpeechGate()
                    val started = SystemClock.elapsedRealtime()
                    var spoke = false
                    var result: String? = null
                    while (SystemClock.elapsedRealtime() - started < MAX_LISTEN_MS) {
                        val n = recorder!!.read(chunk, 0, chunk.size)
                        if (n <= 0) break
                        if (gate.update(chunk, n)) spoke = true
                        if (recognizer.acceptWaveForm(chunk, n)) {
                            val r = recognizer.result
                            if (text(r).isNotBlank()) {
                                result = r
                                break
                            }
                        }
                        // Ya habló y volvió el silencio: terminó.
                        if (spoke && !gate.open) break
                    }
                    parse(result ?: recognizer.finalResult)
                }
            }
        } catch (e: IOException) {
            AgentLog.add("VOZ no pude cargar el modelo: ${e.message}")
            null
        } catch (e: Exception) {
            AgentLog.add("VOZ error al escuchar: ${e.message}")
            null
        } finally {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            VoskModelStore.exit()
        }
    }

    private fun text(json: String): String =
        runCatching { (Json.parseToJsonElement(json) as JsonObject)["text"]?.let { (it as JsonPrimitive).contentOrNull } }
            .getOrNull().orEmpty().trim()

    internal fun parse(json: String): Sample? {
        val obj = runCatching { Json.parseToJsonElement(json) as JsonObject }.getOrNull() ?: return null
        val text = (obj["text"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        val print = (obj["spk"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.floatOrNull }?.toFloatArray()
            ?.takeIf { it.isNotEmpty() }
        val frames = (obj["spk_frames"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
        return Sample(text, print, frames)
    }

    // ---- El modelo de hablantes ----

    private fun speakerDir(context: Context) = File(context.noBackupFilesDir, "vosk-spk")

    fun isSpeakerModelReady(context: Context): Boolean = File(speakerDir(context), "version").isFile

    /** Baja e instala el modelo de hablantes (13 MB). [onProgress] de 0 a 1. */
    suspend fun downloadSpeakerModel(context: Context, onProgress: (Float) -> Unit): String? = withContext(Dispatchers.IO) {
        if (isSpeakerModelReady(context)) return@withContext null
        val zip = File(context.cacheDir, "vosk-spk.zip")
        try {
            val connection = (URL(SPK_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: SPK_SIZE
            connection.inputStream.use { input ->
                zip.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        read += n
                        onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            val dir = speakerDir(context)
            val staging = File(context.noBackupFilesDir, "vosk-spk.tmp").apply { deleteRecursively(); mkdirs() }
            val root = staging.canonicalPath + File.separator
            ZipInputStream(zip.inputStream().buffered()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val relative = entry.name.substringAfter('/', "")
                    if (entry.isDirectory || relative.isEmpty()) continue
                    val out = File(staging, relative)
                    if (!out.canonicalPath.startsWith(root)) throw IOException("Archivo inválido en el zip")
                    out.parentFile?.mkdirs()
                    out.outputStream().use { input.copyTo(it) }
                }
            }
            File(staging, "version").writeText(SPK_NAME)
            dir.deleteRecursively()
            if (!staging.renameTo(dir)) throw IOException("No pude instalar el modelo de voz")
            AgentLog.add("VOZ modelo de hablantes instalado")
            null
        } catch (e: Exception) {
            AgentLog.add("VOZ no pude bajar el modelo de hablantes: ${e.message}")
            "No pude bajar el modelo de reconocimiento de voz (13 MB). Revisá la conexión y probá de nuevo."
        } finally {
            zip.delete()
        }
    }

    private const val KEY_PASSWORD = "password"
    private const val KEY_PRINT = "print"
    private const val KEY_THRESHOLD = "threshold"
    private const val DEFAULT_THRESHOLD = 0.6f
    private const val SAMPLE_RATE = 16_000
    private const val MAX_LISTEN_MS = 5_000L
    private const val VALID_MS = 2 * 60_000L
    private const val SPK_NAME = "vosk-model-spk-0.4"
    private const val SPK_URL = "https://alphacephei.com/vosk/models/$SPK_NAME.zip"
    private const val SPK_SIZE = 13_000_000L
}
