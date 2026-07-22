package com.controlxia.app.ui

import android.Manifest
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
import com.controlxia.app.permissions.PermissionManager
import com.controlxia.app.service.ServicePrefs
import com.controlxia.app.service.WakeWordService
import com.controlxia.app.ui.components.Hairline
import com.controlxia.app.ui.components.SectionLabel
import com.controlxia.app.ui.components.StatusDot
import com.controlxia.app.ui.components.StatusRow
import com.controlxia.app.ui.components.TechButton
import com.controlxia.app.ui.components.TechPanel
import com.controlxia.app.ui.components.rememberResumeTick
import com.controlxia.app.ui.theme.Muted
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
    val oemNeeded = remember { PermissionManager.needsOemSetup() }
    val llmConfigured = remember(tick, bump) { LlmSettings(context).activeConfig() != null }
    val llmSummary = remember(tick, bump) {
        val settings = LlmSettings(context)
        "${settings.activeProvider.displayName} · ${settings.model(settings.activeProvider).ifBlank { "sin modelo" }}"
    }

    var serviceOn by remember(tick, bump) {
        mutableStateOf(ServicePrefs.isEnabled(context) || WakeWordService.running)
    }

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
            Column {
                Spacer(Modifier.height(12.dp))
                Text(
                    if (serviceOn) "Xia está\nescuchando." else "Xia está\nen pausa.",
                    style = MaterialTheme.typography.headlineLarge,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (serviceOn) true else null)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (serviceOn) "SERVICIO ACTIVO — SOBREVIVE BLOQUEO Y REINICIO"
                        else "SERVICIO INACTIVO",
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
                        serviceOn = !serviceOn
                        if (serviceOn) WakeWordService.start(context)
                        else WakeWordService.stop(context)
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
            Column {
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

            // Cerebro
            Column {
                SectionLabel("Cerebro / LLM")
                Spacer(Modifier.height(12.dp))
                TechPanel {
                    StatusRow(label = llmSummary, ok = llmConfigured)
                    Spacer(Modifier.height(8.dp))
                    TechButton(
                        text = if (llmConfigured) "Cambiar" else "Configurar",
                        filled = false,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onOpenLlm,
                    )
                }
            }

            // Feedback
            Column {
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
