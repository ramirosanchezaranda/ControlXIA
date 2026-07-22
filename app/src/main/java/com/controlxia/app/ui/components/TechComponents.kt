package com.controlxia.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.controlxia.app.ui.theme.Accent
import com.controlxia.app.ui.theme.Muted

/** Etiqueta de sección estilo plano técnico: "[ SISTEMA ]". */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        "[ ${text.uppercase()} ]",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** Línea de 1dp, el separador universal del sistema. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline,
    )
}

/** Panel con borde fino, sin sombra ni relleno: reemplaza a las Cards. */
@Composable
fun TechPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .padding(16.dp),
        content = content,
    )
}

/** Botón rectangular: negro relleno (primario) o con borde (secundario). */
@Composable
fun TechButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val background = when {
        !filled -> Color.Transparent
        enabled -> colors.onBackground
        else -> colors.outline
    }
    val foreground = when {
        filled -> colors.background
        enabled -> colors.onBackground
        else -> colors.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .then(
                if (filled) Modifier.background(background)
                else Modifier.border(1.dp, if (enabled) colors.onBackground else colors.outline)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = foreground)
    }
}

/** Punto de estado: azul = ok, gris = manual/desconocido, rojo = falta. */
@Composable
fun StatusDot(ok: Boolean?, modifier: Modifier = Modifier) {
    val color = when (ok) {
        true -> Accent
        false -> MaterialTheme.colorScheme.error
        null -> Muted
    }
    Box(modifier.size(7.dp).background(color, CircleShape))
}

/** Barra superior propia: título mono + hairline, sin Material TopAppBar. */
@Composable
fun TechTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            trailing()
        }
        Hairline()
    }
}

/** Fila de estado con punto, etiqueta y acción opcional a la derecha. */
@Composable
fun StatusRow(
    label: String,
    ok: Boolean?,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(ok)
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (ok == true) {
            Text("OK", style = MaterialTheme.typography.labelSmall, color = Muted)
        } else if (actionLabel != null && onAction != null) {
            Text(
                actionLabel.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Accent,
                modifier = Modifier.clickable(onClick = onAction).padding(4.dp),
            )
        }
    }
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
