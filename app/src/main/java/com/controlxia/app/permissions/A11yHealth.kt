package com.controlxia.app.permissions

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.controlxia.app.R
import com.controlxia.app.XiaApplication
import com.controlxia.app.diagnostics.AgentLog
import com.controlxia.app.service.XiaAccessibilityService
import com.controlxia.app.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Si el control de apps (el servicio de accesibilidad) anda de verdad. Sin él
 * no hay toques, deslizar ni gestos en otras apps. En OPPO, Xiaomi y otros
 * pasa seguido que:
 *
 * - el sistema cierra la app a la fuerza (al limpiar las recientes o por el
 *   ahorro de batería) y Android lo **apaga** solo ([State.OFF]);
 * - la app se cerró con un error y queda "activado" pero **sin correr** (en
 *   Ajustes dice «No funciona», [State.NOT_RUNNING]): hay que apagarlo y
 *   volver a prenderlo.
 *
 * Si anduvo y deja de andar, avisa una vez con una notificación. Con el
 * permiso WRITE_SECURE_SETTINGS (se da una sola vez por adb) lo vuelve a
 * prender solo.
 */
object A11yHealth {

    enum class State { OK, OFF, NOT_RUNNING }

    private const val PREFS = "a11y_health"
    private const val KEY_WAS_ON = "was_on"
    private const val KEY_NOTIFIED = "notified"
    private const val NOTIFICATION_ID = 1003
    /** Lo que tarda Android en conectarlo cuando arranca la app. */
    private const val CHECK_DELAY_MS = 8_000L
    /** Entre apagarlo y prenderlo, para que Android vea el cambio. */
    private const val TOGGLE_MS = 1_500L
    private val TOKEN = Any()

    /** El comando para que se arregle solo (con la PC, una sola vez). */
    const val ADB_GRANT = "adb shell pm grant com.controlxia.app android.permission.WRITE_SECURE_SETTINGS"

    private val main = Handler(Looper.getMainLooper())
    private val _connected = MutableStateFlow(false)

    /** Si está conectado ahora: la interfaz lo observa y se actualiza sola. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun state(context: Context): State = when {
        XiaAccessibilityService.instance != null -> State.OK
        !XiaAccessibilityService.isEnabled(context) -> State.OFF
        else -> State.NOT_RUNNING
    }

    /** Anduvo alguna vez: si ahora no anda, se apagó o se trabó (no es que falte activarlo). */
    fun wasOn(context: Context): Boolean = prefs(context).getBoolean(KEY_WAS_ON, false)

    fun onConnected(context: Context) {
        _connected.value = true
        prefs(context).edit().putBoolean(KEY_WAS_ON, true).putBoolean(KEY_NOTIFIED, false).apply()
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    fun onDisconnected() {
        _connected.value = false
    }

    /** Con el permiso dado por adb lo puede prender solo. */
    fun canRepair(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * Revisa en un rato (para darle tiempo a Android a conectarlo) y, si anduvo
     * y ahora no, lo arregla solo o avisa una vez.
     */
    fun check(context: Context, delayMs: Long = CHECK_DELAY_MS) {
        val app = context.applicationContext
        main.removeCallbacksAndMessages(TOKEN)
        main.postAtTime({ checkNow(app) }, TOKEN, SystemClock.uptimeMillis() + delayMs)
    }

    private fun checkNow(context: Context) {
        val state = state(context)
        if (state == State.OK || !wasOn(context)) return
        if (repair(context, state)) return
        if (prefs(context).getBoolean(KEY_NOTIFIED, false)) return
        AgentLog.add("ACCESIBILIDAD ${problem(state)}")
        notify(context, state)
    }

    /** Lo vuelve a prender; si estaba trabado, primero lo apaga. false si no tiene el permiso. */
    fun repair(context: Context, state: State = state(context)): Boolean {
        if (state == State.OK || !canRepair(context)) return false
        val resolver = context.contentResolver
        val me = ComponentName(context, XiaAccessibilityService::class.java)
        val others = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':')
            .filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != me }
        val withMe = (others + me.flattenToString()).joinToString(":")
        return runCatching {
            if (state == State.NOT_RUNNING) {
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
                main.postDelayed({ enable(context, withMe) }, TOGGLE_MS)
            } else {
                enable(context, withMe)
            }
            AgentLog.add("ACCESIBILIDAD ${problem(state)}: la vuelvo a prender sola")
            true
        }.getOrElse {
            AgentLog.add("ACCESIBILIDAD no pude prenderla sola: ${it.message}")
            false
        }
    }

    private fun enable(context: Context, services: String) {
        runCatching {
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services)
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }
    }

    /** Abre ControlXIA en Accesibilidad (directo en Android 11+; si no, la lista). */
    fun openSettings(context: Context) {
        val component = ComponentName(context, XiaAccessibilityService::class.java).flattenToString()
        val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(details) }
            .recoverCatching { context.startActivity(PermissionManager.accessibilitySettingsIntent()) }
            .recoverCatching { context.startActivity(PermissionManager.appDetailsIntent(context)) }
    }

    /** Qué pasa, en una frase. */
    fun problem(state: State): String = when (state) {
        State.OK -> "el control de apps anda"
        State.OFF -> "el control de apps se apagó"
        State.NOT_RUNNING -> "el control de apps está activado pero no corre"
    }

    /** Los pasos para arreglarlo, según lo que pasa. */
    fun steps(context: Context, state: State): List<String> {
        val brand = PermissionManager.oemName()
        val keep = "Para que no vuelva a pasar: en Recientes, mantené apretada la tarjeta de ControlXIA " +
            "(o tocá ⋮) y elegí «Bloquear»; y en Permisos dejá la batería sin restricción" +
            if (PermissionManager.needsOemSetup()) " y el inicio automático de tu $brand prendido." else "."
        return when (state) {
            State.OK -> emptyList()
            State.OFF -> listOfNotNull(
                if (wasOn(context)) {
                    "Android lo apagó: casi siempre pasa cuando el sistema cierra la app a la fuerza."
                } else {
                    null
                },
                "Tocá «Prender» y activá ControlXIA (está en «Apps descargadas» o «Servicios instalados»).",
                "Si dice «Ajuste restringido» o no te deja: tocá «Info de la app» → menú ⋮ → " +
                    "«Permitir ajustes restringidos», y probá de nuevo.",
                keep,
            )
            State.NOT_RUNNING -> listOf(
                "Está activado pero no corre (en Ajustes puede decir «No funciona»). Pasa cuando la app se cerró de golpe.",
                "Tocá «Arreglar», apagá ControlXIA y volvé a prenderlo.",
                "Si no aparece o no te deja: reiniciá el teléfono y probá de nuevo.",
                keep,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun notify(context: Context, state: State) {
        if (!PermissionManager.hasPostNotifications(context)) return
        val open = PendingIntent.getActivity(
            context, 4,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = "Sin eso no puedo tocar ni deslizar en otras apps, ni andan los gestos. Tocá para arreglarlo."
        val notification = NotificationCompat.Builder(context, XiaApplication.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(problem(state).replaceFirstChar { it.uppercase() })
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
        prefs(context).edit().putBoolean(KEY_NOTIFIED, true).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
