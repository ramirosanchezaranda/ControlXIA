package com.controlxia.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.controlxia.app.service.WakeWordService
import com.controlxia.app.ui.components.StatusRow
import com.controlxia.app.ui.components.TechButton
import com.controlxia.app.ui.components.rememberResumeTick
import com.controlxia.app.ui.theme.Muted
import com.controlxia.app.voice.VoskModelDownloader
import com.controlxia.app.voice.VoskModelStore

/**
 * Estado del modelo de voz en español (lo necesita la palabra de activación):
 * botón para descargarlo, progreso mientras baja, o "listo". Con
 * [showWhenReady] en false no muestra nada si ya está instalado.
 */
@Composable
fun VoiceModelContent(modifier: Modifier = Modifier, showWhenReady: Boolean = true) {
    val context = LocalContext.current
    val state by VoskModelDownloader.state.collectAsState()
    val tick = rememberResumeTick()
    val available = remember(state, tick) { VoskModelStore.isAvailable(context) }
    if (available && !showWhenReady) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val current = state
        when {
            available -> StatusRow(label = "Modelo de voz en español listo", ok = true)
            current is VoskModelDownloader.State.Downloading -> {
                val progress = current.progress
                val percent = progress?.let { " ${(it * 100).toInt()}%" }.orEmpty()
                StatusRow(label = "Descargando el modelo de voz…$percent", ok = null)
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            current is VoskModelDownloader.State.Installing -> {
                StatusRow(label = "Instalando el modelo de voz…", ok = null)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            else -> {
                if (current is VoskModelDownloader.State.Failed) {
                    Text(
                        current.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "Para reconocer su nombre, la app necesita el modelo de voz en " +
                        "español (39 MB, una sola vez). Conviene usar Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                TechButton(
                    text = if (current is VoskModelDownloader.State.Failed) {
                        "Reintentar la descarga"
                    } else {
                        "Descargar modelo de voz (39 MB)"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val app = context.applicationContext
                        // Si la escucha ya estaba encendida, arranca sola al terminar.
                        VoskModelDownloader.start(app) { WakeWordService.reload(app) }
                    },
                )
            }
        }
    }
}
