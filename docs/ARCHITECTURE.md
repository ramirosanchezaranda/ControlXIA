# Arquitectura

Cómo está armada la app, para tocarla sin romper nada. Detalles de cada parte: [WAKE_WORD.md](WAKE_WORD.md) (escucha del nombre), [VOICE_CONTROL.md](VOICE_CONTROL.md) (comandos sin IA), [AGENT.md](AGENT.md) (el agente con herramientas) y [DESIGN.md](DESIGN.md) (interfaz).

## El camino de un pedido

```
micrófono ─▶ SpeechGate ─▶ WakePhraseDetector (Vosk) ─▶ "Beto"
                                                         │
                     WakeWordService (servicio en primer plano)
                                                         │
             tono / vibración (Feedback) ◀──────────────┤
                                                         ▼
                                  AndroidSpeechToText: el pedido en texto
                                                         │
                                                         ▼
 CommandProcessor ─▶ 1. TaskPlanner    (varios pasos: abrir, buscar, ordenar, capturar, anotar, chatear) ─▶ PlanRunner
                  ─▶ 2. DirectCommands (atrás, bajá, volumen…)  ─▶ DirectCommandRunner
                  ─▶ 3. LocalSkills    (alarmas, mensajes, cuentas…) ─▶ ToolRegistry
                  ─▶ 4. modelo: responde preguntas o traduce a frases de 1–3 (LlmPlanner)
                       (con "Dejar que la IA maneje las apps": agente con herramientas)
                                                         │
                                                         ▼
                               actions/*  (accesibilidad, intents, sistema)
                                                         │
                                                         ▼
                                     Reply ─▶ Speaker (voz) + pantalla
```

Los pasos 1 a 3 no usan internet; solo lo que no entienden va al modelo, y el modelo no toca nada: lo que hay que hacer lo hace la app.

## Paquetes

| Paquete | Qué tiene | Depende de Android |
|---|---|---|
| `brain/` | Entender el pedido y decidir: `TaskPlanner`, `LlmPlanner`, `MessageEdit` (corregir el mensaje de palabra), `DirectCommands`, `LocalSkills`, `Calculator`, `NotificationSummary`, `TaskCheck` (sin Android, con pruebas), `PlanRunner` (hace los pasos), `CommandProcessor` (el agente), `ToolRegistry` (las herramientas) y los proveedores de modelos. | Solo `CommandProcessor`, `ToolRegistry` y los proveedores |
| `actions/` | Hacer cosas en el teléfono: abrir apps, tocar y escribir por accesibilidad (`Nodes`, `Gestures`, `ScreenControl`, `ScreenNumbers`, `AppUi`, `ChatUi`, `WhatsAppUi`), capturas (`Screenshots`), productos en pantalla (`ScreenItems`), notas (`NotesStore`), lo privado bloqueado (`PrivateGate`), alarmas, llamadas, cámara, mensajes. | Sí |
| `voice/` | Escuchar y hablar: `SpeechGate` y `WakePhraseDetector` (sin Android), el motor Vosk, el reconocimiento del pedido, `Speaker` (voz), `Feedback` (tono y vibración), `HandSigns`, `HandMotion`, `AirGestures`, `FaceSigns` y `WaveDetector` (señas, deslizar en el aire con la palma, guiño/cabeza y saludos, sin Android), `HandAnalyzer` (los modelos de MediaPipe, compartido por el servicio y la prueba), `VoiceId` y `VoicePrint` (tu voz con contraseña), ajustes. | En parte |
| `service/` | Lo que corre en segundo plano: `WakeWordService`, `HandsWatchService` (cámara para las señas), `WaveGestures` (saludos con el sensor de proximidad), `XiaAccessibilityService`, el lector de notificaciones, `ListeningPolicy` (cuándo escuchar), `ServiceStatus` (estado para la interfaz) y los receptores (arranque, alarmas, vigilancia). | Sí |
| `iot/` | La casa: `HomeCommands` y `HomeLinks` (entender "prendé la tele", sin Android), el control remoto de las teles Android (`Proto`, `AtvMessages`, `SelfSignedCert` sin Android; `AndroidTv`, `ClientIdentity`, `TvDiscovery`), las compus con Windows (`PcProtocol` sin Android, `PcAgent`, `WakeOnLan`), `HomeDevices` (las vinculadas, cifradas) y `HomeController` (hace los pedidos). El programita de Windows está en `pc/`. | En parte |
| `permissions/` | Qué permisos hay y cómo pedirlos (`PermissionManager`, con los pasos de cada marca) y si el control de apps anda de verdad (`A11yHealth`: apagado, trabado, aviso y arreglo solo). | Sí |
| `ui/` | Compose: pantallas, tema (`theme/`) y componentes (`components/`). Solo lee estado y llama al servicio. | Sí |
| `diagnostics/` | `AgentLog`: el registro paso a paso que se ve en Actividad. | No |

## Reglas que seguimos

- **La lógica, sin Android.** Lo que decide (qué comando es, qué seña, cuándo pausar) son funciones puras que se prueban en la PC. Android queda en los bordes (`actions/`, `service/`).
- **Estado por flujos, no consultando.** El servicio publica su estado en `ServiceStatus.state` (un `StateFlow`) y el registro avisa con `AgentLog.changes`; la interfaz los observa con `collectAsState()` y se redibuja solo cuando algo cambia. Nada de bucles que pregunten cada segundo.
- **Eventos, no sondeos.** La cámara de las señas se prende y apaga por avisos (suena audio, cambia la app de adelante, se apaga la pantalla) en vez de mirar cada tanto; la escucha se pausa o reanuda por avisos de pantalla, cargador y batería (`ListeningPolicy`).
- **Una sola copia de cada cosa.** Tocar, escribir y esperar elementos en otras apps está en `actions/Nodes`; la voz en `voice/Speaker`; el tono y la vibración en `voice/Feedback`.
- **Que se vea y se oiga.** El tema pone el color del texto por defecto (claro en oscuro), así ningún texto sale negro sobre el fondo; los estados no son solo un color (TalkBack dice «Listo» o «Falta») y las pestañas y opciones avisan cuál está elegida.
- **Lo que tiene consecuencias lo confirma el código**, no el modelo (ver `Confirmation` y [AGENT.md](AGENT.md)).

## Batería

| Qué | Cómo ahorra |
|---|---|
| `SpeechGate` | El reconocedor de la palabra de activación no trabaja en silencio: una compuerta de volumen, que aprende el ruido de fondo, lo despierta solo cuando hay sonido (con 1 s de audio previo para no perder el comienzo). |
| `ListeningPolicy` | Con la pantalla apagada y sin cargar, pausa la escucha si la batería está baja (≤ 15 %) o en modo ahorro, o siempre si el usuario lo elige. Con la pantalla prendida o cargando, escucha siempre. |
| `HandsWatchService` | La cámara solo se prende con la pantalla prendida y video o música sonando; usa la menor cantidad de cuadros por segundo que permita la cámara y analiza más espaciado cuando no hay una mano a la vista. |
| Animaciones | La esfera y el fondo se redibujan a 20–30 cuadros por segundo (no a 60/120), se detienen cuando no se ven y quedan quietas con el ahorro de batería o las animaciones del sistema apagadas. |
| Saludos | El sensor de proximidad gasta muy poco; igual se suelta con la pantalla apagada y la batería baja o en ahorro. |
| Wake locks | Solo con tope de tiempo y mientras dura un pedido. |

## Pruebas

```
./gradlew testDebugUnitTest                         # lógica: comandos, habilidades, notificaciones, señas, saludos, compuerta, política
./gradlew testDebugUnitTest -Pscreenshots=true \
    --tests '*ScreenshotTest*'                      # capturas de las pantallas → app/build/screenshots
```
