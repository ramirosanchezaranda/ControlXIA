package com.controlxia.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.controlxia.app.ui.theme.Aura
import com.controlxia.app.ui.theme.pressable

/** Título de un grupo, como los de Ajustes de iOS. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = Aura.colors.secondaryLabel,
        modifier = modifier.padding(start = 4.dp),
    )
}

/** Separador fino entre filas. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 0.5.dp,
        color = Aura.colors.separator,
    )
}

/** Tarjeta de vidrio con el contenido adentro. */
@Composable
fun TechPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .glass()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        content = content,
    )
}

/**
 * Botón cápsula de iOS: azul lleno (principal) o azul suave (secundario). Se
 * achica un poco al tocarlo y vuelve con un rebote.
 */
@Composable
fun TechButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = true,
) {
    val colors = Aura.colors
    val background by animateColorAsState(
        when {
            !enabled -> colors.gray.copy(alpha = 0.18f)
            filled -> colors.blue
            else -> colors.blue.copy(alpha = if (colors.dark) 0.2f else 0.11f)
        },
        label = "button-bg",
    )
    val foreground = when {
        !enabled -> colors.tertiaryLabel
        filled -> Color.White
        else -> colors.blue
    }
    Box(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(CircleShape)
            .background(background)
            .pressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = foreground)
    }
}

/** Punto de estado: verde = bien, rojo = falta, gris = no se sabe. */
@Composable
fun StatusDot(ok: Boolean?, modifier: Modifier = Modifier) {
    val color = when (ok) {
        true -> Aura.colors.green
        false -> Aura.colors.red
        null -> Aura.colors.gray
    }
    Box(modifier.size(8.dp).background(color, CircleShape))
}

/**
 * Barra de arriba de una pantalla interna: botón "atrás" de vidrio y el
 * título grande debajo, como en iOS.
 */
@Composable
fun TechTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onBack != null) {
                GlassIconButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBackIos,
                    contentDescription = "Volver",
                    onClick = onBack,
                    iconOffsetX = 3.dp,
                )
            }
            Spacer(Modifier.weight(1f))
            trailing()
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = Aura.colors.label,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )
    }
}

/** Fila de estado: ícono de bien/falta, texto y un botoncito para resolverlo. */
@Composable
fun StatusRow(
    label: String,
    ok: Boolean?,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = Aura.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (tint, icon) = when (ok) {
            true -> colors.green to Icons.Rounded.Check
            false -> colors.red to Icons.Rounded.PriorityHigh
            null -> colors.orange to Icons.Rounded.PriorityHigh
        }
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            // Para TalkBack: el estado no puede ser solo un color.
            Icon(
                icon,
                contentDescription = when (ok) {
                    true -> "Listo"
                    false -> "Falta"
                    null -> "Revisar"
                },
                tint = tint,
                modifier = Modifier.size(15.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.label,
            modifier = Modifier.weight(1f),
        )
        if (ok != true && actionLabel != null && onAction != null) {
            Spacer(Modifier.width(8.dp))
            SmallPillButton(actionLabel, onAction)
        }
    }
}

/** Botoncito cápsula azul suave ("Permitir", "Activar"). */
@Composable
fun SmallPillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Aura.colors
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = colors.blue,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(colors.blue.copy(alpha = if (colors.dark) 0.2f else 0.11f))
            .pressable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/**
 * Contador que se incrementa cada vez que la pantalla vuelve al frente,
 * para re-evaluar permisos concedidos en Ajustes del sistema.
 */
@Composable
fun rememberResumeTick(): Int {
    val lifecycleOwner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return tick
}
