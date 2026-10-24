package com.controlxia.app.actions

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manda a un chat de WhatsApp un video ya grabado (el de los gestos): directo
 * si el chat es un contacto con número, si no eligiéndolo por nombre en
 * "Enviar a…" (grupos).
 */
class VideoMessage(private val context: Context) {

    suspend fun send(file: File, chat: String): String {
        // Sin el nombre del chat: WhatsApp abre "Enviar a…" y elegís vos.
        if (chat.isBlank()) return pickInWhatsApp(file)
        val contacts = Contacts(context)
        val number = (withContext(Dispatchers.IO) { contacts.find(chat) } as? Contacts.Lookup.Found)
            ?.let { contacts.international(it.contact.phone) }
        return WhatsAppUi(context).sendFile(file, "video/mp4", number, chat) ?: "Listo, le mandé el video a «$chat»."
    }

    private suspend fun pickInWhatsApp(file: File): String = withContext(Dispatchers.Main) {
        val pkg = WhatsAppUi.PACKAGES.firstOrNull { context.packageManager.getLaunchIntentForPackage(it) != null }
            ?: return@withContext "No encontré WhatsApp instalado."
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val share = Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .setPackage(pkg)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        share.clipData = ClipData.newRawUri("video", uri)
        if (runCatching { context.startActivity(share) }.isSuccess) {
            "Te abrí WhatsApp con el video: elegí a quién mandarlo."
        } else {
            "No pude pasarle el video a WhatsApp."
        }
    }

    companion object {
        /** La carpeta de los videos para compartir (caché: se borran solos). Deja los últimos. */
        fun dir(context: Context): File = File(context.cacheDir, "video").apply {
            mkdirs()
            listFiles()?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
        }
    }
}
