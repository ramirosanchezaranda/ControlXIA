package com.controlxia.app.ui

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.controlxia.app.service.WakeWordService
import com.controlxia.app.ui.components.AuraBackground
import com.controlxia.app.ui.components.AuraOrb
import com.controlxia.app.ui.components.GlassIconButton
import com.controlxia.app.ui.components.VoiceWave
import com.controlxia.app.ui.components.mood
import com.controlxia.app.ui.theme.Aura
import com.controlxia.app.ui.theme.AuraMotion
import com.controlxia.app.ui.theme.XiaTheme
import com.controlxia.app.voice.AssistantState

/**
 * Pantalla del asistente que se muestra **sobre el bloqueo**: al detectar la
 * palabra de activación con el teléfono bloqueado o la pantalla apagada, el
 * servicio la lanza, ésta prende la pantalla y muestra la esfera grande (que
 * crece con tu voz), lo que entendió y la respuesta, sin desbloquear. Siempre
 * en oscuro, como Siri. Se cierra sola al terminar.
 */
class AssistantActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        setContent {
            XiaTheme(dark = true) {
                val ui by AssistantState.state.collectAsState()
                val level by AssistantState.level.collectAsState()

                // Cerrar al terminar, o si se desbloqueó para usar una app.
                LaunchedEffect(ui.phase, ui.locked) {
                    if (ui.phase == AssistantState.Phase.IDLE) finish()
                    if (!ui.locked) finish()
                }

                val stop = {
                    WakeWordService.interrupt(this@AssistantActivity)
                    finish()
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = stop),
                ) {
                    val energy by animateFloatAsState(
                        if (ui.phase == AssistantState.Phase.LISTENING) 0.6f + 0.4f * level else 0.4f,
                        AuraMotion.gentle(),
                        label = "energy",
                    )
                    AuraBackground(energy = energy)
                    AssistantContent(
                        agentName = ui.agentName,
                        status = ui.status,
                        phase = ui.phase,
                        level = level,
                    )
                    GlassIconButton(
                        icon = Icons.Rounded.Close,
                        contentDescription = "Frenar",
                        onClick = stop,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(16.dp),
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

/** La esfera y el texto: uno arriba del otro en vertical, lado a lado en horizontal. */
@Composable
private fun AssistantContent(
    agentName: String,
    status: String,
    phase: AssistantState.Phase,
    level: Float,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
    ) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            val orb = (maxHeight * 0.8f).coerceIn(140.dp, 340.dp)
            Row(
                Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
            ) {
                OrbWithWave(orb, phase, level)
                Texts(agentName, status, Modifier.weight(1f, fill = false).widthIn(max = 460.dp), TextAlign.Start)
            }
        } else {
            val orb = (minOf(maxWidth * 0.72f, maxHeight * 0.42f)).coerceIn(160.dp, 360.dp)
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                OrbWithWave(orb, phase, level)
                Texts(agentName, status, Modifier.widthIn(max = 520.dp).padding(top = 12.dp), TextAlign.Center)
            }
        }
    }
}

/** La esfera con la onda de la voz cruzándola, más ancha que la esfera. */
@Composable
private fun OrbWithWave(size: Dp, phase: AssistantState.Phase, level: Float) {
    Box(Modifier.size(width = size * 1.45f, height = size), contentAlignment = Alignment.Center) {
        AuraOrb(Modifier.size(size), mood = phase.mood(), level = level)
        VoiceWave(
            Modifier.fillMaxWidth().height(size * 0.42f),
            level = level,
            active = phase == AssistantState.Phase.LISTENING,
        )
    }
}

@Composable
private fun Texts(agentName: String, status: String, modifier: Modifier, align: TextAlign) {
    val colors = Aura.colors
    Column(modifier, horizontalAlignment = if (align == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start) {
        Text(agentName, style = MaterialTheme.typography.titleMedium, color = colors.secondaryLabel)
        AnimatedContent(
            targetState = status,
            transitionSpec = {
                (fadeIn(AuraMotion.smooth()) + slideInVertically(AuraMotion.smooth()) { it / 6 })
                    .togetherWith(fadeOut(AuraMotion.smooth()))
            },
            label = "status",
        ) { text ->
            Text(
                text,
                style = MaterialTheme.typography.headlineSmall,
                color = colors.label,
                textAlign = align,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Text(
            "Tocá la pantalla o decí «pará» para frenar",
            style = MaterialTheme.typography.bodySmall,
            color = colors.tertiaryLabel,
            textAlign = align,
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}
