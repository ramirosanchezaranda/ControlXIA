# ControlXIA

Asistente de voz para Android que controla el teléfono con lenguaje natural ("Xia, escribile a mi pareja…"), potenciado por el LLM que elijas. El plan completo del proyecto está en [PLAN.md](PLAN.md).

## Estado actual (Iteración 1)

- ✅ **Servicio de escucha persistente** (Foreground Service tipo micrófono) que sobrevive pantalla bloqueada, Doze y reinicios: `START_STICKY` + watchdog con AlarmManager + `BootReceiver`.
- ✅ **Checklist de permisos** en la app: micrófono, notificaciones, exención de batería y ajustes específicos del fabricante (Xiaomi, Samsung, Huawei, Oppo, Vivo, OnePlus).
- ✅ **Capa multi-LLM configurable**: Anthropic (Claude), OpenAI (GPT), Google (Gemini) y cualquier endpoint OpenAI-compatible (Groq, OpenRouter, Ollama local). API keys cifradas en el dispositivo, modelo elegible o escrito a mano, botón "Probar conexión".
- ✅ **Prueba de vida con pantalla bloqueada**: botón "Probar voz" en la notificación persistente → dice la hora por TTS sin desbloquear.
- ✅ **UI minimalista editorial**: onboarding guiado que pide los permisos de a uno con contexto, dashboard con estado en vivo del sistema, y tema propio (papel/tinta/acento azul, etiquetas monospace).
- ✅ **Transiciones estilo GSAP**: curva `expo.out` centralizada en `ui/theme/Motion.kt`; cambios de pantalla/paso con `AnimatedContent` (fade + rise) y reveal escalonado de las secciones del dashboard (`Modifier.staggerReveal`).
- ✅ **Feedback con el propio LLM**: pantalla donde el usuario cuenta qué falló o qué quiere; el LLM configurado lo analiza, responde y lo clasifica ([BUG]/[IDEA]/[MEJORA]); historial local como insumo del roadmap.
- ✅ **Wake word por nombre, 100 % open source**: detección on-device con **Vosk** y su modelo en español (`voice/VoskWakeWordEngine` + `voice/WakePhraseDetector`), sin cuentas, sin claves y sin internet. Se dice el nombre del asistente ("Xia") y responde; doble verificación para no activarse con palabras como "fantasía". Al detectar: vibración + TTS "Te escucho". Funciona con pantalla bloqueada. Detalles en [docs/WAKE_WORD.md](docs/WAKE_WORD.md).
- ✅ **Cadena de voz completa → acciones reales**: transcripción → `brain/CommandProcessor` (LLM configurado + catálogo de tools) → ejecuta la acción → responde en voz alta. Tools que ya andan: `get_time`, `get_date`, `open_app`, `set_alarm`, `set_timer`. Memoria conversacional corta. "Actividad reciente" muestra el comando y la respuesta.
- ✅ **Voz configurable**: pantalla para elegir la voz del sistema (con género inferido), tono y velocidad, con presets Grave/Neutra/Aguda y "Probar" (`voice/VoiceSettings` + `ui/VoiceSettingsScreen`). El asistente usa esa voz para todo lo que dice.
- ✅ **Nombre del asistente configurable**: se le puede cambiar el nombre (cómo se llama a sí mismo, la notificación y el overlay). El nombre es la palabra de activación; si no lo reconoce, se puede escribir cómo suena.
- ✅ **Overlay flotante de "escuchando"**: al activarse, muestra un indicador **sobre cualquier app** (estilo Google Assistant), con el nombre del asistente y lo que va entendiendo (`voice/ListeningOverlay`). Requiere el permiso "Mostrar sobre otras apps".
- ✅ **Respuesta sobre el bloqueo (modo Alexa)**: con el teléfono bloqueado o la pantalla apagada, al decir la palabra se **prende la pantalla y responde sobre el candado** sin desbloquear (`ui/AssistantActivity` con show-when-locked/turn-screen-on, estado en vivo vía `voice/AssistantState`). No usa el botón home: la escucha es siempre activa. Toggle de **privacidad** para mostrar u ocultar el contenido en pantalla estando bloqueado. Abrir apps de terceros bloqueado avisa "desbloqueá para abrir X" (límite de seguridad de Android).
- ✅ **Build release**: `signingConfig` (keystore por `keystore.properties`/env) + minify/shrink con reglas ProGuard (`app/proguard-rules.pro`).
- ⏳ Próximo: tools de mensajería (SMS/WhatsApp/llamadas) con sus permisos; activación con botón home (rol de asistente).

## Compilar

Requiere Android Studio (o SDK de Android 35 + JDK 17):

```bash
./gradlew assembleDebug   # o abrir el proyecto en Android Studio
```

El APK queda en `app/build/outputs/apk/debug/`. Instalar en un **dispositivo real** (el emulador no sirve para probar micrófono continuo ni Doze).

La primera compilación descarga el modelo de voz en español de Vosk (`vosk-model-small-es-0.42`, 39 MB) desde alphacephei.com y lo guarda en `.gradle/vosk/`. Para compilar sin internet, copiá ese zip ahí a mano. El modelo y las librerías nativas suman ~60 MB al APK.

## Probar la confiabilidad (en dispositivo real)

1. Abrir la app → conceder micrófono y notificaciones → configurar batería "sin restricción" → encender el servicio.
2. Bloquear el teléfono 1+ hora → tocar **"Probar voz"** en la notificación → debe decir la hora sin desbloquear.
3. Reiniciar el teléfono → la notificación "Xia está escuchando" debe volver sola.
4. En Xiaomi/Samsung/etc.: repetir el paso 2 con y sin los ajustes del fabricante que indica la app.

## Configurar el LLM

En la app: ⚙️ → elegir proveedor → pegar API key → elegir modelo → **Probar conexión**.

| Proveedor | Dónde sacar la key |
|---|---|
| Anthropic | https://console.anthropic.com |
| OpenAI | https://platform.openai.com |
| Google Gemini | https://aistudio.google.com |
| Groq / OpenRouter / Ollama | Su consola, con la base URL del servicio |

## Configurar la palabra de activación

No hace falta configurar nada: la palabra de activación es el **nombre del asistente** (por defecto "Xia") y se reconoce en el teléfono con Vosk.

1. Encendé la escucha en el panel. La primera vez prepara el modelo de voz (unos segundos; el panel muestra "Preparando el reconocimiento de voz…").
2. Decí el nombre → sentís la vibración y "Te escucho" → decí el pedido.
3. En panel → **Palabra de activación → Ajustar** podés cambiar el nombre, escribir cómo suena si no te reconoce (ej. "sia") y la sensibilidad.

## Estructura

```
app/src/main/java/com/controlxia/app/
├── service/       # WakeWordService (orquesta wake word → ASR → cerebro → TTS), BootReceiver, Watchdog
├── permissions/   # PermissionManager (fabricante + overlay para abrir apps bloqueado)
├── brain/         # Multi-LLM + CommandProcessor, ToolRegistry, ConversationMemory
├── actions/       # AppLauncher (abrir apps por nombre), AlarmActions (alarmas/timers)
├── voice/         # WakeWordEngine (Vosk + WakePhraseDetector + VoskModelStore), SpeechToText, ListeningOverlay, VoiceSettings, settings, RecentCommandsStore
├── feedback/      # FeedbackStore (historial local analizado por el LLM)
└── ui/            # Onboarding, Dashboard, LlmSettings, Feedback, WakeWordSettings, VoiceSettings
    ├── theme/     # XiaTheme (papel/tinta/acento, tipografía mono) + Motion (expo.out)
    └── components/# TechButton, TechPanel, StatusRow, hairlines…
```
