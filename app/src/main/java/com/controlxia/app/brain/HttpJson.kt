package com.controlxia.app.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Cliente HTTP y JSON compartidos por todos los proveedores. */
internal object HttpJson {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /**
     * POST con body JSON. Devuelve el body parseado; ante un status de error
     * lanza [LlmException] con el mensaje real de la API (clave inválida,
     * modelo inexistente, etc.), que la UI muestra tal cual.
     */
    suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: JsonObject,
    ): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw LlmException("HTTP ${response.code}: ${text.take(500)}")
                }
                json.parseToJsonElement(text) as? JsonObject
                    ?: throw LlmException("Respuesta inesperada: ${text.take(200)}")
            }
        } catch (e: LlmException) {
            throw e
        } catch (e: Exception) {
            throw LlmException("Error de red: ${e.message}", e)
        }
    }
}
