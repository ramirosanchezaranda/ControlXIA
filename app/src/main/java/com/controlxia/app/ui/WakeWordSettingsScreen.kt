package com.controlxia.app.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.controlxia.app.service.WakeWordService
import com.controlxia.app.ui.components.SectionLabel
import com.controlxia.app.ui.components.TechTopBar
import com.controlxia.app.ui.theme.Accent
import com.controlxia.app.ui.theme.Muted
import com.controlxia.app.voice.WakeWordSettings
import kotlin.math.roundToInt

/**
 * Ajustes del asistente: nombre, palabra de activación (cómo suena),
 * sensibilidad y privacidad con el teléfono bloqueado. Los cambios se aplican
 * solos al salir de la pantalla, sin apagar el servicio.
 */
@Composable
fun WakeWordSettingsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val settings = remember { WakeWordSettings(context) }

    // Al salir, el servicio (si está corriendo) recarga el wake word con los
    // ajustes nuevos.
    DisposableEffect(Unit) {
        onDispose { WakeWordService.reload(context) }
    }

    var sensitivity by remember { mutableFloatStateOf(settings.sensitivity) }
    var showLockedContent by remember { mutableStateOf(settings.showContentWhenLocked) }

    Column(Modifier.fillMaxSize()) {
        TechTopBar(title = "Asistente y activación", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AgentNameContent(settings)

            Column {
                Row(Modifier.fillMaxWidth()) {
                    SectionLabel("Sensibilidad", Modifier.weight(1f))
                    Text(
                        "${(sensitivity * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = Accent,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Slider(
                    value = sensitivity,
                    onValueChange = { sensitivity = it },
                    onValueChangeFinished = { settings.sensitivity = sensitivity },
                )
                Text(
                    "Más alta responde aunque no se te escuche del todo claro, pero " +
                        "se activa más seguido sin querer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }

            // Privacidad con el teléfono bloqueado
            SectionLabel("Privacidad")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Mostrar contenido bloqueado", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Con el teléfono bloqueado, mostrar en pantalla lo que se " +
                            "escuchó y la respuesta. Si lo apagás, la voz igual responde " +
                            "pero la pantalla no muestra el contenido.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
                Switch(
                    checked = showLockedContent,
                    onCheckedChange = {
                        showLockedContent = it
                        settings.showContentWhenLocked = it
                    },
                )
            }

            Text(
                "Los cambios se aplican solos al volver al panel.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
    }
}

/**
 * Nombre del asistente y cómo suena su palabra de activación (se guardan al
 * instante). Lo usan los ajustes y el onboarding.
 */
@Composable
fun AgentNameContent(settings: WakeWordSettings, modifier: Modifier = Modifier) {
    var agentName by remember { mutableStateOf(settings.agentName) }
    var wakeWord by remember { mutableStateOf(settings.wakeWord) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionLabel("Nombre del asistente")
        OutlinedTextField(
            value = agentName,
            onValueChange = {
                agentName = it
                settings.agentName = it
            },
            label = { Text("Nombre") },
            singleLine = true,
            supportingText = {
                Text("Decí su nombre para despertarlo. Así se presenta y figura en el aviso de escucha.")
            },
            modifier = Modifier.fillMaxWidth(),
        )

        SectionLabel("Cómo suena (opcional)")
        OutlinedTextField(
            value = wakeWord,
            onValueChange = {
                wakeWord = it
                settings.wakeWord = it
            },
            label = { Text("Palabra de activación") },
            placeholder = { Text(agentName.ifBlank { "Xia" }.lowercase()) },
            singleLine = true,
            supportingText = {
                Text(
                    "Si no te reconoce, escribila como la decís (ej. «sia»). También " +
                        "acepta una frase corta, como «oye xia»."
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            "La palabra se reconoce en el teléfono con Vosk: sin internet, sin " +
                "cuentas y sin enviar audio a ningún lado.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
    }
}
