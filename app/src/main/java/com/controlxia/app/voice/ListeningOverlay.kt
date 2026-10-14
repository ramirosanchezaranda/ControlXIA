package com.controlxia.app.voice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.getSystemService
import com.controlxia.app.ui.theme.ThemeSettings
import com.controlxia.app.ui.theme.auraColors
import kotlin.math.sin

/**
 * La burbuja de "escuchando" que flota **por encima de cualquier app**, como
 * Siri o el asistente de Google: una cápsula de vidrio oscuro con la esfera del
 * asistente girando, el nombre, lo que va entendiendo y una ✕ para frenarlo.
 * Entra con un pequeño rebote y sale achicándose.
 *
 * Requiere el permiso "Mostrar sobre otras apps" (SYSTEM_ALERT_WINDOW). Si no
 * está concedido, no hace nada (la app igual responde por voz). Todas las
 * operaciones deben llamarse desde el main thread.
 */
class ListeningOverlay(private val context: Context) {

    private val windowManager = context.getSystemService<WindowManager>()
    private var view: View? = null
    private var statusView: TextView? = null
    private var orb: OrbView? = null

    /** Tocar la burbuja frena lo que el asistente esté haciendo o diciendo. */
    var onTap: (() -> Unit)? = null

    fun canShow(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /** Muestra la burbuja con el nombre del asistente y un estado. */
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
            y = dp(64)
        }
        runCatching {
            windowManager?.addView(root, params)
            view = root
            shown = java.lang.ref.WeakReference(this)
            // Entrada con rebote (como las notificaciones de iOS).
            root.alpha = 0f
            root.scaleX = 0.82f
            root.scaleY = 0.82f
            root.translationY = dp(18).toFloat()
            root.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(420).setInterpolator(OvershootInterpolator(1.4f)).start()
        }
    }

    /** Cambia el texto de estado ("Escuchando…", "Escuché: …"). */
    fun updateStatus(status: String) {
        val v = statusView ?: return
        if (v.text == status) return
        v.text = status
        v.alpha = 0.4f
        v.animate().alpha(1f).setDuration(180).start()
    }

    fun hide() {
        if (shown?.get() === this) shown = null
        orb?.stop()
        orb = null
        val v = view ?: return
        view = null
        statusView = null
        // Salida: se achica y se desvanece, y recién ahí se saca de la pantalla.
        runCatching {
            v.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(160)
                .withEndAction { runCatching { windowManager?.removeView(v) } }
                .start()
        }.onFailure { runCatching { windowManager?.removeView(v) } }
    }

    private fun buildView(agentName: String, status: String): View {
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(32).toFloat()
                // Vidrio oscuro: casi negro, translúcido, con un borde de luz.
                colors = intArrayOf(Color.parseColor("#F21C1D26"), Color.parseColor("#F20E0F16"))
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            elevation = dp(14).toFloat()
        }

        val orbView = OrbView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(12) }
            start()
        }
        orb = orbView

        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(context).apply {
            text = agentName
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val statusView = TextView(context).apply {
            text = status
            setTextColor(Color.parseColor("#C7C7D1"))
            textSize = 13f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = (context.resources.displayMetrics.widthPixels * 0.58f).toInt()
        }
        texts.addView(title)
        texts.addView(statusView)

        // Botón de frenar: también se puede decir "pará" o "basta".
        val stop = TextView(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            contentDescription = "Frenar"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#2EFFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { leftMargin = dp(14) }
        }

        pill.addView(orbView)
        pill.addView(texts)
        pill.addView(stop)
        pill.setOnClickListener {
            it.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80)
                .withEndAction { it.animate().scaleX(1f).scaleY(1f).setDuration(160).start() }.start()
            onTap?.invoke()
        }
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

    /**
     * La esfera chica de la burbuja: cuerpo azul con luz arriba, un anillo de
     * colores que gira adentro y un latido que crece con la voz.
     */
    private class OrbView(context: Context) : View(context) {
        private val body = Paint(Paint.ANTI_ALIAS_FLAG)
        private val swirl = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rotation = Matrix()
        private var angle = 0f
        private var time = 0f
        private var animator: ValueAnimator? = null

        fun start() {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1_000
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                var last = 0L
                addUpdateListener {
                    val now = System.nanoTime()
                    val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                    last = now
                    time += dt
                    angle = (angle + dt * 160f) % 360f
                    invalidate()
                }
                start()
            }
        }

        fun stop() {
            animator?.cancel()
            animator = null
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val r = minOf(w, h) / 2f
            // Los colores de la esfera de la paleta elegida (Ajustes → Apariencia).
            val orb = auraColors(ThemeSettings.state.value.palette, dark = true).orb
            fun argb(c: androidx.compose.ui.graphics.Color, alpha: Float = 1f) = c.copy(alpha = alpha).toArgb()
            body.shader = RadialGradient(
                w / 2f - r * 0.3f, h / 2f - r * 0.4f, r * 1.6f,
                intArrayOf(argb(orb.light), argb(orb.mid), argb(orb.deep)),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            val (a, b, c) = Triple(orb.lobes[1], orb.lobes[2], orb.lobes[3])
            swirl.shader = SweepGradient(
                w / 2f, h / 2f,
                intArrayOf(
                    argb(a, 0f), argb(a, 0.8f), argb(b, 0f), argb(b, 0.8f), argb(c, 0f), argb(c, 0.8f), argb(a, 0f),
                ),
                null,
            )
            shine.shader = RadialGradient(
                w / 2f - r * 0.35f, h / 2f - r * 0.42f, r * 0.55f,
                intArrayOf(Color.parseColor("#E6FFFFFF"), Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP,
            )
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val level = AssistantState.level.value
            val pulse = 0.5f + 0.5f * sin(time * 2.6f)
            val r = minOf(width, height) / 2f * (0.86f + 0.04f * pulse + 0.1f * level)
            canvas.drawCircle(cx, cy, r, body)
            rotation.setRotate(angle, cx, cy)
            swirl.shader?.setLocalMatrix(rotation)
            canvas.drawCircle(cx, cy, r * 0.92f, swirl)
            canvas.drawCircle(cx, cy, r, shine)
        }
    }

    companion object {
        @Volatile
        private var shown: java.lang.ref.WeakReference<ListeningOverlay>? = null

        /** Saca la burbuja de la pantalla (para que no salga en una captura). */
        fun hideAll() {
            val overlay = shown?.get() ?: return
            android.os.Handler(android.os.Looper.getMainLooper()).post { overlay.hide() }
        }
    }
}
