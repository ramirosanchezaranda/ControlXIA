package com.controlxia.app.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.controlxia.app.ui.components.StatusDot
import com.controlxia.app.ui.components.TechTopBar
import com.controlxia.app.ui.theme.Accent
import com.controlxia.app.ui.theme.Muted
import com.controlxia.app.voice.VoskModelDownloader
import com.controlxia.app.voice.VoskModelStore
import com.controlxia.app.voice.WakePhraseDetector
import com.controlxia.app.voice.WakePhraseResolver
import com.controlxia.app.voice.WakeWordSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    var conversation by remember { mutableStateOf(settings.conversationMode) }
    var waitSeconds by remember { mutableFloatStateOf(settings.conversationSeconds.toFloat()) }

    Column(Modifier.fillMaxSize()) {
        TechTopBar(title = "Asistente y activación", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionLabel("Modelo de voz")
            VoiceModelContent()

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

            // Modo conversación: seguir escuchando sin repetir el nombre.
            SectionLabel("Conversación")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Seguir escuchando", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Después de responder, espera tu próximo pedido sin que tengas que " +
                            "decir el nombre. Si no decís nada, vuelve a esperar el nombre. " +
                            "Decí «gracias» o «listo» para terminar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
                Switch(
                    checked = conversation,
                    onCheckedChange = {
                        conversation = it
                        settings.conversationMode = it
                    },
                )
            }
            if (conversation) {
                Column {
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            "Cuánto espera",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${waitSeconds.roundToInt()} s",
                            style = MaterialTheme.typography.labelSmall,
                            color = Accent,
                        )
                    }
                    Slider(
                        value = waitSeconds,
                        onValueChange = { waitSeconds = it },
                        valueRange = WakeWordSettings.MIN_WAIT_S.toFloat()..WakeWordSettings.MAX_WAIT_S.toFloat(),
                        steps = WakeWordSettings.MAX_WAIT_S - WakeWordSettings.MIN_WAIT_S - 1,
                        onValueChangeFinished = { settings.conversationSeconds = waitSeconds.roundToInt() },
                    )
                }
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
    val context = LocalContext.current
    var agentName by remember { mutableStateOf(settings.agentName) }
    var wakeWord by remember { mutableStateOf(settings.wakeWord) }

    // Verificación en vivo: ¿el modelo de voz va a reconocer este nombre?
    val modelState by VoskModelDownloader.state.collectAsState()
    val phrase = wakeWord.trim().ifBlank { agentName.trim().ifBlank { "Xia" } }
    var check by remember { mutableStateOf<NameCheck?>(null) }
    LaunchedEffect(phrase, modelState is VoskModelDownloader.State.Done) {
        if (!VoskModelStore.isAvailable(context)) {
            check = NameCheck(null, "Cuando esté el modelo de voz se verifica si reconoce «$phrase».")
            return@LaunchedEffect
        }
        check = NameCheck(null, "Verificando «$phrase»…")
        delay(600) // esperar a que termine de escribir
        check = withContext(Dispatchers.IO) { checkName(context, phrase) }
    }

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

        check?.let { result ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(result.ok)
                Spacer(Modifier.width(10.dp))
                Text(result.message, style = MaterialTheme.typography.bodySmall)
            }
        }

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
                    "Solo si no te reconoce: escribí el nombre como lo decís (ej. «sia»). " +
                        "También acepta una frase corta, como «oye xia»."
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

private class NameCheck(val ok: Boolean?, val message: String)

/** Carga el modelo (si hace falta) y prueba si [phrase] se puede reconocer. */
private fun checkName(context: Context, phrase: String): NameCheck {
    val result = runCatching {
        val model = VoskModelStore.get(context)
        WakePhraseResolver.resolve(phrase) { VoskModelStore.hasWord(model, it) }
    }
    // Sin la escucha encendida no tiene sentido dejar el modelo en memoria.
    if (!WakeWordService.running) VoskModelStore.releaseIfIdle()
    val words = result.getOrElse { return NameCheck(false, it.message ?: "No se pudo verificar") }
        ?: return NameCheck(false, "No reconozco «$phrase». Escribí abajo cómo suena.")
    val heard = words.joinToString(" ")
    return if (heard == WakePhraseDetector.normalize(phrase).joinToString(" ")) {
        NameCheck(true, "Va a responder cuando digas «$phrase».")
    } else {
        NameCheck(true, "Va a responder a «$phrase» (lo reconoce como «$heard»).")
    }
}
