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
- ✅ **Wake word real (on-device)**: detección con Picovoice Porcupine (`voice/`), config cifrada (AccessKey + palabra de fábrica + sensibilidad). Al detectar: vibración + TTS "Te escucho" + **transcripción del comando** con `SpeechRecognizer` (es-AR), visible en "Actividad reciente". Funciona con pantalla bloqueada.
- ⏳ Próximo: cablear la transcripción → LLM (tool use) → ejecutar la acción; entrenar el `.ppn` custom de "Xia".

## Compilar

Requiere Android Studio (o SDK de Android 35 + JDK 17):

```bash
./gradlew assembleDebug   # o abrir el proyecto en Android Studio
```

El APK queda en `app/build/outputs/apk/debug/`. Instalar en un **dispositivo real** (el emulador no sirve para probar micrófono continuo ni Doze).

> Nota: la capa `brain/` (proveedores LLM) es JVM pura y está verificada por compilación; el módulo Android completo debe compilarse localmente porque requiere el SDK de Google.

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

En la app: panel → **Palabra de activación**. Necesitás un AccessKey gratuito de Picovoice:

1. Creá una cuenta en https://console.picovoice.ai (gratis para uso personal).
2. Copiá el **AccessKey** y pegalo en la app (se guarda cifrado).
3. Elegí una palabra de fábrica (Jarvis / Computer / Bumblebee / Picovoice) y la sensibilidad.
4. Encendé la escucha en el panel.

Probá (mejor con pantalla bloqueada): decí la palabra → sentís la vibración y "Te escucho" → decí un comando → la transcripción aparece en **Actividad reciente**. El wake word "Xia" propio requiere entrenar un `.ppn` en la consola de Picovoice (paso siguiente).

## Estructura

```
app/src/main/java/com/controlxia/app/
├── service/       # WakeWordService (orquesta wake word → confirmación → ASR), BootReceiver, Watchdog
├── permissions/   # PermissionManager (incl. ajustes por fabricante)
├── brain/         # Capa multi-LLM (providers, settings cifrados)
├── voice/         # WakeWordEngine/Porcupine, SpeechToText/Android, settings cifrados, RecentCommandsStore
├── feedback/      # FeedbackStore (historial local analizado por el LLM)
└── ui/            # Onboarding, Dashboard, LlmSettings, Feedback, WakeWordSettings
    ├── theme/     # XiaTheme (papel/tinta/acento, tipografía mono) + Motion (expo.out)
    └── components/# TechButton, TechPanel, StatusRow, hairlines…
```
