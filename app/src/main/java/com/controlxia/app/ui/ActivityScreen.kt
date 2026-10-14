package com.controlxia.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.controlxia.app.diagnostics.AgentLog
import com.controlxia.app.service.WakeWordService
import com.controlxia.app.ui.components.AuraOrb
import com.controlxia.app.ui.components.ChatBubble
import com.controlxia.app.ui.components.LargeTitle
import com.controlxia.app.ui.components.SectionLabel
import com.controlxia.app.ui.components.SmallPillButton
import com.controlxia.app.ui.components.glass
import com.controlxia.app.ui.theme.Aura
import com.controlxia.app.ui.theme.Mono
import com.controlxia.app.ui.theme.staggerReveal
import com.controlxia.app.voice.RecentCommandsStore
import com.controlxia.app.voice.WakeWordSettings
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Actividad: lo que le pediste y lo que contestó, como una conversación (lo
 * más nuevo arriba, agrupado por día), y el registro paso a paso del agente
 * para entender qué pasó cuando algo falla.
 */
@Composable
fun ActivityScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val colors = Aura.colors
    val agentName = remember { WakeWordSettings(context).agentName }
    var lastReply by remember { mutableStateOf(WakeWordService.lastReply) }
    var lastCommand by remember { mutableStateOf(WakeWordService.lastCommand) }
    var logVersion by remember { mutableIntStateOf(AgentLog.version) }
    LaunchedEffect(Unit) {
        while (true) {
            lastReply = WakeWordService.lastReply
            lastCommand = WakeWordService.lastCommand
            logVersion = AgentLog.version
            delay(1_000)
        }
    }
    val history = remember(lastReply, lastCommand) { RecentCommandsStore(context).all().asReversed() }
    val log = remember(logVersion) { AgentLog.recent(40).asReversed() }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = MAX_CONTENT).fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { LargeTitle("Actividad", subtitle = "Tu historial", modifier = Modifier.padding(bottom = 8.dp)) }
            if (history.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().glass().padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AuraOrb(Modifier.size(96.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Todavía no hablamos", style = MaterialTheme.typography.titleMedium, color = colors.label)
                        Text(
                            "Decí «$agentName» o tocá la esfera en Inicio. Lo que pidas va a aparecer acá.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.secondaryLabel,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                var lastDay = ""
                history.forEachIndexed { index, cmd ->
                    val day = dayLabel(cmd.at)
                    if (day != lastDay) {
                        lastDay = day
                        item(key = "day-$index") { SectionLabel(day, Modifier.padding(top = 8.dp)) }
                    }
                    item(key = "cmd-$index") {
                        Column(Modifier.staggerReveal(index.coerceAtMost(6)), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ChatBubble(cmd.text, mine = true, caption = time(cmd.at))
                            cmd.reply?.let { ChatBubble(it, mine = false) }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Registro del agente", Modifier.weight(1f))
                    if (log.isNotEmpty()) {
                        SmallPillButton("Copiar", onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(ClipData.newPlainText("Registro ControlXIA", AgentLog.asText()))
                            Toast.makeText(context, "Registro copiado", Toast.LENGTH_SHORT).show()
                        })
                        Spacer(Modifier.size(8.dp))
                        SmallPillButton("Borrar", onClick = { AgentLog.clear() })
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().glass().padding(16.dp)) {
                    if (log.isEmpty()) {
                        Text(
                            "Acá vas a ver paso a paso qué hizo el asistente en cada pedido.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.secondaryLabel,
                        )
                    } else {
                        log.forEach { entry ->
                            Row(Modifier.padding(vertical = 3.dp)) {
                                Text(
                                    seconds(entry.time),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                                    color = colors.tertiaryLabel,
                                    modifier = Modifier.padding(end = 10.dp),
                                )
                                Text(entry.text, style = MaterialTheme.typography.bodySmall, color = colors.label)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun dayLabel(at: Long): String {
    val cal = Calendar.getInstance()
    val today = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
    cal.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
    cal.timeInMillis = at
    val day = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
    return when (day) {
        today -> "Hoy"
        yesterday -> "Ayer"
        else -> SimpleDateFormat("EEEE d 'de' MMMM", Locale("es")).format(Date(at)).replaceFirstChar { it.uppercase() }
    }
}

private fun time(at: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))

private fun seconds(at: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))
