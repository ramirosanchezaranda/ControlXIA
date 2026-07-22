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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.controlxia.app.permissions.PermissionManager
import com.controlxia.app.service.WakeWordService
import com.controlxia.app.ui.components.Hairline
import com.controlxia.app.ui.components.SectionLabel
import com.controlxia.app.ui.components.StatusDot
import com.controlxia.app.ui.components.TechButton
import com.controlxia.app.ui.components.rememberResumeTick
import com.controlxia.app.ui.theme.Accent
import com.controlxia.app.ui.theme.Muted
import java.util.Locale

/**
 * Configuración inicial: presenta la app y pide los permisos de a uno, con
 * contexto, antes de llegar al dashboard. Cada paso se puede omitir; el
 * dashboard después muestra lo que quedó pendiente.
 */
@Composable
fun OnboardingFlow(onFinished: () -> Unit) {
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

    // Un paso de permiso: estado (null = no verificable por API) + acción.
    class PermStep(
        val label: String,
        val title: String,
        val description: String,
        val granted: Boolean?,
        val actionLabel: String,
        val action: () -> Unit,
    )

    val permSteps = buildList {
        add(PermStep(
            label = "MICRÓFONO",
            title = "Xia necesita escucharte.",
            description = "El micrófono se usa únicamente para detectar la palabra de " +
                "activación y transcribir tus pedidos. Nada se graba ni se sube.",
            granted = micOk,
            actionLabel = "Permitir micrófono",
            action = { permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) },
        ))
        add(PermStep(
            label = "NOTIFICACIONES",
            title = "Una notificación fija la mantiene viva.",
            description = "Android solo permite escuchar en segundo plano si hay una " +
                "notificación visible. Es silenciosa y desde ahí también podés probar la voz.",
            granted = notifOk,
            actionLabel = "Permitir notificaciones",
            action = { permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) },
        ))
        add(PermStep(
            label = "BATERÍA",
            title = "Sin restricción de batería.",
            description = "Con el ahorro de energía activado, el sistema apaga la escucha " +
                "después de una hora con la pantalla bloqueada. Esta exención lo evita.",
            granted = batteryOk,
            actionLabel = "Quitar restricción",
            action = {
                runCatching {
                    context.startActivity(PermissionManager.batteryExemptionIntent(context))
                }.onFailure {
                    context.startActivity(PermissionManager.appDetailsIntent(context))
                }
            },
        ))
        if (oemNeeded) {
            add(PermStep(
                label = "FABRICANTE / ${PermissionManager.oemName().uppercase()}",
                title = "Tu ${PermissionManager.oemName()} tiene su propio ahorro.",
                description = "Algunos fabricantes matan las apps en segundo plano por su " +
                    "cuenta. Activá el inicio automático para ControlXIA en la pantalla " +
                    "que se abre. Este paso no se puede verificar automáticamente.",
                granted = null,
                actionLabel = "Abrir ajustes de ${PermissionManager.oemName()}",
                action = {
                    val intent = PermissionManager.oemSettingsIntent(context)
                        ?: PermissionManager.appDetailsIntent(context)
                    runCatching { context.startActivity(intent) }.onFailure {
                        context.startActivity(PermissionManager.appDetailsIntent(context))
                    }
                },
            ))
        }
    }

    // Páginas: 0 = bienvenida · 1..n = permisos · n+1 = LLM · n+2 = final
    val llmPage = 1 + permSteps.size
    val donePage = llmPage + 1
    val totalPages = donePage + 1
    var page by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        // Encabezado técnico con progreso
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("XIA", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            Text(
                String.format(Locale.ROOT, "CONFIG %02d / %02d", page + 1, totalPages),
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
        }
        Hairline()

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            when {
                page == 0 -> {
                    Spacer(Modifier.height(32.dp))
                    SectionLabel("Configuración inicial")
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Tu teléfono,\ncontrolado\npor voz.",
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "Xia escucha tu nombre de activación, entiende pedidos en " +
                            "lenguaje natural y ejecuta acciones reales: leer mensajes, " +
                            "escribir, abrir apps, decirte la hora.\n\n" +
                            "Antes de empezar, vamos a configurar los permisos que la " +
                            "mantienen despierta incluso con la pantalla bloqueada.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Muted,
                    )
                }
                page in 1..permSteps.size -> {
                    val step = permSteps[page - 1]
                    Spacer(Modifier.height(32.dp))
                    Text(
                        String.format(Locale.ROOT, "%02d", page),
                        style = MaterialTheme.typography.labelLarge,
                        color = Accent,
                    )
                    Spacer(Modifier.height(8.dp))
                    SectionLabel(step.label)
                    Spacer(Modifier.height(16.dp))
                    Text(step.title, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        step.description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Muted,
                    )
                    Spacer(Modifier.height(24.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(step.granted)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            when (step.granted) {
                                true -> "CONCEDIDO"
                                false -> "PENDIENTE"
                                null -> "VERIFICACIÓN MANUAL"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Muted,
                        )
                    }
                }
                page == llmPage -> {
                    Spacer(Modifier.height(32.dp))
                    SectionLabel("Cerebro / LLM")
                    Spacer(Modifier.height(16.dp))
                    Text("Elegí quién piensa.", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Xia usa un modelo de lenguaje para entender tus pedidos. " +
                            "Elegí el proveedor, pegá tu API key y probá la conexión. " +
                            "Podés cambiarlo cuando quieras desde el panel.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Muted,
                    )
                    Spacer(Modifier.height(24.dp))
                    LlmSettingsContent()
                }
                else -> {
                    Spacer(Modifier.height(32.dp))
                    SectionLabel("Listo")
                    Spacer(Modifier.height(16.dp))
                    Text("Todo\nconfigurado.", style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "Encendé la escucha y Xia queda disponible incluso con el " +
                            "teléfono bloqueado. Lo que haya quedado pendiente lo vas " +
                            "a ver marcado en el panel.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Muted,
                    )
                }
            }
        }

        // Barra de acciones inferior
        Hairline()
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                page == 0 -> TechButton(
                    text = "Empezar",
                    onClick = { page++ },
                    modifier = Modifier.fillMaxWidth(),
                )
                page in 1..permSteps.size -> {
                    val step = permSteps[page - 1]
                    if (step.granted == true) {
                        TechButton(
                            text = "Continuar",
                            onClick = { page++ },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        TechButton(
                            text = step.actionLabel,
                            onClick = step.action,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(
                            onClick = { page++ },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (step.granted == null) "YA LO ACTIVÉ / CONTINUAR"
                                else "OMITIR POR AHORA",
                                style = MaterialTheme.typography.labelSmall,
                                color = Muted,
                            )
                        }
                    }
                }
                page == llmPage -> TechButton(
                    text = "Continuar",
                    onClick = { page++ },
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> {
                    TechButton(
                        text = "Encender y entrar",
                        onClick = {
                            WakeWordService.start(context)
                            onFinished()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = micOk && notifOk,
                    )
                    TextButton(onClick = onFinished, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "ENTRAR SIN ENCENDER",
                            style = MaterialTheme.typography.labelSmall,
                            color = Muted,
                        )
                    }
                }
            }
        }
    }
}
