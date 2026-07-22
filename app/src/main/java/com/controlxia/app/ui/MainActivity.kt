package com.controlxia.app.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.controlxia.app.permissions.PermissionManager
import com.controlxia.app.service.ServicePrefs
import com.controlxia.app.service.WakeWordService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var screen by remember { mutableStateOf(Screen.HOME) }
                when (screen) {
                    Screen.HOME -> HomeScreen(onOpenSettings = { screen = Screen.LLM_SETTINGS })
                    Screen.LLM_SETTINGS -> LlmSettingsScreen(onBack = { screen = Screen.HOME })
                }
            }
        }
    }

    private enum class Screen { HOME, LLM_SETTINGS }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Se re-evalúa cada vez que la pantalla vuelve al frente (ON_RESUME),
    // para reflejar permisos otorgados en Ajustes del sistema.
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh++ }

    var serviceOn by remember(refresh) {
        mutableStateOf(ServicePrefs.isEnabled(context) || WakeWordService.running)
    }

    val micOk = remember(refresh) { PermissionManager.hasRecordAudio(context) }
    val notifOk = remember(refresh) { PermissionManager.hasPostNotifications(context) }
    val batteryOk = remember(refresh) { PermissionManager.isIgnoringBatteryOptimizations(context) }
    val oemNeeded = remember { PermissionManager.needsOemSetup() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ControlXIA") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Ajustes de LLM")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Servicio de escucha", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (serviceOn) "Xia está escuchando" else "Apagado",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Switch(
                        checked = serviceOn,
                        onCheckedChange = { on ->
                            serviceOn = on
                            if (on) WakeWordService.start(context) else WakeWordService.stop(context)
                        },
                        enabled = micOk && notifOk
                    )
                }
            }

            Text(
                "Para que Xia responda incluso con el teléfono bloqueado, " +
                    "necesita estos permisos:",
                style = MaterialTheme.typography.bodyMedium
            )

            ChecklistItem(
                title = "Micrófono",
                subtitle = "Para escuchar la palabra de activación",
                ok = micOk,
                actionLabel = "Permitir",
                onAction = {
                    permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                }
            )
            ChecklistItem(
                title = "Notificaciones",
                subtitle = "La notificación fija mantiene vivo el servicio",
                ok = notifOk,
                actionLabel = "Permitir",
                onAction = {
                    permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
            )
            ChecklistItem(
                title = "Batería sin restricción",
                subtitle = "Sin esto, el sistema apaga la escucha tras ~1 hora bloqueado",
                ok = batteryOk,
                actionLabel = "Configurar",
                onAction = {
                    runCatching {
                        context.startActivity(PermissionManager.batteryExemptionIntent(context))
                    }.onFailure {
                        context.startActivity(PermissionManager.appDetailsIntent(context))
                    }
                }
            )
            if (oemNeeded) {
                ChecklistItem(
                    title = "Ajustes de ${PermissionManager.oemName()}",
                    subtitle = "Tu fabricante tiene su propio ahorro de energía: " +
                        "activá el inicio automático para ControlXIA",
                    ok = null, // no se puede verificar por API
                    actionLabel = "Abrir",
                    onAction = {
                        val intent = PermissionManager.oemSettingsIntent(context)
                            ?: PermissionManager.appDetailsIntent(context)
                        runCatching { context.startActivity(intent) }.onFailure {
                            context.startActivity(PermissionManager.appDetailsIntent(context))
                        }
                    }
                )
            }

            Text(
                "Prueba: encendé el servicio, bloqueá el teléfono y tocá " +
                    "\"Probar voz\" en la notificación — Xia dice la hora sin desbloquear.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ChecklistItem(
    title: String,
    subtitle: String,
    ok: Boolean?,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (ok) {
                true -> Icon(
                    Icons.Default.CheckCircle, contentDescription = "Concedido",
                    tint = Color(0xFF2E7D32)
                )
                false -> Icon(
                    Icons.Default.Error, contentDescription = "Pendiente",
                    tint = MaterialTheme.colorScheme.error
                )
                null -> Icon(
                    Icons.Default.Error, contentDescription = "Verificar manualmente",
                    tint = Color(0xFFF9A825)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            if (ok != true) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
