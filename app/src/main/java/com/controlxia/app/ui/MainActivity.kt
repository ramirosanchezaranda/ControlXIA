package com.controlxia.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.controlxia.app.service.ServicePrefs
import com.controlxia.app.ui.theme.XiaTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XiaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    var onboarded by remember {
                        mutableStateOf(ServicePrefs.isOnboarded(this@MainActivity))
                    }
                    var screen by remember { mutableStateOf(Screen.DASHBOARD) }

                    if (!onboarded) {
                        OnboardingFlow(
                            onFinished = {
                                ServicePrefs.setOnboarded(this@MainActivity, true)
                                onboarded = true
                            }
                        )
                    } else {
                        when (screen) {
                            Screen.DASHBOARD -> DashboardScreen(
                                onOpenLlm = { screen = Screen.LLM_SETTINGS },
                                onOpenFeedback = { screen = Screen.FEEDBACK },
                            )
                            Screen.LLM_SETTINGS -> LlmSettingsScreen(
                                onBack = { screen = Screen.DASHBOARD }
                            )
                            Screen.FEEDBACK -> FeedbackScreen(
                                onBack = { screen = Screen.DASHBOARD }
                            )
                        }
                    }
                }
            }
        }
    }

    private enum class Screen { DASHBOARD, LLM_SETTINGS, FEEDBACK }
}
