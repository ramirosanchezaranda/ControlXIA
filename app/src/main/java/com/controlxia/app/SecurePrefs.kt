package com.controlxia.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Acceso único a las preferencias cifradas (API keys y ajustes del asistente).
 *
 * - Cachea cada archivo: abrir EncryptedSharedPreferences es caro (Keystore +
 *   Tink) y antes se hacía en cada `LlmSettings(...)` / `WakeWordSettings(...)`,
 *   incluso al armar cada notificación.
 * - Se recupera de un Keystore corrupto, que pasa en algunos equipos tras
 *   restaurar un backup o cambiar el bloqueo de pantalla: borra el archivo y lo
 *   vuelve a crear (se pierden las claves, pero la app no queda crasheando en
 *   bucle). Si ni así funciona, cae a preferencias privadas sin cifrar, que
 *   siguen siendo inaccesibles para otras apps (allowBackup=false).
 */
object SecurePrefs {

    private val cache = mutableMapOf<String, SharedPreferences>()

    @Synchronized
    fun get(context: Context, name: String): SharedPreferences =
        cache.getOrPut(name) { open(context.applicationContext, name) }

    private fun open(context: Context, name: String): SharedPreferences =
        try {
            create(context, name)
        } catch (e: Exception) {
            context.deleteSharedPreferences(name)
            try {
                create(context, name)
            } catch (e2: Exception) {
                context.getSharedPreferences("${name}_plain", Context.MODE_PRIVATE)
            }
        }

    private fun create(context: Context, name: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            name,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
