package com.controlxia.app.ui

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.controlxia.app.ui.theme.Accent
import com.controlxia.app.ui.theme.Muted
import com.controlxia.app.ui.theme.XiaTheme
import com.controlxia.app.voice.AssistantState

/**
 * Pantalla del asistente que se muestra **sobre el bloqueo**: al detectar la
 * palabra de activación con el teléfono bloqueado o la pantalla apagada, el
 * servicio la lanza, ésta prende la pantalla y muestra "escuchando" + la
 * respuesta, sin desbloquear. Se cierra sola al terminar la interacción.
 */
class AssistantActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        setContent {
            XiaTheme {
                val ui by AssistantState.state.collectAsState()

                // Cerrar cuando la interacción vuelve a IDLE.
                LaunchedEffect(ui.phase) {
                    if (ui.phase == AssistantState.Phase.IDLE) finish()
                }

                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { finish() },
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
                ) {
                    AssistantContent(
                        agentName = ui.agentName,
                        status = ui.status,
                        listening = ui.phase == AssistantState.Phase.LISTENING,
                    )
                }
            }
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

@Composable
private fun AssistantContent(agentName: String, status: String, listening: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PulsingDot(active = listening)
        Text(
            agentName.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = Muted,
            modifier = Modifier.padding(top = 28.dp),
        )
        Text(
            status,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun PulsingDot(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = if (active) 0.7f else 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scale",
    )
    Box(
        modifier = Modifier
            .size(20.dp)
            .scale(if (active) scale else 1f)
            .alpha(if (active) scale else 1f)
            .background(Accent, CircleShape),
    )
}
