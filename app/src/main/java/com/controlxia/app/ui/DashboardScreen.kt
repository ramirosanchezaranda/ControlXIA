package com.controlxia.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.controlxia.app.brain.LlmSettings
import com.controlxia.app.diagnostics.AgentLog
import com.controlxia.app.permissions.PermissionManager
import com.controlxia.app.service.ServicePrefs
import com.controlxia.app.service.WakeWordService
import com.controlxia.app.service.XiaAccessibilityService
import com.controlxia.app.service.XiaNotificationListener
import com.controlxia.app.ui.components.Hairline
import com.controlxia.app.ui.components.SectionLabel
import com.controlxia.app.ui.components.StatusDot
import com.controlxia.app.ui.components.StatusRow
import com.controlxia.app.ui.components.TechButton
import com.controlxia.app.ui.components.TechPanel
import com.controlxia.app.ui.components.rememberResumeTick
import com.controlxia.app.ui.theme.Accent
import com.controlxia.app.ui.theme.Muted
import com.controlxia.app.ui.theme.staggerReveal
import com.controlxia.app.voice.RecentCommandsStore
import com.controlxia.app.voice.VoiceSettings
import com.controlxia.app.voice.WakeWordSettings
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Panel principal: estado del servicio, checklist del sistema, cerebro
 * configurado y acceso al canal de feedback.
 */
@Composable
fun DashboardScreen(
    onOpenLlm: () -> Unit,
    onOpenFeedback: () -> Unit,
    onOpenWakeWord: () -> Unit,
    onOpenVoice: () -> Unit,
) {
    val context = LocalContext.current
    val tick = rememberResumeTick()
    var bump by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { bump++ }

    val micOk = remember(tick, bump) { PermissionManager.hasRecordAudio(context) }
    val notifOk = remember(tick, bump) { PermissionManager.hasPostNotifications(context) }
    val batteryOk = remember(tick, bump) { PermissionManager.isIgnoringBatteryOptimizations(context) }
    val overlayOk = remember(tick, bump) { PermissionManager.canDrawOverlays(context) }
    val screenControlOk = remember(tick, bump) { XiaAccessibilityService.isEnabled(context) }
    val messagesOk = remember(tick, bump) { XiaNotificationListener.isEnabled(context) }
    val contactsOk = remember(tick, bump) {
        PermissionManager.hasPhonePermissions(context)
    }
    val oemNeeded = remember { PermissionManager.needsOemSetup() }
    val llmConfigured = remember(tick, bump) { LlmSettings(context).activeConfig() != null }
    val llmSummary = remember(tick, bump) {
        val settings = LlmSettings(context)
        "${settings.activeProvider.displayName} · ${settings.model(settings.activeProvider).ifBlank { "sin modelo" }}"
    }
    val agentName = remember(tick, bump) { WakeWordSettings(context).agentName }
    val wakeWord = remember(tick, bump) { WakeWordSettings(context).spokenWakeWord() }
    val voiceSummary = remember(tick, bump) {
        val vs = VoiceSettings(context)
        val name = vs.voiceName?.substringBefore("#")?.take(22) ?: "Voz por defecto"
        "$name · tono ${(vs.pitch * 100).toInt()}%"
    }

    // Lo que decidió el usuario (encendido/apagado), separado de si el
    // servicio realmente está corriendo: Android puede haberlo frenado.
    var serviceOn by remember(tick, bump) { mutableStateOf(ServicePrefs.isEnabled(context)) }

    // Estado en vivo del servicio: se refresca cada segundo para que el panel
    // muestre al instante si el wake word arrancó, falló o hubo un error.
    var running by remember { mutableStateOf(WakeWordService.running) }
    var wakeActive by remember { mutableStateOf(WakeWordService.wakeWordActive) }
    var wakeStatus by remember { mutableStateOf(WakeWordService.wakeWordStatus) }
    var wakeFailed by remember { mutableStateOf(WakeWordService.wakeWordFailed) }
    var brainError by remember { mutableStateOf(WakeWordService.lastBrainError) }
    var lastReply by remember { mutableStateOf(WakeWordService.lastReply) }
    var logVersion by remember { mutableIntStateOf(AgentLog.version) }
    LaunchedEffect(Unit) {
        while (true) {
            running = WakeWordService.running
            wakeActive = WakeWordService.wakeWordActive
            wakeStatus = WakeWordService.wakeWordStatus
            wakeFailed = WakeWordService.wakeWordFailed
            brainError = WakeWordService.lastBrainError
            lastReply = WakeWordService.lastReply
            logVersion = AgentLog.version
            delay(1_000)
        }
    }
    val recentCommands = remember(tick, bump, lastReply) { RecentCommandsStore(context).all() }

    // Reloj del encabezado, como los "LOCAL TIME" de la referencia.
    var now by remember { mutableStateOf(currentTime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = currentTime()
            delay(15_000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("CONTROLXIA", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            Text("HORA LOCAL  $now", style = MaterialTheme.typography.labelSmall, color = Muted)
        }
        Hairline()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Estado principal
            Column(Modifier.staggerReveal(0)) {
                Spacer(Modifier.height(12.dp))
                Text(
                    if (serviceOn) "$agentName está\nescuchando." else "$agentName está\nen pausa.",
                    style = MaterialTheme.typography.headlineLarge,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (serviceOn && running) true else null)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when {
                            serviceOn && running -> "SERVICIO ACTIVO — SOBREVIVE BLOQUEO Y REINICIO"
                            serviceOn -> "INICIANDO…"
                            else -> "SERVICIO INACTIVO"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                    )
                }
                Spacer(Modifier.height(20.dp))
                TechButton(
                    text = if (serviceOn) "Apagar escucha" else "Encender escucha",
                    filled = !serviceOn,
                    enabled = micOk && notifOk,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (serviceOn) {
                            WakeWordService.stop(context)
                            serviceOn = false
                        } else {
                            serviceOn = WakeWordService.start(context)
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                TechButton(
                    text = "Probar voz — dice la hora",
                    filled = false,
                    enabled = serviceOn,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { WakeWordService.speakTime(context) },
                )
                if (serviceOn) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Probalo con la pantalla bloqueada: el botón también está " +
                            "en la notificación.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
            }

            // Sistema
            Column(Modifier.staggerReveal(1)) {
                SectionLabel("Sistema")
                Spacer(Modifier.height(12.dp))
                TechPanel {
                    StatusRow(
                        label = "Micrófono",
                        ok = micOk,
                        actionLabel = "Permitir",
                        onAction = {
                            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                        },
                    )
                    Hairline()
                    StatusRow(
                        label = "Notificaciones",
                        ok = notifOk,
                        actionLabel = "Permitir",
                        onAction = {
                            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        },
                    )
                    Hairline()
                    StatusRow(
                        label = "Batería sin restricción",
                        ok = batteryOk,
                        actionLabel = "Configurar",
                        onAction = {
                            runCatching {
                                context.startActivity(
                                    PermissionManager.batteryExemptionIntent(context)
                                )
                            }.onFailure {
                                context.startActivity(PermissionManager.appDetailsIntent(context))
                            }
                        },
                    )
                    Hairline()
                    StatusRow(
                        label = "Abrir apps bloqueado",
                        ok = if (overlayOk) true else null,
                        actionLabel = "Permitir",
                        onAction = {
                            runCatching {
                                context.startActivity(
                                    PermissionManager.overlayPermissionIntent(context)
                                )
                            }.onFailure {
                                context.startActivity(PermissionManager.appDetailsIntent(context))
                            }
                        },
                    )
                    Hairline()
                    StatusRow(
                        label = "Control de apps",
                        ok = if (screenControlOk) true else null,
                        actionLabel = "Activar",
                        onAction = {
                            runCatching {
                                context.startActivity(PermissionManager.accessibilitySettingsIntent())
                            }
                        },
                    )
                    Hairline()
                    StatusRow(
                        label = "Leer mensajes",
                        ok = if (messagesOk) true else null,
                        actionLabel = "Activar",
                        onAction = {
                            runCatching {
                                context.startActivity(PermissionManager.notificationAccessIntent())
                            }
                        },
                    )
                    Hairline()
                    StatusRow(
                        label = "Contactos, SMS y llamadas",
                        ok = if (contactsOk) true else null,
                        actionLabel = "Permitir",
                        onAction = {
                            permissionLauncher.launch(
                                PermissionManager.PHONE_PERMISSIONS
                            )
                        },
                    )
                    if (!overlayOk || !screenControlOk || !messagesOk) {
                        RestrictedSettingsHint()
                    }
                    if (oemNeeded) {
                        Hairline()
                        StatusRow(
                            label = "Autostart ${PermissionManager.oemName()}",
                            ok = null,
                            actionLabel = "Abrir",
                            onAction = {
                                val intent = PermissionManager.oemSettingsIntent(context)
                                    ?: PermissionManager.appDetailsIntent(context)
                                runCatching { context.startActivity(intent) }.onFailure {
                                    context.startActivity(
                                        PermissionManager.appDetailsIntent(context)
                                    )
                                }
                            },
                        )
                    }
                }
            }

            // Wake word (palabra de activación)
            Column(Modifier.staggerReveal(2)) {
                SectionLabel("Palabra de activación")
                Spacer(Modifier.height(12.dp))
                TechPanel {
                    // Con el servicio corriendo, el estado trae lo que pasa en vivo:
                    // preparando el modelo, escuchando o el motivo de un error.
                    val live = running && wakeStatus != null
                    StatusRow(
                        label = if (live) wakeStatus.orEmpty() else "Decí “$wakeWord” — se activa con la escucha",
                        ok = when {
                            wakeActive -> true
                            live && wakeFailed -> false
                            else -> null
                        },
                    )
                    // Sin el modelo de voz no hay palabra de activación: botón para bajarlo.
                    VoiceModelContent(Modifier.padding(top = 8.dp), showWhenReady = false)
                    Spacer(Modifier.height(8.dp))
                    TechButton(
                        text = "Ajustar nombre y sensibilidad",
                        filled = false,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onOpenWakeWord,
                    )
                }
            }

            // Actividad reciente (transcripciones tras el wake word)
            Column(Modifier.staggerReveal(3)) {
                SectionLabel("Actividad reciente")
                Spacer(Modifier.height(12.dp))
                TechPanel {
                    if (recentCommands.isEmpty()) {
                        Text(
                            "Todavía no escuché ningún comando. Encendé la escucha, " +
                                "decí la palabra de activación y hablá.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Muted,
                        )
                    } else {
                        recentCommands.asReversed().forEachIndexed { i, cmd ->
                            if (i > 0) Hairline()
                            Column(Modifier.padding(vertical = 10.dp)) {
                                Text(
                                    formatClock(cmd.at),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Muted,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text("“${cmd.text}”", style = MaterialTheme.typography.bodyMedium)
                                cmd.reply?.let { reply ->
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "→ $reply",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Accent,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Registro del agente: para entender qué pasó en un pedido que falló.
            Column(Modifier.staggerReveal(4)) {
                SectionLabel("Registro del agente")
                Spacer(Modifier.height(12.dp))
                TechPanel {
                    val lines = remember(logVersion) { AgentLog.recent(14) }
                    if (lines.isEmpty()) {
                        Text(
                            "Acá vas a ver paso a paso qué hizo el asistente en cada pedido.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Muted,
                        )
                    } else {
                        lines.forEach { entry ->
                            Text(
                                "${formatSeconds(entry.time)}  ${entry.text}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row {
                            TextButton(onClick = {
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(
                                    ClipData.newPlainText("Registro ControlXIA", AgentLog.asText())
                                )
                                Toast.makeText(context, "Registro copiado", Toast.LENGTH_SHORT).show()
                            }) {
                                Text("COPIAR REGISTRO", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { AgentLog.clear() }) {
                                Text("BORRAR", style = MaterialTheme.typography.labelSmall, color = Muted)
                            }
                        }
                    }
                }
            }

            // Cerebro
            Column(Modifier.staggerReveal(4)) {
                SectionLabel("Cerebro / LLM")
                Spacer(Modifier.height(12.dp))
                TechPanel {
                    StatusRow(label = llmSummary, ok = llmConfigured)
                    brainError?.let { error ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Último error: $error",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TechButton(
                        text = if (llmConfigured) "Cambiar" else "Configurar",
                        filled = false,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onOpenLlm,
                    )
                }
            }

            // Voz
            Column(Modifier.staggerReveal(5)) {
                SectionLabel("Voz")
                Spacer(Modifier.height(12.dp))
                TechPanel {
                    StatusRow(label = voiceSummary, ok = true)
                    Spacer(Modifier.height(8.dp))
                    TechButton(
                        text = "Cambiar voz y tono",
                        filled = false,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onOpenVoice,
                    )
                }
            }

            // Feedback
            Column(Modifier.staggerReveal(6)) {
                SectionLabel("Feedback")
                Spacer(Modifier.height(12.dp))
                TechPanel {
                    Text(
                        "Contale a Xia qué falló o qué te gustaría que haga. " +
                            "El LLM analiza tu comentario y queda registrado para " +
                            "las próximas mejoras.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                    )
                    Spacer(Modifier.height(12.dp))
                    TechButton(
                        text = "Enviar feedback",
                        filled = false,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onOpenFeedback,
                    )
                }
            }
        }
    }
}

private fun currentTime(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

private fun formatSeconds(timestamp: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

private fun formatClock(timestamp: Long): String =
    SimpleDateFormat("dd.MM  HH:mm", Locale.getDefault()).format(Date(timestamp))

/**
 * En Android 13+, a las apps instaladas desde un archivo el sistema les
 * bloquea la accesibilidad y el acceso a notificaciones ("ajuste
 * restringido") hasta que el usuario lo permite desde la info de la app.
 */
@Composable
private fun RestrictedSettingsHint() {
    val context = LocalContext.current
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            "Si Android dice «Ajuste restringido»: abrí la info de la app → menú ⋮ → " +
                "«Permitir ajustes restringidos», y volvé a activarlo.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
        TextButton(onClick = {
            runCatching { context.startActivity(PermissionManager.appDetailsIntent(context)) }
        }) {
            Text("ABRIR INFO DE LA APP", style = MaterialTheme.typography.labelSmall)
        }
    }
}
