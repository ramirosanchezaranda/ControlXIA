import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Credenciales de firma release. Nunca se commitean: se leen de
// keystore.properties (local) o de variables de entorno (CI). Ver
// keystore.properties.example.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
val hasReleaseSigning =
    keystoreProps.getProperty("storeFile") != null || System.getenv("KEYSTORE_FILE") != null

/**
 * Descarga (una sola vez) el modelo de voz en español de Vosk y lo deja como
 * asset de la app en `vosk-model-es/`. El zip queda cacheado en
 * `.gradle/vosk/` (no se commitea); para compilar sin internet, copiá el zip
 * ahí a mano.
 */
abstract class PrepareVoskModel : DefaultTask() {
    @get:Input abstract val modelName: Property<String>
    @get:Input abstract val modelUrl: Property<String>
    @get:Internal abstract val cacheDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun prepare() {
        val name = modelName.get()
        val zip = cacheDir.get().asFile.resolve("$name.zip")
        if (!zip.exists()) download(modelUrl.get(), zip)

        val target = outputDir.get().asFile.resolve("vosk-model-es")
        target.deleteRecursively()
        target.mkdirs()
        val root = target.canonicalPath + File.separator
        ZipFile(zip).use { archive ->
            for (entry in archive.entries()) {
                // El zip trae todo dentro de "<nombre-del-modelo>/": se quita ese nivel.
                val relative = entry.name.substringAfter('/', "")
                if (relative.isEmpty() || entry.isDirectory) continue
                val out = target.resolve(relative)
                if (!out.canonicalPath.startsWith(root)) throw GradleException("Entrada inválida en el zip: ${entry.name}")
                out.parentFile.mkdirs()
                archive.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
        // La app compara esta versión para saber si tiene que volver a copiar el modelo.
        target.resolve("version").writeText(name)
    }

    private fun download(url: String, zip: File) {
        logger.lifecycle("Descargando el modelo de voz de Vosk: $url")
        zip.parentFile.mkdirs()
        val partial = File(zip.parentFile, zip.name + ".part")
        try {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.inputStream.use { input -> partial.outputStream().use { input.copyTo(it) } }
            if (!partial.renameTo(zip)) throw GradleException("No se pudo guardar ${zip.path}")
        } catch (e: Exception) {
            partial.delete()
            throw GradleException(
                "No se pudo descargar el modelo de Vosk ($url). Bajalo a mano y copialo como ${zip.path}",
                e,
            )
        }
    }
}

val prepareVoskModel = tasks.register<PrepareVoskModel>("prepareVoskModel") {
    modelName.set("vosk-model-small-es-0.42")
    modelUrl.set("https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip")
    cacheDir.set(rootProject.layout.projectDirectory.dir(".gradle/vosk"))
}

android {
    namespace = "com.controlxia.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.controlxia.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Vosk trae librerías nativas para cada arquitectura (~10 MB cada una):
        // teléfonos (arm64 y arm de 32 bits) y el emulador de la PC (x86_64).
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            val storePath = keystoreProps.getProperty("storeFile") ?: System.getenv("KEYSTORE_FILE")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = keystoreProps.getProperty("storePassword") ?: System.getenv("KEYSTORE_PASSWORD")
                keyAlias = keystoreProps.getProperty("keyAlias") ?: System.getenv("KEY_ALIAS")
                keyPassword = keystoreProps.getProperty("keyPassword") ?: System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Con keystore configurado, firma release; si no (p. ej. build de
            // prueba en CI), cae a la firma debug para que el APK sea instalable.
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Palabra de activación on-device con Vosk (Apache 2.0): sin cuentas, sin
    // claves y sin internet. Necesita JNA para llamar a la librería nativa.
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
}

// El modelo de voz descargado entra como asset generado en cada variante.
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareVoskModel, PrepareVoskModel::outputDir)
    }
}
