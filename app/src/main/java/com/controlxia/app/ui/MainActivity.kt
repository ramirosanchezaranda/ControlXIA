package com.controlxia.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
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
import com.controlxia.app.ui.theme.screenTransition

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
                    val target = if (!onboarded) ONBOARDING else screen.name

                    AnimatedContent(
                        targetState = target,
                        transitionSpec = screenTransition(),
                        label = "screens",
                    ) { current ->
                        when (current) {
                            ONBOARDING -> OnboardingFlow(
                                onFinished = {
                                    ServicePrefs.setOnboarded(this@MainActivity, true)
                                    onboarded = true
                                }
                            )
                            Screen.LLM_SETTINGS.name -> LlmSettingsScreen(
                                onBack = { screen = Screen.DASHBOARD }
                            )
                            Screen.FEEDBACK.name -> FeedbackScreen(
                                onBack = { screen = Screen.DASHBOARD }
                            )
                            else -> DashboardScreen(
                                onOpenLlm = { screen = Screen.LLM_SETTINGS },
                                onOpenFeedback = { screen = Screen.FEEDBACK },
                            )
                        }
                    }
                }
            }
        }
    }

    private enum class Screen { DASHBOARD, LLM_SETTINGS, FEEDBACK }

    private companion object {
        const val ONBOARDING = "ONBOARDING"
    }
}
