package com.controlxia.app.actions

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.controlxia.app.diagnostics.AgentLog
import com.controlxia.app.permissions.PermissionManager
import com.controlxia.app.voice.VoiceBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Audios para mandar por WhatsApp: graba la voz del usuario (hasta que deja de
 * hablar) o dice un texto con la voz del asistente, y lo guarda como OGG/Opus,
 * el formato de las notas de voz de WhatsApp (Android 10+; si el teléfono no
 * tiene codificador Opus, M4A/AAC).
 */
class AudioMessage(private val context: Context) {

    /** Un audio listo para compartir. */
    class Audio(val file: File, val mime: String, val seconds: Int)

    /**
     * Graba al usuario: avisa con un tono, y corta solo después de [SILENCE_MS]
     * de silencio (o a los [MAX_SECONDS]). Devuelve null y el motivo si no se
     * escuchó nada.
     */
    @SuppressLint("MissingPermission")
    suspend fun record(): Pair<Audio?, String?> {
        if (!PermissionManager.hasRecordAudio(context)) return null to "Falta el permiso de micrófono."
        VoiceBridge.say("Hablá después del tono. Cuando termines, quedate callado.")
        return VoiceBridge.withMicrophone {
            withContext(Dispatchers.IO) {
                beep()
                val pcm = captureUntilSilence() ?: return@withContext null to "No escuché nada, así que no grabé el audio."
                AgentLog.add("AUDIO grabado: ${pcm.size / SAMPLE_RATE} s")
                beep()
                encode(pcm, SAMPLE_RATE) to null
            }
        }
    }

    /** Un audio con [text] dicho por la voz del asistente. */
    suspend fun synthesize(text: String): Pair<Audio?, String?> = withContext(Dispatchers.IO) {
        val wav = File(audioDir(), "tts_${System.currentTimeMillis()}.wav")
        val ok = synthesizeToFile(text, wav)
        if (!ok || !wav.exists()) return@withContext null to "No pude generar el audio."
        val (pcm, rate) = readWav(wav) ?: return@withContext null to "No pude leer el audio generado."
        wav.delete()
        encode(resample(pcm, rate, SAMPLE_RATE), SAMPLE_RATE) to null
    }

    private suspend fun captureUntilSilence(): ShortArray? {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        @SuppressLint("MissingPermission")
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, // con supresión de ruido, como una llamada
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, SAMPLE_RATE),
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return null
        }
        val out = ShortArray(SAMPLE_RATE * MAX_SECONDS)
        var total = 0
        val frame = ShortArray(SAMPLE_RATE / 10) // 100 ms
        var floor = Double.MAX_VALUE
        var started = false
        var startedAt = 0
        var lastVoice = 0
        try {
            recorder.startRecording()
            while (total + frame.size <= out.size) {
                kotlin.coroutines.coroutineContext.ensureActive() // "pará" corta la grabación
                val n = recorder.read(frame, 0, frame.size)
                if (n <= 0) break
                System.arraycopy(frame, 0, out, total, n)
                total += n
                val level = kotlin.math.sqrt((0 until n).sumOf { frame[it].toDouble() * frame[it] } / n)
                if (total <= SAMPLE_RATE / 2) floor = minOf(floor, level) // el primer medio segundo: ruido de fondo
                val voice = level > maxOf(floor * 3, MIN_VOICE_RMS)
                if (voice) {
                    if (!started) startedAt = total - n
                    started = true
                    lastVoice = total
                }
                if (!started && total > SAMPLE_RATE * NO_SPEECH_SECONDS) return null
                if (started && total - lastVoice > SAMPLE_RATE * SILENCE_MS / 1000) break
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        if (!started) return null
        // Desde un poco antes de la primera palabra hasta un poco después de la última.
        val from = (startedAt - SAMPLE_RATE / 5).coerceAtLeast(0)
        val to = (lastVoice + SAMPLE_RATE / 3).coerceAtMost(total)
        return out.copyOfRange(from, to)
    }

    private fun beep() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            Thread.sleep(200)
            tone.release()
        }
    }

    private suspend fun synthesizeToFile(text: String, file: File): Boolean {
        var engine: TextToSpeech? = null
        val ready = withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont ->
                engine = TextToSpeech(context) { status -> if (cont.isActive) cont.resume(status == TextToSpeech.SUCCESS) }
            }
        } ?: false
        val tts = engine ?: return false
        try {
            if (!ready) return false
            listOf(Locale("es", "AR"), Locale("es", "US"), Locale("es")).firstOrNull {
                tts.setLanguage(it) >= TextToSpeech.LANG_AVAILABLE
            }
            return withTimeoutOrNull(20_000) {
                suspendCancellableCoroutine { cont ->
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) { if (cont.isActive) cont.resume(true) }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { if (cont.isActive) cont.resume(false) }
                    })
                    if (tts.synthesizeToFile(text, null, file, "audio_message") != TextToSpeech.SUCCESS) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
            } ?: false
        } finally {
            tts.shutdown()
        }
    }

    /** PCM 16 bits de un WAV, en mono, con su frecuencia de muestreo. */
    private fun readWav(file: File): Pair<ShortArray, Int>? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(12)
            raf.readFully(header)
            var rate = 0
            var channels = 1
            var data: ByteArray? = null
            while (raf.filePointer < raf.length()) {
                val chunk = ByteArray(8)
                raf.readFully(chunk)
                val id = String(chunk, 0, 4)
                val size = ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (id == "fmt ") {
                    val fmt = ByteArray(size)
                    raf.readFully(fmt)
                    val b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    channels = b.getShort(2).toInt()
                    rate = b.getInt(4)
                } else if (id == "data") {
                    val bytes = ByteArray(minOf(size.toLong(), raf.length() - raf.filePointer).toInt())
                    raf.readFully(bytes)
                    data = bytes
                    break
                } else {
                    raf.seek(raf.filePointer + size)
                }
            }
            val bytes = data ?: return null
            val samples = ShortArray(bytes.size / 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
            val mono = if (channels <= 1) samples else ShortArray(samples.size / channels) { samples[it * channels] }
            mono to rate
        }
    }.getOrNull()

    private fun resample(pcm: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || from <= 0) return pcm
        val out = ShortArray((pcm.size.toLong() * to / from).toInt())
        for (i in out.indices) {
            val pos = i.toDouble() * from / to
            val a = pos.toInt().coerceAtMost(pcm.size - 1)
            val b = (a + 1).coerceAtMost(pcm.size - 1)
            val t = pos - a
            out[i] = (pcm[a] * (1 - t) + pcm[b] * t).toInt().toShort()
        }
        return out
    }

    /** Codifica a OGG/Opus (o M4A/AAC) con MediaCodec + MediaMuxer. */
    private fun encode(pcm: ShortArray, rate: Int): Audio? = runCatching {
        val opus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasEncoder(MediaFormat.MIMETYPE_AUDIO_OPUS)
        val mime = if (opus) MediaFormat.MIMETYPE_AUDIO_OPUS else MediaFormat.MIMETYPE_AUDIO_AAC
        val file = File(audioDir(), "audio_${System.currentTimeMillis()}.${if (opus) "ogg" else "m4a"}")
        val format = MediaFormat.createAudioFormat(mime, rate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, if (opus) 24_000 else 48_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            if (!opus) setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        val codec = MediaCodec.createEncoderByType(mime)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(
            file.path,
            if (opus) MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        )
        val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(pcm)
        val input = bytes.array()
        var offset = 0
        var inputDone = false
        var track = -1
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val size = minOf(buffer.capacity(), input.size - offset)
                        val timeUs = offset / 2L * 1_000_000L / rate
                        if (size <= 0) {
                            codec.queueInputBuffer(index, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buffer.clear()
                            buffer.put(input, offset, size)
                            codec.queueInputBuffer(index, 0, size, timeUs, 0)
                            offset += size
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    out >= 0 -> {
                        val buffer = codec.getOutputBuffer(out)!!
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (info.size > 0 && !config && track >= 0) muxer.writeSampleData(track, buffer, info)
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { if (track >= 0) muxer.stop() }
            muxer.release()
        }
        AgentLog.add("AUDIO codificado ${file.name} (${file.length() / 1024} KB)")
        Audio(file, if (opus) "audio/ogg" else "audio/mp4", (pcm.size / rate).coerceAtLeast(1))
    }.onFailure { AgentLog.add("AUDIO no pude codificar: ${it.message}") }.getOrNull()

    private fun hasEncoder(mime: String): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }

    private fun audioDir(): File = File(context.cacheDir, "audio").apply {
        mkdirs()
        // Solo el último par de audios: los viejos ya se mandaron.
        listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_SECONDS = 120
        const val NO_SPEECH_SECONDS = 7
        const val SILENCE_MS = 1_800
        const val MIN_VOICE_RMS = 600.0
    }
}
