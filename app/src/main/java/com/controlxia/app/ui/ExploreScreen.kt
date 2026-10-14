package com.controlxia.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.controlxia.app.ui.components.IconTile
import com.controlxia.app.ui.components.LargeTitle
import com.controlxia.app.ui.components.glass
import com.controlxia.app.ui.theme.Aura
import com.controlxia.app.ui.theme.AuraColors
import com.controlxia.app.ui.theme.pressable
import com.controlxia.app.ui.theme.staggerReveal

/**
 * Explorar: todo lo que se le puede pedir, por tema, con ejemplos que se
 * tocan para probarlos (quedan escritos en Inicio, listos para mandar). Una
 * columna en el teléfono, dos o tres en pantallas anchas.
 */
@Composable
fun ExploreScreen(contentPadding: PaddingValues, onTry: (String) -> Unit) {
    val colors = Aura.colors
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 300.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(bottom = 4.dp)) {
                LargeTitle("Explorar", subtitle = "Lo que podés pedir")
                Text(
                    "Casi todo anda sin IA ni internet. Tocá un ejemplo para probarlo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.secondaryLabel,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                )
            }
        }
        itemsIndexed(CATEGORIES) { index, category ->
            CategoryCard(category, colors, onTry, Modifier.staggerReveal(index))
        }
    }
}

@Composable
private fun CategoryCard(category: Category, colors: AuraColors, onTry: (String) -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth().glass().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(category.icon, category.color(colors), size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Text(category.title, style = MaterialTheme.typography.titleMedium, color = colors.label, modifier = Modifier.weight(1f))
            if (category.offline) {
                Text(
                    "Sin IA",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.green,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(colors.green.copy(alpha = 0.14f))
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        category.examples.forEach { example ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .pressable(pressedScale = 0.98f, onClick = { onTry(example) })
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "“$example”",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.label,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Rounded.NorthWest, contentDescription = "Probar", tint = colors.blue, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private class Category(
    val title: String,
    val icon: ImageVector,
    val color: (AuraColors) -> Color,
    val examples: List<String>,
    val offline: Boolean = true,
)

private val CATEGORIES = listOf(
    Category("Mensajes", Icons.AutoMirrored.Rounded.Chat, { it.green }, listOf(
        "Mandale a Juan que llego tarde",
        "Mandale un audio a mamá",
        "Decile a Ana por Telegram que ya voy",
        "Respondele que sí",
    )),
    Category("Notificaciones", Icons.Rounded.Notifications, { it.red }, listOf(
        "Leeme las notificaciones",
        "¿Tengo alguna notificación del banco?",
        "Abrí la notificación de Gmail",
        "Borrá las notificaciones",
    )),
    Category("Llamadas", Icons.Rounded.Call, { it.teal }, listOf(
        "Llamá a mamá",
        "Atendé",
        "Colgá",
    )),
    Category("Apps", Icons.Rounded.Apps, { it.blue }, listOf(
        "Abrí Instagram",
        "Buscá pizza en PedidosYa",
        "En Spotify tocá Biblioteca",
        "Abrí los reels",
    )),
    Category("Pantalla", Icons.Rounded.TouchApp, { it.indigo }, listOf(
        "Mostrá los números",
        "Tocá 5",
        "Bajá",
        "Atrás",
    )),
    Category("Cámara", Icons.Rounded.PhotoCamera, { it.gray }, listOf(
        "Sacá una foto",
        "Empezá a grabar un video",
        "Terminá el video",
    )),
    Category("Alarmas y agenda", Icons.Rounded.Alarm, { it.orange }, listOf(
        "Poné una alarma a las 7 y media",
        "¿A qué hora suena mi alarma?",
        "Apagá la alarma",
        "Temporizador de 10 minutos",
        "Agendá dentista el jueves a las 4",
    )),
    Category("Música y videos", Icons.Rounded.MusicNote, { it.pink }, listOf(
        "Pausa",
        "Siguiente",
        "Poné música de Queen en Spotify",
    )),
    Category("Ajustes del teléfono", Icons.Rounded.Tune, { it.gray }, listOf(
        "Activá no molestar",
        "Prendé la linterna",
        "Subí el brillo al máximo",
        "Apagá el wifi",
    )),
    Category("Bloqueado", Icons.Rounded.Lock, { it.purple }, listOf(
        "Leeme los mensajes",
        "Mandale un SMS a Juan que ya salgo",
        "¿Qué hora es?",
    )),
    Category("Cuentas y datos", Icons.Rounded.Calculate, { it.yellow }, listOf(
        "Cuánto es 25 por 4",
        "El 15 por ciento de 2000",
        "¿Qué día es hoy?",
    )),
    Category("Con IA", Icons.Rounded.Psychology, { it.purple }, listOf(
        "Buscá las zapatillas más baratas y mandale el link a Juan",
        "¿Qué me conviene llevar si llueve mañana?",
    ), offline = false),
)
