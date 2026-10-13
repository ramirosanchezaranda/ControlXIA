package com.controlxia.app.actions

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.controlxia.app.diagnostics.AgentLog
import com.controlxia.app.permissions.PermissionManager
import com.controlxia.app.ui.UnlockActivity
import com.controlxia.app.voice.VoiceBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Usar apps con el teléfono bloqueado. Android no deja que otra app maneje la
 * pantalla sobre el bloqueo (por seguridad, ni el asistente de Google puede),
 * así que cuando un pedido necesita la pantalla —mandar un WhatsApp, abrir una
 * app— el asistente pide desbloquear (huella, cara o PIN: un toque) y sigue
 * solo con el pedido apenas se desbloquea. Lo que no necesita la pantalla
 * (SMS, llamadas, alarmas, leer y responder notificaciones) anda bloqueado.
 */
object UnlockGate {

    /** Avisa al servicio de voz que ya está desbloqueado (deja la pantalla sobre el bloqueo). */
    @Volatile
    var onUnlocked: (() -> Unit)? = null

    @Volatile
    private var waiting: CompletableDeferred<Boolean>? = null

    fun isLocked(context: Context): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    /**
     * Si está bloqueado, pide desbloquearlo ([why]: "para abrir WhatsApp") y
     * espera. true = ya se puede usar la pantalla.
     */
    suspend fun unlock(context: Context, why: String): Boolean {
        if (!isLocked(context)) return true
        if (!PermissionManager.canLaunchActivities(context)) {
            AgentLog.add("DESBLOQUEO imposible: falta el permiso Mostrar sobre otras apps")
            return false
        }
        val result = CompletableDeferred<Boolean>()
        waiting?.complete(false)
        waiting = result
        AgentLog.add("DESBLOQUEO pedido $why")
        try {
            val started = withContext(Dispatchers.Main) {
                runCatching {
                    context.startActivity(
                        Intent(context, UnlockActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                    )
                }.isSuccess
            }
            if (!started) return false
            val unlocked = coroutineScope {
                val speech = launch { VoiceBridge.say("Desbloqueá el teléfono $why.") }
                val ok = withTimeoutOrNull(WAIT_MS) { result.await() } ?: false
                speech.cancel()
                ok
            } || !isLocked(context)
            AgentLog.add(if (unlocked) "DESBLOQUEADO: sigo" else "NO se desbloqueó")
            if (unlocked) {
                onUnlocked?.invoke()
                // Que el bloqueo termine de irse antes de abrir apps.
                delay(SETTLE_MS)
            }
            return unlocked
        } finally {
            if (waiting === result) waiting = null
            if (!result.isCompleted) Handler(Looper.getMainLooper()).post { UnlockActivity.close() }
        }
    }

    /** Lo llama [UnlockActivity] cuando Android avisa si se desbloqueó o no. */
    fun finished(success: Boolean) {
        waiting?.complete(success)
    }

    /** Lo que se dice si no se desbloqueó a tiempo. */
    const val NOT_UNLOCKED = "No se desbloqueó el teléfono, así que lo dejé ahí. Pedímelo de nuevo cuando quieras."

    /** Si el texto de una acción es "está bloqueado" (para reintentar después de desbloquear). */
    fun isLockedMessage(text: String?): Boolean =
        text != null && (text.startsWith("Desbloqueá el teléfono") || text.startsWith("El teléfono está bloqueado"))

    private const val WAIT_MS = 30_000L
    private const val SETTLE_MS = 600L
}
