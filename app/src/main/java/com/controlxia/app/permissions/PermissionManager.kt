package com.controlxia.app.permissions

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Estado y navegación de los permisos que la app necesita para funcionar
 * de forma confiable con la pantalla bloqueada.
 */
object PermissionManager {

    fun hasRecordAudio(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun hasPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun hasContacts(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun hasCallPhone(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

    fun hasAnswerCalls(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED

    /** Contactos, SMS y llamadas: se piden juntos. */
    val PHONE_PERMISSIONS = arrayOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.ANSWER_PHONE_CALLS,
    )

    fun hasPhonePermissions(context: Context): Boolean =
        PHONE_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    fun hasCamera(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    fun hasSendSms(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /** Ajustes de accesibilidad, donde se activa el "Control de apps". */
    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Ajustes de acceso a notificaciones, para leer y responder mensajes. */
    fun notificationAccessIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Sin esta exención, Doze frena el servicio tras ~1 h de pantalla apagada. */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)

    /** Diálogo del sistema para pedir la exención de batería. */
    fun batteryExemptionIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )

    /** Pantalla de detalles de la app, como fallback general. */
    fun appDetailsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )

    /**
     * "Mostrar sobre otras apps". Habilita abrir apps y lanzar acciones con UI
     * desde el servicio en segundo plano / pantalla bloqueada (background
     * activity start). Opcional: sin esto, abrir apps solo anda con la app en
     * primer plano; las alarmas/temporizadores igual funcionan (usan SKIP_UI).
     */
    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    /**
     * true si Android va a dejar abrir una pantalla ahora. Desde segundo plano
     * (el servicio con el teléfono en otra app) solo se puede con "Mostrar
     * sobre otras apps"; si no, `startActivity` se ignora en silencio y la app
     * diría "listo" sin haber hecho nada.
     */
    fun canLaunchActivities(context: Context): Boolean {
        if (canDrawOverlays(context)) return true
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    fun overlayPermissionIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )

    // ---------------------------------------------------------------------
    // OEM killers: Xiaomi, Samsung, Huawei, etc. agregan sus propios ajustes
    // de autoarranque/batería por encima de Android. No hay API para saber si
    // están concedidos: solo podemos llevar al usuario a la pantalla correcta.
    // Referencia por fabricante: https://dontkillmyapp.com
    // ---------------------------------------------------------------------

    /** true si el fabricante tiene ajustes propios que hay que tocar a mano. */
    fun needsOemSetup(): Boolean = oemSettingsComponents().isNotEmpty()

    fun oemName(): String = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }

    /**
     * Devuelve el primer intent de ajustes OEM que exista en este equipo,
     * o null si el fabricante no necesita configuración extra.
     */
    fun oemSettingsIntent(context: Context): Intent? {
        for (component in oemSettingsComponents()) {
            val intent = Intent().setComponent(component)
            if (context.packageManager.resolveActivity(intent, 0) != null) {
                return intent
            }
        }
        return null
    }

    private fun oemSettingsComponents(): List<ComponentName> =
        when (Build.MANUFACTURER.lowercase()) {
            "xiaomi", "redmi", "poco" -> listOf(
                // Autostart de MIUI
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            )
            "samsung" -> listOf(
                // "Apps que no se suspenderán" / cuidado del dispositivo
                ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.BatteryActivity"
                ),
                ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"
                )
            )
            "huawei", "honor" -> listOf(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            )
            "oppo", "realme" -> listOf(
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
            )
            "vivo", "iqoo" -> listOf(
                ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )
            )
            "oneplus" -> listOf(
                ComponentName(
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                )
            )
            else -> emptyList()
        }
}
