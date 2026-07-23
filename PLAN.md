# ControlXIA — Asistente de voz para controlar el celular

Planificación técnica y funcional de una app Android que permite controlar el teléfono por voz, al estilo Alexa/Google Assistant, potenciada por un LLM.

---

## 1. Visión

Una app que escucha un **nombre de activación** (ej: "Xia" o el nombre que elijas), entiende pedidos en lenguaje natural en español y ejecuta acciones reales en el dispositivo:

- "Xia, leeme los mensajes que tengo"
- "Xia, escribile un mensaje a mi pareja que diga: llego tarde"
- "Xia, abrí Instagram"
- "Xia, decime la hora"
- "Xia, poné una alarma a las 7"

La clave que la diferencia de Google Assistant: el **LLM interpreta pedidos ambiguos o encadenados** ("avisale a mamá que voy para allá y ponéme el GPS hasta su casa") y responde conversacionalmente.

---

## 2. Plataforma: ¿por qué Android nativo?

**Decisión: app nativa Android en Kotlin.** No hay alternativa real para este caso:

| Opción | Veredicto | Motivo |
|---|---|---|
| **Android nativo (Kotlin)** | ✅ Elegida | Único camino con acceso total: SMS, notificaciones, accesibilidad, servicio en segundo plano, rol de asistente |
| iOS | ❌ Imposible | Apple no permite apps de terceros con acceso a SMS, notificaciones de otras apps ni control del sistema |
| Flutter / React Native | ⚠️ No conviene | Todo lo importante (AccessibilityService, NotificationListener, wake word) requiere código nativo igual; el framework solo suma fricción |

- **Lenguaje:** Kotlin
- **UI:** Jetpack Compose. Lenguaje visual editorial/técnico: fondo papel, tinta casi negra, un acento azul "blueprint", etiquetas monospace en mayúsculas con tracking (`[ SISTEMA ]`), líneas de 1dp y esquinas casi rectas. Tres superficies: **onboarding** guiado (presenta la app y pide los permisos de a uno antes de habilitar nada), **dashboard** (estado del servicio + checklist del sistema + cerebro + feedback) y **ajustes del LLM**
- **Movimiento:** transiciones minimalistas estilo GSAP — curva `expo.out` (`CubicBezierEasing(0.16, 1, 0.3, 1)`) centralizada en `ui/theme/Motion.kt`. Cambios de pantalla y pasos con `AnimatedContent` (fade + rise); secciones del dashboard con reveal escalonado (`Modifier.staggerReveal`)
- **Mínimo SDK:** API 26 (Android 8.0) — cubre ~95% de dispositivos; ideal API 29+ para mejores APIs de voz

---

## 3. Arquitectura general (pipeline de voz)

```
[Micrófono siempre escuchando]
        │
        ▼
① Wake word ("Xia")          → on-device, sin internet, bajo consumo
        │
        ▼
② ASR (voz → texto)          → transcribe el pedido en español
        │
        ▼
③ LLM (cerebro)              → interpreta la intención y elige la "tool" a ejecutar
        │
        ▼
④ Ejecutor de acciones       → módulos nativos Android (SMS, apps, alarmas...)
        │
        ▼
⑤ TTS (texto → voz)          → responde en voz alta
```

### ① Wake word (palabra de activación)

Debe correr 24/7 on-device con consumo mínimo de batería.

| Opción | Pros | Contras |
|---|---|---|
| **Picovoice Porcupine** ✅ | Wake word personalizada ("Xia"), muy eficiente, SDK Android oficial, entrenás la palabra en su consola web | Plan gratuito limitado (uso personal OK), pago para producción |
| openWakeWord | Open source, gratis | Más difícil de integrar en Android, modelos custom requieren entrenamiento propio |
| Vosk keyword spotting | Gratis, offline | Más falsos positivos, mayor consumo |

**Recomendación:** Porcupine para MVP (gratis para uso personal). Se implementa dentro de un **Foreground Service** con notificación persistente ("Xia está escuchando").

### ② ASR — Reconocimiento de voz (Speech-to-Text)

| Opción | Pros | Contras |
|---|---|---|
| **`SpeechRecognizer` de Android** ✅ MVP | Gratis, nativo, excelente en español, sin API keys | Requiere Google apps; online en la mayoría de los equipos |
| **Whisper on-device** (whisper.cpp / whisper-android, modelo `small`/`base`) | 100% offline, privado, muy buen español rioplatense | Consume batería/CPU, latencia en gama baja |
| Whisper API / Deepgram / Google Cloud STT | Máxima calidad, streaming | Costo por minuto, requiere internet |

**Recomendación:** empezar con `SpeechRecognizer` nativo (costo cero, cero fricción) y dejar la capa ASR abstraída detrás de una interfaz para poder enchufar Whisper on-device en fase 2.

### ③ LLM — el cerebro (NLU + conversación)

El LLM recibe el texto transcripto y decide **qué acción ejecutar** usando **tool use / function calling**. No genera código: elige entre un catálogo de herramientas definidas.

**Arquitectura multi-LLM configurable.** La app no se casa con un proveedor: el usuario elige en Ajustes el proveedor, carga su propia API key y selecciona el modelo. Todo el resto de la app habla con una interfaz común (`LlmProvider`), así cambiar de proveedor es un toggle, no un refactor.

| Proveedor | API | Modelos sugeridos |
|---|---|---|
| **Anthropic (Claude)** | Messages API + tool use | `claude-haiku-4-5` (recomendado para ruteo: rápido y barato), `claude-sonnet-5` |
| **OpenAI (GPT)** | Chat Completions + function calling | `gpt-4o-mini`, `gpt-4o` |
| **Google (Gemini)** | generateContent + function declarations | `gemini-2.0-flash` |
| **OpenAI-compatible genérico** | Chat Completions con base URL configurable | Cubre Groq, OpenRouter, Mistral, **Ollama en red local** (offline), etc. |

- Las API keys se guardan **cifradas en el dispositivo** (`EncryptedSharedPreferences`), una por proveedor
- El modelo se elige de una lista sugerida o se escribe a mano (para IDs nuevos sin actualizar la app)
- Botón "Probar conexión" en Ajustes que valida key + modelo con un request mínimo
- Patrón "router" (fase 2+): un modelo chico rutea intenciones y delega en uno grande solo para pedidos conversacionales complejos

**Alternativa/complemento offline:** vía el proveedor OpenAI-compatible apuntando a Ollama en la red local, o Gemini Nano (AICore, solo Pixel/gama alta). Fase 3.

**Cómo funciona el tool use** — se le declaran herramientas al LLM y él devuelve un JSON con la llamada:

```json
// Usuario dice: "escribile a mi pareja que llego tarde"
// El LLM responde con:
{
  "tool": "send_sms",
  "input": {
    "contact_alias": "pareja",
    "message": "Llego tarde"
  }
}
```

**Catálogo inicial de tools:**

| Tool | Descripción |
|---|---|
| `read_messages` | Lee en voz alta SMS/notificaciones recientes (con filtro por remitente) |
| `send_sms` | Envía SMS a un contacto (por nombre o alias como "mi pareja") |
| `send_whatsapp` | Envía WhatsApp (ver limitaciones en §5) |
| `open_app` | Abre una app por nombre ("abrí Instagram") |
| `get_time` / `get_date` | Hora y fecha |
| `set_alarm` / `set_timer` | Alarmas y temporizadores |
| `make_call` | Llama a un contacto |
| `get_weather` | Clima (API externa, ej. Open-Meteo gratis) |
| `play_music` | Reproduce música (intent a Spotify/YouTube Music) |
| `toggle_setting` | WiFi, Bluetooth, linterna, volumen, no molestar |
| `create_reminder` / `calendar_event` | Recordatorios y eventos |
| `answer_question` | Fallback: el LLM responde directamente ("¿cuánto es 15% de 3400?") |

**Contexto que se le pasa al LLM en cada request:** fecha/hora, lista de apps instaladas (nombres), aliases de contactos ("mi pareja" → contacto configurado en onboarding), últimas N interacciones (memoria conversacional para "y ahora mandale otro").

### ④ Ejecutor de acciones

Capa Kotlin que mapea cada tool a APIs de Android. Detalle de permisos en §5.

### ⑤ TTS — Respuesta en voz

- **MVP:** `TextToSpeech` nativo de Android — gratis, offline, voces en español decentes
- **Fase 2 (voz premium):** ElevenLabs / OpenAI TTS / Google Cloud TTS — voz natural, pero costo por caracter y latencia. Cachear frases frecuentes ("Listo", "Mensaje enviado").

---

## 4. Backend

**MVP: sin backend.** La app llama directo a la API del LLM.

⚠️ Riesgo: la API key viaja en la app (extraíble por ingeniería inversa). Aceptable para uso personal/beta.

**Producción: backend mínimo** (Cloudflare Workers, o Node/FastAPI en Railway/Fly.io):
- Proxy hacia el LLM (la key nunca vive en el celular)
- Autenticación de usuarios, rate limiting, control de costos
- Métricas y logs de intenciones fallidas (para mejorar los prompts)

---

## 5. Permisos y capacidades del dispositivo

Esta es la parte más delicada. Android protege cada capacidad con un mecanismo distinto. **No existe un "permiso total" único**: hay que pedir cada uno, y algunos requieren que el usuario los active a mano en Ajustes.

### 5.1 Permisos de runtime (diálogo estándar)

| Permiso | Para qué |
|---|---|
| `RECORD_AUDIO` | Micrófono (wake word + ASR) |
| `READ_SMS` / `SEND_SMS` / `RECEIVE_SMS` | Leer y enviar mensajes de texto |
| `READ_CONTACTS` | Resolver "mi pareja", "mamá" → número |
| `CALL_PHONE` | Llamadas |
| `POST_NOTIFICATIONS` | Notificación del servicio (Android 13+) |
| `READ_CALENDAR` / `WRITE_CALENDAR` | Eventos |
| `ACCESS_FINE_LOCATION` | Clima local, "llevame a casa" |

### 5.2 Accesos especiales (pantallas de Ajustes — requieren onboarding guiado)

| Acceso | Cómo se otorga | Qué habilita |
|---|---|---|
| **NotificationListenerService** | Ajustes → Acceso a notificaciones | Leer notificaciones de TODAS las apps: WhatsApp, Instagram, Telegram… Es la única forma de "leeme los mensajes de WhatsApp" |
| **AccessibilityService** | Ajustes → Accesibilidad | Interactuar con la UI de otras apps: tocar botones, escribir texto. Necesario para enviar WhatsApp automáticamente. ⚠️ Google Play lo audita fuerte: hay que declarar el uso y justificarlo |
| **Rol de asistente digital** (`RoleManager.ROLE_ASSISTANT`) | Ajustes → App de asistente predeterminada | La app se activa con mantener apretado el botón home/power, como Google Assistant |
| **App de SMS predeterminada** (opcional) | Diálogo de rol | Control total de SMS; no es necesario para el MVP (leer/enviar alcanza con los permisos de runtime) |
| `SYSTEM_ALERT_WINDOW` | Ajustes → Mostrar sobre otras apps | Overlay visual de "escuchando…" sobre cualquier pantalla |
| Ignorar optimización de batería | Diálogo especial | Que el servicio de escucha no sea matado por el sistema (crítico en Xiaomi/Samsung/Huawei) |
| `SCHEDULE_EXACT_ALARM` | Ajustes (Android 12+) | Alarmas exactas |

### 5.3 Estrategia de onboarding de permisos

Un wizard paso a paso, pidiendo cada permiso **en contexto y de a uno**, con explicación de para qué sirve y botón que abre directo la pantalla de Ajustes correspondiente. Pantalla de estado tipo checklist (verde/rojo) para ver qué capacidades están activas. La app debe **degradar con gracia**: si no dieron acceso a notificaciones, todo lo demás sigue funcionando.

### 5.4 Confiabilidad: pantalla bloqueada, Doze y fabricantes

El requisito más crítico: que "Xia" responda **aunque el teléfono lleve horas bloqueado**. Android hace todo lo posible por matar apps en segundo plano, así que la confiabilidad se construye en capas:

**a) Foreground Service de micrófono (la base)**
- El servicio de escucha corre como Foreground Service con `foregroundServiceType="microphone"` y notificación persistente. Es la única categoría a la que Android le permite usar el micrófono de forma continua.
- Android 14+ exige además el permiso `FOREGROUND_SERVICE_MICROPHONE` declarado en el manifest.
- `START_STICKY`: si el sistema mata el proceso, lo re-crea cuando hay recursos.

**b) Restricción clave de Android 11+**
Un servicio **iniciado desde background no puede acceder al micrófono**, aunque sea foreground service. El servicio debe arrancarse desde la app visible (el switch en la UI) o desde exenciones permitidas como `BOOT_COMPLETED`. El flujo correcto: el usuario lo enciende una vez desde la app → sobrevive bloqueos → tras un reboot lo relanza el `BootReceiver`.

**c) Doze y ahorro de batería**
- Pedir exención de optimización de batería (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) en el onboarding — sin esto, Doze frena el servicio tras ~1h de pantalla apagada.
- Wake lock parcial **solo mientras se procesa un comando** (no permanente): detectar wake word → adquirir → ejecutar → soltar.

**d) OEM killers (la causa #1 de "dejó de escuchar")**
Xiaomi/MIUI, Samsung, Huawei, Oppo, Vivo y otros agregan sus propios asesinos de apps por encima de Android. La app detecta el fabricante y guía al usuario a la pantalla exacta:
- **Xiaomi:** activar "Inicio automático" + batería "Sin restricciones"
- **Samsung:** sacar la app de "Apps en suspensión" / desactivar "Suspensión profunda"
- **Huawei:** "Gestión manual" en Inicio de aplicaciones
- **Oppo/Vivo:** permitir autoarranque y actividad en segundo plano
- Referencia por fabricante: [dontkillmyapp.com](https://dontkillmyapp.com)

**e) Watchdog de resurrección**
- `AlarmManager` periódico (~15 min) verifica que el servicio esté vivo y lo relanza si murió (`WorkManager` como refuerzo opcional a futuro).
- `BootReceiver` (`RECEIVE_BOOT_COMPLETED`) lo levanta tras cada reinicio del teléfono.

**f) Responder con pantalla bloqueada**
- La respuesta por **audio (TTS) funciona sin desbloquear** — no requiere nada especial.
- Para mostrar UI sobre el bloqueo: activity con `setShowWhenLocked()` + `setTurnScreenOn()`.
- Política de privacidad configurable: acciones sensibles (leer mensajes en voz alta) pueden limitarse a "solo con el teléfono desbloqueado".

**Checklist de prueba manual en dispositivo real:**
1. Encender el servicio → bloquear el teléfono 1+ hora → disparar la acción de prueba desde la notificación → debe responder por voz
2. Reiniciar el teléfono → el servicio debe volver solo (sin abrir la app)
3. Activar ahorro de batería del sistema → repetir la prueba 1
4. En Xiaomi/Samsung: repetir la prueba 1 sin las exenciones OEM (debe fallar) y con ellas (debe funcionar) — valida el wizard

### 5.5 Limitaciones que hay que conocer desde el día 1

- **WhatsApp no tiene API pública** para apps de terceros. Opciones reales:
  - *Semiautomático:* intent `wa.me` que deja el chat abierto con el texto listo — el usuario solo toca "enviar". Simple y robusto. ✅ MVP
  - *Automático:* AccessibilityService que toca el botón de enviar solo. Funciona, pero es frágil ante updates de WhatsApp y riesgoso para publicar en Play Store. Fase 3, como opción avanzada.
- **Leer mensajes de WhatsApp/Instagram** = solo vía NotificationListener (lo que llega como notificación). No se puede leer el historial de chats.
- **Google Play** restringe apps con SMS/Accessibility: hay que completar el formulario de declaración de permisos y justificar cada uso. Para uso personal se puede distribuir por APK directo y evitar todo esto.
- **iOS queda descartado** para esta funcionalidad (ver §2).

---

## 6. Roadmap por fases

> **Estado:** la base de la Fase 0 ya está en el repo — servicio persistente confiable (bloqueado/Doze/OEMs, §5.4), capa multi-LLM configurable (§3③), onboarding guiado de permisos, dashboard de control y canal de **feedback analizado por el propio LLM** (clasifica cada comentario en [BUG]/[IDEA]/[MEJORA] y lo guarda como insumo del roadmap).
>
> **Cadena de voz completa — hecho:** wake word on-device con **Porcupine** → confirmación (vibración + TTS) → **transcripción** con `SpeechRecognizer` (es-AR) → **cerebro** (`brain/CommandProcessor`): manda el texto al LLM configurado con el catálogo de tools, **ejecuta la acción** que el modelo elige y **responde en voz alta**. Memoria conversacional corta (`ConversationMemory`) para pedidos encadenados.
>
> **Tools que ya se ejecutan:** `get_time`, `get_date`, `open_app` (abre apps por nombre), `set_alarm`, `set_timer` (alarmas/timers con `SKIP_UI`, sin abrir el reloj). Abrir apps con la pantalla bloqueada requiere el permiso "Mostrar sobre otras apps" (fila nueva en el checklist del dashboard).
>
> **Identidad y presencia:** el **nombre del asistente es configurable** (se usa en el prompt, la notificación y el overlay). El wake word puede ser una palabra de fábrica o un **modelo custom entrenado** (guía en `docs/CUSTOM_WAKE_WORD.md`). Al activarse aparece un **overlay flotante "escuchando" sobre cualquier app** (estilo Google Assistant, `voice/ListeningOverlay`, requiere permiso de overlay).
>
> **Build/release:** `signingConfig` con keystore por archivo o env, y minify/shrink con reglas ProGuard (`app/proguard-rules.pro`).
>
> **Falta:** tools de mensajería (SMS/WhatsApp/llamadas) que necesitan más permisos; activación con botón home (rol de asistente digital); entrenar el `.ppn` custom de "Xia".

### Fase 0 — Esqueleto (1-2 semanas) ✅
- Proyecto Android en Kotlin + Compose
- Foreground Service persistente con micrófono
- Pipeline: `SpeechRecognizer` → LLM configurable con tools → TTS nativo
- **Meta cumplida: "abrí Instagram" y "decime la hora" funcionando end-to-end**

### Fase 1 — MVP asistente (3-4 semanas)
- ✅ Wake word con Porcupine (palabra de fábrica; "Xia" custom pendiente) + transcripción del comando con `SpeechRecognizer`
- Tools: `send_sms`, `read_messages` (SMS), `make_call`, `set_alarm`, `set_timer`
- Aliases de contactos ("mi pareja") en onboarding
- Wizard de permisos completo
- Memoria conversacional corta (últimos ~10 turnos)

### Fase 2 — Mensajería completa (3-4 semanas)
- NotificationListenerService: leer notificaciones de WhatsApp/Telegram/Instagram
- Envío de WhatsApp semiautomático (intent `wa.me`)
- Rol de asistente digital (activación con botón home)
- `get_weather`, `play_music`, `toggle_setting`, calendario
- Backend proxy para la API key

### Fase 3 — Potencia (continuo)
- AccessibilityService para envío 100% automático de WhatsApp (opt-in avanzado)
- ASR offline con Whisper on-device + comandos básicos sin internet
- TTS premium (ElevenLabs) configurable
- Rutinas ("cuando diga 'me voy a dormir': activá no molestar y alarma a las 7")
- Multi-idioma, control de hogar (futuro)

---

## 7. Costos estimados (MVP, uso personal)

| Componente | Costo |
|---|---|
| ASR (`SpeechRecognizer`) | $0 |
| TTS nativo | $0 |
| Porcupine (uso personal) | $0 |
| LLM (Claude Haiku, ~100 comandos/día) | ~USD 1-3/mes |
| Backend | $0 (MVP sin backend) |

---

## 8. Privacidad y seguridad

- La wake word se procesa **100% on-device**: nada sale del teléfono hasta que se activa
- Enviar al LLM **solo el texto transcripto**, nunca audio crudo
- Contenido de mensajes/notificaciones: minimizar lo que se manda al LLM (ej. para "leeme los mensajes", el TTS puede leer directo sin pasar el contenido por la API)
- Todo dato local cifrado (EncryptedSharedPreferences / SQLCipher)
- Pantalla de privacidad clara: qué se escucha, qué se envía, qué se guarda
- Botón físico/virtual de "mute" del micrófono

---

## 9. Estructura de proyecto propuesta

```
app/
├── service/
│   ├── WakeWordService.kt          # Foreground service + Porcupine
│   ├── NotificationReaderService.kt # NotificationListenerService
│   └── XiaAccessibilityService.kt   # (Fase 3)
├── voice/
│   ├── SpeechToText.kt             # interfaz ASR (impl: Android / Whisper)
│   └── TextToSpeechEngine.kt       # interfaz TTS (impl: nativo / ElevenLabs)
├── brain/
│   ├── LlmClient.kt                # cliente API Claude (tool use)
│   ├── ToolRegistry.kt             # catálogo de tools + schemas
│   └── ConversationMemory.kt
├── actions/                        # una clase por tool
│   ├── SmsActions.kt
│   ├── AppLauncher.kt
│   ├── AlarmActions.kt
│   ├── ContactResolver.kt          # aliases → contactos
│   └── ...
├── permissions/
│   └── PermissionWizard.kt         # onboarding guiado
└── ui/                             # Compose: onboarding, config, historial
```

---

## 10. Próximos pasos inmediatos

1. Definir el nombre/wake word final y registrarla en Picovoice Console
2. Crear el proyecto Android (Fase 0)
3. Conseguir API key de Anthropic y definir el system prompt + schemas de las primeras 3 tools
4. Probar el loop completo en un dispositivo real (el emulador no sirve para micrófono continuo)
