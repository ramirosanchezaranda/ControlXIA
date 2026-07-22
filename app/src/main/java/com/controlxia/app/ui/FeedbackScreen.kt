package com.controlxia.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.controlxia.app.brain.ChatMessage
import com.controlxia.app.brain.ChatRole
import com.controlxia.app.brain.LlmProviderFactory
import com.controlxia.app.brain.LlmResponse
import com.controlxia.app.brain.LlmSettings
import com.controlxia.app.feedback.FeedbackEntry
import com.controlxia.app.feedback.FeedbackStore
import com.controlxia.app.ui.components.Hairline
import com.controlxia.app.ui.components.SectionLabel
import com.controlxia.app.ui.components.TechButton
import com.controlxia.app.ui.components.TechPanel
import com.controlxia.app.ui.components.TechTopBar
import com.controlxia.app.ui.theme.Accent
import com.controlxia.app.ui.theme.Muted
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Canal de feedback: el usuario escribe qué falló o qué quiere mejorar, el LLM
 * configurado lo analiza (clasifica y responde) y todo queda en un historial
 * local que sirve de insumo para las próximas iteraciones.
 */
@Composable
fun FeedbackScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val store = remember { FeedbackStore(context) }
    val settings = remember { LlmSettings(context) }
    val scope = rememberCoroutineScope()

    var entries by remember { mutableStateOf(store.all()) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val llmConfigured = remember { settings.activeConfig() != null }

    Column(Modifier.fillMaxSize()) {
        TechTopBar(title = "Feedback", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionLabel("Mejoremos a Xia")
            Text(
                "¿Qué falló? ¿Qué te gustaría que haga? Xia analiza tu comentario " +
                    "y lo guarda para las próximas mejoras.",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
            )

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Tu feedback") },
                minLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp),
            )

            TechButton(
                text = if (sending) "Analizando…" else "Enviar",
                enabled = !sending && input.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val text = input.trim()
                    sending = true
                    scope.launch {
                        var reply: String? = null
                        var tag: String? = null
                        val config = settings.activeConfig()
                        if (config != null) {
                            runCatching {
                                val response = LlmProviderFactory.create(config).complete(
                                    listOf(
                                        ChatMessage(ChatRole.SYSTEM, ANALYST_PROMPT),
                                        ChatMessage(ChatRole.USER, text),
                                    )
                                )
                                (response as? LlmResponse.Text)?.text
                            }.getOrNull()?.let { raw ->
                                val trimmed = raw.trim()
                                val match = TAG_REGEX.find(trimmed)
                                tag = match?.groupValues?.get(1)
                                reply = trimmed.removePrefix(match?.value.orEmpty()).trim()
                            }
                        }
                        store.add(
                            FeedbackEntry(
                                id = System.currentTimeMillis(),
                                createdAt = System.currentTimeMillis(),
                                text = text,
                                reply = reply,
                                tag = tag,
                            )
                        )
                        entries = store.all()
                        input = ""
                        sending = false
                    }
                },
            )

            if (!llmConfigured) {
                Text(
                    "El cerebro (LLM) no está configurado: el feedback se guarda " +
                        "igual, pero sin análisis automático.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (entries.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                SectionLabel("Historial")
                entries.sortedByDescending { it.createdAt }.forEach { entry ->
                    TechPanel {
                        Row {
                            Text(
                                formatDate(entry.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = Muted,
                                modifier = Modifier.weight(1f),
                            )
                            entry.tag?.let { tag ->
                                Text(
                                    "[$tag]",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Accent,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(entry.text, style = MaterialTheme.typography.bodyMedium)
                        entry.reply?.let { reply ->
                            Spacer(Modifier.height(12.dp))
                            Hairline()
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "XIA",
                                style = MaterialTheme.typography.labelSmall,
                                color = Accent,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                reply,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Muted,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val TAG_REGEX = Regex("^\\[(BUG|IDEA|MEJORA)\\]\\s*")

private const val ANALYST_PROMPT =
    "Sos el canal de feedback de ControlXIA, una app Android que controla el " +
        "teléfono por voz (servicio en segundo plano, wake word, permisos, LLM " +
        "configurable). El usuario te manda un comentario: puede ser un error, " +
        "una idea o una mejora. Respondé en español rioplatense, máximo tres " +
        "frases: agradecé, y si describe un problema sugerí una causa o " +
        "solución posible. Empezá tu respuesta con una etiqueta exacta: " +
        "[BUG], [IDEA] o [MEJORA]."

private fun formatDate(timestamp: Long): String =
    SimpleDateFormat("dd.MM.yyyy  HH:mm", Locale.getDefault()).format(Date(timestamp))
