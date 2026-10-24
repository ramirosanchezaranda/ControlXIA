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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.controlxia.app.diagnostics.GestureDiagnostics
import com.controlxia.app.permissions.PermissionManager
import com.controlxia.app.service.HandsWatchService
import com.controlxia.app.service.WakeWordService
import com.controlxia.app.service.WaveGestures
import com.controlxia.app.ui.components.AuraSwitch
import com.controlxia.app.ui.components.SectionLabel
import com.controlxia.app.ui.components.SegmentedControl
import com.controlxia.app.ui.components.StatusDot
import com.controlxia.app.ui.components.TechButton
import com.controlxia.app.ui.components.TechTopBar
import com.controlxia.app.ui.components.glass
import com.controlxia.app.ui.theme.Accent
import com.controlxia.app.ui.theme.Muted
import com.controlxia.app.voice.GestureModel
import com.controlxia.app.voice.LockedAccess
import com.controlxia.app.voice.VoiceId
import com.controlxia.app.voice.VoicePrint
import com.controlxia.app.voice.VoskModelDownloader
import com.controlxia.app.voice.VoskModelStore
import com.controlxia.app.voice.WakePhraseDetector
import com.controlxia.app.voice.WakePhraseResolver
import com.controlxia.app.voice.WakeWordSettings
import com.controlxia.app.voice.WaveDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
            GestureDiagnosticsButton()

            // Privacidad con el teléfono bloqueado
            SectionLabel("Privacidad")
            LockedAccessContent(settings)
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
    var always by remember { mutableStateOf(settings.handsAlways) }
    var answers by remember { mutableStateOf(settings.confirmGestures) }
    val download by GestureModel.state.collectAsState()

    fun turnOn() {
        settings.handsPause = true
        enabled = true
        GestureModel.ensure(context) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                HandsWatchService.start(context)
                HandsWatchService.refresh()
            }
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) turnOn() }

    SwitchRow(
        title = "Señas con la cámara",
        detail = "La cámara frontal mira tus manos mientras se reproduce algo o hay una app de videos " +
            "adelante (Instagram, TikTok, YouTube…), y tu cara cuando te pregunto algo. Todo se analiza " +
            "en el teléfono, sin guardar imágenes. Las señas que tocan la pantalla necesitan el control de apps.",
        checked = enabled,
        onChange = { on ->
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
    if (enabled) {
        SwitchRow(
            title = "Señas con una mano",
            detail = "Pasar de reel, me gusta, pausa, hablarme (abajo, la lista).",
            checked = oneHand,
            onChange = {
                oneHand = it
                settings.handGestures = it
                HandsWatchService.refresh()
            },
        )
        SwitchRow(
            title = "Con cualquier app",
            detail = "La cámara mira siempre que la pantalla esté prendida, no solo con videos o música. Gasta bastante más batería.",
            checked = always,
            onChange = {
                always = it
                settings.handsAlways = it
                HandsWatchService.refresh()
            },
        )
        SwitchRow(
            title = "Contestar sí o no con un gesto",
            detail = "Cuando pregunto algo (\"¿Le mando…?\"), la cámara mira tu cara y tus manos un momento.",
            checked = answers,
            onChange = {
                answers = it
                settings.confirmGestures = it
                HandsWatchService.refresh()
            },
        )
        Legend((if (oneHand) HAND_LEGEND else HAND_LEGEND.takeLast(1)) + (if (answers) ANSWER_LEGEND else emptyList()))
        CameraTester()
    }
    when (val state = download) {
        is GestureModel.State.Downloading -> Text(
            "Bajando los modelos de gestos… ${(state.progress * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = Accent,
        )
        is GestureModel.State.Failed -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = Accent)
        else -> Unit
    }
}

/** Qué está haciendo la cámara y la última seña que vio; "Probar" la prende 20 segundos. */
@Composable
private fun CameraTester() {
    val live by HandsWatchService.live.collectAsState()
    var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(live) {
        while (true) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "Cámara: ${live.reason.ifBlank { if (HandsWatchService.running) "preparando…" else "apagada" }}",
                style = MaterialTheme.typography.bodySmall,
                color = Accent,
            )
            live.seen?.let { sign ->
                val ago = ((now - live.seenAt) / 1_000).coerceAtLeast(0)
                Text("Última seña: $sign (hace $ago s)", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
    }
    var testing by remember { mutableStateOf(false) }
    TechButton("Probar con la cámara", onClick = { testing = true })
    if (testing) GestureTestDialog(onClose = { testing = false })
}

/** Copia un resumen de cómo están los gestos en este teléfono, para pegarlo en un chat. */
@Composable
private fun GestureDiagnosticsButton() {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "¿Algún gesto no anda? Probalo, tocá «Copiar diagnóstico» y pegalo en el chat.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            modifier = Modifier.weight(1f),
        )
        TechButton("Copiar diagnóstico", filled = false, onClick = {
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Diagnóstico de gestos", GestureDiagnostics.report(context)))
            android.widget.Toast.makeText(context, "Copiado: pegalo en el chat", android.widget.Toast.LENGTH_LONG).show()
        })
    }
}


/** Mensajes, notificaciones y llamadas con el teléfono bloqueado: no, con huella, con tu voz, o siempre. */
@Composable
private fun LockedAccessContent(settings: WakeWordSettings) {
    var access by remember { mutableStateOf(settings.lockedAccess) }
    val options = listOf(LockedAccess.BLOCKED, LockedAccess.ASK_UNLOCK, LockedAccess.VOICE, LockedAccess.ALLOWED)
    Text("Mensajes y llamadas con el teléfono bloqueado", style = MaterialTheme.typography.titleSmall)
    SegmentedControl(
        options = listOf("No", "Huella", "Mi voz", "Siempre"),
        selected = options.indexOf(access),
        onSelect = {
            access = options[it]
            settings.lockedAccess = access
        },
    )
    Text(
        when (access) {
            LockedAccess.BLOCKED ->
                "Bloqueado no lee mensajes ni notificaciones, no manda y no llama. La hora, las " +
                    "alarmas, la linterna y la música andan igual."
            LockedAccess.ASK_UNLOCK ->
                "Antes de leerte mensajes o notificaciones, mandar o llamar, te pide la huella " +
                    "(o la cara o el PIN) y sigue solo. Así nadie más puede pedirlo con tu teléfono bloqueado."
            LockedAccess.VOICE ->
                "Te pide decir tu contraseña: comprueba que sea la contraseña y que sea tu voz. Si no te " +
                    "reconoce, pide la huella. Es más cómodo pero menos seguro que la huella: no digas la " +
                    "contraseña delante de otros."
            LockedAccess.ALLOWED ->
                "Lo hace sin desbloquear. Ojo: cualquiera que diga mi nombre cerca de tu teléfono " +
                    "puede escuchar tus mensajes o mandar uno."
        },
        style = MaterialTheme.typography.bodySmall,
        color = Muted,
    )
    if (access == LockedAccess.VOICE) VoiceIdContent()
}

/**
 * Grabar tu voz con tu contraseña (3 veces) y probarla. Baja el modelo de
 * hablantes la primera vez.
 */
@Composable
private fun VoiceIdContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enrolled by remember { mutableStateOf(VoiceId.isEnrolled(context)) }
    var password by remember { mutableStateOf("") }
    var resolved by remember { mutableStateOf<String?>(null) }
    var prints by remember { mutableStateOf(listOf<FloatArray>()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (enrolled) {
            Text("Tu voz está guardada.", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TechButton("Probar", enabled = !busy, onClick = {
                    busy = true
                    message = "Decí tu contraseña ahora…"
                    scope.launch {
                        val check = VoiceId.verify(context)
                        message = when {
                            check == null -> "No pude usar el micrófono. Probá de nuevo."
                            check.ok -> "✓ Te reconocí. Parecido ${percent(check.similarity)} (pide ${percent(check.threshold)})."
                            !check.passwordOk -> "✗ No entendí la contraseña (oí «${check.heard}»)."
                            else -> "✗ La contraseña está bien, pero la voz no se parece lo suficiente: ${percent(check.similarity)} (pide ${percent(check.threshold)})."
                        }
                        busy = false
                    }
                })
                TechButton("Borrar mi voz", filled = false, enabled = !busy, onClick = {
                    VoiceId.clear(context)
                    enrolled = false
                    resolved = null
                    prints = emptyList()
                    message = null
                })
            }
        } else if (resolved == null) {
            Text(
                "Elegí una contraseña de 2 a 4 palabras comunes que no digas seguido " +
                    "(por ejemplo «girasol azul de mayo»). Después la decís 3 veces para que aprenda tu voz.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Tu contraseña") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TechButton("Empezar", enabled = !busy && password.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    message = when {
                        !PermissionManager.hasRecordAudio(context) -> "Falta el permiso de micrófono."
                        !VoskModelStore.isAvailable(context) -> "Primero bajá el modelo de voz (arriba, en Modelo de voz)."
                        else -> {
                            val error = VoiceId.downloadSpeakerModel(context) { p ->
                                message = "Bajando el reconocimiento de voz… ${(p * 100).roundToInt()} %"
                            }
                            if (error != null) {
                                error
                            } else {
                                resolved = VoiceId.resolvePassword(context, password)
                                if (resolved == null) "Alguna palabra no la conozco o son muchas: probá con otras." else null
                            }
                        }
                    }
                    busy = false
                }
            })
        } else {
            val words = resolved.orEmpty()
            val done = prints.size
            Text("Decí «$words» (${done + 1} de $ENROLL_SAMPLES)", style = MaterialTheme.typography.titleSmall)
            TechButton(if (busy) "Escuchando…" else "Grabar", enabled = !busy, onClick = {
                busy = true
                message = "Hablá ahora…"
                scope.launch {
                    val sample = VoiceId.listen(context, words)
                    message = when {
                        sample == null -> "No pude usar el micrófono. Probá de nuevo."
                        sample.text != words -> "✗ Entendí «${sample.text}». Probá de nuevo, un poco más claro."
                        sample.print == null -> "✗ Fue muy corto para reconocer la voz. Decila un poco más lento."
                        else -> {
                            prints = prints + sample.print
                            if (prints.size >= ENROLL_SAMPLES) {
                                VoiceId.save(context, words, prints)
                                enrolled = true
                                val even = VoicePrint.consistency(prints)
                                if (even < 0.6f) {
                                    "Listo, tu voz quedó guardada, pero las grabaciones salieron distintas entre sí. " +
                                        "Si no te reconoce bien, borrala y grabala de nuevo en un lugar tranquilo."
                                } else {
                                    "Listo, tu voz quedó guardada. Tocá Probar para ver si te reconoce."
                                }
                            } else {
                                "✓ Bien. Otra vez."
                            }
                        }
                    }
                    busy = false
                }
            })
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Accent) }
    }
}

private fun percent(value: Float?): String = value?.let { "${(it * 100).roundToInt()} %" } ?: "—"

private const val ENROLL_SAMPLES = 3

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
        if (remember { !WaveGestures.wakesUp(context) }) {
            Text(
                "Tu teléfono avisa que su sensor podría no funcionar con la pantalla apagada " +
                    "(muchos son \"virtuales\"). Probalo: si no prende, usá dos saludos con la pantalla prendida.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
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

/** Contestar cuando pregunto algo. */
private val ANSWER_LEGEND = listOf(
    "😉" to "Guiñar un ojo o asentir con la cabeza: sí",
    "🙅" to "Negar con la cabeza: no",
    "👍👎" to "Pulgar arriba: sí · pulgar abajo: no",
)

/** Qué hace cada seña frente a la cámara (la última, las dos manos, siempre está). */
private val HAND_LEGEND = listOf(
    "✋⬆️" to "Palma abierta hacia la cámara, quieta hasta que vibre apenas… y movela rápido para arriba: el reel o video siguiente",
    "✋⬇️" to "Para abajo: el anterior",
    "✋↔️" to "A un costado: la foto o historia siguiente o anterior. Es el mismo deslizamiento que con el dedo, y volver la mano no cuenta",
    "✋" to "Palma quieta 2 segundos, sin moverla: pausa o sigue el video",
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
