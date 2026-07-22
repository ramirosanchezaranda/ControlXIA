# ControlXIA

Asistente de voz para Android que controla el teléfono con lenguaje natural ("Xia, escribile a mi pareja…"), potenciado por el LLM que elijas. El plan completo del proyecto está en [PLAN.md](PLAN.md).

## Estado actual (Iteración 1)

- ✅ **Servicio de escucha persistente** (Foreground Service tipo micrófono) que sobrevive pantalla bloqueada, Doze y reinicios: `START_STICKY` + watchdog con AlarmManager + `BootReceiver`.
- ✅ **Checklist de permisos** en la app: micrófono, notificaciones, exención de batería y ajustes específicos del fabricante (Xiaomi, Samsung, Huawei, Oppo, Vivo, OnePlus).
- ✅ **Capa multi-LLM configurable**: Anthropic (Claude), OpenAI (GPT), Google (Gemini) y cualquier endpoint OpenAI-compatible (Groq, OpenRouter, Ollama local). API keys cifradas en el dispositivo, modelo elegible o escrito a mano, botón "Probar conexión".
- ✅ **Prueba de vida con pantalla bloqueada**: botón "Probar voz" en la notificación persistente → dice la hora por TTS sin desbloquear.
- ⏳ Próximo: wake word real ("Xia") con Porcupine y pipeline voz → LLM → acciones.

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

## Estructura

```
app/src/main/java/com/controlxia/app/
├── service/       # WakeWordService, BootReceiver, WatchdogReceiver
├── permissions/   # PermissionManager (incl. ajustes por fabricante)
├── brain/         # Capa multi-LLM (providers, settings cifrados)
└── ui/            # MainActivity (checklist) + LlmSettingsScreen
```
