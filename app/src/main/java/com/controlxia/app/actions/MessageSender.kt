package com.controlxia.app.actions

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.view.accessibility.AccessibilityNodeInfo
import com.controlxia.app.diagnostics.AgentLog
import com.controlxia.app.permissions.PermissionManager
import com.controlxia.app.service.XiaAccessibilityService
import kotlinx.coroutines.delay

/**
 * Manda mensajes nuevos a un contacto. Se llama recién después de que el
 * usuario confirmó por voz (ver CommandProcessor).
 *
 * - SMS: directo, sin abrir nada (permiso SEND_SMS).
 * - WhatsApp: abre el chat con el texto escrito y, si el control de apps está
 *   activo, toca "Enviar"; si no, deja el mensaje listo para que lo mande el
 *   usuario.
 */
class MessageSender(private val context: Context) {

    fun sendSms(contact: Contacts.Contact, text: String): String {
        if (!PermissionManager.hasSendSms(context)) {
            return "Necesito permiso para mandar SMS: activalo en ControlXIA."
        }
        return try {
            @Suppress("DEPRECATION")
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }
            val parts = sms.divideMessage(text)
            sms.sendMultipartTextMessage(contact.phone, null, parts, null, null)
            AgentLog.add("SMS enviado a ${contact.name} (${contact.phone})")
            "Listo, le mandé un SMS a ${contact.name}."
        } catch (e: Exception) {
            "No pude mandar el SMS: ${e.message ?: "error del sistema"}."
        }
    }

    suspend fun sendWhatsApp(contact: Contacts.Contact, number: String, text: String): String {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguard.isKeyguardLocked) return "Desbloqueá el teléfono para mandar por WhatsApp."
        if (!PermissionManager.canLaunchActivities(context)) {
            return "Para abrir WhatsApp necesito el permiso Mostrar sobre otras apps."
        }
        AgentLog.add("WHATSAPP abrir chat de ${contact.name} (${contact.phone} → $number)")
        val query = "phone=$number&text=${Uri.encode(text)}"
        // El esquema propio de WhatsApp abre el chat directo en todas las
        // versiones; el enlace web queda de respaldo.
        val links = listOf("whatsapp://send?$query", "https://api.whatsapp.com/send?$query")
        val opened = links.any { link ->
            WHATSAPP_PACKAGES.any { pkg ->
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(link))
                            .setPackage(pkg)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure { AgentLog.add("WHATSAPP $pkg no abrió ${link.substringBefore('?')}: ${it.javaClass.simpleName}") }
                    .isSuccess
            }
        }
        if (!opened) return "No pude abrir WhatsApp en este teléfono."

        // Con el control de apps activo, tocar "Enviar" cuando aparezca el chat.
        val service = XiaAccessibilityService.instance
        if (service == null) {
            AgentLog.add("WHATSAPP sin control de apps: el usuario tiene que tocar enviar")
            return "Te dejé el mensaje para ${contact.name} escrito en WhatsApp: tocá enviar."
        }
        var lastPackage: String? = null
        repeat(16) {
            delay(500)
            val root = service.rootInActiveWindow ?: return@repeat
            lastPackage = root.packageName?.toString()
            if (lastPackage !in WHATSAPP_PACKAGES) return@repeat
            if (INVALID.any { root.findAccessibilityNodeInfosByText(it).isNotEmpty() }) {
                AgentLog.add("WHATSAPP dice que el número $number no es válido")
                return "WhatsApp dice que el número de ${contact.name} (${contact.phone}) no es " +
                    "válido. ¿Querés que lo mande por SMS?"
            }
            val send = findSendButton(root)
            if (send != null && send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                AgentLog.add("WHATSAPP enviado")
                return "Listo, le mandé el WhatsApp a ${contact.name}."
            }
        }
        AgentLog.add("WHATSAPP no encontré el botón Enviar (app en pantalla: $lastPackage)")
        return "Abrí el chat de ${contact.name} con el mensaje escrito, pero no encontré el botón " +
            "de enviar: tocalo vos."
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        WHATSAPP_PACKAGES.forEach { pkg ->
            root.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull()?.let { return it }
        }
        return listOf("Enviar", "Send").firstNotNullOfOrNull { label ->
            root.findAccessibilityNodeInfosByText(label).firstOrNull {
                it.isClickable && it.contentDescription?.toString().equals(label, ignoreCase = true)
            }
        }
    }

    private companion object {
        val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

        /** Textos del aviso de WhatsApp cuando el número del enlace no existe. */
        val INVALID = listOf("no es válido", "no está en WhatsApp", "is invalid", "isn't on WhatsApp")
    }
}
