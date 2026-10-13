package com.controlxia.app.voice

import android.content.Context
import android.content.res.AssetManager
import com.sun.jna.NativeLibrary
import org.vosk.Model
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Modelo de voz en español de Vosk, compartido por toda la app.
 *
 * El modelo viaja como asset (`vosk-model-es/`, lo descarga Gradle al
 * compilar) pero Vosk necesita archivos reales, así que la primera vez se copia
 * al almacenamiento interno (~60 MB, unos segundos). Después se carga en
 * memoria (~110 MB) y se mantiene mientras el servicio vive: recargarlo en cada
 * pedido haría lenta la vuelta a la escucha.
 */
object VoskModelStore {

    private const val ASSET_DIR = "vosk-model-es"

    private var model: Model? = null

    /** Hilos que están usando el modelo; no se libera mientras haya alguno. */
    private val users = AtomicInteger(0)

    fun enter() {
        users.incrementAndGet()
    }

    fun exit() {
        users.decrementAndGet()
    }

    /** Devuelve el modelo, copiándolo y cargándolo si hace falta. Bloquea: no usar en el main thread. */
    @Synchronized
    fun get(context: Context): Model {
        model?.let { return it }
        val dir = ensureUnpacked(context.applicationContext)
        return Model(dir.absolutePath).also { model = it }
    }

    /** Libera la memoria del modelo si nadie lo está usando. */
    @Synchronized
    fun releaseIfIdle() {
        if (users.get() > 0) return
        model?.let { runCatching { it.close() } }
        model = null
    }

    /**
     * true si [word] está en el vocabulario del modelo. Vosk ignora en silencio
     * las palabras de la gramática que no conoce, así que hay que chequearlo
     * antes para poder avisarle al usuario.
     */
    fun hasWord(model: Model, word: String): Boolean = runCatching {
        NativeLibrary.getInstance("vosk")
            .getFunction("vosk_model_find_word")
            .invokeInt(arrayOf(model.pointer, word)) >= 0
    }.getOrDefault(true) // si no se puede consultar, no bloquear la escucha

    private fun ensureUnpacked(context: Context): File {
        val assets = context.assets
        val version = try {
            assets.open("$ASSET_DIR/version").bufferedReader().use { it.readText().trim() }
        } catch (e: FileNotFoundException) {
            throw IllegalStateException(
                "La app no trae el modelo de voz: volvé a compilarla con internet", e
            )
        }
        val target = File(context.noBackupFilesDir, ASSET_DIR)
        val marker = File(target, "version")
        if (marker.exists() && marker.readText().trim() == version) return target

        // Copia nueva (primera vez o modelo actualizado). La marca de versión se
        // escribe al final: si la copia se corta, se rehace la próxima vez.
        target.deleteRecursively()
        copyAssets(assets, ASSET_DIR, target)
        marker.writeText(version)
        return target
    }

    private fun copyAssets(assets: AssetManager, path: String, out: File) {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            out.parentFile?.mkdirs()
            assets.open(path).use { input -> out.outputStream().use { input.copyTo(it) } }
            return
        }
        out.mkdirs()
        for (child in children) {
            if (path == ASSET_DIR && child == "version") continue
            copyAssets(assets, "$path/$child", File(out, child))
        }
    }
}
