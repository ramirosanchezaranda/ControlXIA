package com.controlxia.app.ui

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.controlxia.app.actions.OwnAlarms
import com.controlxia.app.ui.components.AuraBackground
import com.controlxia.app.ui.components.AuraOrb
import com.controlxia.app.ui.components.OrbMood
import com.controlxia.app.ui.components.glass
import com.controlxia.app.ui.theme.Aura
import com.controlxia.app.ui.theme.XiaTheme
import com.controlxia.app.ui.theme.pressable
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * La alarma sonando, sobre el bloqueo y con la pantalla prendida, como el
 * despertador de iOS: la hora grande, la esfera latiendo y dos botones
 * grandes, Posponer y Detener. Se cierra sola si la alarma se apaga por otro
 * lado (la notificación, la voz).
 */
class AlarmRingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            XiaTheme(forceDark = true) {
                val alarm by OwnAlarms.ringing.collectAsState()
                var seen by remember { mutableStateOf(false) }
                LaunchedEffect(alarm) {
                    if (alarm != null) seen = true
                    // Se apagó (por la notificación o por voz): cerrar.
                    if (alarm == null && seen) finish()
                }
                LaunchedEffect(Unit) {
                    // Abierta sin ninguna sonando (por ejemplo, desde la notificación vieja).
                    delay(1_500)
                    if (OwnAlarms.ringing.value == null) finish()
                }
                AlarmContent(
                    title = when {
                        alarm?.timer == true -> "Terminó el temporizador"
                        alarm?.label?.isNotBlank() == true -> alarm!!.label
                        else -> "Alarma"
                    },
                    onSnooze = {
                        OwnAlarms.snooze(this)
                        finish()
                    },
                    onStop = {
                        OwnAlarms.stop(this)
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun AlarmContent(title: String, onSnooze: () -> Unit, onStop: () -> Unit) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(5_000)
        }
    }
    val colors = Aura.colors
    Box(Modifier.fillMaxSize()) {
        AuraBackground(energy = 0.8f)
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp),
        ) {
            val orb = (minOf(maxWidth * 0.5f, maxHeight * 0.28f)).coerceIn(110.dp, 240.dp)
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        SimpleDateFormat("EEEE d 'de' MMMM", Locale("es")).format(now).replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.secondaryLabel,
                    )
                    Text(
                        SimpleDateFormat("HH:mm", Locale.getDefault()).format(now),
                        style = MaterialTheme.typography.displaySmall.copy(fontSize = 84.sp, lineHeight = 90.sp, fontWeight = FontWeight.SemiBold),
                        color = colors.label,
                    )
                    Text(title, style = MaterialTheme.typography.headlineSmall, color = colors.label, textAlign = TextAlign.Center)
                }
                AuraOrb(Modifier.size(orb), mood = OrbMood.SPEAKING, level = 0.6f)
                Column(
                    Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "También podés decir «apagá la alarma» o «posponela».",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.tertiaryLabel,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BigButton("Posponer", Modifier.weight(1f), filled = false, onClick = onSnooze)
                        BigButton("Detener", Modifier.weight(1f), filled = true, onClick = onStop)
                    }
                }
            }
        }
    }
}

@Composable
private fun BigButton(text: String, modifier: Modifier, filled: Boolean, onClick: () -> Unit) {
    val colors = Aura.colors
    Box(
        modifier
            .heightIn(min = 64.dp)
            .then(if (filled) Modifier.clip(CircleShape).background(colors.highlight) else Modifier.glass(CircleShape, strong = true))
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = if (filled) colors.onHighlight else colors.label)
    }
}
