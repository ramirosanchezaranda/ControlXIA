package com.controlxia.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.controlxia.app.brain.PendingAction
import com.controlxia.app.iot.AndroidTv
import com.controlxia.app.iot.HomeCommands.Action
import com.controlxia.app.iot.HomeController
import com.controlxia.app.iot.HomeDevice
import com.controlxia.app.iot.HomeDevices
import com.controlxia.app.iot.PcAgent
import com.controlxia.app.iot.PcProtocol
import com.controlxia.app.iot.TvDiscovery
import com.controlxia.app.ui.components.Hairline
import com.controlxia.app.ui.components.SectionLabel
import com.controlxia.app.ui.components.SmallPillButton
import com.controlxia.app.ui.components.TechButton
import com.controlxia.app.ui.components.TechTopBar
import com.controlxia.app.ui.components.glass
import com.controlxia.app.ui.theme.Aura
import kotlinx.coroutines.launch

/** El comando para instalar el programita en una PC con Windows (PowerShell). */
const val PC_INSTALL_COMMAND =
    "iwr -useb https://raw.githubusercontent.com/ramirosanchezaranda/ControlXIA/claude/voice-control-app-planning-f1uhhu/pc/ControlXIA-PC.ps1 " +
        "-OutFile \$env:TEMP\\ControlXIA-PC.ps1; powershell -ExecutionPolicy Bypass -File \$env:TEMP\\ControlXIA-PC.ps1 -Install"

/**
 * Ajustes → Casa: buscar y vincular las teles Android y las compus con
 * Windows de la red, probarlas, ponerles nombre ("living", "escritorio") y
 * cómo preparar cada una.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DevicesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = Aura.colors
    val devices = HomeDevices.flow(context).collectAsState().value.orEmpty()
    val controller = remember { HomeController(context) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var tvsFound by remember { mutableStateOf<List<TvDiscovery.Found>?>(null) }
    var pcsFound by remember { mutableStateOf<List<PcAgent.Found>?>(null) }
    var tvPairing by remember { mutableStateOf<Pair<TvDiscovery.Found, AndroidTv.Pairing>?>(null) }
    var pcPairing by remember { mutableStateOf<PcAgent.Found?>(null) }
    var renaming by remember { mutableStateOf<HomeDevice?>(null) }
    var confirming by remember { mutableStateOf<PendingAction?>(null) }

    // Si se sale a mitad de una vinculación, se corta la conexión con la tele.
    DisposableEffect(Unit) { onDispose { tvPairing?.second?.close() } }

    fun act(device: HomeDevice, action: Action) {
        if (busy != null) return
        scope.launch {
            busy = "Hablando con «${device.name}»…"
            val outcome = controller.perform(device, action)
            busy = null
            if (outcome.pending != null) confirming = outcome.pending else message = outcome.reply.text
        }
    }

    Column(Modifier.fillMaxSize()) {
        TechTopBar("Casa", onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Prendé y apagá las teles Android y las compus con Windows de tu wifi, subí el volumen o abrí apps: " +
                    "«prendé la tele», «poné Netflix en la tele del living», «apagá la compu». Todo dentro de tu red, sin internet.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.secondaryLabel,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            (busy ?: message)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = if (busy != null) colors.secondaryLabel else colors.blue, modifier = Modifier.padding(horizontal = 4.dp))
            }

            // ---- Teles ----
            SectionLabel("Teles", Modifier.padding(top = 6.dp))
            val tvs = devices.filter { it.kind == HomeDevice.Kind.TV }
            if (tvs.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().glass().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    tvs.forEachIndexed { i, tv ->
                        if (i > 0) Hairline()
                        DeviceRow(tv, onRename = { renaming = tv }, onForget = { HomeDevices.remove(context, tv.id) }) {
                            SmallPillButton("Prender", onClick = { act(tv, Action.ON) })
                            SmallPillButton("Apagar", onClick = { act(tv, Action.OFF) })
                            SmallPillButton("Vol −", onClick = { act(tv, Action.VOLUME_DOWN) })
                            SmallPillButton("Vol +", onClick = { act(tv, Action.VOLUME_UP) })
                        }
                    }
                }
            }
            TechButton(if (tvs.isEmpty()) "Buscar teles" else "Buscar más teles", enabled = busy == null, filled = tvs.isEmpty(), onClick = {
                scope.launch {
                    busy = "Buscando teles en tu wifi…"
                    message = null
                    val found = TvDiscovery(context).scan()
                    tvsFound = found.filter { f -> devices.none { it.kind == HomeDevice.Kind.TV && (it.foundAs == f.name || it.host == f.host) } }
                    busy = null
                    if (found.isEmpty()) message = "No encontré teles. Fijate que estén prendidas y en el mismo wifi que el teléfono."
                    else if (tvsFound.isNullOrEmpty()) message = "Las teles que encontré ya están vinculadas."
                }
            })
            tvsFound?.takeIf { it.isNotEmpty() }?.let { found ->
                Column(Modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    found.forEach { tv ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tv.name, style = MaterialTheme.typography.bodyLarge, color = colors.label)
                                Text(tv.host, style = MaterialTheme.typography.bodySmall, color = colors.secondaryLabel)
                            }
                            SmallPillButton("Vincular", onClick = {
                                if (busy != null) return@SmallPillButton
                                scope.launch {
                                    busy = "Pidiéndole el código a «${tv.name}»…"
                                    runCatching { AndroidTv(context).startPairing(tv.host) }
                                        .onSuccess { tvPairing = tv to it }
                                        .onFailure { message = "No pude conectar con «${tv.name}»: ${it.message}" }
                                    busy = null
                                }
                            })
                        }
                    }
                }
            }
            Tip(
                "Para prenderla cuando está apagada, en la tele activá «Encender con Wi-Fi» o el inicio rápido " +
                    "(Ajustes → Sistema → Energía; el nombre cambia según la marca).",
            )

            // ---- Compus ----
            SectionLabel("Compus con Windows", Modifier.padding(top = 10.dp))
            val pcs = devices.filter { it.kind == HomeDevice.Kind.PC }
            if (pcs.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().glass().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    pcs.forEachIndexed { i, pc ->
                        if (i > 0) Hairline()
                        DeviceRow(pc, onRename = { renaming = pc }, onForget = { HomeDevices.remove(context, pc.id) }) {
                            SmallPillButton("Prender", onClick = { act(pc, Action.ON) })
                            SmallPillButton("Bloquear", onClick = { act(pc, Action.LOCK) })
                            SmallPillButton("Suspender", onClick = { act(pc, Action.SLEEP) })
                            SmallPillButton("Apagar", onClick = { act(pc, Action.OFF) })
                        }
                    }
                }
            }
            TechButton(if (pcs.isEmpty()) "Buscar compus" else "Buscar más compus", enabled = busy == null, filled = pcs.isEmpty(), onClick = {
                scope.launch {
                    busy = "Buscando compus con el programita…"
                    message = null
                    val found = PcAgent(context).discover()
                    pcsFound = found.filter { f -> devices.none { it.kind == HomeDevice.Kind.PC && (it.foundAs == f.name || it.host == f.host) } }
                    busy = null
                    if (found.isEmpty()) message = "No encontré compus. ¿Instalaste el programita (abajo) y están en el mismo wifi?"
                    else if (pcsFound.isNullOrEmpty()) message = "Las compus que encontré ya están vinculadas."
                }
            })
            pcsFound?.takeIf { it.isNotEmpty() }?.let { found ->
                Column(Modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    found.forEach { pc ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(pc.name, style = MaterialTheme.typography.bodyLarge, color = colors.label)
                                Text(pc.host, style = MaterialTheme.typography.bodySmall, color = colors.secondaryLabel)
                            }
                            SmallPillButton("Vincular", onClick = { pcPairing = pc })
                        }
                    }
                }
            }
            PcSetupCard()
            Spacer(Modifier.size(24.dp))
        }
    }

    tvPairing?.let { (tv, pairing) ->
        CodeDialog(
            title = "Vincular «${tv.name}»",
            text = "Mirá la tele: aparece un código de 6 caracteres (números y letras de la A a la F). Escribilo acá.",
            label = "Código de la tele",
            onDismiss = {
                pairing.close()
                tvPairing = null
            },
            onCode = { code ->
                scope.launch {
                    busy = "Vinculando…"
                    val error = pairing.finish(code)
                    tvPairing = null
                    busy = null
                    if (error == null) {
                        HomeDevices.save(
                            context,
                            HomeDevice(kind = HomeDevice.Kind.TV, name = tv.name, foundAs = tv.name, host = tv.host, port = tv.port, pin = pairing.pin),
                        )
                        tvsFound = tvsFound?.filterNot { it == tv }
                        message = "Listo, vinculé «${tv.name}». Ponele un nombre corto (por ejemplo «living») para pedirla por voz."
                    } else {
                        message = "$error Tocá «Vincular» de nuevo."
                    }
                }
            },
        )
    }

    pcPairing?.let { pc ->
        CodeDialog(
            title = "Vincular «${pc.name}»",
            text = "Escribí el código que mostró la compu al instalar el programita (lo ves de nuevo en la compu con ControlXIA-PC.ps1 -Code).",
            label = "Código de la compu",
            onDismiss = { pcPairing = null },
            onCode = { code ->
                scope.launch {
                    busy = "Vinculando…"
                    val answer = PcAgent(context).send(pc.host, pc.port, code, "ping")
                    pcPairing = null
                    busy = null
                    val ok = answer.getOrNull()
                    when {
                        ok?.ok == true -> {
                            HomeDevices.save(
                                context,
                                HomeDevice(
                                    kind = HomeDevice.Kind.PC,
                                    name = pc.name.lowercase().replaceFirstChar { it.uppercase() },
                                    foundAs = pc.name,
                                    host = pc.host,
                                    port = pc.port,
                                    code = PcProtocol.cleanCode(code),
                                    macs = pc.macs.ifEmpty { ok.macs },
                                ),
                            )
                            pcsFound = pcsFound?.filterNot { it == pc }
                            message = "Listo, vinculé «${pc.name}». Probá «bloqueá la compu»."
                        }
                        ok?.message == "codigo" -> message = "Ese código no coincide. Fijate en la compu y probá de nuevo."
                        else -> message = "No pude vincular «${pc.name}»: ${ok?.message ?: answer.exceptionOrNull()?.message}"
                    }
                }
            },
        )
    }

    renaming?.let { device ->
        var name by remember(device.id) { mutableStateOf(device.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Nombre") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Cómo la vas a nombrar por voz: «living», «pieza», «escritorio».", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Nombre") })
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    HomeDevices.save(context, device.copy(name = name.trim()))
                    renaming = null
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancelar") } },
        )
    }

    confirming?.let { action ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(action.question) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    scope.launch {
                        busy = action.progress
                        message = action.execute()
                        busy = null
                    }
                }) { Text("Sí") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("No") } },
        )
    }
}

/** Una tele o compu vinculada: nombre, dirección, botones para probarla, renombrar y olvidar. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceRow(
    device: HomeDevice,
    onRename: () -> Unit,
    onForget: () -> Unit,
    actions: @Composable () -> Unit,
) {
    val colors = Aura.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.bodyLarge, color = colors.label)
                Text(
                    device.host + if (device.kind == HomeDevice.Kind.PC && device.macs.isEmpty()) " · sin MAC (no la puedo prender)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondaryLabel,
                )
            }
            TextButton(onClick = onRename) { Text("Nombre") }
            TextButton(onClick = onForget) { Text("Olvidar", color = colors.red) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

@Composable
private fun CodeDialog(title: String, text: String, label: String, onDismiss: () -> Unit, onCode: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = code, onValueChange = { code = it }, singleLine = true, label = { Text(label) })
            }
        },
        confirmButton = { TextButton(enabled = code.isNotBlank(), onClick = { onCode(code.trim()) }) { Text("Vincular") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun Tip(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Aura.colors.secondaryLabel, modifier = Modifier.padding(horizontal = 4.dp))
}

/** Cómo instalar el programita en cada PC, y cómo dejarla lista para prenderla desde el teléfono. */
@Composable
private fun PcSetupCard() {
    val context = LocalContext.current
    val colors = Aura.colors
    Column(Modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("El programita para Windows", style = MaterialTheme.typography.titleSmall, color = colors.label)
        Text(
            "1. En cada compu abrí PowerShell (tecla Windows, escribí «PowerShell») y pegá este comando:",
            style = MaterialTheme.typography.bodySmall,
            color = colors.secondaryLabel,
        )
        Text(PC_INSTALL_COMMAND, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = colors.label)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallPillButton("Copiar", onClick = {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("ControlXIA", PC_INSTALL_COMMAND))
                Toast.makeText(context, "Copiado", Toast.LENGTH_SHORT).show()
            })
            SmallPillButton("Mandarlo a la compu", onClick = {
                val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, PC_INSTALL_COMMAND)
                runCatching { context.startActivity(Intent.createChooser(share, "Mandar el comando").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            })
        }
        listOf(
            "Te muestra un código: lo escribís acá al vincularla.",
            "Si Windows pregunta, permití el acceso en redes privadas (y que tu wifi esté como red privada).",
            "Arranca solo cada vez que entrás a Windows. Para sacarlo: ControlXIA-PC.ps1 -Uninstall.",
            "Para prenderla desde el teléfono (Wake-on-LAN): mejor por cable de red, con «Wake on LAN» activado en la BIOS. " +
                "Al instalar, aceptá el permiso de administrador: deja lista la placa de red y apaga el «inicio rápido» de Windows.",
        ).forEachIndexed { i, step ->
            Text("${i + 2}. $step", style = MaterialTheme.typography.bodySmall, color = colors.secondaryLabel)
        }
    }
}
