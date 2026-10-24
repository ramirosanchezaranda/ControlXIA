package com.controlxia.app.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.controlxia.app.service.HandsWatchService
import com.controlxia.app.ui.components.TechButton
import com.controlxia.app.ui.theme.Aura
import com.controlxia.app.voice.AirGestures
import com.controlxia.app.voice.FaceSigns
import com.controlxia.app.voice.HandAnalyzer
import com.controlxia.app.voice.HandMotion
import com.controlxia.app.voice.HandSigns
import java.util.concurrent.Executors

/**
 * Probar los gestos viendo la cámara: muestra lo que ve (como un espejo), dónde
 * encuentra la mano y qué entiende en cada momento (seña, "lista para
 * deslizar", el deslizamiento, guiño o cabeza). No hace nada en el teléfono:
 * es para aprender los gestos y ver por qué alguno no sale. Mientras está
 * abierta, el servicio de gestos suelta la cámara.
 */
@Composable
fun GestureTestDialog(onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            GestureTestContent(onClose)
        }
    }
}

private data class TestState(
    val frame: ImageBitmap? = null,
    val hands: List<Pair<Float, Float>> = emptyList(),
    val armed: Boolean = false,
    val sign: String = "",
    val event: String = "",
    val eventAt: Long = 0L,
    val eyes: String = "",
    val fps: Int = 0,
    val status: String = "Prendiendo la cámara…",
)

@Composable
private fun GestureTestContent(onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var state by remember { mutableStateOf(TestState()) }

    DisposableEffect(Unit) {
        HandsWatchService.pause(true)
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        var analyzer: HandAnalyzer? = null
        val air = AirGestures()
        val faces = FaceSigns()
        var provider: ProcessCameraProvider? = null
        var analysis: ImageAnalysis? = null
        var lastAt = 0L
        var frames = 0
        var fpsSince = SystemClock.elapsedRealtime()

        fun post(change: (TestState) -> TestState) = main.execute { state = change(state) }

        executor.execute {
            analyzer = HandAnalyzer.create(context)
            if (analyzer == null) post { it.copy(status = "Falta el modelo de gestos: activá «Señas con la cámara» y esperá que se baje.") }
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val cameras = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = cameras
            val useCase = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(Size(480, 360), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            useCase.setAnalyzer(executor) { image ->
                try {
                    val now = SystemClock.elapsedRealtime()
                    val engine = analyzer
                    if (engine == null || now - lastAt < FRAME_MS) return@setAnalyzer
                    lastAt = now
                    val upright = HandAnalyzer.upright(image)
                    val result = engine.analyze(upright, withFace = engine.hasFace)
                    frames++
                    val fps = if (now - fpsSince >= 1_000) {
                        val f = frames
                        frames = 0
                        fpsSince = now
                        f
                    } else {
                        -1
                    }
                    val hand = result.hands.firstOrNull { it.presented } ?: result.hands.firstOrNull()
                    val gesture = air.feed(hand?.sign, now, presented = hand?.presented ?: false, sure = hand?.sure ?: false)
                    val swipe = (gesture as? AirGestures.Event.Swipe)?.swipe
                    val fired = (gesture as? AirGestures.Event.Sign)?.sign
                    val sign = hand?.sign ?: HandSigns.Sign.NONE
                    val answer = faces.feed(result.face, now)
                    val event = when {
                        swipe != null -> "${SWIPES[swipe]} ¡Deslizaste!"
                        result.raised >= 2 -> "🙌 Las dos manos arriba: pausa y pregunta"
                        fired != null -> "${NAMES[fired] ?: fired.name}: ¡seña reconocida!"
                        answer == FaceSigns.Answer.YES -> "😉 Sí (guiño o asentir)"
                        answer == FaceSigns.Answer.NO -> "🙅 No (negar con la cabeza)"
                        else -> null
                    }
                    val eyes = result.face?.let { "Ojos: izquierdo ${(it.blinkLeft * 100).toInt()} % · derecho ${(it.blinkRight * 100).toInt()} % cerrado" }.orEmpty()
                    val shown = mirror(upright).asImageBitmap()
                    post {
                        it.copy(
                            frame = shown,
                            // Se muestra como espejo: la x se invierte.
                            hands = result.hands.map { h -> (1f - h.x) to h.y },
                            armed = hand?.presented == true,
                            sign = when {
                                result.hands.isEmpty() -> "No veo ninguna mano. Ponela frente a la cámara, a unos 30–50 cm, con luz."
                                hand?.presented == false ->
                                    "Veo una mano, pero no cuenta: tiene que estar entera en la imagen, ni pegada a la pantalla ni lejos (a unos 30–50 cm)."
                                sign != HandSigns.Sign.NONE && hand?.sure == false && sign != HandSigns.Sign.FIST ->
                                    "Veo: ${NAMES[sign] ?: sign.name}, pero no estoy seguro: más luz o más de frente."
                                sign != HandSigns.Sign.NONE -> "Veo: ${NAMES[sign] ?: sign.name}"
                                else -> "Veo tu mano: apuntá con el índice arriba o abajo (o al costado), o cerrá el puño."
                            },
                            event = event ?: it.event,
                            eventAt = if (event != null) now else it.eventAt,
                            eyes = eyes,
                            fps = if (fps >= 0) fps else it.fps,
                            status = "",
                        )
                    }
                } catch (e: Exception) {
                    post { it.copy(status = "Error: ${e.message}") }
                } finally {
                    image.close()
                }
            }
            analysis = useCase
            val ok = runCatching {
                cameras.unbindAll()
                cameras.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, useCase)
            }
            if (ok.isFailure) post { it.copy(status = "No pude usar la cámara: ${ok.exceptionOrNull()?.message}") }
        }, main)

        onDispose {
            analysis?.let { useCase -> runCatching { provider?.unbind(useCase) } }
            executor.execute { analyzer?.close() }
            executor.shutdown()
            HandsWatchService.pause(false)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Probar gestos", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TechButton("Cerrar", filled = false, onClick = onClose)
        }
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(24.dp)).background(Color.Black)) {
            state.frame?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            val ring = if (state.armed) Color(0xFF34C759) else Color(0xFF0A84FF)
            Canvas(Modifier.fillMaxSize()) {
                state.hands.forEach { (x, y) ->
                    drawCircle(ring, radius = size.minDimension * 0.08f, center = Offset(x * size.width, y * size.height), style = Stroke(width = 8f))
                }
            }
        }
        if (state.status.isNotEmpty()) Text(state.status, style = MaterialTheme.typography.bodyMedium, color = Aura.colors.secondaryLabel)
        Text(state.sign, style = MaterialTheme.typography.titleMedium)
        val recent = SystemClock.elapsedRealtime() - state.eventAt < 3_000
        if (state.event.isNotEmpty()) {
            Text(
                state.event,
                style = MaterialTheme.typography.titleLarge,
                color = if (recent) Aura.colors.highlight else Aura.colors.secondaryLabel,
            )
        }
        if (state.eyes.isNotEmpty()) Text(state.eyes, style = MaterialTheme.typography.bodySmall, color = Aura.colors.secondaryLabel)
        Text(
            "La mano a unos 30–50 cm, entera en la imagen (el círculo se pone verde cuando cuenta). ☝️ índice arriba: " +
                "siguiente · 👇 abajo: anterior · 👉👈 al costado: foto o historia, medio segundo cada uno y relajando la " +
                "mano entre uno y otro. ✊ puño: pausa o sigue (en WhatsApp, graba un video) · 👍 me gusta · ✌️ hablarme · " +
                "🙌 las dos manos. Analiza ${state.fps} cuadros por segundo.",
            style = MaterialTheme.typography.bodySmall,
            color = Aura.colors.secondaryLabel,
        )
    }
}

/** El cuadro como un espejo (así se ve natural, como la cámara de selfies). */
private fun mirror(bitmap: Bitmap): Bitmap {
    val matrix = Matrix().apply { preScale(-1f, 1f) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
}

private const val FRAME_MS = 100L

private val SWIPES = mapOf(
    HandMotion.Swipe.UP to "⬆️ Arriba (siguiente)",
    HandMotion.Swipe.DOWN to "⬇️ Abajo (anterior)",
    HandMotion.Swipe.LEFT to "⬅️ Izquierda",
    HandMotion.Swipe.RIGHT to "➡️ Derecha",
)

private val NAMES = mapOf(
    HandSigns.Sign.POINT_UP to "☝️ dedo arriba", HandSigns.Sign.POINT_DOWN to "👇 dedo abajo",
    HandSigns.Sign.POINT_LEFT to "👈 dedo a la izquierda", HandSigns.Sign.POINT_RIGHT to "👉 dedo a la derecha",
    HandSigns.Sign.THUMB_UP to "👍 pulgar arriba", HandSigns.Sign.THUMB_DOWN to "👎 pulgar abajo",
    HandSigns.Sign.VICTORY to "✌️ V", HandSigns.Sign.OPEN_PALM to "✋ palma", HandSigns.Sign.FIST to "✊ puño",
)
