package com.controlxia.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.controlxia.app.ui.components.AuraBackground
import com.controlxia.app.ui.components.GlassTabBar
import com.controlxia.app.ui.components.GlassTabRail
import com.controlxia.app.ui.components.LocalHazeState
import com.controlxia.app.ui.components.TabItem
import com.controlxia.app.ui.theme.AuraMotion
import com.controlxia.app.ui.theme.pushTransition
import com.controlxia.app.ui.theme.tabTransition
import com.controlxia.app.voice.AssistantState
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze

/** Las pestañas de la app. */
enum class Tab(val item: TabItem) {
    HOME(TabItem("Inicio", Icons.Rounded.Home)),
    EXPLORE(TabItem("Explorar", Icons.Rounded.AutoAwesome)),
    ACTIVITY(TabItem("Actividad", Icons.Rounded.History)),
    SETTINGS(TabItem("Ajustes", Icons.Rounded.Settings)),
}

/** Pantallas internas, que entran desde la derecha encima de las pestañas. */
enum class Route { LLM, VOICE, WAKEWORD, FEEDBACK, PERMISSIONS, DEVICES }

/** Ancho a partir del cual las pestañas van al costado (tablet o teléfono horizontal). */
private val WIDE = 720.dp

/** Ancho máximo del contenido: en pantallas grandes no se estira de lado a lado. */
val MAX_CONTENT = 720.dp

/**
 * La app entera: fondo de luces, pestañas de vidrio (abajo en el teléfono, al
 * costado en tablet u horizontal) y las pantallas internas con el "push" de
 * iOS. El fondo se desenfoca detrás de las barras.
 */
@Composable
fun AuraApp(onboarded: Boolean, onOnboarded: () -> Unit, initialTab: Tab = Tab.HOME) {
    val hazeState = remember { HazeState() }
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var route by rememberSaveable { mutableStateOf<Route?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    val assistant by AssistantState.state.collectAsState()
    val energy by animateFloatAsState(
        if (assistant.phase == AssistantState.Phase.IDLE) 0f else 1f,
        AuraMotion.gentle(),
        label = "bg-energy",
    )

    BackHandler(enabled = onboarded && route != null) { route = null }
    BackHandler(enabled = onboarded && route == null && tab != Tab.HOME) { tab = Tab.HOME }

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= WIDE
            val insets = WindowInsets.safeDrawing.asPaddingValues()
            val direction = LocalLayoutDirection.current
            // Lugar para la barra de pestañas, para que el contenido no quede debajo.
            val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val tabPadding = if (wide) {
                PaddingValues(
                    start = insets.calculateStartPadding(direction) + 84.dp + 28.dp,
                    end = insets.calculateEndPadding(direction) + 16.dp,
                    top = insets.calculateTopPadding() + 12.dp,
                    bottom = insets.calculateBottomPadding() + 24.dp,
                )
            } else {
                PaddingValues(
                    start = insets.calculateStartPadding(direction) + 16.dp,
                    end = insets.calculateEndPadding(direction) + 16.dp,
                    top = insets.calculateTopPadding() + 12.dp,
                    bottom = navBottom + 64.dp + 36.dp,
                )
            }

            Box(Modifier.fillMaxSize().haze(hazeState)) {
                AuraBackground(energy = energy)
                if (!onboarded) {
                    ScreenFrame(maxWidth = 600.dp) { OnboardingFlow(onFinished = onOnboarded) }
                } else {
                    AnimatedContent(
                        targetState = route,
                        transitionSpec = { pushTransition<Route?>(forward = targetState != null)(this) },
                        label = "route",
                    ) { current ->
                        when (current) {
                            null -> AnimatedContent(targetState = tab, transitionSpec = tabTransition(), label = "tab") { t ->
                                val open: (Route) -> Unit = { route = it }
                                when (t) {
                                    Tab.HOME -> HomeScreen(
                                        contentPadding = tabPadding,
                                        wide = wide,
                                        draft = draft,
                                        onDraftChange = { draft = it },
                                        onOpen = open,
                                    )
                                    Tab.EXPLORE -> ExploreScreen(
                                        contentPadding = tabPadding,
                                        onTry = { phrase ->
                                            draft = phrase
                                            tab = Tab.HOME
                                        },
                                    )
                                    Tab.ACTIVITY -> ActivityScreen(contentPadding = tabPadding)
                                    Tab.SETTINGS -> SettingsScreen(
                                        contentPadding = tabPadding,
                                        onOpen = open,
                                        onExplore = { tab = Tab.EXPLORE },
                                    )
                                }
                            }
                            Route.LLM -> ScreenFrame { LlmSettingsScreen(onBack = { route = null }) }
                            Route.FEEDBACK -> ScreenFrame { FeedbackScreen(onBack = { route = null }) }
                            Route.WAKEWORD -> ScreenFrame { WakeWordSettingsScreen(onBack = { route = null }) }
                            Route.VOICE -> ScreenFrame { VoiceSettingsScreen(onBack = { route = null }) }
                            Route.PERMISSIONS -> ScreenFrame { PermissionsScreen(onBack = { route = null }) }
                            Route.DEVICES -> ScreenFrame { DevicesScreen(onBack = { route = null }) }
                        }
                    }
                }
            }

            // Pestañas de vidrio: se esconden en las pantallas internas.
            val items = Tab.entries.map { it.item }
            if (wide) {
                AnimatedVisibility(
                    visible = onboarded && route == null,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
                        .padding(start = 16.dp),
                    enter = slideInHorizontally(AuraMotion.smooth()) { -it } + fadeIn(),
                    exit = slideOutHorizontally(AuraMotion.smooth()) { -it } + fadeOut(),
                ) {
                    GlassTabRail(items, tab.ordinal, onSelect = { tab = Tab.entries[it] })
                }
            } else {
                AnimatedVisibility(
                    visible = onboarded && route == null,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                        .padding(horizontal = 20.dp)
                        .padding(bottom = bottomGap(navBottom)),
                    enter = slideInVertically(AuraMotion.smooth()) { it * 2 } + fadeIn(),
                    exit = slideOutVertically(AuraMotion.smooth()) { it * 2 } + fadeOut(),
                ) {
                    GlassTabBar(items, tab.ordinal, onSelect = { tab = Tab.entries[it] })
                }
            }
        }
    }
}

/** Con navegación por gestos la barra queda pegada al borde: un poco de aire. */
@Composable
private fun bottomGap(navBottom: Dp): Dp = if (navBottom < 24.dp) 14.dp else 6.dp

/**
 * Marco de una pantalla: respeta la barra de estado, el recorte de la cámara,
 * la de navegación y el teclado, y no se estira más que [maxWidth] (queda
 * centrada en tablets).
 */
@Composable
fun ScreenFrame(maxWidth: Dp = MAX_CONTENT, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.widthIn(max = maxWidth).fillMaxSize()) { content() }
    }
}
