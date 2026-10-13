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
- ✅ **Agente que usa tus apps**: resuelve pedidos en varios pasos (`brain/CommandProcessor`). Abre apps directo en búsquedas o secciones (Mercado Libre ordenado por precio, Instagram Reels…), lee y maneja la pantalla de cualquier app con el control de apps (accesibilidad), y lee, responde y manda mensajes (WhatsApp, SMS) a contactos o apodos. Antes de enviar, comprar o pagar siempre pide confirmación por voz. Ver [docs/AGENT.md](docs/AGENT.md).
- ✅ **Modo conversación**: después de responder sigue escuchando unos segundos (configurable) para el próximo pedido, sin repetir el nombre; "gracias" o "listo" la cierran.
- ✅ **Groq como proveedor**: `openai/gpt-oss-120b` recomendado; el agente se adapta al plan gratis (pedidos livianos, espera y reintenta solo si Groq pide esperar).
- ✅ **Control de que hizo todo**: si el modelo dice haber hecho algo sin hacerlo, o hace una parte de un pedido de varias cosas, se le muestra lo hecho de verdad y completa lo que falta; después de confirmar un mensaje sigue con el resto del pedido.
- ✅ **Manejar el teléfono solo con la voz** (como el Acceso por voz de Google, sin pasar por el modelo): "atrás", "inicio", "bajá", "mostrá los números" + "tocá 5", "tocá Guardar", "escribí…", volumen, música y videos, linterna, Wi-Fi, Bluetooth, brillo, "abrí Instagram". Ver [docs/VOICE_CONTROL.md](docs/VOICE_CONTROL.md).
- ✅ **Cámara por voz**: abrir, foto, selfie, empezar y terminar un video ("terminá el video" se escucha sin internet mientras graba), cambiar de cámara.
- ✅ **Audios de WhatsApp**: graba tu voz hasta que te callás, o lo dice la voz del asistente; confirma y lo manda (también a grupos).
- ✅ **Llamadas**: "llamá a mamá", "atendé", "colgá".
- ✅ **Pausa con las manos** (opcional): mirando un video, levantá las dos manos y pone pausa y te pregunta qué necesitás (cámara frontal + MediaPipe, todo en el teléfono).
- ✅ **Frenarlo por voz**: "pará", "basta" o "cancelá" mientras trabaja o habla (o su nombre para interrumpirlo); también tocando la píldora o "Frenar" en la notificación. Se entera de que lo frenaron y de qué alcanzó a hacer.
- ✅ **Modelos recomendados** al elegir proveedor y modelo, con una nota de para qué sirve cada uno (gratis: Groq gpt-oss-120b o Gemini 3.8 Flash; pago y eficiente: Claude Haiku 5.5 o GPT-6 Luna).
- ✅ **WhatsApp por nombre**: abre chats y grupos buscándolos en WhatsApp, aunque el reconocimiento de voz entienda mal el nombre ("fulgo 2" → «Fútbol 2 ⚽»), y confirma con el nombre real antes de enviar.
- ⏳ Próximo: llamadas, recordatorios, activación con botón home (rol de asistente).

## Compilar

Requiere Android Studio (o SDK de Android 35 + JDK 17):

```bash
./gradlew assembleDebug   # o abrir el proyecto en Android Studio
```

El APK queda en `app/build/outputs/apk/debug/`. Instalar en un **dispositivo real** (el emulador no sirve para probar micrófono continuo ni Doze).

El modelo de voz en español de Vosk (39 MB) no viaja en el APK: la app lo descarga la primera vez. Para incluirlo igual, compilá con `-Pcontrolxia.bundleVoskModel=true` (ver [docs/WAKE_WORD.md](docs/WAKE_WORD.md)).

## Probar la confiabilidad (en dispositivo real)

1. Abrir la app → conceder micrófono y notificaciones → configurar batería "sin restricción" → encender el servicio.
2. Bloquear el teléfono 1+ hora → tocar **"Probar voz"** en la notificación → debe decir la hora sin desbloquear.
3. Reiniciar el teléfono → la notificación "Xia está escuchando" debe volver sola.
4. En Xiaomi/Samsung/etc.: repetir el paso 2 con y sin los ajustes del fabricante que indica la app.

## Configurar el LLM

En la app: ⚙️ → tocar uno de los **Recomendados** (o elegir proveedor y modelo) → pegar API key → **Probar conexión**. Qué modelo conviene y por qué: [docs/AGENT.md](docs/AGENT.md#qué-modelo-elegir).

| Proveedor | Dónde sacar la key |
|---|---|
| Anthropic | https://console.anthropic.com |
| OpenAI | https://platform.openai.com |
| Google Gemini | https://aistudio.google.com |
| Groq | https://console.groq.com (elegí el proveedor «Groq», sin base URL) |
| OpenRouter / Ollama | Su consola, con la base URL del servicio |

## Configurar la palabra de activación

La palabra de activación es el **nombre que le pongas** (por defecto "Xia") y se reconoce en el teléfono con Vosk.

1. Tocá **Descargar modelo de voz (39 MB)** (en el onboarding o en el panel). Es una sola vez.
2. Poné el nombre: debajo te confirma si lo va a reconocer.
3. Encendé la escucha, decí el nombre → sentís la vibración y "Te escucho" → decí el pedido.

## Estructura

```
app/src/main/java/com/controlxia/app/
├── service/       # WakeWordService (orquesta wake word → ASR → cerebro → TTS), BootReceiver, Watchdog
├── permissions/   # PermissionManager (fabricante + overlay para abrir apps bloqueado)
├── brain/         # Multi-LLM + CommandProcessor, ToolRegistry, ConversationMemory
├── actions/       # AppLauncher, AlarmActions, AppDeepLinks (búsquedas/secciones), ScreenControl (accesibilidad), MessageStore/MessageSender/Contacts (mensajería)
├── voice/         # WakeWordEngine (Vosk + WakePhraseDetector + VoskModelStore), SpeechToText, ListeningOverlay, VoiceSettings, settings, RecentCommandsStore
├── feedback/      # FeedbackStore (historial local analizado por el LLM)
└── ui/            # Onboarding, Dashboard, LlmSettings, Feedback, WakeWordSettings, VoiceSettings
    ├── theme/     # XiaTheme (papel/tinta/acento, tipografía mono) + Motion (expo.out)
    └── components/# TechButton, TechPanel, StatusRow, hairlines…
```
