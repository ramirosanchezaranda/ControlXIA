package com.controlxia.app.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** Cliente HTTP y JSON compartidos por todos los proveedores. */
internal object HttpJson {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    // callTimeout acota la espera total: para un asistente de voz, más de 45 s
    // en silencio es peor que un "tardó demasiado".
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /**
     * POST con body JSON. Devuelve el body parseado; ante un status de error
     * lanza [LlmException] con el código y el mensaje real de la API (clave
     * inválida, modelo inexistente, sin crédito, etc.).
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
                    val detail = apiErrorMessage(text) ?: text.take(300)
                    throw LlmException(
                        "HTTP ${response.code}: $detail",
                        httpCode = response.code,
                    )
                }
                val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
                parsed as? JsonObject ?: throw LlmException(
                    "Respuesta inesperada: ${text.take(200)}",
                    kind = LlmException.Kind.BAD_RESPONSE,
                )
            }
        } catch (e: LlmException) {
            throw e
        } catch (e: InterruptedIOException) {
            // SocketTimeoutException y el callTimeout de OkHttp.
            throw LlmException("Tiempo de espera agotado", e, kind = LlmException.Kind.TIMEOUT)
        } catch (e: Exception) {
            throw LlmException("Error de red: ${e.message}", e, kind = LlmException.Kind.NETWORK)
        }
    }

    /**
     * Anthropic, OpenAI y Gemini devuelven el error como {"error": {"message": …}}.
     */
    private fun apiErrorMessage(text: String): String? = runCatching {
        val error = (json.parseToJsonElement(text) as? JsonObject)?.get("error")
        when (error) {
            is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull
            is JsonPrimitive -> error.contentOrNull
            else -> null
        }
    }.getOrNull()?.take(300)
}
