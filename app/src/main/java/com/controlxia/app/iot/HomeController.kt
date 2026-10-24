package com.controlxia.app.iot

import android.content.Context
import com.controlxia.app.brain.PendingAction
import com.controlxia.app.brain.Reply
import com.controlxia.app.diagnostics.AgentLog
import com.controlxia.app.iot.HomeCommands.Action
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.cert.CertificateException

/**
 * Hace los pedidos de [HomeCommands] con las teles y compus vinculadas:
 * elige cuál (o pregunta), se conecta, y si cambió de dirección en la red la
 * vuelve a encontrar. Apagar o reiniciar una compu pide el "sí".
 */
class HomeController(private val context: Context) {

    /** La respuesta, y si quedó algo esperando el "sí". */
    class Outcome(val reply: Reply, val pending: PendingAction? = null)

    private sealed interface Step {
        data class Done(val text: String) : Step
        data class Failed(val text: String) : Step
        data class Ask(val action: PendingAction) : Step
    }

    private val tvs = AndroidTv(context)
    private val pcs = PcAgent(context)

    /** "¿Cuál tele?": los pedidos que esperan que digas cuál. */
    private var waitingWhich: List<HomeCommands.Command>? = null

    suspend fun handle(text: String, onProgress: (String) -> Unit = {}): Outcome? {
        val devices = HomeDevices.all(context)
        waitingWhich?.let { pending ->
            waitingWhich = null
            val said = HomeCommands.normalize(text)
            val chosen = devices.filter { it.kind == pending.first().kind }
                .firstOrNull { HomeCommands.normalize(it.name).let { name -> name.isNotEmpty() && name in said } }
            if (chosen != null) return run(pending.map { it.copy(which = chosen.name) }, devices, onProgress)
        }
        val commands = HomeCommands.parse(
            text,
            devices.filter { it.kind == HomeDevice.Kind.TV }.map { it.name },
            devices.filter { it.kind == HomeDevice.Kind.PC }.map { it.name },
        ) ?: return null
        AgentLog.add("CASA ${commands.joinToString { "${it.action} ${it.kind}${it.which?.let { w -> " «$w»" }.orEmpty()}" }}")
        return run(commands, devices, onProgress)
    }

    /** Un pedido directo a un aparato (los botones de Ajustes → Casa). */
    suspend fun perform(device: HomeDevice, action: Action, arg: String? = null): Outcome =
        run(listOf(HomeCommands.Command(device.kind, action, device.name, arg)), HomeDevices.all(context)) {}

    private suspend fun run(commands: List<HomeCommands.Command>, devices: List<HomeDevice>, onProgress: (String) -> Unit): Outcome {
        val said = mutableListOf<String>()
        for ((i, command) in commands.withIndex()) {
            val tv = command.kind == HomeDevice.Kind.TV
            val candidates = devices.filter { it.kind == command.kind }
            if (candidates.isEmpty()) {
                val what = if (tv) "ninguna tele" else "ninguna compu"
                val how = if (tv) "«Buscar teles»" else "«Buscar compus»"
                return Outcome(Reply(join(said + "Todavía no vinculé $what. Entrá a Ajustes → Casa y tocá $how.")))
            }
            val device = choose(candidates, command.which) ?: run {
                waitingWhich = commands.drop(i)
                val noun = if (tv) "tele" else "compu"
                return Outcome(Reply(join(said + "¿Cuál $noun: ${candidates.joinToString(" o ") { "«${it.name}»" }}?"), expectsReply = true))
            }
            val step = try {
                if (tv) tv(device, command, onProgress) else pc(device, command, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Step.Failed("No pude: ${e.message ?: "error inesperado"}.")
            }
            when (step) {
                is Step.Done -> said += step.text
                is Step.Failed -> return Outcome(Reply(join(said + step.text)))
                is Step.Ask -> return Outcome(Reply(join(said + step.action.question), expectsReply = true), step.action)
            }
        }
        return Outcome(Reply(join(said)))
    }

    /** La que nombraste, o la única que hay; null si hay que preguntar. */
    private fun choose(candidates: List<HomeDevice>, which: String?): HomeDevice? {
        if (which == null) return candidates.singleOrNull()
        val wanted = HomeCommands.normalize(which)
        return candidates.firstOrNull { HomeCommands.normalize(it.name) == wanted }
            ?: candidates.firstOrNull { HomeCommands.normalize(it.name).let { name -> name.contains(wanted) || wanted.contains(name) } }
            ?: candidates.singleOrNull()
    }

    // ---- Teles ----

    private suspend fun tv(device: HomeDevice, command: HomeCommands.Command, onProgress: (String) -> Unit): Step {
        val name = device.name
        onProgress("Hablando con la tele «$name»…")
        return try {
            val text = withTvRetry(device) { current -> tvs.remote(current) { session -> tvAct(session, command, name) } }
            Step.Done(text)
        } catch (e: CertificateException) {
            Step.Failed("La tele «$name» cambió de certificado (¿la reseteaste?): volvé a vincularla en Ajustes → Casa.")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AgentLog.add("TELE «$name» no contesta: ${e.message}")
            if (e.cause is CertificateException) {
                return Step.Failed("La tele «$name» cambió de certificado (¿la reseteaste?): volvé a vincularla en Ajustes → Casa.")
            }
            Step.Failed(
                if (command.action == Action.ON) {
                    "No encuentro la tele «$name». Si está apagada del todo, activá en la tele «Encender con Wi-Fi» o el " +
                        "inicio rápido (Ajustes → Sistema → Energía; el nombre cambia según la marca), y que esté en el mismo wifi."
                } else {
                    "No encuentro la tele «$name»: ¿está prendida y en el mismo wifi?"
                },
            )
        }
    }

    private suspend fun tvAct(session: AndroidTv.RemoteSession, command: HomeCommands.Command, name: String): String {
        suspend fun keys(code: Int, times: Int = 1) = repeat(times) {
            session.key(code)
            delay(KEY_GAP_MS)
        }
        return when (command.action) {
            Action.ON -> when (session.isOn) {
                true -> "La tele «$name» ya está prendida."
                false -> keys(RemoteMessages.KEY_POWER).let { "Listo, prendí la tele «$name»." }
                null -> keys(KEY_WAKEUP).let { "Listo, prendí la tele «$name»." }
            }
            Action.OFF, Action.SLEEP -> when (session.isOn) {
                false -> "La tele «$name» ya está apagada."
                true -> keys(RemoteMessages.KEY_POWER).let { "Listo, apagué la tele «$name»." }
                null -> keys(KEY_SLEEP).let { "Listo, apagué la tele «$name»." }
            }
            Action.VOLUME_UP -> keys(RemoteMessages.KEY_VOLUME_UP, command.times).let { "Listo, subí el volumen." }
            Action.VOLUME_DOWN -> keys(RemoteMessages.KEY_VOLUME_DOWN, command.times).let { "Listo, bajé el volumen." }
            Action.MUTE -> keys(RemoteMessages.KEY_MUTE).let { "Listo." }
            Action.PAUSE -> keys(RemoteMessages.KEY_PAUSE).let { "Listo, en pausa." }
            Action.PLAY -> keys(RemoteMessages.KEY_PLAY).let { "Listo." }
            Action.NEXT -> keys(RemoteMessages.KEY_NEXT).let { "Listo." }
            Action.PREVIOUS -> keys(RemoteMessages.KEY_PREVIOUS).let { "Listo." }
            Action.HOME -> keys(RemoteMessages.KEY_HOME).let { "Listo, al inicio." }
            Action.BACK -> keys(RemoteMessages.KEY_BACK).let { "Listo." }
            Action.OPEN -> {
                val (link, label) = HomeLinks.tvApp(command.arg.orEmpty())
                    ?: return "No sé abrir «${command.arg}» en la tele. Por ahora: ${HomeLinks.tvAppNames.joinToString(", ")}."
                if (session.isOn == false) {
                    keys(RemoteMessages.KEY_POWER)
                    delay(WAKE_UP_MS)
                }
                session.app(link)
                "Abrí $label en la tele «$name»."
            }
            Action.LOCK, Action.RESTART -> "Eso lo hago en las compus, no en la tele."
        }
    }

    /** Si no contesta en su dirección, la busca de nuevo (el router le pudo dar otra) y reintenta. */
    private suspend fun <T> withTvRetry(device: HomeDevice, block: suspend (HomeDevice) -> T): T = try {
        block(device)
    } catch (e: IOException) {
        if (e.cause is CertificateException) throw e
        val found = TvDiscovery(context).scan(RETRY_SCAN_MS).firstOrNull { it.name == device.foundAs } ?: throw e
        if (found.host == device.host) throw e
        val moved = device.copy(host = found.host)
        HomeDevices.save(context, moved)
        AgentLog.add("TELE «${device.name}» cambió de dirección: ${found.host}")
        block(moved)
    }

    // ---- Compus ----

    private suspend fun pc(device: HomeDevice, command: HomeCommands.Command, onProgress: (String) -> Unit): Step {
        val name = device.name
        onProgress("Hablando con la compu «$name»…")
        return when (command.action) {
            Action.ON -> {
                if (pcs.send(device, "ping").getOrNull()?.ok == true) return Step.Done("La compu «$name» ya está prendida.")
                if (device.macs.isEmpty()) {
                    return Step.Failed("No sé la dirección física de «$name» para prenderla: buscala en Ajustes → Casa con la compu prendida.")
                }
                val sent = withContext(Dispatchers.IO) { runCatching { WakeOnLan.send(context, device.macs) }.getOrDefault(false) }
                if (sent) {
                    Step.Done(
                        "Le mandé la señal para prender «$name». Si en un minuto no prende, revisá que tenga Wake-on-LAN " +
                            "activado (la guía está en Ajustes → Casa).",
                    )
                } else {
                    Step.Failed("No pude mandar la señal: ¿estás conectado al wifi de casa?")
                }
            }
            Action.OFF -> Step.Ask(
                PendingAction(
                    question = "¿Apago la compu «$name»?",
                    progress = "Apagando «$name»…",
                    execute = { pcCommand(device, "shutdown", "", "Listo, apago «$name».").text },
                ),
            )
            Action.RESTART -> Step.Ask(
                PendingAction(
                    question = "¿Reinicio la compu «$name»?",
                    progress = "Reiniciando «$name»…",
                    execute = { pcCommand(device, "restart", "", "Listo, reinicio «$name».").text },
                ),
            )
            Action.SLEEP -> done(pcCommand(device, "sleep", "", "Listo, suspendí «$name»."))
            Action.LOCK -> done(pcCommand(device, "lock", "", "Listo, bloqueé «$name»."))
            Action.VOLUME_UP -> done(pcCommand(device, "volume_up", (command.times * 2).toString(), "Listo, subí el volumen."))
            Action.VOLUME_DOWN -> done(pcCommand(device, "volume_down", (command.times * 2).toString(), "Listo, bajé el volumen."))
            Action.MUTE -> done(pcCommand(device, "mute", "", "Listo."))
            Action.PLAY, Action.PAUSE -> done(pcCommand(device, "play_pause", "", "Listo."))
            Action.NEXT -> done(pcCommand(device, "next", "", "Listo."))
            Action.PREVIOUS -> done(pcCommand(device, "previous", "", "Listo."))
            Action.OPEN -> {
                val url = HomeLinks.site(command.arg.orEmpty())
                done(pcCommand(device, "open", url, "Abrí ${command.arg} en la compu «$name»."))
            }
            Action.HOME, Action.BACK -> Step.Failed("Eso lo hago en la tele, no en la compu.")
        }
    }

    /** Lo que contestó la compu, dicho para el usuario. */
    private class PcResult(val ok: Boolean, val text: String)

    private fun done(result: PcResult): Step = if (result.ok) Step.Done(result.text) else Step.Failed(result.text)

    /** Manda el pedido (buscando la compu de nuevo si cambió de dirección); [ok] si salió bien, si no el motivo. */
    private suspend fun pcCommand(device: HomeDevice, cmd: String, arg: String, ok: String): PcResult {
        var current = device
        var result = pcs.send(current, cmd, arg)
        if (result.isFailure) {
            val found = pcs.discover().firstOrNull { it.name == device.foundAs }
            if (found != null && found.host != device.host) {
                current = device.copy(host = found.host, port = found.port, macs = found.macs.ifEmpty { device.macs })
                HomeDevices.save(context, current)
                AgentLog.add("COMPU «${device.name}» cambió de dirección: ${found.host}")
                result = pcs.send(current, cmd, arg)
            }
        }
        val answer = result.getOrElse {
            AgentLog.add("COMPU «${device.name}» no contesta: ${it.message}")
            return PcResult(false, "No me contesta «${device.name}»: ¿está prendida, en el mismo wifi y con el programita de ControlXIA?")
        }
        if (answer.ok) {
            // De paso, si cambió la MAC (otra placa de red), se guarda.
            if (answer.macs.isNotEmpty() && answer.macs != current.macs) HomeDevices.save(context, current.copy(macs = answer.macs))
            return PcResult(true, ok)
        }
        return PcResult(
            false,
            when (answer.message) {
                "codigo" -> "El código no coincide: volvé a vincular «${device.name}» en Ajustes → Casa."
                "hora" -> "La hora de «${device.name}» no coincide con la del teléfono: ajustala y probá de nuevo."
                else -> "«${device.name}» dice: ${answer.message}"
            },
        )
    }

    private fun join(parts: List<String>): String = parts.filter { it.isNotBlank() }.joinToString(" ")

    private companion object {
        const val KEY_WAKEUP = 224
        const val KEY_SLEEP = 223
        const val KEY_GAP_MS = 120L
        const val WAKE_UP_MS = 3_000L
        const val RETRY_SCAN_MS = 2_500L
    }
}
