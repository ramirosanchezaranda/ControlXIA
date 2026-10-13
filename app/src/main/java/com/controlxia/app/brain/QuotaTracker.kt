package com.controlxia.app.brain

import android.content.Context

/**
 * Qué modelos se quedaron sin cupo y hasta cuándo, para no gastar un pedido
 * en ellos y pasar directo al siguiente. En Groq gratis cada modelo tiene su
 * propio cupo diario: cuando gpt-oss-120b se termina, qwen y gpt-oss-20b
 * siguen andando.
 */
object QuotaTracker {

    private const val FILE = "llm_quota"

    private fun key(config: LlmConfig) = "${config.type.name}|${config.model}"

    fun isExhausted(context: Context, config: LlmConfig): Boolean {
        val until = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong(key(config), 0L)
        return System.currentTimeMillis() < until
    }

    /** Sin cupo por [seconds] (lo que dijo el proveedor), o una hora si no lo dijo. */
    fun markExhausted(context: Context, config: LlmConfig, seconds: Double?) {
        val wait = ((seconds ?: 3_600.0).coerceIn(60.0, 24 * 3_600.0) * 1_000).toLong()
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putLong(key(config), System.currentTimeMillis() + wait)
            .apply()
    }

    /**
     * El error dice que se terminó el cupo (del día, o hay que esperar más de
     * lo que tiene sentido para un pedido por voz), o los créditos.
     */
    fun isQuotaError(e: LlmException, maxWaitSeconds: Double): Boolean {
        val detail = e.message.orEmpty().lowercase()
        val quotaWords = listOf(
            "per day", "tpd", "rpd", "quota", "insufficient", "credit", "billing", "exceeded your",
            "resource_exhausted", "resource has been exhausted",
        )
        return when (e.httpCode) {
            429 -> quotaWords.any { it in detail } || (e.retryAfterSeconds ?: 0.0) > maxWaitSeconds
            400, 402, 403 -> listOf("credit", "billing", "quota", "insufficient").any { it in detail }
            else -> false
        }
    }
}
