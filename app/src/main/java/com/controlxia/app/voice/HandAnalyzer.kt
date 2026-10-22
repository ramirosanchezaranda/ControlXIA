package com.controlxia.app.voice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.controlxia.app.diagnostics.AgentLog
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * Lo que ve la cámara en un cuadro: cada mano (su seña, dónde está y si es una
 * palma levantada) y, si se pide, la cara (ojos y nariz). Lo usan el servicio
 * de gestos y la pantalla de prueba, con los mismos modelos de MediaPipe.
 */
class HandAnalyzer private constructor(
    private val gestures: GestureRecognizer,
    private val faces: FaceLandmarker?,
) : Closeable {

    /** Una mano: su seña, el centro de la palma (0 a 1 en la imagen derecha) y si es una palma levantada. */
    data class Hand(val sign: HandSigns.Sign, val x: Float, val y: Float, val raisedPalm: Boolean)

    class Result(val hands: List<Hand>, val face: FaceSigns.Face?) {
        /** Palmas abiertas levantadas (dos = "pausa y preguntá"). */
        val raised: Int get() = hands.count { it.raisedPalm }
    }

    val hasFace: Boolean get() = faces != null

    fun analyze(frame: Bitmap, withFace: Boolean = false): Result {
        val image = BitmapImageBuilder(frame).build()
        val aspect = frame.width.toFloat() / frame.height
        val result = gestures.recognize(image)
        val hands = result.landmarks().indices.map { i ->
            val points = result.landmarks()[i].map { HandSigns.Point(it.x(), it.y()) }
            val top = result.gestures().getOrNull(i)?.firstOrNull()
            val sign = HandSigns.classify(top?.categoryName(), top?.score() ?: 0f, points, aspect)
            val palm = PALM.mapNotNull { points.getOrNull(it) }
            val x = palm.map { it.x }.average().toFloat()
            val y = palm.map { it.y }.average().toFloat()
            val raised = top?.categoryName() == "Open_Palm" && (top.score() >= RAISED_SCORE) &&
                (points.firstOrNull()?.y ?: 1f) < MAX_WRIST_Y
            Hand(sign, x, y, raised)
        }
        val face = if (withFace) face(image) else null
        return Result(hands, face)
    }

    private fun face(image: com.google.mediapipe.framework.image.MPImage): FaceSigns.Face? {
        val landmarker = faces ?: return null
        val result = runCatching { landmarker.detect(image) }.getOrNull() ?: return null
        val shapes = result.faceBlendshapes().orElse(null)?.firstOrNull() ?: return null
        val nose = result.faceLandmarks().firstOrNull()?.getOrNull(NOSE_TIP) ?: return null
        fun score(name: String) = shapes.firstOrNull { it.categoryName() == name }?.score() ?: 0f
        return FaceSigns.Face(score("eyeBlinkLeft"), score("eyeBlinkRight"), nose.x(), nose.y())
    }

    override fun close() {
        runCatching { gestures.close() }
        runCatching { faces?.close() }
    }

    companion object {
        private val PALM = listOf(0, 5, 9, 13, 17)
        private const val NOSE_TIP = 1
        private const val RAISED_SCORE = 0.55f
        private const val MAX_WRIST_Y = 0.8f

        /** Carga los modelos (el de la cara si está). null si falta el de gestos. */
        fun create(context: Context): HandAnalyzer? {
            if (!GestureModel.isInstalled(context)) {
                AgentLog.add("MANOS falta el modelo de gestos")
                return null
            }
            val gestures = runCatching {
                GestureRecognizer.createFromOptions(
                    context,
                    GestureRecognizer.GestureRecognizerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(map(GestureModel.file(context))).build())
                        .setRunningMode(RunningMode.IMAGE)
                        .setNumHands(2)
                        .setMinHandDetectionConfidence(0.4f)
                        .setMinHandPresenceConfidence(0.4f)
                        .build(),
                )
            }.onFailure { AgentLog.add("MANOS no pude cargar el modelo de gestos: ${it.message}") }.getOrNull() ?: return null
            val faces = if (GestureModel.isFaceInstalled(context)) {
                runCatching {
                    FaceLandmarker.createFromOptions(
                        context,
                        FaceLandmarker.FaceLandmarkerOptions.builder()
                            .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(map(GestureModel.faceFile(context))).build())
                            .setRunningMode(RunningMode.IMAGE)
                            .setNumFaces(1)
                            .setOutputFaceBlendshapes(true)
                            .build(),
                    )
                }.onFailure { AgentLog.add("MANOS no pude cargar el modelo de la cara: ${it.message}") }.getOrNull()
            } else {
                null
            }
            return HandAnalyzer(gestures, faces)
        }

        private fun map(file: File) =
            RandomAccessFile(file, "r").channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }

        /** El cuadro de la cámara derecho (la cámara lo entrega girado según cómo esté el sensor). */
        fun upright(image: ImageProxy): Bitmap {
            val bitmap = image.toBitmap()
            val degrees = image.imageInfo.rotationDegrees
            if (degrees == 0) return bitmap
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
    }
}
