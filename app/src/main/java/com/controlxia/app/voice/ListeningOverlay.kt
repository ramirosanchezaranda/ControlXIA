package com.controlxia.app.voice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.getSystemService

/**
 * Indicador flotante de "escuchando" que se dibuja **por encima de cualquier
 * app**, como el asistente de Google. Aparece cuando se detecta la palabra de
 * activación y desaparece al terminar la interacción.
 *
 * Requiere el permiso "Mostrar sobre otras apps" (SYSTEM_ALERT_WINDOW). Si no
 * está concedido, no hace nada (la app igual responde por voz). Todas las
 * operaciones deben llamarse desde el main thread.
 */
class ListeningOverlay(private val context: Context) {

    private val windowManager = context.getSystemService<WindowManager>()
    private var view: View? = null
    private var statusView: TextView? = null
    private var dotAnimator: ValueAnimator? = null

    /** Tocar la píldora frena lo que el asistente esté haciendo o diciendo. */
    var onTap: (() -> Unit)? = null

    fun canShow(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /** Muestra el overlay con el nombre del asistente y un estado. */
    fun show(agentName: String, status: String) {
        if (!canShow()) return
        if (view != null) {
            updateStatus(status)
            return
        }
        val root = buildView(agentName, status)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(72)
        }
        runCatching {
            windowManager?.addView(root, params)
            view = root
            shown = java.lang.ref.WeakReference(this)
        }
    }

    /** Cambia el texto de estado ("Escuchando…", "Escuché: …"). */
    fun updateStatus(status: String) {
        statusView?.text = status
    }

    fun hide() {
        if (shown?.get() === this) shown = null
        dotAnimator?.cancel()
        dotAnimator = null
        view?.let { v -> runCatching { windowManager?.removeView(v) } }
        view = null
        statusView = null
    }

    private fun buildView(agentName: String, status: String): View {
        val ink = Color.parseColor("#0B0B0C")
        val paper = Color.parseColor("#FBFBF9")
        val accent = Color.parseColor("#7186F2")

        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(20), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(ink)
            }
            elevation = dp(8).toFloat()
        }

        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
            }
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply {
                rightMargin = dp(12)
            }
        }
        // Pulso continuo del punto: escala + alpha.
        dotAnimator = ValueAnimator.ofFloat(0.5f, 1f).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                dot.scaleX = f; dot.scaleY = f; dot.alpha = f
            }
            start()
        }

        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(context).apply {
            text = agentName
            setTextColor(paper)
            textSize = 15f
        }
        val statusView = TextView(context).apply {
            text = status
            setTextColor(Color.parseColor("#B8B8B2"))
            textSize = 12f
        }
        texts.addView(title)
        texts.addView(statusView)

        // Botón de frenar: también se puede decir "pará" o "basta".
        val stop = TextView(context).apply {
            text = "✕"
            setTextColor(paper)
            textSize = 16f
            contentDescription = "Frenar"
            setPadding(dp(16), 0, 0, 0)
        }

        pill.addView(dot)
        pill.addView(texts)
        pill.addView(stop)
        pill.setOnClickListener { onTap?.invoke() }
        this.statusView = statusView
        return pill
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    companion object {
        @Volatile
        private var shown: java.lang.ref.WeakReference<ListeningOverlay>? = null

        /** Saca la píldora de la pantalla (para que no salga en una captura). */
        fun hideAll() {
            val overlay = shown?.get() ?: return
            android.os.Handler(android.os.Looper.getMainLooper()).post { overlay.hide() }
        }
    }
}
