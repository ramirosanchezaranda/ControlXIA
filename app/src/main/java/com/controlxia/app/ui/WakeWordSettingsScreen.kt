package com.controlxia.app.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.controlxia.app.voice.WakeKeyword
import com.controlxia.app.voice.WakeWordSettings
import com.controlxia.app.ui.components.SectionLabel
import com.controlxia.app.ui.components.TechButton
import com.controlxia.app.ui.components.TechTopBar
import com.controlxia.app.ui.theme.Accent
import com.controlxia.app.ui.theme.Muted
import kotlin.math.roundToInt

/**
 * Ajustes de la palabra de activación: AccessKey de Picovoice (cifrado), palabra
 * de fábrica y sensibilidad. Los cambios se aplican al reiniciar la escucha
 * (apagar/encender el servicio desde el panel).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WakeWordSettingsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val settings = remember { WakeWordSettings(context) }

    var agentName by remember { mutableStateOf(settings.agentName) }
    var accessKey by remember { mutableStateOf(settings.accessKey) }
    var keyword by remember { mutableStateOf(settings.keyword) }
    var sensitivity by remember { mutableFloatStateOf(settings.sensitivity) }
    var useCustom by remember { mutableStateOf(settings.useCustomKeyword) }
    var showLockedContent by remember { mutableStateOf(settings.showContentWhenLocked) }
    var showKey by remember { mutableStateOf(false) }
    var keywordMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TechTopBar(title = "Asistente y activación", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
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
                    Text("Cómo se llama a sí mismo y cómo figura en el aviso de escucha")
                },
                modifier = Modifier.fillMaxWidth(),
            )

            SectionLabel("Motor · Picovoice Porcupine")
            Text(
                "El wake word corre 100% en el teléfono. Porcupine necesita un " +
                    "AccessKey gratuito (uso personal).",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
            )
            TechButton(
                text = "Obtener AccessKey gratis",
                filled = false,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://console.picovoice.ai"))
                        )
                    }
                },
            )

            OutlinedTextField(
                value = accessKey,
                onValueChange = {
                    accessKey = it
                    settings.accessKey = it.trim()
                },
                label = { Text("AccessKey") },
                singleLine = true,
                visualTransformation = if (showKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showKey) "Ocultar" else "Mostrar"
                        )
                    }
                },
                supportingText = { Text("Se guarda cifrada en el dispositivo") },
                modifier = Modifier.fillMaxWidth()
            )

            // Wake word custom (modelo entrenado propio, ej. "Xia")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Wake word custom", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Usar tu modelo entrenado (assets/xia.ppn). Ver la guía " +
                            "CUSTOM_WAKE_WORD.md.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
                Switch(
                    checked = useCustom,
                    onCheckedChange = {
                        useCustom = it
                        settings.useCustomKeyword = it
                    },
                )
            }

            if (!useCustom) {
                ExposedDropdownMenuBox(
                    expanded = keywordMenu,
                    onExpandedChange = { keywordMenu = it }
                ) {
                    OutlinedTextField(
                        value = keyword.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Palabra de activación (de fábrica)") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = keywordMenu)
                        },
                        supportingText = { Text("Decí esta palabra para despertar al asistente") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = keywordMenu,
                        onDismissRequest = { keywordMenu = false }
                    ) {
                        WakeKeyword.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    keyword = option
                                    settings.keyword = option
                                    keywordMenu = false
                                }
                            )
                        }
                    }
                }
            } else {
                Text(
                    "Activá el wake word custom colocando xia.ppn y " +
                        "porcupine_params_es.pv en app/src/main/assets/. Con eso, decí " +
                        "“$agentName” para despertarlo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }

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
                    "Más alta detecta mejor pero con más falsos positivos.",
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
                "Los cambios de wake word se aplican al reiniciar la escucha: apagá " +
                    "y encendé el servicio desde el panel.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
    }
}
