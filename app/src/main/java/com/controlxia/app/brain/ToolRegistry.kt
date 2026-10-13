package com.controlxia.app.brain

import android.content.Context
import com.controlxia.app.actions.AlarmActions
import com.controlxia.app.actions.AppLauncher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Catálogo de herramientas que el LLM puede invocar y su ejecución. Cada tool
 * declara su JSON Schema para el modelo y se despacha a la acción nativa
 * correspondiente. Devuelve una frase corta lista para decir en voz alta.
 */
class ToolRegistry(private val context: Context) {

    private val appLauncher by lazy { AppLauncher(context) }
    private val alarms by lazy { AlarmActions(context) }

    val specs: List<ToolSpec> = listOf(
        ToolSpec(
            name = "get_time",
            description = "Dice la hora actual. Usar cuando el usuario pregunta qué hora es.",
            parameters = emptyObject(),
        ),
        ToolSpec(
            name = "get_date",
            description = "Dice la fecha de hoy. Usar cuando el usuario pregunta qué día es.",
            parameters = emptyObject(),
        ),
        ToolSpec(
            name = "open_app",
            description = "Abre una aplicación del teléfono por su nombre, ej: Instagram, WhatsApp.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("app_name", buildJsonObject {
                        put("type", "string")
                        put("description", "Nombre de la app tal como lo dijo el usuario")
                    })
                })
                put("required", listOf("app_name").toJsonArray())
            },
        ),
        ToolSpec(
            name = "set_alarm",
            description = "Pone una alarma a una hora del día (formato 24 h).",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("hour", intProp("Hora 0-23"))
                    put("minute", intProp("Minuto 0-59"))
                    put("label", buildJsonObject {
                        put("type", "string")
                        put("description", "Etiqueta opcional de la alarma")
                    })
                })
                put("required", listOf("hour", "minute").toJsonArray())
            },
        ),
        ToolSpec(
            name = "set_timer",
            description = "Inicia un temporizador de una cantidad de segundos.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("seconds", intProp("Duración total en segundos"))
                    put("label", buildJsonObject {
                        put("type", "string")
                        put("description", "Etiqueta opcional del temporizador")
                    })
                })
                put("required", listOf("seconds").toJsonArray())
            },
        ),
    )

    /**
     * Ejecuta la tool [name] con [args] y devuelve la frase a decir. Si falta
     * un dato obligatorio lo dice, en vez de asumir un 0 (una alarma a la
     * medianoche o un temporizador vacío).
     */
    fun execute(name: String, args: JsonObject): String = when (name) {
        "get_time" -> "Son las " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        "get_date" -> "Hoy es " + SimpleDateFormat("EEEE d 'de' MMMM", Locale("es"))
            .format(Date())
        "open_app" -> appLauncher.open(args.strArg("app_name"))
        "set_alarm" -> {
            val hour = args.intArgOrNull("hour")
            if (hour == null) {
                "¿A qué hora querés la alarma?"
            } else {
                alarms.setAlarm(
                    hour = hour,
                    minute = args.intArgOrNull("minute") ?: 0,
                    label = args.strArgOrNull("label"),
                )
            }
        }
        "set_timer" -> {
            val seconds = args.intArgOrNull("seconds")
            if (seconds == null) {
                "¿De cuánto tiempo querés el temporizador?"
            } else {
                alarms.setTimer(seconds = seconds, label = args.strArgOrNull("label"))
            }
        }
        else -> "No sé cómo hacer eso todavía"
    }

    private fun emptyObject(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
    }

    private fun intProp(desc: String): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", desc)
    }
}

private fun List<String>.toJsonArray() =
    kotlinx.serialization.json.buildJsonArray { forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }

// Los modelos no siempre respetan el tipo del schema: un número puede llegar
// como 7, 7.0 o "7", y un texto como null. Nada de esto debe tirar excepción.

private fun JsonObject.strArg(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.strArgOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.intArgOrNull(key: String): Int? {
    val p = this[key] as? JsonPrimitive ?: return null
    return p.intOrNull
        ?: p.doubleOrNull?.takeIf { it.isFinite() }?.roundToInt()
        ?: p.contentOrNull?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }?.roundToInt()
}
