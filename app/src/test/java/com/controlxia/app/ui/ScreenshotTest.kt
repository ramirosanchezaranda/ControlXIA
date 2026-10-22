package com.controlxia.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.controlxia.app.service.ServicePrefs
import com.controlxia.app.ui.theme.XiaTheme
import com.controlxia.app.voice.AssistantState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * Capturas de las pantallas para revisar el diseño sin un teléfono: vertical,
 * horizontal y tablet, en claro y oscuro. Quedan en app/build/screenshots.
 * No compara nada: es para mirar.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {

    @Test
    @Config(qualifiers = PHONE)
    fun phoneLight() = shootTabs("phone-light", dark = false)

    @Test
    @Config(qualifiers = PHONE_NIGHT)
    fun phoneDark() = shootTabs("phone-dark", dark = true)

    @Test
    @Config(qualifiers = SMALL)
    fun smallPhone() = shoot("small-home", dark = false) { AuraApp(onboarded = true, onOnboarded = {}) }

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun landscape() = shoot("landscape-home", dark = true) { AuraApp(onboarded = true, onOnboarded = {}) }

    @Test
    @Config(qualifiers = TABLET)
    fun tablet() {
        shoot("tablet-home", dark = false) { AuraApp(onboarded = true, onOnboarded = {}) }
        shoot("tablet-explore", dark = false) { AuraApp(onboarded = true, onOnboarded = {}, initialTab = Tab.EXPLORE) }
    }

    @Test
    @Config(qualifiers = PHONE)
    fun onboarding() = shoot("phone-onboarding", dark = false) { AuraApp(onboarded = false, onOnboarded = {}) }

    @Test
    @Config(qualifiers = PHONE_NIGHT)
    fun listening() {
        AssistantState.begin("Beto", locked = true)
        AssistantState.setLevel(0.7f)
        AssistantState.update(AssistantState.Phase.LISTENING, "Te escucho…")
        stillFrames(org.robolectric.RuntimeEnvironment.getApplication())
        val activity = Robolectric.buildActivity(AssistantActivity::class.java).setup().get()
        settle()
        save("phone-lockscreen", capture(activity))
        AssistantState.idle()
    }

    @Test
    @Config(qualifiers = PHONE_NIGHT)
    fun alarm() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        stillFrames(app)
        com.controlxia.app.actions.OwnAlarms.ring(app, 1)
        val activity = Robolectric.buildActivity(AlarmRingActivity::class.java).setup().get()
        settle()
        save("phone-alarm", capture(activity))
        com.controlxia.app.actions.OwnAlarms.stop(app)
    }

    @Test
    @Config(qualifiers = PHONE)
    fun palettes() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        listOf(com.controlxia.app.ui.theme.Palette.STUDIO, com.controlxia.app.ui.theme.Palette.TUSCAN).forEach { palette ->
            com.controlxia.app.ui.theme.ThemeSettings.setPalette(app, palette)
            val name = palette.name.lowercase()
            shoot("palette-$name-light-home", dark = false) { AuraApp(onboarded = true, onOnboarded = {}) }
            shoot("palette-$name-dark-home", dark = true) { AuraApp(onboarded = true, onOnboarded = {}) }
            shoot("palette-$name-light-settings", dark = false) { AuraApp(onboarded = true, onOnboarded = {}, initialTab = Tab.SETTINGS) }
        }
        com.controlxia.app.ui.theme.ThemeSettings.setPalette(app, com.controlxia.app.ui.theme.Palette.AURA)
    }

    /** Ajustes de la escucha enteros (pantalla alta), con la sección de batería. */
    @Test
    @Config(qualifiers = TALL)
    fun listeningSettings() {
        // Un sensor de proximidad de mentira, para ver los saludos como en un teléfono.
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val sensors = app.getSystemService(android.hardware.SensorManager::class.java)!!
        shadowOf(sensors).addSensor(org.robolectric.shadows.ShadowSensor.newInstance(android.hardware.Sensor.TYPE_PROXIMITY))
        // Con "Mi voz" elegido, para ver la inscripción.
        com.controlxia.app.voice.WakeWordSettings(app).lockedAccess = com.controlxia.app.voice.LockedAccess.VOICE
        shoot("listening-settings", dark = false) { WakeWordSettingsScreen(onBack = {}) }
    }

    private fun shootTabs(prefix: String, dark: Boolean) {
        Tab.entries.forEach { tab ->
            shoot("$prefix-${tab.name.lowercase()}", dark) {
                AuraApp(onboarded = true, onOnboarded = {}, initialTab = tab)
            }
        }
    }

    private fun shoot(name: String, dark: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        stillFrames(activity)
        ServicePrefs.setOnboarded(activity, true)
        activity.setContent { XiaTheme(forceDark = dark) { content() } }
        settle()
        save(name, capture(activity))
        controller.pause().stop().destroy()
    }

    /**
     * Animaciones del sistema apagadas: la esfera y el fondo quedan quietos
     * (las animaciones infinitas no terminan nunca en Robolectric).
     */
    private fun stillFrames(context: android.content.Context) {
        android.provider.Settings.Global.putFloat(
            context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f,
        )
    }

    private fun settle() {
        repeat(30) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
    }

    private fun capture(activity: ComponentActivity): Bitmap {
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        var copied = false
        runCatching {
            PixelCopy.request(activity.window, bitmap, { result -> copied = result == PixelCopy.SUCCESS }, Handler(Looper.getMainLooper()))
            shadowOf(Looper.getMainLooper()).idle()
        }
        if (!copied) view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun save(name: String, bitmap: Bitmap) {
        val dir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val PHONE = "w393dp-h852dp-port-xxhdpi"
        const val PHONE_NIGHT = "w393dp-h852dp-port-night-xxhdpi"
        const val SMALL = "w320dp-h640dp-port-hdpi"
        const val LANDSCAPE = "w852dp-h393dp-land-xxhdpi"
        const val TABLET = "w1024dp-h768dp-land-mdpi"
        const val TALL = "w393dp-h3000dp-port-hdpi"
    }
}
