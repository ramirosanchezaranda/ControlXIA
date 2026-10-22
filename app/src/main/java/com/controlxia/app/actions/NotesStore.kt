package com.controlxia.app.actions

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.controlxia.app.diagnostics.AgentLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Notas en archivos de texto (Documentos/ControlXIA): lo que encontró una
 * búsqueda, con las capturas que se sacaron. Se abren con cualquier app de
 * archivos o de notas y se pueden compartir.
 */
object NotesStore {

    /** Guarda la nota y devuelve dónde quedó (para decirlo), o null si no pudo. */
    suspend fun save(context: Context, title: String, body: String): String? = withContext(Dispatchers.IO) {
        val file = "${safeName(title)} ${STAMP.format(Date())}.txt"
        val text = "$title\n${"=".repeat(title.length.coerceAtMost(60))}\n\n$body\n"
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/$FOLDER")
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values) ?: return@runCatching null
                resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                "Documentos/$FOLDER/$file"
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), FOLDER).apply { mkdirs() }
                File(dir, file).writeText(text)
                "${dir.path}/$file"
            }
        }.onFailure { AgentLog.add("NOTA no pude guardarla: ${it.message}") }.getOrNull()
    }

    private fun safeName(name: String) = name.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(50).ifBlank { "Nota" }

    private val STAMP = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US)
    private const val FOLDER = "ControlXIA"
}
