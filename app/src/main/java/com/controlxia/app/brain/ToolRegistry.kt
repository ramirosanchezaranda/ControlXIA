package com.controlxia.app.brain

import android.content.Context
import com.controlxia.app.actions.AlarmActions
import com.controlxia.app.actions.AppLauncher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    /** Ejecuta la tool [name] con [args] y devuelve la frase a decir. */
    fun execute(name: String, args: JsonObject): String = when (name) {
        "get_time" -> "Son las " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        "get_date" -> "Hoy es " + SimpleDateFormat("EEEE d 'de' MMMM", Locale("es"))
            .format(Date())
        "open_app" -> appLauncher.open(args.strArg("app_name"))
        "set_alarm" -> alarms.setAlarm(
            hour = args.intArg("hour"),
            minute = args.intArg("minute"),
            label = args.strArgOrNull("label"),
        )
        "set_timer" -> alarms.setTimer(
            seconds = args.intArg("seconds"),
            label = args.strArgOrNull("label"),
        )
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

private fun JsonObject.strArg(key: String): String =
    this[key]?.jsonPrimitive?.content.orEmpty()

private fun JsonObject.strArgOrNull(key: String): String? =
    this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

private fun JsonObject.intArg(key: String): Int =
    this[key]?.jsonPrimitive?.int ?: 0
