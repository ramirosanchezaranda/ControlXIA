package com.controlxia.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.BackHand
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.controlxia.app.brain.LlmSettings
import com.controlxia.app.iot.HomeDevices
import com.controlxia.app.permissions.A11yHealth
import com.controlxia.app.permissions.PermissionManager
import com.controlxia.app.service.XiaNotificationListener
import com.controlxia.app.ui.components.AuraOrb
import com.controlxia.app.ui.components.Hairline
import com.controlxia.app.ui.components.IconTile
import com.controlxia.app.ui.components.LargeTitle
import com.controlxia.app.ui.components.SectionLabel
import com.controlxia.app.ui.components.SegmentedControl
import com.controlxia.app.ui.components.SettingsRow
import com.controlxia.app.ui.components.SmallPillButton
import com.controlxia.app.ui.components.TechTopBar
import com.controlxia.app.ui.components.glass
import com.controlxia.app.ui.components.rememberResumeTick
import com.controlxia.app.ui.theme.Aura
import com.controlxia.app.ui.theme.Palette
import com.controlxia.app.ui.theme.ThemeMode
import com.controlxia.app.ui.theme.ThemeSettings
import com.controlxia.app.ui.theme.auraColors
import com.controlxia.app.ui.theme.pressable
import com.controlxia.app.ui.theme.staggerReveal
import com.controlxia.app.voice.VoiceSettings
import com.controlxia.app.voice.WakeWordSettings

/** Ajustes, como los de iOS: el asistente arriba y grupos con íconos de color. */
@Composable
fun SettingsScreen(contentPadding: PaddingValues, onOpen: (Route) -> Unit, onExplore: () -> Unit) {
    val context = LocalContext.current
    val tick = rememberResumeTick()
    val colors = Aura.colors
    val wake = remember(tick) { WakeWordSettings(context) }
    val missing = remember(tick) { missingPermissions(context) }
    val brain = remember(tick) {
        val settings = LlmSettings(context)
        if (settings.activeConfig() == null) "Sin configurar" else settings.activeProvider.displayName
    }
    val voice = remember(tick) {
        VoiceSettings(context).voiceName?.substringBefore("#")?.substringAfterLast('-')?.take(14) ?: "Del sistema"
    }
    val homeCount = HomeDevices.flow(context).collectAsState().value.orEmpty().size
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = MAX_CONTENT)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LargeTitle("Ajustes", Modifier.padding(bottom = 8.dp))

            // El asistente, como la tarjeta de perfil de iOS.
            Row(
                Modifier
                    .fillMaxWidth()
                    .glass()
                    .pressable(pressedScale = 0.98f, onClick = { onOpen(Route.WAKEWORD) })
                    .padding(16.dp)
                    .staggerReveal(0),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AuraOrb(Modifier.size(64.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(wake.agentName, style = MaterialTheme.typography.titleLarge, color = colors.label)
                    Text(
                        "Decí «${wake.spokenWakeWord()}» para llamarlo",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.secondaryLabel,
                    )
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.tertiaryLabel)
            }

            SectionLabel("Asistente", Modifier.padding(top = 10.dp))
            Group(1) {
                SettingsRow(Icons.Rounded.RecordVoiceOver, colors.purple, "Nombre y activación", value = wake.spokenWakeWord(), onClick = { onOpen(Route.WAKEWORD) })
                Divider()
                SettingsRow(Icons.Rounded.GraphicEq, colors.pink, "Voz", value = voice, onClick = { onOpen(Route.VOICE) })
                Divider()
                SettingsRow(Icons.Rounded.Psychology, colors.indigo, "Cerebro (IA)", value = brain, onClick = { onOpen(Route.LLM) })
                Divider()
                SettingsRow(
                    Icons.Rounded.BackHand,
                    colors.teal,
                    "Gestos con las manos",
                    subtitle = "Deslizar con la palma, me gusta, pausa, videos",
                    value = if (wake.handsPause) "Sí" else "No",
                    onClick = { onOpen(Route.WAKEWORD) },
                )
                Divider()
                SettingsRow(
                    Icons.Rounded.Tv,
                    colors.orange,
                    "Casa: teles y compus",
                    subtitle = "Prenderlas, apagarlas, volumen y apps",
                    value = homeCount.takeIf { it > 0 }?.toString(),
                    onClick = { onOpen(Route.DEVICES) },
                )
            }

            SectionLabel("Apariencia", Modifier.padding(top = 10.dp))
            AppearanceGroup()

            SectionLabel("Sistema", Modifier.padding(top = 10.dp))
            Group(2) {
                SettingsRow(
                    Icons.Rounded.Shield,
                    colors.green,
                    "Permisos",
                    subtitle = if (missing == 0) "Todo listo" else "Para que funcione también bloqueado",
                    badge = missing,
                    onClick = { onOpen(Route.PERMISSIONS) },
                )
            }

            SectionLabel("Ayuda", Modifier.padding(top = 10.dp))
            Group(3) {
                SettingsRow(Icons.AutoMirrored.Rounded.HelpOutline, colors.blue, "Qué puedo decir", onClick = onExplore)
                Divider()
                SettingsRow(Icons.AutoMirrored.Rounded.Chat, colors.orange, "Contanos qué mejorar", onClick = { onOpen(Route.FEEDBACK) })
                Divider()
                SettingsRow(Icons.Rounded.Info, colors.gray, "Versión", value = version)
            }
        }
    }
}

/** Claro u oscuro, y la paleta de colores (cambia en vivo). */
@Composable
private fun AppearanceGroup() {
    val context = LocalContext.current
    val choice by ThemeSettings.state.collectAsState()
    val colors = Aura.colors
    Column(
        Modifier.fillMaxWidth().glass().padding(16.dp).staggerReveal(2),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SegmentedControl(
            options = ThemeMode.entries.map { it.label },
            selected = choice.mode.ordinal,
            onSelect = { ThemeSettings.setMode(context, ThemeMode.entries[it]) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Palette.entries.forEach { palette ->
                PaletteCard(
                    palette = palette,
                    selected = palette == choice.palette,
                    onClick = { ThemeSettings.setPalette(context, palette) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            Palette.entries.first { it == choice.palette }.description,
            style = MaterialTheme.typography.bodySmall,
            color = colors.secondaryLabel,
        )
    }
}

/** Una paleta: sus colores en círculos superpuestos y el nombre; la elegida, con borde. */
@Composable
private fun PaletteCard(palette: Palette, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = Aura.colors
    val swatch = auraColors(palette, colors.dark)
    val border by animateColorAsState(if (selected) colors.blue else Color.Transparent, label = "palette-border")
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(swatch.background)
            .border(2.dp, border, RoundedCornerShape(18.dp))
            .pressable(pressedScale = 0.96f, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
            listOf(swatch.blue, swatch.orb.lobes[1], swatch.orb.lobes[2], swatch.label).forEach { c ->
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(1.5.dp, swatch.background, CircleShape),
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        Text(palette.label, style = MaterialTheme.typography.labelMedium, color = swatch.label, maxLines = 1)
    }
}

/** Grupo de filas en una tarjeta de vidrio. */
@Composable
private fun Group(index: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().glass().padding(horizontal = 16.dp, vertical = 4.dp).staggerReveal(index),
        content = content,
    )
}

/** Separador con sangría (empieza después del ícono, como en iOS). */
@Composable
private fun Divider() = Hairline(Modifier.padding(start = 44.dp))

/**
 * Permisos: cada uno con su ícono, para qué sirve y un botón para darlo. Lo
 * que estaba en el panel viejo, ordenado y explicado.
 */
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val tick = rememberResumeTick()
    var bump by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { bump++ }
    val colors = Aura.colors

    val micOk = remember(tick, bump) { PermissionManager.hasRecordAudio(context) }
    val notifOk = remember(tick, bump) { PermissionManager.hasPostNotifications(context) }
    val batteryOk = remember(tick, bump) { PermissionManager.isIgnoringBatteryOptimizations(context) }
    val overlayOk = remember(tick, bump) { PermissionManager.canDrawOverlays(context) }
    val a11yConnected by A11yHealth.connected.collectAsState()
    val a11yState = remember(tick, bump, a11yConnected) { A11yHealth.state(context) }
    val a11yOk = a11yState == A11yHealth.State.OK
    val a11yWasOn = remember(tick, bump, a11yConnected) { A11yHealth.wasOn(context) }
    val canRepair = remember(tick, bump) { A11yHealth.canRepair(context) }
    val listenerOk = remember(tick, bump) { XiaNotificationListener.isEnabled(context) }
    val phoneOk = remember(tick, bump) { PermissionManager.hasPhonePermissions(context) }
    val oemNeeded = remember { PermissionManager.needsOemSetup() }

    fun open(intent: android.content.Intent) {
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching { context.startActivity(PermissionManager.appDetailsIntent(context)) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TechTopBar("Permisos", onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Cada uno destraba algo. Ninguno manda tus datos a ningún lado.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.secondaryLabel,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            SectionLabel("Para escucharte", Modifier.padding(top = 6.dp))
            Group(0) {
                PermissionRow(Icons.Rounded.Mic, colors.red, "Micrófono", "Oír su nombre y tus pedidos", micOk, "Permitir") {
                    launcher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                }
                Divider()
                PermissionRow(Icons.Rounded.Notifications, colors.orange, "Notificación fija", "Android la exige para escuchar siempre", notifOk, "Permitir") {
                    launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
                Divider()
                PermissionRow(Icons.Rounded.BatteryChargingFull, colors.green, "Batería sin restricción", "Que no lo apague el ahorro de energía", batteryOk, "Configurar") {
                    open(PermissionManager.batteryExemptionIntent(context))
                }
            }
            SectionLabel("Para actuar", Modifier.padding(top = 6.dp))
            Group(1) {
                PermissionRow(Icons.Rounded.Layers, colors.blue, "Mostrar sobre otras apps", "Abrir apps, la burbuja y pedir la huella bloqueado", overlayOk, "Permitir") {
                    open(PermissionManager.overlayPermissionIntent(context))
                }
                Divider()
                PermissionRow(
                    Icons.Rounded.Accessibility,
                    colors.indigo,
                    "Control de apps",
                    when {
                        a11yState == A11yHealth.State.NOT_RUNNING -> "Está activado pero no anda: apagalo y prendelo"
                        a11yState == A11yHealth.State.OFF && a11yWasOn -> "Se apagó: el sistema cerró la app"
                        else -> "Tocar, deslizar, escribir y leer la pantalla por vos (y los gestos)"
                    },
                    a11yOk,
                    if (a11yState == A11yHealth.State.NOT_RUNNING) "Arreglar" else "Activar",
                ) {
                    if (A11yHealth.repair(context, a11yState)) bump++ else A11yHealth.openSettings(context)
                }
                Divider()
                PermissionRow(Icons.Rounded.NotificationsActive, colors.pink, "Leer notificaciones", "Leerte y responder mensajes", listenerOk, "Activar") {
                    open(PermissionManager.notificationAccessIntent())
                }
                Divider()
                PermissionRow(Icons.Rounded.Contacts, colors.teal, "Contactos, SMS y llamadas", "Mandar mensajes y llamar por nombre", phoneOk, "Permitir") {
                    launcher.launch(PermissionManager.PHONE_PERMISSIONS)
                }
                if (oemNeeded) {
                    Divider()
                    PermissionRow(Icons.Rounded.PhoneAndroid, colors.gray, "Inicio automático (${PermissionManager.oemName()})", "Tu marca tiene su propio ahorro: verificalo vos", null, "Abrir") {
                        open(PermissionManager.oemSettingsIntent(context) ?: PermissionManager.appDetailsIntent(context))
                    }
                }
            }
            if (oemNeeded) {
                Column(Modifier.fillMaxWidth().glass().padding(16.dp)) {
                    Text("Para que tu ${PermissionManager.oemName()} no la cierre", style = MaterialTheme.typography.titleSmall, color = colors.label)
                    PermissionManager.oemSteps().forEachIndexed { i, step ->
                        Text("${i + 1}. $step", style = MaterialTheme.typography.bodySmall, color = colors.secondaryLabel, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            if (!a11yOk || oemNeeded) A11yHelp(a11yState, canRepair, onOpenAppInfo = { open(PermissionManager.appDetailsIntent(context)) })
            if (!overlayOk || !listenerOk) {
                Column(Modifier.fillMaxWidth().glass().padding(16.dp)) {
                    Text("¿Dice «Ajuste restringido»?", style = MaterialTheme.typography.titleSmall, color = colors.label)
                    Text(
                        "Abrí la info de la app → menú ⋮ → «Permitir ajustes restringidos», y volvé a activarlo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.secondaryLabel,
                        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                    )
                    SmallPillButton("Abrir info de la app", onClick = { open(PermissionManager.appDetailsIntent(context)) })
                }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

/**
 * Qué hacer cuando el control de apps no anda (se apagó o quedó trabado), y
 * cómo dejarlo para que se prenda solo.
 */
@Composable
private fun A11yHelp(state: A11yHealth.State, canRepair: Boolean, onOpenAppInfo: () -> Unit) {
    val context = LocalContext.current
    val colors = Aura.colors
    Column(Modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state != A11yHealth.State.OK) {
            Text(
                if (state == A11yHealth.State.NOT_RUNNING) "El control de apps no anda" else "Para prender el control de apps",
                style = MaterialTheme.typography.titleSmall,
                color = colors.label,
            )
            A11yHealth.steps(context, state).forEachIndexed { i, step ->
                Text("${i + 1}. $step", style = MaterialTheme.typography.bodySmall, color = colors.secondaryLabel)
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallPillButton(
                    if (state == A11yHealth.State.NOT_RUNNING) "Arreglar" else "Prender",
                    onClick = { A11yHealth.openSettings(context) },
                )
                SmallPillButton("Info de la app", onClick = onOpenAppInfo)
            }
        }
        Text(
            "Que se prenda solo",
            style = MaterialTheme.typography.titleSmall,
            color = colors.label,
            modifier = Modifier.padding(top = if (state != A11yHealth.State.OK) 10.dp else 0.dp),
        )
        if (canRepair) {
            Text(
                "✓ Listo: si el sistema lo apaga o se traba, lo vuelvo a prender yo.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.green,
            )
        } else {
            Text(
                "Opcional. Con la PC y el cable (depuración USB prendida), una sola vez:",
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondaryLabel,
            )
            Text(
                A11yHealth.ADB_GRANT,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = colors.label,
            )
            Text(
                "Desde ahí, si el sistema apaga el control de apps, lo vuelvo a prender solo.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondaryLabel,
            )
            SmallPillButton("Copiar comando", onClick = {
                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("adb", A11yHealth.ADB_GRANT))
                android.widget.Toast.makeText(context, "Copiado", android.widget.Toast.LENGTH_SHORT).show()
            })
        }
    }
}

/** Un permiso: ícono, nombre, para qué sirve, y "✓" o el botón para darlo. */
@Composable
private fun PermissionRow(
    icon: ImageVector,
    color: Color,
    title: String,
    subtitle: String,
    granted: Boolean?,
    action: String,
    onAction: () -> Unit,
) {
    val colors = Aura.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, color)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.label)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.secondaryLabel)
        }
        Spacer(Modifier.width(8.dp))
        if (granted == true) {
            Text("Listo", style = MaterialTheme.typography.labelMedium, color = colors.green)
        } else {
            SmallPillButton(action, onClick = onAction)
        }
    }
}
