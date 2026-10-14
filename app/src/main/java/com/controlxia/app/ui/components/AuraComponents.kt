package com.controlxia.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.controlxia.app.ui.theme.Aura
import com.controlxia.app.ui.theme.AuraMotion
import com.controlxia.app.ui.theme.pressable

/** Botón redondo de vidrio con un ícono (atrás, cerrar, ajustes). */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    tint: Color = Aura.colors.label,
    iconOffsetX: Dp = 0.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .glass(CircleShape, strong = true)
            .pressable(pressedScale = 0.9f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size * 0.45f).offset(x = iconOffsetX),
        )
    }
}

/** El cuadradito de color con un ícono blanco de las listas de Ajustes de iOS. */
@Composable
fun IconTile(icon: ImageVector, color: Color, modifier: Modifier = Modifier, size: Dp = 30.dp) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.27f))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.6f))
    }
}

/** Fila de una lista de ajustes: ícono, título, detalle y flecha. */
@Composable
fun SettingsRow(
    icon: ImageVector,
    color: Color,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    badge: Int = 0,
    onClick: (() -> Unit)? = null,
) {
    val colors = Aura.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.pressable(pressedScale = 0.98f, onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, color)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (badge > 0) {
            Box(
                Modifier.padding(start = 8.dp).size(22.dp).clip(CircleShape).background(colors.red),
                contentAlignment = Alignment.Center,
            ) {
                Text("$badge", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
        value?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).widthIn(max = 160.dp),
            )
        }
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.tertiaryLabel,
                modifier = Modifier.padding(start = 4.dp).size(22.dp),
            )
        }
    }
}

/** Sugerencia en forma de cápsula de vidrio. */
@Composable
fun AuraChip(text: String, icon: ImageVector? = null, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Aura.colors
    Row(
        modifier = modifier
            .glass(CircleShape, elevated = false)
            .pressable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = colors.blue, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.label, maxLines = 1)
    }
}

/** Interruptor con los colores de iOS (verde encendido). */
@Composable
fun AuraSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = Aura.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = colors.green,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = colors.gray.copy(alpha = if (colors.dark) 0.4f else 0.28f),
            uncheckedBorderColor = Color.Transparent,
        ),
        thumbContent = null,
    )
}

/**
 * La barra para pedir escribiendo: cápsula de vidrio con micrófono y el
 * botón de enviar, que aparece al escribir.
 */
@Composable
fun AskField(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Pedime lo que quieras…",
) {
    val colors = Aura.colors
    val canSend = value.isNotBlank()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .glass(CircleShape, strong = true)
            .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.tertiaryLabel, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.label),
                cursorBrush = SolidColor(colors.blue),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(8.dp))
        val sendColor by animateColorAsState(if (canSend) colors.blue else colors.blue.copy(alpha = 0.12f), label = "send")
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(sendColor)
                .pressable(pressedScale = 0.88f, onClick = { if (canSend) onSend() else onMic() }),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (canSend) Icons.Rounded.ArrowUpward else Icons.Rounded.Mic,
                contentDescription = if (canSend) "Enviar" else "Hablar",
                tint = if (canSend) Color.White else colors.blue,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Un elemento de la barra de pestañas. */
class TabItem(val label: String, val icon: ImageVector)

/**
 * Barra de pestañas flotante de vidrio (como iOS 26): una cápsula esmerilada
 * con un indicador que se desliza con un resorte hasta la pestaña elegida.
 */
@Composable
fun GlassTabBar(
    items: List<TabItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Aura.colors
    BoxWithConstraints(
        modifier
            .widthIn(max = 460.dp)
            .fillMaxWidth()
            .height(64.dp)
            .frostedGlass(CircleShape)
            .padding(5.dp),
    ) {
        val itemWidth = maxWidth / items.size
        val indicatorX by animateDpAsState(itemWidth * selected, AuraMotion.snappy(), label = "tab-indicator")
        Box(
            Modifier
                .offset(x = indicatorX)
                .width(itemWidth)
                .fillMaxSize()
                .clip(CircleShape)
                .background(if (colors.dark) Color.White.copy(alpha = 0.14f) else colors.blue.copy(alpha = 0.1f)),
        )
        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { index, item ->
                val active = index == selected
                val tint by animateColorAsState(if (active) colors.blue else colors.secondaryLabel, label = "tab-tint")
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .pressable(pressedScale = 0.92f, onClick = { onSelect(index) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(23.dp))
                    Text(item.label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
                }
            }
        }
    }
}

/** La misma barra, vertical, para pantallas anchas (tablet u horizontal). */
@Composable
fun GlassTabRail(
    items: List<TabItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Aura.colors
    Column(
        modifier
            .width(84.dp)
            .frostedGlass(RoundedCornerShape(32.dp))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEachIndexed { index, item ->
            val active = index == selected
            val tint by animateColorAsState(if (active) colors.blue else colors.secondaryLabel, label = "rail-tint")
            val bg by animateColorAsState(
                if (active) (if (colors.dark) Color.White.copy(alpha = 0.14f) else colors.blue.copy(alpha = 0.1f)) else Color.Transparent,
                label = "rail-bg",
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(bg)
                    .pressable(pressedScale = 0.92f, onClick = { onSelect(index) })
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                Text(item.label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Título grande de una pestaña con una línea de contexto opcional. */
@Composable
fun LargeTitle(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier.padding(horizontal = 4.dp)) {
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = Aura.colors.secondaryLabel)
        }
        Text(title, style = MaterialTheme.typography.headlineLarge, color = Aura.colors.label)
    }
}

/** Globo de conversación: lo tuyo a la derecha en azul, la respuesta a la izquierda en vidrio. */
@Composable
fun ChatBubble(text: String, mine: Boolean, modifier: Modifier = Modifier, caption: String? = null) {
    val colors = Aura.colors
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        val shape = RoundedCornerShape(
            topStart = 22.dp,
            topEnd = 22.dp,
            bottomStart = if (mine) 22.dp else 6.dp,
            bottomEnd = if (mine) 6.dp else 22.dp,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (mine) Color.White else colors.label,
            modifier = Modifier
                .padding(start = if (mine) 48.dp else 0.dp, end = if (mine) 0.dp else 48.dp)
                .widthIn(max = 520.dp)
                .then(
                    if (mine) {
                        Modifier.clip(shape).background(colors.blue)
                    } else {
                        Modifier.glass(shape, strong = true, elevated = false)
                    },
                )
                .padding(horizontal = 16.dp, vertical = 11.dp),
        )
        caption?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = colors.tertiaryLabel,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** Aparece/desaparece con un fundido y un mínimo de escala (como los avisos de iOS). */
@Composable
fun FadeScale(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(AuraMotion.smooth()) + scaleIn(AuraMotion.snappy(), initialScale = 0.92f),
        exit = fadeOut(AuraMotion.smooth()) + scaleOut(AuraMotion.smooth(), targetScale = 0.96f),
    ) { content() }
}
