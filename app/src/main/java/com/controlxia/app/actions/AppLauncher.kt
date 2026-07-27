package com.controlxia.app.actions

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent

/**
 * Resuelve el nombre de una app hablada ("Instagram") a su paquete y la abre.
 *
 * Enumera las apps con actividad de lanzador (permitido con el `<queries>` de
 * MAIN/LAUNCHER del manifest) y hace matching por nombre visible. Lanza con
 * FLAG_ACTIVITY_NEW_TASK porque el disparo viene de un servicio, no de una
 * Activity. Abrir apps con la pantalla bloqueada/segundo plano necesita el
 * permiso "Mostrar sobre otras apps" (ver PermissionManager.canDrawOverlays).
 */
class AppLauncher(private val context: Context) {

    fun open(appName: String): String {
        val query = appName.trim().lowercase()
        if (query.isBlank()) return "No entendí qué app abrir"

        val pm = context.packageManager
        val launchables = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0,
        ).mapNotNull { info ->
            val label = info.loadLabel(pm).toString()
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            label to pkg
        }

        // Coincidencia exacta primero; si no, la que contenga el texto.
        val match = launchables.firstOrNull { it.first.lowercase() == query }
            ?: launchables.firstOrNull { it.first.lowercase().contains(query) }
            ?: return "No encontré la app $appName"

        val launch = pm.getLaunchIntentForPackage(match.second)
            ?: return "No pude abrir ${match.first}"

        // Con el teléfono bloqueado, Android no permite abrir apps de terceros
        // sin desbloquear (por seguridad). Avisamos en vez de fallar en silencio.
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguard.isKeyguardLocked) {
            return "Desbloqueá el teléfono para abrir ${match.first}"
        }

        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launch)
            "Abriendo ${match.first}"
        } catch (e: Exception) {
            "No pude abrir ${match.first}"
        }
    }
}
