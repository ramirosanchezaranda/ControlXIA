package com.controlxia.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.controlxia.app.permissions.PermissionManager
import com.controlxia.app.service.HandsWatchService
import com.controlxia.app.service.ServicePrefs
import com.controlxia.app.service.WakeWordService
import com.controlxia.app.ui.theme.ThemeSettings
import com.controlxia.app.ui.theme.XiaTheme
import com.controlxia.app.voice.GestureModel
import com.controlxia.app.voice.WakeWordSettings

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // De borde a borde: el fondo de luces pasa por detrás de las barras del sistema.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // Los íconos de la barra de estado, oscuros o claros según el tema elegido (no el del teléfono).
            val choice by ThemeSettings.state.collectAsState()
            val dark = choice.mode.resolve(isSystemInDarkTheme())
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            XiaTheme {
                var onboarded by remember {
                    mutableStateOf(ServicePrefs.isOnboarded(this@MainActivity))
                }
                AuraApp(
                    onboarded = onboarded,
                    onOnboarded = {
                        ServicePrefs.setOnboarded(this@MainActivity, true)
                        onboarded = true
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Con la app a la vista, la esfera reemplaza a la burbuja flotante.
        WakeWordService.appVisible = true
    }

    override fun onStop() {
        WakeWordService.appVisible = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Si la escucha quedó encendida pero Android la frenó (reinicio en
        // Android 15, arranque bloqueado desde segundo plano, proceso matado),
        // se relanza ahora que la app está en primer plano y está permitido.
        if (ServicePrefs.isOnboarded(this) &&
            ServicePrefs.isEnabled(this) &&
            !WakeWordService.running &&
            PermissionManager.hasRecordAudio(this)
        ) {
            WakeWordService.tryStart(this)
        }
        // "Pausa con las manos" solo puede prender la cámara si arranca con la app a la vista.
        if (WakeWordSettings(this).handsPause && !HandsWatchService.running &&
            PermissionManager.hasCamera(this) && GestureModel.isInstalled(this)
        ) {
            HandsWatchService.start(this)
        }
    }

}
