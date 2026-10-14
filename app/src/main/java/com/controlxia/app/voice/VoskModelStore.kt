package com.controlxia.app.voice

import android.content.Context
import android.content.res.AssetManager
import com.controlxia.app.BuildConfig
import com.sun.jna.Library
import com.sun.jna.NativeLibrary
import org.vosk.LibVosk
import org.vosk.Model
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

/** El modelo de voz todavía no está en el teléfono: hay que descargarlo. */
class VoskModelMissingException :
    IOException("Falta descargar el modelo de voz (39 MB). Tocá «Descargar» en Ajustes → Nombre y activación")

/**
 * Modelo de voz en español de Vosk, compartido por toda la app.
 *
 * Vive en el almacenamiento interno (`vosk-model-es/`, ~60 MB). Llega por una
 * de dos vías: la app lo descarga la primera vez ([VoskModelDownloader] →
 * [install]) o viene dentro del APK si se compiló con el modelo incluido, y
 * entonces se copia desde los assets. Una vez cargado (~110 MB de RAM) se
 * mantiene mientras el servicio vive: recargarlo en cada pedido haría lenta la
 * vuelta a la escucha.
 */
object VoskModelStore {

    val MODEL_NAME: String = BuildConfig.VOSK_MODEL_NAME
    val MODEL_URL: String = BuildConfig.VOSK_MODEL_URL

    private const val DIR_NAME = "vosk-model-es"
    private const val ASSET_DIR = "vosk-model-es"

    /** Archivos sin los cuales el modelo no sirve (para validar una descarga). */
    private val REQUIRED = listOf("am/final.mdl", "conf/model.conf", "graph/HCLr.fst", "graph/Gr.fst")

    private var model: Model? = null

    /** Hilos que están usando el modelo; no se libera mientras haya alguno. */
    private val users = AtomicInteger(0)

    fun enter() {
        users.incrementAndGet()
    }

    fun exit() {
        users.decrementAndGet()
    }

    /** true si el modelo ya está instalado o viene dentro del APK. */
    fun isAvailable(context: Context): Boolean =
        isInstalled(context) || hasBundledModel(context)

    /**
     * Devuelve el modelo cargado, instalándolo desde el APK si viene incluido.
     * Bloquea (copia y carga tardan segundos): no usar en el main thread.
     * @throws VoskModelMissingException si hay que descargarlo.
     */
    @Synchronized
    fun get(context: Context): Model {
        model?.let { return it }
        val app = context.applicationContext
        if (!isInstalled(app)) {
            if (!hasBundledModel(app)) throw VoskModelMissingException()
            installFromAssets(app)
        }
        return Model(modelDir(app).absolutePath).also { model = it }
    }

    /** Libera la memoria del modelo si nadie lo está usando. */
    @Synchronized
    fun releaseIfIdle() {
        if (users.get() > 0) return
        model?.let { runCatching { it.close() } }
        model = null
    }

    /**
     * Instala el modelo desde el zip descargado. Descomprime en una carpeta
     * temporal, valida que estén los archivos clave y recién ahí reemplaza la
     * instalación anterior, así una descarga cortada nunca deja un modelo roto.
     */
    @Synchronized
    fun install(context: Context, zip: File) {
        val app = context.applicationContext
        val target = modelDir(app)
        val staging = File(app.noBackupFilesDir, "$DIR_NAME.tmp")
        staging.deleteRecursively()
        staging.mkdirs()
        val root = staging.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                // El zip trae todo dentro de "<nombre-del-modelo>/": se quita ese nivel.
                val relative = entry.name.substringAfter('/', "")
                if (entry.isDirectory || relative.isEmpty()) continue
                val out = File(staging, relative)
                if (!out.canonicalPath.startsWith(root)) throw IOException("Archivo inválido en el zip")
                out.parentFile?.mkdirs()
                out.outputStream().use { input.copyTo(it) }
            }
        }
        val missing = REQUIRED.filterNot { File(staging, it).isFile }
        if (missing.isNotEmpty()) {
            staging.deleteRecursively()
            throw IOException("El modelo descargado está incompleto")
        }
        File(staging, "version").writeText(MODEL_NAME)

        model?.let { runCatching { it.close() } }
        model = null
        target.deleteRecursively()
        if (!staging.renameTo(target)) throw IOException("No se pudo guardar el modelo")
    }

    /**
     * true si [word] está en el vocabulario del modelo. Vosk ignora en silencio
     * las palabras de la gramática que no conoce, así que hay que chequearlo
     * antes (ver [WakePhraseResolver]).
     */
    fun hasWord(model: Model, word: String): Boolean = runCatching {
        findWord.invokeInt(arrayOf(model.pointer, word)) >= 0
    }.getOrDefault(true) // si no se puede consultar, no bloquear la escucha

    /**
     * vosk_model_find_word no está expuesta en la API Java de Vosk. Se toma de
     * la misma instancia de la librería que abrió Vosk (mismas opciones que
     * `Native.register`), así no se carga dos veces.
     */
    private val findWord by lazy {
        NativeLibrary.getInstance(
            "vosk",
            mapOf(Library.OPTION_CLASSLOADER to LibVosk::class.java.classLoader),
        ).getFunction("vosk_model_find_word")
    }

    private fun modelDir(context: Context) = File(context.noBackupFilesDir, DIR_NAME)

    private fun isInstalled(context: Context): Boolean {
        val marker = File(modelDir(context), "version")
        return marker.isFile && marker.readText().trim() == MODEL_NAME
    }

    private fun hasBundledModel(context: Context): Boolean =
        runCatching { context.assets.open("$ASSET_DIR/version").close() }.isSuccess

    private fun installFromAssets(context: Context) {
        val target = modelDir(context)
        target.deleteRecursively()
        copyAssets(context.assets, ASSET_DIR, target)
        // La marca de versión se escribe al final: si la copia se corta, se rehace.
        File(target, "version").writeText(MODEL_NAME)
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
