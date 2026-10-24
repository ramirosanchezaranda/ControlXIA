package com.controlxia.app.actions

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.ToneGenerator
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
 * hablar) o dice un texto con la voz del asistente, y lo guarda como M4A (AAC)
 * comprobado, o WAV si el M4A no sale bien.
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
                export(pcm, SAMPLE_RATE)
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
        export(pcm, rate)
    }

    private suspend fun captureUntilSilence(): ShortArray? {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        @SuppressLint("MissingPermission")
        val recorder = AudioRecord(
            // El micrófono común, como una grabadora (el de llamadas en algunos teléfonos graba muy bajo).
            MediaRecorder.AudioSource.MIC,
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

    /**
     * Lo deja listo para mandar: más fuerte si quedó bajo, en M4A (AAC, lo que
     * reproduce cualquier teléfono y WhatsApp) y comprobado; si el M4A no sale
     * bien, en WAV. Antes era OGG/Opus, y en algunos teléfonos llegaba mudo.
     */
    private fun export(pcm: ShortArray, rate: Int): Pair<Audio?, String?> {
        val peak = pcm.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
        if (peak < SILENT_PEAK) {
            AgentLog.add("AUDIO en silencio (pico $peak): no lo mando")
            return null to "El audio quedó en silencio, así que no lo mandé. Probá de nuevo."
        }
        val loud = normalize(pcm, peak)
        val seconds = (loud.size / rate).coerceAtLeast(1)
        encodeAac(resample(loud, rate, AAC_RATE))?.let { file ->
            if (playable(file)) {
                AgentLog.add("AUDIO listo: ${file.name} (${file.length() / 1024} KB, $seconds s, pico $peak)")
                return Audio(file, "audio/mp4", seconds) to null
            }
            AgentLog.add("AUDIO el M4A no se puede reproducir: lo mando como WAV")
            file.delete()
        }
        val wav = writeWav(loud, rate) ?: return null to "No pude guardar el audio."
        AgentLog.add("AUDIO listo: ${wav.name} (${wav.length() / 1024} KB, $seconds s)")
        return Audio(wav, "audio/wav", seconds) to null
    }

    /** Si quedó bajo, lo sube hasta casi el máximo (sin pasarse, para no distorsionar). */
    private fun normalize(pcm: ShortArray, peak: Int): ShortArray {
        val gain = minOf(TARGET_PEAK / peak, MAX_GAIN)
        if (gain <= 1.05) return pcm
        return ShortArray(pcm.size) { (pcm[it] * gain).toInt().coerceIn(-32_767, 32_767).toShort() }
    }

    /** AAC en un M4A con MediaCodec + MediaMuxer. */
    private fun encodeAac(pcm: ShortArray): File? = runCatching {
        val rate = AAC_RATE
        val mime = MediaFormat.MIMETYPE_AUDIO_AAC
        val file = File(audioDir(), "audio_${System.currentTimeMillis()}.m4a")
        val format = MediaFormat.createAudioFormat(mime, rate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        val codec = MediaCodec.createEncoderByType(mime)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
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
        file
    }.onFailure { AgentLog.add("AUDIO no pude codificar: ${it.message}") }.getOrNull()

    /** Que el archivo tenga una pista de audio con datos de verdad. */
    private fun playable(file: File): Boolean = runCatching {
        if (file.length() < 1_024) return@runCatching false
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            if (extractor.trackCount < 1) return@runCatching false
            val audio = extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            extractor.selectTrack(0)
            val buffer = ByteBuffer.allocate(64 * 1_024)
            var frames = 0
            while (frames < MIN_FRAMES && extractor.readSampleData(buffer, 0) > 0) {
                frames++
                extractor.advance()
            }
            audio && frames >= MIN_FRAMES
        } finally {
            extractor.release()
        }
    }.getOrDefault(false)

    /** WAV de 16 bits: lo abre cualquier reproductor. */
    private fun writeWav(pcm: ShortArray, rate: Int): File? = runCatching {
        val file = File(audioDir(), "audio_${System.currentTimeMillis()}.wav")
        val data = pcm.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2)
            putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data)
        }
        val body = ByteBuffer.allocate(data).order(ByteOrder.LITTLE_ENDIAN)
        body.asShortBuffer().put(pcm)
        file.outputStream().use { out ->
            out.write(header.array())
            out.write(body.array())
        }
        file
    }.onFailure { AgentLog.add("AUDIO no pude guardar el WAV: ${it.message}") }.getOrNull()

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
        /** Lo que reproduce cualquier codificador AAC. */
        const val AAC_RATE = 44_100
        /** Más bajo que esto en todo el audio es silencio. */
        const val SILENT_PEAK = 300
        const val TARGET_PEAK = 29_000.0
        const val MAX_GAIN = 8.0
        const val MIN_FRAMES = 5
    }
}
