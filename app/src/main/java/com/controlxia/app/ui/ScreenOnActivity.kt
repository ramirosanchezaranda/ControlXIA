package com.controlxia.app.ui

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.WindowManager

/**
 * Invisible: solo prende la pantalla (para el saludo, si el teléfono no deja
 * prenderla de otra forma) y se cierra. Queda la pantalla de bloqueo, o la app
 * que estaba si no había bloqueo.
 */
class ScreenOnActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Un instante para que Android termine de prender la pantalla.
        window.decorView.postDelayed({ finish() }, CLOSE_AFTER_MS)
    }

    private companion object {
        const val CLOSE_AFTER_MS = 300L
    }
}
