package com.controlxia.app.actions

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import com.controlxia.app.permissions.PermissionManager
import java.text.Normalizer

/**
 * Resuelve el nombre de una app hablada ("Instagram") a su paquete y la abre.
 *
 * Enumera las apps con actividad de lanzador (permitido con el `<queries>` de
 * MAIN/LAUNCHER del manifest) y hace matching por nombre visible, sin tildes ni
 * mayúsculas ("cámara" = "Camara"). Lanza con FLAG_ACTIVITY_NEW_TASK porque el
 * disparo viene de un servicio, no de una Activity. Abrir apps desde segundo
 * plano necesita el permiso "Mostrar sobre otras apps".
 */
class AppLauncher(private val context: Context) {

    fun open(appName: String): String {
        val query = normalize(appName)
        if (query.isBlank()) return "No entendí qué app abrir"

        val pm = context.packageManager
        val launchables = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0,
        ).mapNotNull { info ->
            val label = info.loadLabel(pm).toString()
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            Triple(label, normalize(label), pkg)
        }

        // Coincidencia exacta; si no, la más corta que contenga lo pedido
        // ("maps" → "Maps" antes que "Maps Go"); si no, la que esté contenida
        // en lo pedido ("whatsapp de trabajo" → "WhatsApp").
        val match = launchables.firstOrNull { it.second == query }
            ?: launchables.filter { it.second.contains(query) }.minByOrNull { it.second.length }
            ?: launchables.filter { it.second.length >= 3 && query.contains(it.second) }
                .maxByOrNull { it.second.length }
            ?: return "No encontré la app $appName"
        val label = match.first

        val launch = pm.getLaunchIntentForPackage(match.third)
            ?: return "No pude abrir $label"

        // Con el teléfono bloqueado, Android no permite abrir apps de terceros
        // sin desbloquear (por seguridad). Avisamos en vez de fallar en silencio.
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguard.isKeyguardLocked) {
            return "Desbloqueá el teléfono para abrir $label"
        }
        if (!PermissionManager.canLaunchActivities(context)) {
            return "Para abrir apps necesito el permiso Mostrar sobre otras apps. Activalo en ControlXIA."
        }

        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launch)
            "Abriendo $label"
        } catch (e: Exception) {
            "No pude abrir $label"
        }
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text.trim().lowercase(), Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .replace(SPACES, " ")

    private companion object {
        val DIACRITICS = Regex("\\p{Mn}+")
        val SPACES = Regex("\\s+")
    }
}
