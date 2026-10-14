package com.controlxia.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.controlxia.app.ui.components.AuraOrb
import com.controlxia.app.ui.components.IconTile
import com.controlxia.app.ui.components.OrbMood
import com.controlxia.app.ui.theme.Aura
import com.controlxia.app.ui.theme.AuraColors
import com.controlxia.app.ui.theme.AuraMotion
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
import com.controlxia.app.service.XiaAccessibilityService
import com.controlxia.app.service.XiaNotificationListener
import com.controlxia.app.ui.components.SectionLabel
import com.controlxia.app.ui.components.StatusDot
import com.controlxia.app.ui.components.TechButton
import com.controlxia.app.ui.components.rememberResumeTick
import com.controlxia.app.ui.theme.Accent
import com.controlxia.app.ui.theme.Muted
import com.controlxia.app.ui.theme.screenTransition
import com.controlxia.app.voice.WakeWordSettings

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
    val overlayOk = remember(tick, bump) { PermissionManager.canDrawOverlays(context) }
    val screenControlOk = remember(tick, bump) { XiaAccessibilityService.isEnabled(context) }
    val messagesOk = remember(tick, bump) { XiaNotificationListener.isEnabled(context) }
    val contactsOk = remember(tick, bump) {
        PermissionManager.hasPhonePermissions(context)
    }
    val oemNeeded = remember { PermissionManager.needsOemSetup() }
    val wakeSettings = remember { WakeWordSettings(context) }

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
        add(PermStep(
            label = "SOBRE OTRAS APPS",
            title = "Que aparezca donde estés.",
            description = "Con este permiso Xia muestra el aviso de escucha encima de " +
                "cualquier app, responde sobre la pantalla bloqueada y puede abrir apps " +
                "y poner alarmas aunque no estés en ControlXIA. Sin él, solo responde por voz. " +
                "Si Android dice que se negó el acceso, abrí la info de la app → ⋮ → " +
                "«Permitir ajustes restringidos» y volvé a intentarlo.",
            granted = overlayOk,
            actionLabel = "Permitir mostrar sobre otras apps",
            action = {
                runCatching {
                    context.startActivity(PermissionManager.overlayPermissionIntent(context))
                }.onFailure {
                    context.startActivity(PermissionManager.appDetailsIntent(context))
                }
            },
        ))
        add(PermStep(
            label = "CONTROL DE APPS",
            title = "Que pueda usar tus apps.",
            description = "Con el control de apps (accesibilidad), Xia lee la pantalla y toca, " +
                "escribe o desplaza por vos: «buscá en Mercado Libre las zapatillas más " +
                "baratas», «abrí los Reels». Antes de enviar, comprar o pagar siempre te " +
                "pregunta. Si Android dice «Ajuste restringido», abrí la info de la app → ⋮ → " +
                "«Permitir ajustes restringidos».",
            granted = screenControlOk,
            actionLabel = "Activar control de apps",
            action = {
                runCatching { context.startActivity(PermissionManager.accessibilitySettingsIntent()) }
            },
        ))
        add(PermStep(
            label = "MENSAJES",
            title = "Leer y responder mensajes.",
            description = "Para leerte los mensajes que llegan y responderlos, Xia necesita el " +
                "acceso a notificaciones. Para escribirle a tus contactos («mandale a mi " +
                "pareja que llego en 10»), los contactos y los SMS. Siempre te pide " +
                "confirmación antes de enviar.",
            granted = messagesOk && contactsOk,
            actionLabel = if (!contactsOk) "Permitir contactos, SMS y llamadas" else "Activar leer mensajes",
            action = {
                if (!contactsOk) {
                    permissionLauncher.launch(
                        PermissionManager.PHONE_PERMISSIONS
                    )
                } else {
                    runCatching { context.startActivity(PermissionManager.notificationAccessIntent()) }
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

    // Páginas: 0 = bienvenida · 1..n = permisos · n+1 = palabra de activación
    // · n+2 = LLM · n+3 = final
    val wakePage = 1 + permSteps.size
    val llmPage = wakePage + 1
    val donePage = llmPage + 1
    val totalPages = donePage + 1
    var page by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        // Progreso: una cápsula que se llena con un resorte.
        val progress by animateFloatAsState((page + 1f) / totalPages, AuraMotion.smooth(), label = "progress")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(Aura.colors.separator),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(Aura.colors.blue),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "${page + 1} de $totalPages",
                style = MaterialTheme.typography.labelMedium,
                color = Muted,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            // Cada paso entra con la transición estándar (fade + rise, expo.out)
            AnimatedContent(
                targetState = page,
                transitionSpec = screenTransition(),
                label = "onboarding-step",
            ) { p ->
            Column {
            when {
                p == 0 -> {
                    Spacer(Modifier.height(12.dp))
                    AuraOrb(Modifier.align(Alignment.CenterHorizontally).size(200.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Hola, soy ${wakeSettings.agentName}.",
                        style = MaterialTheme.typography.displaySmall,
                    )
                    Text(
                        "Tu teléfono, controlado por voz.",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Accent,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Xia escucha tu palabra de activación, entiende pedidos en " +
                            "lenguaje natural y ejecuta acciones reales: abrir apps, " +
                            "poner alarmas y temporizadores, decirte la hora y " +
                            "responder preguntas.\n\n" +
                            "Antes de empezar, vamos a configurar los permisos que la " +
                            "mantienen despierta incluso con la pantalla bloqueada.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Muted,
                    )
                }
                p in 1..permSteps.size -> {
                    val step = permSteps[p - 1]
                    Spacer(Modifier.height(24.dp))
                    val (icon, tint) = permIcon(step.label)
                    IconTile(icon, tint(Aura.colors), size = 64.dp)
                    Spacer(Modifier.height(16.dp))
                    SectionLabel(step.label.lowercase().replaceFirstChar { it.uppercase() })
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
                                true -> "Listo"
                                false -> "Pendiente"
                                null -> "Verificalo vos"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = Muted,
                        )
                    }
                }
                p == wakePage -> {
                    Spacer(Modifier.height(32.dp))
                    SectionLabel("Palabra de activación")
                    Spacer(Modifier.height(16.dp))
                    Text("Ponele nombre.", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Para despertarla, decí su nombre: el que vos quieras. Se " +
                            "reconoce en el teléfono, sin cuentas y sin mandar audio a " +
                            "ningún lado. Solo hace falta bajar una vez el modelo de voz.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Muted,
                    )
                    Spacer(Modifier.height(24.dp))
                    VoiceModelContent()
                    Spacer(Modifier.height(24.dp))
                    AgentNameContent(wakeSettings)
                }
                p == llmPage -> {
                    Spacer(Modifier.height(32.dp))
                    SectionLabel("Cerebro / LLM")
                    Spacer(Modifier.height(16.dp))
                    Text("Elegí quién piensa.", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Xia usa un modelo de lenguaje para entender tus pedidos. " +
                            "Elegí el proveedor, pegá tu API key y probá la conexión. " +
                            "Podés cambiarlo cuando quieras en Ajustes.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Muted,
                    )
                    Spacer(Modifier.height(24.dp))
                    LlmSettingsContent()
                }
                else -> {
                    Spacer(Modifier.height(12.dp))
                    AuraOrb(Modifier.align(Alignment.CenterHorizontally).size(150.dp), mood = OrbMood.SPEAKING)
                    Spacer(Modifier.height(12.dp))
                    Text("Todo listo.", style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "Encendé la escucha y Xia queda disponible incluso con el " +
                            "teléfono bloqueado. Decí “${wakeSettings.spokenWakeWord()}” " +
                            "y después tu pedido. Lo que haya quedado pendiente lo vas " +
                            "a ver marcado en Ajustes → Permisos.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Muted,
                    )
                }
            }
            }
            }
        }

        // Barra de acciones inferior
        AnimatedContent(
            targetState = page,
            transitionSpec = screenTransition(),
            label = "onboarding-actions",
        ) { p ->
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                p == 0 -> TechButton(
                    text = "Empezar",
                    onClick = { page++ },
                    modifier = Modifier.fillMaxWidth(),
                )
                p in 1..permSteps.size -> {
                    val step = permSteps[p - 1]
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
                                if (step.granted == null) "Ya lo activé, continuar"
                                else "Omitir por ahora",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Accent,
                            )
                        }
                    }
                }
                p == wakePage || p == llmPage -> TechButton(
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
                            "Entrar sin encender",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Accent,
                        )
                    }
                }
            }
        }
        }
    }
}

/** El ícono de color de cada permiso. */
private fun permIcon(label: String): Pair<ImageVector, (AuraColors) -> Color> = when {
    label.startsWith("MICR") -> Icons.Rounded.Mic to { it.red }
    label.startsWith("NOTIF") -> Icons.Rounded.Notifications to { it.orange }
    label.startsWith("BATER") -> Icons.Rounded.BatteryChargingFull to { it.green }
    label.startsWith("SOBRE") -> Icons.Rounded.Layers to { it.blue }
    label.startsWith("CONTROL") -> Icons.Rounded.Accessibility to { it.indigo }
    label.startsWith("MENSAJES") -> Icons.AutoMirrored.Rounded.Chat to { it.pink }
    else -> Icons.Rounded.PhoneAndroid to { it.gray }
}
