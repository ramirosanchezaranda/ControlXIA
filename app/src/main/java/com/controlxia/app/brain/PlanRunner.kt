package com.controlxia.app.brain

import android.content.Context
import com.controlxia.app.actions.AppLauncher
import com.controlxia.app.actions.AppUi
import com.controlxia.app.actions.ChatUi
import com.controlxia.app.actions.Nodes
import com.controlxia.app.actions.NotesStore
import com.controlxia.app.actions.PrivateGate
import com.controlxia.app.actions.ScreenItems
import com.controlxia.app.actions.Screenshots
import com.controlxia.app.actions.UnlockGate
import com.controlxia.app.brain.TaskPlanner.Step
import com.controlxia.app.diagnostics.AgentLog
import com.controlxia.app.service.XiaAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hace un pedido de varios pasos ([TaskPlanner]) con las mismas herramientas
 * que usaría el modelo, pero sin él: abre, busca, ordena, captura, anota,
 * escribe y manda, en orden. Recuerda lo que va pasando (la app abierta, la
 * búsqueda, las capturas, los productos) para que cada paso siga al anterior.
 * Lo que manda algo pide el "sí" como siempre, y el resto del pedido sigue
 * recién después de la respuesta. Si un paso falla, frena y dice hasta dónde
 * llegó.
 */
class PlanRunner(
    private val context: Context,
    private val registry: ToolRegistry,
    private val direct: DirectCommandRunner,
) {
    /** Lo que se dice al terminar; con [pending], la pregunta que espera el "sí". */
    class Result(val spoken: String, val pending: PendingAction? = null)

    private class State {
        var app: String? = null
        var appLabel: String? = null
        var chat: String? = null
        var query: String? = null
        var deepSearch = false
        var sort: String? = null
        var written: String? = null
        val shots = mutableListOf<String>()
        val items = mutableListOf<ScreenItems.Item>()
    }

    private sealed interface Outcome {
        data class Done(val said: String) : Outcome
        data class Failed(val reason: String) : Outcome
        data class Ask(val action: PendingAction) : Outcome
    }

    suspend fun run(plan: TaskPlanner.Plan, onProgress: (String) -> Unit): Result {
        AgentLog.add("PLAN ${plan.steps.size} pasos: ${plan.steps.joinToString(" → ") { it.javaClass.simpleName }}")
        return runFrom(plan.steps, 0, State(), onProgress)
    }

    private suspend fun runFrom(steps: List<Step>, start: Int, state: State, onProgress: (String) -> Unit): Result {
        val done = mutableListOf<String>()
        for (i in start until steps.size) {
            val outcome = try {
                step(steps[i], state, onProgress)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome.Failed("tuve un error: ${e.message ?: e.javaClass.simpleName}")
            }
            AgentLog.add("PASO ${i + 1}: $outcome")
            when (outcome) {
                is Outcome.Done -> if (outcome.said.isNotBlank()) done += outcome.said
                is Outcome.Failed -> return Result(summary(done, state, failure = outcome.reason))
                is Outcome.Ask -> {
                    val continued = thenRest(outcome.action, steps, i, state, onProgress)
                    val before = if (done.isEmpty()) "" else summary(done, state) + " "
                    return Result(before + continued.question, pending = continued)
                }
            }
        }
        return Result(summary(done, state))
    }

    /** Después del "sí": lo confirmado y el resto del pedido (también si el mensaje se corrigió antes). */
    private fun thenRest(action: PendingAction, steps: List<Step>, i: Int, state: State, onProgress: (String) -> Unit): PendingAction =
        PendingAction(
            question = action.question,
            progress = action.progress,
            execute = {
                val first = action.execute()
                val rest = if (i + 1 < steps.size) runFrom(steps, i + 1, state, onProgress).spoken else ""
                listOf(first, rest).filter { it.isNotBlank() }.joinToString(" ")
            },
            message = action.message?.let { message ->
                PendingAction.Message(message.text, message.to) { edited ->
                    thenRest(message.rebuild(edited), steps, i, state, onProgress)
                }
            },
        )

    private suspend fun step(step: Step, state: State, onProgress: (String) -> Unit): Outcome = when (step) {
        is Step.Direct -> direct(step.command, state, onProgress)
        is Step.Skill -> skill(step.skill, state, onProgress)
        is Step.Search -> search(step.app, step.query, step.sort, state, onProgress)
        is Step.Sort -> sort(step.order, state, onProgress)
        is Step.Capture -> capture(step.items, state, onProgress)
        is Step.SaveNote -> saveNote(step.title, state, onProgress)
        is Step.OpenChat -> openChat(step.name, state, onProgress)
        is Step.Write -> write(step.text, state, onProgress)
        Step.SendWritten -> Outcome.Ask(
            PendingAction(
                question = state.written?.let { "¿Mando «${it.take(80)}»${state.chat?.let { c -> " a $c" }.orEmpty()}?" } ?: "¿Lo mando?",
                progress = "Enviando…",
                execute = { ChatUi.send() ?: "Listo, lo mandé." },
            ),
        )
        is Step.SendHere -> sendHere(step.text, state)
        is Step.AudioHere -> audio(step.text, state, onProgress)
    }

    // ---- Cada paso ----

    private suspend fun direct(command: DirectCommands.Command, state: State, onProgress: (String) -> Unit): Outcome {
        if (command is DirectCommands.OpenApp) {
            onProgress("Abriendo ${command.name}…")
            val text = registry.execute("open_app", buildJsonObject { put("app_name", command.name) }).text
            if (failed(text)) return Outcome.Failed(lower(text))
            val app = AppLauncher(context).find(command.name)
            state.app = LocalSkills.appId(command.name)
            state.appLabel = app?.label ?: command.name
            state.chat = null
            app?.let { waitForApp(it.pkg) }
            return Outcome.Done("abrí ${state.appLabel}")
        }
        val reply = direct.run(command) ?: return Outcome.Failed("no pude hacer «${command.javaClass.simpleName}»")
        val text = reply.text.ifBlank { reply.shown.orEmpty() }
        if (failed(text)) return Outcome.Failed(lower(text))
        delay(STEP_SETTLE_MS)
        return Outcome.Done(lower(text.trimEnd('.')))
    }

    private suspend fun skill(skill: LocalSkills.Skill, state: State, onProgress: (String) -> Unit): Outcome = when (skill) {
        is LocalSkills.Tool -> tool(skill.name, json(skill.args), state, onProgress)
        is LocalSkills.ReadMessages -> tool(
            "read_messages",
            buildJsonObject {
                skill.from?.let { put("from", it) }
                skill.app?.let { put("app", it) }
            },
            state,
            onProgress,
        )
        is LocalSkills.Answer -> Outcome.Done(skill.text)
        is LocalSkills.ReplyLast -> Outcome.Failed("«respondele» lo hago si me lo pedís aparte")
        LocalSkills.JustTheName, LocalSkills.Repeat -> Outcome.Done("")
    }

    private suspend fun tool(name: String, args: JsonObject, state: State, onProgress: (String) -> Unit): Outcome {
        onProgress(registry.progressLabel(ToolCallRequest(id = "local", name = name, arguments = args)))
        val outcome = registry.execute(name, args)
        outcome.pending?.let { return Outcome.Ask(it) }
        if (failed(outcome.text)) return Outcome.Failed(lower(outcome.text))
        when (name) {
            "search_in_app", "app_search" -> {
                state.app = args.str("app")?.let { LocalSkills.appId(it) }
                state.query = args.str("query")
                state.deepSearch = name == "search_in_app"
                state.sort = args.str("sort")
                delay(RESULTS_SETTLE_MS)
            }
            "whatsapp_open_chat" -> {
                state.app = "whatsapp"
                state.chat = args.str("name")
            }
            else -> delay(STEP_SETTLE_MS)
        }
        return Outcome.Done(lower(outcome.text.lineSequence().first().trimEnd('.')))
    }

    private suspend fun search(app: String, query: String, sort: String?, state: State, onProgress: (String) -> Unit): Outcome {
        val deep = LocalSkills.hasDeepSearch(app)
        val args = buildJsonObject {
            put("app", app)
            put("query", query)
            if (deep && sort != null) put("sort", sort)
        }
        val outcome = tool(if (deep) "search_in_app" else "app_search", args, state, onProgress)
        if (outcome !is Outcome.Done) return outcome
        state.app = app
        state.query = query
        state.deepSearch = deep
        if (!deep && sort != null) return sort(sort, state, onProgress)
        return Outcome.Done("busqué «$query»${sortSaid(sort)}")
    }

    private suspend fun sort(order: String, state: State, onProgress: (String) -> Unit): Outcome {
        onProgress(if (order == "price_low") "Ordenando de menor a mayor precio…" else "Ordenando de mayor a menor precio…")
        val query = state.query
        val app = state.app
        if (state.deepSearch && query != null && app != null) {
            // Con enlace directo (Mercado Libre): se vuelve a abrir la búsqueda ya ordenada.
            val text = registry.execute(
                "search_in_app",
                buildJsonObject {
                    put("app", app)
                    put("query", query)
                    put("sort", order)
                },
            ).text
            if (failed(text)) return Outcome.Failed(lower(text))
            state.sort = order
            delay(RESULTS_SETTLE_MS)
            return Outcome.Done("ordené${sortSaid(order)}")
        }
        if (!UnlockGate.unlock(context, "para ordenar")) return Outcome.Failed(UnlockGate.NOT_UNLOCKED)
        AppUi(context).sortByPrice(order == "price_low")?.let { return Outcome.Failed(lower(it)) }
        state.sort = order
        return Outcome.Done("ordené${sortSaid(order)}")
    }

    private suspend fun capture(items: Int?, state: State, onProgress: (String) -> Unit): Outcome {
        if (!UnlockGate.unlock(context, "para sacar la captura")) return Outcome.Failed(UnlockGate.NOT_UNLOCKED)
        val service = XiaAccessibilityService.instance ?: return Outcome.Failed("para sacar capturas necesito el control de apps activado")
        onProgress(if (items != null) "Sacando captura de los primeros $items…" else "Sacando la captura…")
        // Que terminen de cargar los resultados (con precios a la vista).
        if (items != null) {
            Nodes.waitFor(service, null, LOAD_WAIT_MS) { root ->
                root.takeIf { ScreenItems.extract(ScreenItems.texts(it), 1).isNotEmpty() }
            }
        } else {
            delay(STEP_SETTLE_MS)
        }
        var shots = 0
        repeat(MAX_SHOTS) { round ->
            val shot = Screenshots.capture(context, state.query ?: "captura")
                ?: return if (shots == 0) Outcome.Failed("no pude sacar la captura") else Outcome.Done(captureSaid(shots, items, state))
            shots++
            state.shots += shot.where
            if (items == null) return Outcome.Done(captureSaid(shots, null, state))
            val seen = ScreenItems.extract(ScreenItems.texts(service.rootInActiveWindow), items)
            seen.filter { item -> state.items.none { it.title == item.title } }.forEach { state.items += it }
            if (state.items.size >= items || round == MAX_SHOTS - 1) return Outcome.Done(captureSaid(shots, items, state))
            // Faltan productos: bajar y sacar otra.
            registry.execute("screen_scroll", buildJsonObject { put("direction", "down") })
            delay(SCROLL_SETTLE_MS)
        }
        return Outcome.Done(captureSaid(shots, items, state))
    }

    private suspend fun saveNote(title: String?, state: State, onProgress: (String) -> Unit): Outcome {
        onProgress("Guardando la nota…")
        val name = title ?: state.query?.let { "Búsqueda $it" } ?: "Nota"
        val body = buildString {
            state.query?.let { query ->
                appendLine("Búsqueda: «$query»${state.appLabel?.let { " en $it" } ?: state.app?.let { " en $it" }.orEmpty()}${sortSaid(state.sort)}")
            }
            appendLine("Fecha: ${SimpleDateFormat("d/M/yyyy HH:mm", Locale("es")).format(Date())}")
            if (state.items.isNotEmpty()) {
                appendLine()
                state.items.forEachIndexed { i, item -> appendLine("${i + 1}. ${item.title} — ${item.price}") }
            }
            if (state.shots.isNotEmpty()) {
                appendLine()
                appendLine("Capturas:")
                state.shots.forEach { appendLine("- $it") }
            }
            // Sin nada juntado: lo que se ve en pantalla.
            if (state.items.isEmpty() && state.shots.isEmpty()) {
                val texts = ScreenItems.texts(XiaAccessibilityService.instance?.rootInActiveWindow).take(MAX_NOTE_LINES)
                if (texts.isEmpty()) return Outcome.Failed("no tengo nada para guardar todavía")
                appendLine()
                texts.forEach { appendLine(it) }
            }
        }
        val where = NotesStore.save(context, name, body.trimEnd()) ?: return Outcome.Failed("no pude guardar la nota")
        AgentLog.add("NOTA guardada: $where")
        return Outcome.Done("guardé la nota en ${where.substringBeforeLast('/')}")
    }

    private suspend fun openChat(name: String, state: State, onProgress: (String) -> Unit): Outcome {
        val outcome = tool("whatsapp_open_chat", buildJsonObject { put("name", name) }, state, onProgress)
        if (outcome is Outcome.Done) {
            state.chat = name
            state.app = "whatsapp"
            delay(STEP_SETTLE_MS)
        }
        return outcome
    }

    private suspend fun write(text: String, state: State, onProgress: (String) -> Unit): Outcome {
        if (!UnlockGate.unlock(context, "para escribir el mensaje")) return Outcome.Failed(UnlockGate.NOT_UNLOCKED)
        onProgress("Escribiendo…")
        ChatUi.write(text)?.let { return Outcome.Failed(lower(it)) }
        state.written = text
        return Outcome.Done("escribí «${text.take(60)}»")
    }

    private suspend fun sendHere(text: String, state: State): Outcome {
        PrivateGate.check(context, "para mandar el mensaje")?.let { return Outcome.Failed(lower(it)) }
        return Outcome.Ask(sendHereAction(text, state.chat))
    }

    private fun sendHereAction(text: String, chat: String?): PendingAction = PendingAction(
        question = "¿Le mando${chat?.let { " a $it" }.orEmpty()}: «${text.take(120)}»?",
        progress = "Enviando…",
        execute = { ChatUi.write(text) ?: ChatUi.send() ?: "Listo, se lo mandé." },
        message = PendingAction.Message(text, chat) { edited -> sendHereAction(edited, chat) },
    )

    private suspend fun audio(text: String?, state: State, onProgress: (String) -> Unit): Outcome {
        val chat = state.chat ?: return Outcome.Failed("no sé a quién mandarle el audio")
        return tool(
            "send_audio",
            buildJsonObject {
                put("contact", chat)
                text?.let { put("text", it) }
            },
            state,
            onProgress,
        )
    }

    // ---- Ayudas ----

    /** Espera a que [pkg] esté adelante (hasta unos segundos). */
    private suspend fun waitForApp(pkg: String) {
        val service = XiaAccessibilityService.instance
        if (service == null) {
            delay(APP_OPEN_MS)
            return
        }
        Nodes.waitFor(service, pkg, APP_OPEN_MS) { it }
        delay(STEP_SETTLE_MS)
    }

    private fun summary(done: List<String>, state: State, failure: String? = null): String {
        val cheapest = state.items.firstOrNull()?.takeIf { state.sort == "price_low" }
            ?.let { " El más barato: ${it.title.take(70)}, a ${it.price}." }.orEmpty()
        val list = done.joinToString(", ").let { joinLast(it) }
        return when {
            failure == null && done.isEmpty() -> "Listo."
            failure == null -> "Listo: $list.$cheapest"
            done.isEmpty() -> sentence(failure).trimEnd('.') + "."
            else -> "Hice esto: $list. Pero ${failure.trimEnd('.')}."
        }
    }

    /** "a, b, c" → "a, b y c". */
    private fun joinLast(text: String): String {
        val i = text.lastIndexOf(", ")
        return if (i < 0) text else text.substring(0, i) + " y " + text.substring(i + 2)
    }

    private fun captureSaid(shots: Int, items: Int?, state: State): String {
        val what = if (shots == 1) "una captura" else "$shots capturas"
        return if (items == null) "saqué $what" else "saqué $what de los primeros ${minOf(items, state.items.size.takeIf { it > 0 } ?: items)}"
    }

    private fun sortSaid(sort: String?): String = when (sort) {
        "price_low" -> " de menor a mayor precio"
        "price_high" -> " de mayor a menor precio"
        else -> ""
    }

    private fun json(args: Map<String, Any>): JsonObject = buildJsonObject {
        args.forEach { (key, value) ->
            when (value) {
                is Int -> put(key, value)
                is Boolean -> put(key, value)
                else -> put(key, value.toString())
            }
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private fun lower(text: String): String = text.trim().replaceFirstChar { it.lowercase(Locale("es")) }

    private fun sentence(text: String): String = text.trim().replaceFirstChar { it.uppercase(Locale("es")) }

    companion object {
        /** Si lo que devolvió una herramienta es un "no se pudo". */
        fun failed(text: String): Boolean {
            val t = text.trim()
            return t.isEmpty() || t == UnlockGate.NOT_UNLOCKED || t == PrivateGate.BLOCKED_REPLY ||
                FAILED.containsMatchIn(t)
        }

        private val FAILED = Regex(
            "^(?:No (?:pude|encontré|tengo|hay|sé|se pudo|existe|reconozco)|Falta|Necesito|Para (?:eso|abrir|usar|leer|mandar)|" +
                "Desbloqueá|Falló|Tuve un problema|Acción desconocida|Esta versión)",
        )

        private const val STEP_SETTLE_MS = 700L
        private const val RESULTS_SETTLE_MS = 2_500L
        private const val APP_OPEN_MS = 6_000L
        private const val LOAD_WAIT_MS = 8_000L
        private const val SCROLL_SETTLE_MS = 1_500L
        private const val MAX_SHOTS = 3
        private const val MAX_NOTE_LINES = 60
    }
}
