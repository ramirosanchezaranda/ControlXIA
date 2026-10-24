package com.controlxia.app.actions

import android.content.Context
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
        val contacts = Contacts(context)
        val number = (withContext(Dispatchers.IO) { contacts.find(chat) } as? Contacts.Lookup.Found)
            ?.let { contacts.international(it.contact.phone) }
        return WhatsAppUi(context).sendFile(file, "video/mp4", number, chat) ?: "Listo, le mandé el video a «$chat»."
    }

    companion object {
        /** La carpeta de los videos para compartir (caché: se borran solos). Deja los últimos. */
        fun dir(context: Context): File = File(context.cacheDir, "video").apply {
            mkdirs()
            listFiles()?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
        }
    }
}
