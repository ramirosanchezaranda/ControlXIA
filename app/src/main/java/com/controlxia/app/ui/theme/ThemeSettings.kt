package com.controlxia.app.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Claro, oscuro o lo que diga el teléfono. */
enum class ThemeMode(val label: String) {
    SYSTEM("Automático"),
    LIGHT("Claro"),
    DARK("Oscuro");

    fun resolve(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}

/** Las paletas de colores para elegir. */
enum class Palette(val label: String, val description: String) {
    AURA("Aura", "Azul de iOS, violeta y rosa"),
    STUDIO("Studio", "Canvas, Mist, Cornflower y Pear"),
    TUSCAN("Toscana", "Tuscan Red, Pale Cerulean, Noir y Satin Linen"),
}

/**
 * La apariencia que eligió el usuario (modo y paleta). Se lee al arrancar la
 * app y cambia en vivo: todas las pantallas la observan.
 */
object ThemeSettings {

    data class Choice(val mode: ThemeMode = ThemeMode.SYSTEM, val palette: Palette = Palette.AURA)

    private val _state = MutableStateFlow(Choice())
    val state: StateFlow<Choice> = _state.asStateFlow()

    fun init(context: Context) {
        val prefs = prefs(context)
        _state.value = Choice(
            mode = runCatching { ThemeMode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }.getOrDefault(ThemeMode.SYSTEM),
            palette = runCatching { Palette.valueOf(prefs.getString(KEY_PALETTE, null) ?: "") }.getOrDefault(Palette.AURA),
        )
    }

    fun setMode(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
        _state.value = _state.value.copy(mode = mode)
    }

    fun setPalette(context: Context, palette: Palette) {
        prefs(context).edit().putString(KEY_PALETTE, palette.name).apply()
        _state.value = _state.value.copy(palette = palette)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    private const val KEY_MODE = "mode"
    private const val KEY_PALETTE = "palette"
}
