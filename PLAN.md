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
- **UI:** Jetpack Compose (la UI es mínima: onboarding de permisos, configuración, historial)
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

**Modelo recomendado: API de Claude (Anthropic)**
- `claude-haiku-4-5` para el ruteo de intenciones: rápido (~1s), barato, más que suficiente para elegir tools
- Escalar a un modelo mayor solo para pedidos conversacionales complejos (patrón "router": Haiku decide si resuelve solo o delega)

**Alternativa/complemento offline:** Gemini Nano (AICore, solo Pixel/gama alta) o un modelo pequeño local con llama.cpp para comandos básicos sin internet. Fase 3.

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

### 5.4 Limitaciones que hay que conocer desde el día 1

- **WhatsApp no tiene API pública** para apps de terceros. Opciones reales:
  - *Semiautomático:* intent `wa.me` que deja el chat abierto con el texto listo — el usuario solo toca "enviar". Simple y robusto. ✅ MVP
  - *Automático:* AccessibilityService que toca el botón de enviar solo. Funciona, pero es frágil ante updates de WhatsApp y riesgoso para publicar en Play Store. Fase 3, como opción avanzada.
- **Leer mensajes de WhatsApp/Instagram** = solo vía NotificationListener (lo que llega como notificación). No se puede leer el historial de chats.
- **Google Play** restringe apps con SMS/Accessibility: hay que completar el formulario de declaración de permisos y justificar cada uso. Para uso personal se puede distribuir por APK directo y evitar todo esto.
- **iOS queda descartado** para esta funcionalidad (ver §2).

---

## 6. Roadmap por fases

### Fase 0 — Esqueleto (1-2 semanas)
- Proyecto Android en Kotlin + Compose
- Foreground Service persistente con micrófono
- Botón "mantener para hablar" (sin wake word todavía)
- Pipeline: `SpeechRecognizer` → API de Claude con 3 tools (`get_time`, `open_app`, `answer_question`) → TTS nativo
- **Meta: "abrí Instagram" y "decime la hora" funcionando end-to-end**

### Fase 1 — MVP asistente (3-4 semanas)
- Wake word con Porcupine ("Xia, …")
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
