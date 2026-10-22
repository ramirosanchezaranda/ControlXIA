package com.controlxia.app.voice

import android.content.Context
import android.content.SharedPreferences
import com.controlxia.app.SecurePrefs

/**
 * Configuración del asistente y de su palabra de activación (Vosk). No hace
 * falta ninguna cuenta ni clave: la palabra se reconoce con el modelo de voz
 * en español que viaja dentro de la app.
 */
class WakeWordSettings(context: Context) {

    private val prefs: SharedPreferences by lazy {
        SecurePrefs.get(context, "wakeword_settings")
    }

    /** Cómo se llama el asistente: se nombra así y es la palabra para despertarlo. */
    var agentName: String
        get() = prefs.getString(KEY_AGENT, "Xia").orEmpty().ifBlank { "Xia" }
        set(value) = prefs.edit().putString(KEY_AGENT, value.trim()).apply()

    /**
     * Cómo suena la palabra de activación, si no coincide con cómo se escribe
     * el nombre (ej. nombre "Xia", palabra "sia"). Puede ser una frase corta
     * ("oye xia"). Vacío = el nombre.
     */
    var wakeWord: String
        get() = prefs.getString(KEY_WAKE_WORD, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_WAKE_WORD, value.trim()).apply()

    /**
     * Con el teléfono bloqueado, mostrar en pantalla lo que se escuchó y la
     * respuesta. Si está en false, sobre el bloqueo se muestra un texto genérico
     * (la voz igual responde). Privacidad tipo Alexa.
     */
    var showContentWhenLocked: Boolean
        get() = prefs.getBoolean(KEY_LOCKED_CONTENT, true)
        set(value) = prefs.edit().putBoolean(KEY_LOCKED_CONTENT, value).apply()

    /**
     * Modo conversación: después de responder, sigue escuchando unos segundos
     * para el próximo pedido, sin tener que decir el nombre de nuevo.
     */
    var conversationMode: Boolean
        get() = prefs.getBoolean(KEY_CONVERSATION, true)
        set(value) = prefs.edit().putBoolean(KEY_CONVERSATION, value).apply()

    /** Cuántos segundos espera el próximo pedido en modo conversación. */
    var conversationSeconds: Int
        get() = prefs.getInt(KEY_CONVERSATION_SECONDS, DEFAULT_WAIT_S).coerceIn(MIN_WAIT_S, MAX_WAIT_S)
        set(value) = prefs.edit().putInt(KEY_CONVERSATION_SECONDS, value.coerceIn(MIN_WAIT_S, MAX_WAIT_S)).apply()

    /**
     * Al oír su nombre, contesta "Te escucho" en voz alta. Apagado (lo normal)
     * suena un tono corto y escucha enseguida, así no se pierde el principio
     * de lo que decís.
     */
    var spokenAck: Boolean
        get() = prefs.getBoolean(KEY_SPOKEN_ACK, false)
        set(value) = prefs.edit().putBoolean(KEY_SPOKEN_ACK, value).apply()

    /**
     * "Pausa con las manos": mientras se reproduce algo, la cámara frontal mira
     * si levantás las dos manos para pausar y preguntar qué necesitás.
     */
    var handsPause: Boolean
        get() = prefs.getBoolean(KEY_HANDS, false)
        set(value) = prefs.edit().putBoolean(KEY_HANDS, value).apply()

    /**
     * Señas con una mano mientras mira (dedo arriba/abajo para pasar de reel,
     * pulgar arriba para "me gusta", V para hablarle). Requiere [handsPause].
     */
    var handGestures: Boolean
        get() = prefs.getBoolean(KEY_HAND_GESTURES, true)
        set(value) = prefs.edit().putBoolean(KEY_HAND_GESTURES, value).apply()

    /**
     * Qué se puede hacer con el teléfono bloqueado sin desbloquearlo: leer
     * mensajes y notificaciones, mandar, responder y llamar son privados.
     */
    var lockedAccess: LockedAccess
        get() = runCatching { LockedAccess.valueOf(prefs.getString(KEY_LOCKED_ACCESS, null) ?: "") }
            .getOrDefault(LockedAccess.ASK_UNLOCK)
        set(value) = prefs.edit().putString(KEY_LOCKED_ACCESS, value.name).apply()

    /**
     * Saludar al teléfono (sensor de proximidad, sin cámara): un saludo prende
     * la pantalla, dos te escucha, tapar un segundo frena o pospone.
     */
    var waveGestures: Boolean
        get() = prefs.getBoolean(KEY_WAVES, true)
        set(value) = prefs.edit().putBoolean(KEY_WAVES, value).apply()

    /** Escuchar el nombre también con la pantalla apagada (gasta más batería). */
    var listenScreenOff: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_OFF, true)
        set(value) = prefs.edit().putBoolean(KEY_SCREEN_OFF, value).apply()

    /** Con la pantalla apagada, pausar la escucha si la batería está baja o en modo ahorro. */
    var pauseOnLowBattery: Boolean
        get() = prefs.getBoolean(KEY_LOW_BATTERY, true)
        set(value) = prefs.edit().putBoolean(KEY_LOW_BATTERY, value).apply()

    /** 0f = menos sensible (menos falsos positivos), 1f = más sensible. */
    var sensitivity: Float
        get() = prefs.getFloat(KEY_SENS, 0.5f)
        set(value) = prefs.edit().putFloat(KEY_SENS, value.coerceIn(0f, 1f)).apply()

    /** La palabra (o frase) que hay que decir para despertarlo. */
    fun spokenWakeWord(): String = wakeWord.ifBlank { agentName }

    companion object {
        const val MIN_WAIT_S = 4
        const val MAX_WAIT_S = 60
        const val DEFAULT_WAIT_S = 15

        private const val KEY_SPOKEN_ACK = "spoken_ack"

        private const val KEY_HANDS = "hands_pause"
        private const val KEY_SCREEN_OFF = "listen_screen_off"
        private const val KEY_LOW_BATTERY = "pause_low_battery"
        private const val KEY_HAND_GESTURES = "hand_gestures"
        private const val KEY_WAVES = "wave_gestures"
        private const val KEY_LOCKED_ACCESS = "locked_access"
        private const val KEY_CONVERSATION = "conversation_mode"
        private const val KEY_CONVERSATION_SECONDS = "conversation_seconds"
        private const val KEY_AGENT = "agent_name"
        private const val KEY_WAKE_WORD = "wake_word"
        private const val KEY_SENS = "sensitivity"
        private const val KEY_LOCKED_CONTENT = "locked_content"
    }
}

/** Lo privado (mensajes, notificaciones, llamadas) con el teléfono bloqueado. */
enum class LockedAccess {
    /** No lo hace: hay que desbloquear y volver a pedirlo. */
    BLOCKED,

    /** Pide la huella (o cara o PIN) y sigue solo. */
    ASK_UNLOCK,

    /** Lo hace sin desbloquear (cualquiera que diga el nombre puede). */
    ALLOWED,
}
