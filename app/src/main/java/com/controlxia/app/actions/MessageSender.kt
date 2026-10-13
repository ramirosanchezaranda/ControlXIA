package com.controlxia.app.actions

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.view.accessibility.AccessibilityNodeInfo
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
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$number&text=${Uri.encode(text)}")
        val opened = WHATSAPP_PACKAGES.any { pkg ->
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.isSuccess
        }
        if (!opened) return "No encontré WhatsApp instalado."

        // Con el control de apps activo, tocar "Enviar" cuando aparezca el chat.
        val service = XiaAccessibilityService.instance
            ?: return "Te dejé el mensaje para ${contact.name} escrito en WhatsApp: tocá enviar."
        repeat(12) {
            delay(500)
            val root = service.rootInActiveWindow
            if (root != null && root.packageName?.toString() in WHATSAPP_PACKAGES) {
                val send = findSendButton(root)
                if (send != null && send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return "Listo, le mandé el WhatsApp a ${contact.name}."
                }
            }
        }
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
    }
}
