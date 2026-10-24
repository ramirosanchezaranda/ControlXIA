package com.controlxia.app.iot

import android.content.Context
import com.controlxia.app.SecurePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** Una tele o una compu vinculada. */
@Serializable
data class HomeDevice(
    val id: String = UUID.randomUUID().toString(),
    val kind: Kind,
    /** Cómo la llamás: "living", "pieza", "escritorio". */
    val name: String,
    /** Cómo la encontró la búsqueda (para volver a encontrarla si cambia de dirección). */
    val foundAs: String = name,
    val host: String,
    val port: Int,
    /** Tele: la huella del certificado de la tele (se comprueba en cada conexión). */
    val pin: String? = null,
    /** Compu: el código que mostró al instalar el programita. */
    val code: String? = null,
    /** Compu: sus direcciones físicas, para prenderla con Wake-on-LAN. */
    val macs: List<String> = emptyList(),
) {
    enum class Kind { TV, PC }
}

/** Las teles y compus vinculadas, guardadas cifradas. */
object HomeDevices {

    private const val PREFS = "home_devices"
    private const val KEY = "devices"
    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow<List<HomeDevice>?>(null)

    /** Las vinculadas; la pantalla las observa y se actualiza sola. */
    fun flow(context: Context): StateFlow<List<HomeDevice>?> {
        if (state.value == null) state.value = load(context)
        return state.asStateFlow()
    }

    fun all(context: Context): List<HomeDevice> = state.value ?: load(context).also { state.value = it }

    fun of(context: Context, kind: HomeDevice.Kind): List<HomeDevice> = all(context).filter { it.kind == kind }

    @Synchronized
    fun save(context: Context, device: HomeDevice) {
        val others = all(context).filterNot { it.id == device.id }
        store(context, others + device)
    }

    @Synchronized
    fun remove(context: Context, id: String) = store(context, all(context).filterNot { it.id == id })

    private fun load(context: Context): List<HomeDevice> = runCatching {
        SecurePrefs.get(context, PREFS).getString(KEY, null)?.let { json.decodeFromString<List<HomeDevice>>(it) }
    }.getOrNull().orEmpty()

    private fun store(context: Context, devices: List<HomeDevice>) {
        SecurePrefs.get(context, PREFS).edit().putString(KEY, json.encodeToString(devices)).apply()
        state.value = devices
    }
}
