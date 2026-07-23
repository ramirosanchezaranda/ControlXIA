package com.controlxia.app.ui

import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.controlxia.app.ui.components.Hairline
import com.controlxia.app.ui.components.SectionLabel
import com.controlxia.app.ui.components.TechButton
import com.controlxia.app.ui.components.TechTopBar
import com.controlxia.app.ui.theme.Accent
import com.controlxia.app.ui.theme.Muted
import com.controlxia.app.voice.VoiceSettings
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Ajustes de la voz de Xia: elegir voz (con género inferido), tono y velocidad,
 * con presets rápidos. Crea un `TextToSpeech` propio para enumerar las voces
 * del sistema y para el botón "Probar". Los cambios se guardan al instante.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val settings = remember { VoiceSettings(context) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var pitch by remember { mutableFloatStateOf(settings.pitch) }
    var rate by remember { mutableFloatStateOf(settings.rate) }
    var voiceName by remember { mutableStateOf(settings.voiceName) }
    var voices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var ready by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    // Motor TTS para listar voces y probar. Se libera al salir de la pantalla.
    val ttsHolder = remember { arrayOfNulls<TextToSpeech>(1) }
    DisposableEffect(Unit) {
        val engine = TextToSpeech(context) { status ->
            val e = ttsHolder[0]
            if (status == TextToSpeech.SUCCESS && e != null) {
                e.language = Locale("es", "AR")
                val list = runCatching {
                    e.voices
                        ?.filter { it.locale.language == "es" && !it.isNetworkConnectionRequired }
                        ?.sortedBy { it.name }
                        ?: emptyList()
                }.getOrDefault(emptyList())
                mainHandler.post {
                    voices = list.ifEmpty {
                        runCatching { e.voices?.sortedBy { it.name }?.toList() }.getOrNull() ?: emptyList()
                    }
                    ready = true
                }
            } else {
                mainHandler.post { ready = true }
            }
        }
        ttsHolder[0] = engine
        onDispose {
            runCatching { engine.stop() }
            runCatching { engine.shutdown() }
        }
    }

    fun test() {
        val engine = ttsHolder[0] ?: return
        settings.applyTo(engine)
        engine.speak("Hola, soy Xia. Son las ${hhmmNow()}.", TextToSpeech.QUEUE_FLUSH, null, "voice_test")
    }

    val currentGender = voiceName?.let { VoiceSettings.guessGender(it) } ?: "—"

    Column(Modifier.fillMaxSize()) {
        TechTopBar(title = "Voz de Xia", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionLabel("Elegí cómo suena  ·  ${currentGender.uppercase()}")
            Text(
                "Las voces disponibles dependen del motor de voz del teléfono. Los " +
                    "presets ajustan el tono para sonar más grave o más aguda aunque " +
                    "haya una sola voz.",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
            )

            // Selector de voz
            ExposedDropdownMenuBox(expanded = menu, onExpandedChange = { menu = it }) {
                OutlinedTextField(
                    value = when {
                        !ready -> "Cargando voces…"
                        voices.isEmpty() -> "Voz por defecto del sistema"
                        else -> voiceLabel(voiceName, voices)
                    },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Voz (${voices.size})") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menu) },
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                )
                ExposedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    voices.forEach { v ->
                        val g = VoiceSettings.guessGender(v.name)
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "${v.name}  ·  ${v.locale}${if (g != null) "  ·  $g" else ""}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            onClick = {
                                voiceName = v.name
                                settings.voiceName = v.name
                                menu = false
                            },
                        )
                    }
                }
            }

            // Presets
            SectionLabel("Presets")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fun preset(label: String, p: Float, r: Float) {
                    TechButton(
                        text = label,
                        filled = false,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            pitch = p; rate = r
                            settings.pitch = p; settings.rate = r
                            test()
                        },
                    )
                }
                preset("Grave", 0.7f, 0.98f)
                preset("Neutra", 1.0f, 1.0f)
                preset("Aguda", 1.45f, 1.05f)
            }

            // Tono
            Column {
                Row(Modifier.fillMaxWidth()) {
                    SectionLabel("Tono", Modifier.weight(1f))
                    Text(
                        "${(pitch * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = Accent,
                    )
                }
                Slider(
                    value = pitch,
                    onValueChange = { pitch = it },
                    onValueChangeFinished = { settings.pitch = pitch },
                    valueRange = 0.5f..2.0f,
                )
            }

            // Velocidad
            Column {
                Row(Modifier.fillMaxWidth()) {
                    SectionLabel("Velocidad", Modifier.weight(1f))
                    Text(
                        "${(rate * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = Accent,
                    )
                }
                Slider(
                    value = rate,
                    onValueChange = { rate = it },
                    onValueChangeFinished = { settings.rate = rate },
                    valueRange = 0.5f..2.0f,
                )
            }

            Spacer(Modifier.height(4.dp))
            TechButton(
                text = "Probar voz",
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
                onClick = { test() },
            )
            Hairline()
            Text(
                "Xia usa esta voz para confirmar la escucha, decir la hora y " +
                    "responder tus pedidos.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
    }
}

private fun voiceLabel(voiceName: String?, voices: List<Voice>): String {
    val v = voices.firstOrNull { it.name == voiceName } ?: return "Voz por defecto del sistema"
    val g = VoiceSettings.guessGender(v.name)
    return "${v.name}${if (g != null) "  ·  $g" else ""}"
}

private fun hhmmNow(): String =
    java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date())
