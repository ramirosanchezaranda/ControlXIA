package com.controlxia.app.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.controlxia.app.brain.LlmSettings
import com.controlxia.app.permissions.PermissionManager
import com.controlxia.app.service.ServicePrefs
import com.controlxia.app.service.WakeWordService
import com.controlxia.app.service.XiaAccessibilityService
import com.controlxia.app.service.XiaNotificationListener
import com.controlxia.app.ui.components.AskField
import com.controlxia.app.ui.components.AuraChip
import com.controlxia.app.ui.components.AuraOrb
import com.controlxia.app.ui.components.AuraSwitch
import com.controlxia.app.ui.components.ChatBubble
import com.controlxia.app.ui.components.Hairline
import com.controlxia.app.ui.components.IconTile
import com.controlxia.app.ui.components.SettingsRow
import com.controlxia.app.ui.components.SmallPillButton
import com.controlxia.app.ui.components.VoiceWave
import com.controlxia.app.ui.components.glass
import com.controlxia.app.ui.components.mood
import com.controlxia.app.ui.components.rememberResumeTick
import com.controlxia.app.ui.theme.Aura
import com.controlxia.app.ui.theme.AuraMotion
import com.controlxia.app.ui.theme.pressable
import com.controlxia.app.ui.theme.staggerReveal
import com.controlxia.app.voice.AssistantState
import com.controlxia.app.voice.WakeWordSettings
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Inicio: la esfera del asistente en el centro (tocala para hablar), una
 * barra para pedir escribiendo, sugerencias, la última charla y el estado de
 * la escucha. En pantallas anchas, la esfera a la izquierda y el resto a la
 * derecha.
 */
@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    wide: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onOpen: (Route) -> Unit,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val tick = rememberResumeTick()
    val agentName = remember(tick) { WakeWordSettings(context).agentName }
    val wakeWord = remember(tick) { WakeWordSettings(context).spokenWakeWord() }
    val assistant by AssistantState.state.collectAsState()
    val level by AssistantState.level.collectAsState()

    // Lo que pasa en el servicio, refrescado cada segundo.
    var serviceOn by remember(tick) { mutableStateOf(ServicePrefs.isEnabled(context)) }
    var running by remember { mutableStateOf(WakeWordService.running) }
    var wakeStatus by remember { mutableStateOf(WakeWordService.wakeWordStatus) }
    var lastCommand by remember { mutableStateOf(WakeWordService.lastCommand) }
    var lastReply by remember { mutableStateOf(WakeWordService.lastReply) }
    var brainError by remember { mutableStateOf(WakeWordService.lastBrainError) }
    LaunchedEffect(Unit) {
        while (true) {
            running = WakeWordService.running
            serviceOn = ServicePrefs.isEnabled(context)
            wakeStatus = WakeWordService.wakeWordStatus
            lastCommand = WakeWordService.lastCommand
            lastReply = WakeWordService.lastReply
            brainError = WakeWordService.lastBrainError
            delay(1_000)
        }
    }
    var permissionsBump by remember { mutableIntStateOf(0) }
    val missing = remember(tick, permissionsBump) { missingPermissions(context) }
    val brainSummary = remember(tick) {
        val settings = LlmSettings(context)
        if (settings.activeConfig() == null) "Sin configurar" else settings.model(settings.activeProvider).ifBlank { settings.activeProvider.displayName }
    }

    val busy = assistant.phase != AssistantState.Phase.IDLE
    val status = when {
        busy -> assistant.status.ifBlank { "Te escucho…" }
        serviceOn && running -> "Decí «${wakeWord.replaceFirstChar { it.uppercase() }}» o tocá la esfera"
        serviceOn -> wakeStatus ?: "Preparando la escucha…"
        else -> "La escucha está apagada. Tocá la esfera para hablar igual."
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty()) return
        if (!WakeWordService.sendText(context, text)) {
            Toast.makeText(context, "Abrí los permisos para poder escucharte", Toast.LENGTH_SHORT).show()
            return
        }
        onDraftChange("")
        focus.clearFocus()
    }

    fun talk() {
        if (!PermissionManager.hasRecordAudio(context)) {
            onOpen(Route.PERMISSIONS)
            return
        }
        WakeWordService.listenNow(context)
    }

    // La esfera con la onda de la voz cruzándola (como la pantalla "Escuchando" de iOS).
    val orb: @Composable ColumnScope.(Dp, Boolean) -> Unit = { orbSize, showTitle ->
        Header(agentName)
        if (showTitle) {
            Spacer(Modifier.height(8.dp))
            Text(
                "¿En qué te ayudo?",
                style = MaterialTheme.typography.headlineLarge,
                color = Aura.colors.label,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().staggerReveal(0),
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(orbSize).staggerReveal(1), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(orbSize)
                    .clip(CircleShape)
                    .pressable(pressedScale = 0.94f, onClick = ::talk),
            ) {
                AuraOrb(Modifier.fillMaxSize(), mood = assistant.phase.mood(), level = level)
            }
            VoiceWave(
                Modifier.fillMaxWidth().height(orbSize * 0.42f),
                level = level,
                active = assistant.phase == AssistantState.Phase.LISTENING,
            )
        }
        Spacer(Modifier.height(10.dp))
        AnimatedContent(
            targetState = status,
            transitionSpec = { fadeIn(AuraMotion.smooth()) togetherWith fadeOut(AuraMotion.smooth()) },
            label = "status",
            modifier = Modifier.fillMaxWidth(),
        ) { text ->
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = Aura.colors.secondaryLabel,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
    }

    val ask: @Composable ColumnScope.() -> Unit = {
        AskField(
            value = draft,
            onValueChange = onDraftChange,
            onSend = ::send,
            onMic = ::talk,
            modifier = Modifier.staggerReveal(2),
        )
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .staggerReveal(3),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SUGGESTIONS.forEach { (text, icon) -> AuraChip(text, icon, onClick = { onDraftChange(text) }) }
        }
    }

    val cards: @Composable ColumnScope.() -> Unit = {
        if (!lastCommand.isNullOrBlank()) {
            Column(
                Modifier.fillMaxWidth().animateContentSize(AuraMotion.smooth()).staggerReveal(4),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChatBubble(lastCommand.orEmpty(), mine = true)
                lastReply?.takeIf { it.isNotBlank() }?.let { ChatBubble(it, mine = false, caption = agentName) }
            }
        }
        // La escucha: interruptor y prueba de voz.
        Column(Modifier.fillMaxWidth().glass().padding(horizontal = 18.dp, vertical = 10.dp).staggerReveal(5)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Rounded.Mic, if (serviceOn && running) Aura.colors.green else Aura.colors.gray)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Escucha siempre activa", style = MaterialTheme.typography.bodyLarge, color = Aura.colors.label)
                    Text(
                        if (serviceOn && running) "Funciona con la pantalla bloqueada" else "Encendela para llamarlo por su nombre",
                        style = MaterialTheme.typography.bodySmall,
                        color = Aura.colors.secondaryLabel,
                    )
                }
                AuraSwitch(
                    checked = serviceOn,
                    enabled = PermissionManager.hasRecordAudio(context) && PermissionManager.hasPostNotifications(context),
                    onCheckedChange = { on ->
                        if (on) {
                            serviceOn = WakeWordService.start(context)
                        } else {
                            WakeWordService.stop(context)
                            serviceOn = false
                        }
                    },
                )
            }
            if (serviceOn) {
                Hairline(Modifier.padding(start = 44.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Probá la voz con el teléfono bloqueado: dice la hora.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Aura.colors.secondaryLabel,
                        modifier = Modifier.weight(1f).padding(start = 44.dp),
                    )
                    SmallPillButton("Probar", onClick = { WakeWordService.speakTime(context) })
                }
            }
        }
        Column(Modifier.fillMaxWidth().glass().padding(horizontal = 18.dp, vertical = 4.dp).staggerReveal(6)) {
            if (missing > 0) {
                SettingsRow(
                    icon = Icons.Rounded.Shield,
                    color = Aura.colors.orange,
                    title = if (missing == 1) "Falta un permiso" else "Faltan $missing permisos",
                    subtitle = "Para que pueda hacer todo, también bloqueado",
                    badge = missing,
                    onClick = {
                        permissionsBump++
                        onOpen(Route.PERMISSIONS)
                    },
                )
                Hairline(Modifier.padding(start = 44.dp))
            }
            SettingsRow(
                icon = Icons.Rounded.Psychology,
                color = Aura.colors.indigo,
                title = "Cerebro",
                subtitle = brainError?.let { "Último error: $it" } ?: "Sin IA igual entiende lo de todos los días",
                value = brainSummary,
                onClick = { onOpen(Route.LLM) },
            )
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (wide) {
            // Horizontal o tablet: la esfera a la izquierda, centrada; pedir y las tarjetas a la derecha.
            val short = maxHeight < 520.dp
            val orbSize = (minOf(maxWidth * 0.3f, maxHeight * if (short) 0.5f else 0.42f)).coerceIn(120.dp, 320.dp)
            Row(
                Modifier.fillMaxSize().padding(
                    start = contentPadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                    end = contentPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                ),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding()),
                    ) { orb(orbSize, !short) }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .widthIn(max = 520.dp)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column { ask() }
                    cards()
                }
            }
        } else {
            val orbSize = (minOf(maxWidth * 0.64f, maxHeight * 0.34f)).coerceIn(150.dp, 300.dp)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier
                        .widthIn(max = MAX_CONTENT)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(contentPadding),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    orb(orbSize, true)
                    Spacer(Modifier.height(18.dp))
                    ask()
                    Spacer(Modifier.height(22.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { cards() }
                }
            }
        }
    }
}

/** Fecha y estado arriba, como el encabezado de iOS. */
@Composable
private fun Header(agentName: String) {
    val today = remember { SimpleDateFormat("EEEE d 'de' MMMM", Locale("es")).format(Date()) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(today.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelMedium, color = Aura.colors.secondaryLabel)
            Text("Hola, soy $agentName", style = MaterialTheme.typography.titleLarge, color = Aura.colors.label)
        }
        LiveBadge()
    }
}

/** Cápsula "● Escuchando" / "En pausa" con el punto latiendo. */
@Composable
private fun LiveBadge() {
    var on by remember { mutableStateOf(WakeWordService.wakeWordActive) }
    LaunchedEffect(Unit) {
        while (true) {
            on = WakeWordService.wakeWordActive
            delay(1_000)
        }
    }
    val colors = Aura.colors
    Row(
        Modifier.glass(CircleShape, strong = true, elevated = false).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) colors.green else colors.gray))
        Spacer(Modifier.width(7.dp))
        Text(if (on) "Escuchando" else "En pausa", style = MaterialTheme.typography.labelMedium, color = colors.label)
    }
}

/** Cuántos permisos importantes faltan. */
fun missingPermissions(context: android.content.Context): Int = listOf(
    PermissionManager.hasRecordAudio(context),
    PermissionManager.hasPostNotifications(context),
    PermissionManager.isIgnoringBatteryOptimizations(context),
    PermissionManager.canDrawOverlays(context),
    XiaAccessibilityService.isEnabled(context),
    XiaNotificationListener.isEnabled(context),
    PermissionManager.hasPhonePermissions(context),
).count { !it }

private val SUGGESTIONS = listOf(
    "Leeme las notificaciones" to Icons.Rounded.Notifications,
    "Mandale a Juan que llego tarde" to Icons.AutoMirrored.Rounded.Send,
    "Poné una alarma a las 7" to Icons.Rounded.Alarm,
    "Buscá recetas en YouTube" to Icons.Rounded.Search,
    "Sacá una foto" to Icons.Rounded.PhotoCamera,
)
