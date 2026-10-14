package com.controlxia.app.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.controlxia.app.permissions.PermissionManager
import com.controlxia.app.service.HandsWatchService
import com.controlxia.app.service.WakeWordService
import com.controlxia.app.service.WaveGestures
import com.controlxia.app.ui.components.AuraSwitch
import com.controlxia.app.ui.components.SectionLabel
import com.controlxia.app.ui.components.StatusDot
import com.controlxia.app.ui.components.TechTopBar
import com.controlxia.app.ui.components.glass
import com.controlxia.app.ui.theme.Accent
import com.controlxia.app.ui.theme.Muted
import com.controlxia.app.voice.GestureModel
import com.controlxia.app.voice.VoskModelDownloader
import com.controlxia.app.voice.VoskModelStore
import com.controlxia.app.voice.WakePhraseDetector
import com.controlxia.app.voice.WakePhraseResolver
import com.controlxia.app.voice.WakeWordSettings
import com.controlxia.app.voice.WaveDetector
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
    var spokenAck by remember { mutableStateOf(settings.spokenAck) }

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
                AuraSwitch(
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
                        onValueChangeFinished = { settings.conversationSeconds = waitSeconds.roundToInt() },
                    )
                    Text(
                        "Con 30 s o más podés hablarle de corrido: cada pedido sigue al anterior " +
                            "hasta que digas «gracias» o te quedes callado.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Contestar «Te escucho»", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Apagado suena un tono corto y escucha al instante: es más rápido y no se " +
                            "pierde el principio de lo que decís.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
                AuraSwitch(
                    checked = spokenAck,
                    onCheckedChange = {
                        spokenAck = it
                        settings.spokenAck = it
                    },
                )
            }

            BatteryContent(settings)

            SectionLabel("Gestos con las manos")
            WaveContent(settings)
            HandsPauseContent(settings)

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
                AuraSwitch(
                    checked = showLockedContent,
                    onCheckedChange = {
                        showLockedContent = it
                        settings.showContentWhenLocked = it
                    },
                )
            }

            Text(
                "Los cambios se aplican solos al volver.",
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

/**
 * "Pausa con las manos": pide el permiso de cámara, baja el modelo de gestos
 * (8 MB, una vez) y arranca el servicio que mira mientras se reproduce algo.
 */
@Composable
private fun HandsPauseContent(settings: WakeWordSettings) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(settings.handsPause) }
    var oneHand by remember { mutableStateOf(settings.handGestures) }
    val download by GestureModel.state.collectAsState()

    fun turnOn() {
        settings.handsPause = true
        enabled = true
        GestureModel.ensure(context) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { HandsWatchService.start(context) }
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) turnOn() }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Mirar mis manos mientras veo videos", style = MaterialTheme.typography.titleSmall)
            Text(
                "La cámara frontal se prende solo mientras se reproduce algo o hay una app de " +
                    "videos adelante (Instagram, TikTok, YouTube…). Todo se analiza en el " +
                    "teléfono, sin guardar imágenes. Las señas que tocan la pantalla necesitan " +
                    "el control de apps.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
        AuraSwitch(
            checked = enabled,
            onCheckedChange = { on ->
                if (on) {
                    if (PermissionManager.hasCamera(context)) turnOn()
                    else cameraPermission.launch(android.Manifest.permission.CAMERA)
                } else {
                    settings.handsPause = false
                    enabled = false
                    HandsWatchService.stop(context)
                }
            },
        )
    }
    if (enabled) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Señas con una mano", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            AuraSwitch(
                checked = oneHand,
                onCheckedChange = {
                    oneHand = it
                    settings.handGestures = it
                },
            )
        }
        Legend(if (oneHand) HAND_LEGEND else HAND_LEGEND.takeLast(1))
    }
    when (val state = download) {
        is GestureModel.State.Downloading -> Text(
            "Bajando el modelo de gestos… ${(state.progress * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = Accent,
        )
        is GestureModel.State.Failed -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = Accent)
        else -> Unit
    }
}

/**
 * Saludar al teléfono: sin cámara, con el sensor de proximidad. Muestra en
 * vivo lo que ve el sensor para probarlo.
 */
@Composable
private fun WaveContent(settings: WakeWordSettings) {
    val context = LocalContext.current
    val available = remember { WaveGestures.available(context) }
    var enabled by remember { mutableStateOf(settings.waveGestures) }
    SwitchRow(
        title = "Saludar al teléfono",
        detail = if (available) {
            "Pasá la mano por encima de la pantalla, a unos centímetros y sin tocarla. Usa el " +
                "sensor de proximidad (el de las llamadas): anda con la pantalla apagada, sin " +
                "cámara, y casi no gasta batería."
        } else {
            "Tu teléfono no tiene sensor de proximidad, así que no puede ver la mano sin la cámara."
        },
        checked = enabled && available,
        onChange = {
            if (available) {
                enabled = it
                settings.waveGestures = it
                WakeWordService.reload(context)
            }
        },
    )
    if (enabled && available) {
        Legend(WAVE_LEGEND)
        WaveTester()
    }
}

/** Probador: escucha el sensor mientras la pantalla está abierta y dice qué vio. */
@Composable
private fun WaveTester() {
    val context = LocalContext.current
    var seen by remember { mutableStateOf("Probalo: pasá la mano por arriba de la pantalla.") }
    DisposableEffect(Unit) {
        val sensors = context.getSystemService(SensorManager::class.java)
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        val detector = WaveDetector()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val near = WaveGestures.isNear(event)
                when (val result = detector.feed(near, SystemClock.elapsedRealtime())) {
                    is WaveDetector.Event.Wave ->
                        seen = if (result.count >= 2) "👋👋 ¡Dos saludos! Ahí te escucharía." else "👋 ¡Saludo! Con la pantalla apagada, la prendería."
                    else -> if (near) seen = "✋ Veo tu mano…"
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null) sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sensors?.unregisterListener(listener) }
    }
    Text(seen, style = MaterialTheme.typography.bodySmall, color = Accent)
}

/** Una lista de gestos: el dibujo y qué hace. */
@Composable
private fun Legend(items: List<Pair<String, String>>) {
    Column(Modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (sign, meaning) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(sign, style = MaterialTheme.typography.titleLarge, maxLines = 1, modifier = Modifier.width(60.dp))
                Text(meaning, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Qué hace cada gesto encima del teléfono. */
private val WAVE_LEGEND = listOf(
    "👋" to "Un saludo con la pantalla apagada: la prende",
    "👋👋" to "Dos saludos seguidos: te escucho, sin decir mi nombre",
    "✋" to "Tapar un segundo: posponer la alarma, frenarme mientras hablo, o pausar el video que mirás",
)

/** Qué hace cada seña frente a la cámara (la última, las dos manos, siempre está). */
private val HAND_LEGEND = listOf(
    "☝️" to "Dedo para arriba: el reel o video siguiente (mantenelo para seguir pasando)",
    "👇" to "Dedo para abajo: el anterior",
    "👉" to "Dedo al costado: la foto o historia siguiente o anterior",
    "✋" to "Palma abierta: pausa o sigue el video",
    "👍" to "Pulgar arriba: me gusta",
    "✌️" to "V: pausa y te escucho, sin decir mi nombre",
    "🙌" to "Las dos manos arriba: pausa y te pregunto qué necesitás",
)

/** Cuándo soltar el micrófono para ahorrar batería (lo aplica el servicio al instante). */
@Composable
private fun BatteryContent(settings: WakeWordSettings) {
    val context = LocalContext.current
    var screenOff by remember { mutableStateOf(settings.listenScreenOff) }
    var lowBattery by remember { mutableStateOf(settings.pauseOnLowBattery) }
    SectionLabel("Batería")
    SwitchRow(
        title = "Escuchar con la pantalla apagada",
        detail = "Para llamarlo con el teléfono bloqueado y la pantalla negra. Apagado, solo " +
            "te oye con la pantalla prendida y el teléfono duerme de verdad: es lo que más ahorra.",
        checked = screenOff,
        onChange = {
            screenOff = it
            settings.listenScreenOff = it
            WakeWordService.reload(context)
        },
    )
    if (screenOff) {
        SwitchRow(
            title = "Pausar con batería baja",
            detail = "Con la pantalla apagada y menos de ${com.controlxia.app.service.ListeningPolicy.LOW_PERCENT} % " +
                "(o con el ahorro de batería del teléfono), deja de escuchar hasta que prendas la pantalla o lo cargues.",
            checked = lowBattery,
            onChange = {
                lowBattery = it
                settings.pauseOnLowBattery = it
                WakeWordService.reload(context)
            },
        )
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        AuraSwitch(checked = checked, onCheckedChange = onChange)
    }
}
