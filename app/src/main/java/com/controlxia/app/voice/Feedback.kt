package com.controlxia.app.voice

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Los avisos cortos sin voz: vibrar y el tono de "te escucho". */
class Feedback(context: Context) {

    private val appContext = context.applicationContext
    private var tone: ToneGenerator? = null

    @Suppress("DEPRECATION")
    fun vibrate(millis: Long = 120) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    /** Tono corto de "te escucho" ([soft]: el más suave, para seguir una conversación). */
    fun beep(soft: Boolean = false) {
        runCatching {
            val generator = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME).also { tone = it }
            generator.startTone(if (soft) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP, LENGTH_MS)
        }
    }

    fun release() {
        tone?.release()
        tone = null
    }

    private companion object {
        const val VOLUME = 60
        const val LENGTH_MS = 90
    }
}
