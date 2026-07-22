package com.controlxia.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.controlxia.app.brain.ChatMessage
import com.controlxia.app.brain.ChatRole
import com.controlxia.app.brain.LlmProviderFactory
import com.controlxia.app.brain.LlmProviderType
import com.controlxia.app.brain.LlmResponse
import com.controlxia.app.brain.LlmSettings
import com.controlxia.app.ui.components.SectionLabel
import com.controlxia.app.ui.components.TechButton
import com.controlxia.app.ui.components.TechTopBar
import com.controlxia.app.ui.theme.Accent
import kotlinx.coroutines.launch

/** Pantalla completa de ajustes del cerebro, con barra y volver. */
@Composable
fun LlmSettingsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        TechTopBar(title = "Cerebro / LLM", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            SectionLabel("Proveedor y modelo")
            LlmSettingsContent(Modifier.padding(top = 16.dp))
        }
    }
}

/**
 * Formulario de configuración del LLM, reutilizado por el onboarding y por la
 * pantalla de ajustes. Guarda cada cambio al instante (cifrado), así el estado
 * nunca se pierde entre pasos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmSettingsContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember { LlmSettings(context) }
    val scope = rememberCoroutineScope()

    var provider by remember { mutableStateOf(settings.activeProvider) }
    var apiKey by remember(provider) { mutableStateOf(settings.apiKey(provider)) }
    var model by remember(provider) { mutableStateOf(settings.model(provider)) }
    var baseUrl by remember(provider) { mutableStateOf(settings.baseUrl(provider)) }
    var showKey by remember { mutableStateOf(false) }
    var providerMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ExposedDropdownMenuBox(
            expanded = providerMenu,
            onExpandedChange = { providerMenu = it }
        ) {
            OutlinedTextField(
                value = provider.displayName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Proveedor") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerMenu)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = providerMenu,
                onDismissRequest = { providerMenu = false }
            ) {
                LlmProviderType.entries.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.displayName) },
                        onClick = {
                            provider = type
                            settings.activeProvider = type
                            providerMenu = false
                            testResult = null
                        }
                    )
                }
            }
        }

        OutlinedTextField(
            value = apiKey,
            onValueChange = {
                apiKey = it
                settings.setApiKey(provider, it.trim())
            },
            label = { Text("API key") },
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
            supportingText = {
                Text(
                    if (provider == LlmProviderType.OPENAI_COMPATIBLE) {
                        "Opcional para servidores locales como Ollama"
                    } else {
                        "Se guarda cifrada en el dispositivo"
                    }
                )
            },
            modifier = Modifier.fillMaxWidth()
        )

        ExposedDropdownMenuBox(
            expanded = modelMenu,
            onExpandedChange = { modelMenu = it }
        ) {
            OutlinedTextField(
                value = model,
                onValueChange = {
                    model = it
                    settings.setModel(provider, it.trim())
                },
                label = { Text("Modelo") },
                singleLine = true,
                supportingText = { Text("Elegí uno sugerido o escribí cualquier ID") },
                trailingIcon = {
                    if (provider.suggestedModels.isNotEmpty()) {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenu)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
            )
            if (provider.suggestedModels.isNotEmpty()) {
                ExposedDropdownMenu(
                    expanded = modelMenu,
                    onDismissRequest = { modelMenu = false }
                ) {
                    provider.suggestedModels.forEach { suggestion ->
                        DropdownMenuItem(
                            text = { Text(suggestion) },
                            onClick = {
                                model = suggestion
                                settings.setModel(provider, suggestion)
                                modelMenu = false
                            }
                        )
                    }
                }
            }
        }

        if (provider.needsBaseUrl) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    settings.setBaseUrl(provider, it.trim())
                },
                label = { Text("Base URL") },
                singleLine = true,
                supportingText = {
                    Text("Ej: https://api.groq.com/openai/v1 o http://192.168.1.10:11434/v1")
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        TechButton(
            text = if (testing) "Probando…" else "Probar conexión",
            enabled = !testing,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val config = settings.activeConfig()
                if (config == null) {
                    testResult = "✘ Falta completar la configuración"
                    return@TechButton
                }
                testing = true
                testResult = null
                scope.launch {
                    testResult = try {
                        val response = LlmProviderFactory.create(config).complete(
                            listOf(
                                ChatMessage(
                                    ChatRole.USER,
                                    "Respondé únicamente con la palabra OK"
                                )
                            )
                        )
                        when (response) {
                            is LlmResponse.Text ->
                                "✔ Conexión OK — respondió: ${response.text.take(80)}"
                            is LlmResponse.ToolCall ->
                                "✔ Conexión OK (respondió con tool call)"
                        }
                    } catch (e: Exception) {
                        "✘ ${e.message}"
                    } finally {
                        testing = false
                    }
                }
            }
        )

        testResult?.let { result ->
            Text(
                result,
                style = MaterialTheme.typography.bodyMedium,
                color = if (result.startsWith("✔")) Accent else MaterialTheme.colorScheme.error
            )
        }
    }
}
