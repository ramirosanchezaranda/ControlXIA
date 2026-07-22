package com.controlxia.app.permissions

import android.Manifest
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
