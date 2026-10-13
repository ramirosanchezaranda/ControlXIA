package com.controlxia.app.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import com.controlxia.app.actions.UnlockGate
import java.lang.ref.WeakReference

/**
 * Pantalla invisible que aparece sobre el bloqueo solo para pedirle a Android
 * que lo saque: muestra la huella / el PIN y avisa a [UnlockGate] si se
 * desbloqueó. No muestra nada propio y se cierra sola.
 */
class UnlockActivity : Activity() {

    private var asked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
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
        if (asked) return
        asked = true
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isKeyguardLocked) {
            done(true)
            return
        }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = done(true)
            override fun onDismissCancelled() = done(false)
            override fun onDismissError() = done(false)
        })
    }

    private fun done(success: Boolean) {
        UnlockGate.finished(success)
        finish()
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    companion object {
        private var current: WeakReference<UnlockActivity>? = null

        /** Se dejó de esperar (frenaron el pedido o pasó el tiempo). */
        fun close() {
            current?.get()?.finish()
            current = null
        }
    }
}
