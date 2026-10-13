# Manejar el teléfono solo con la voz

Objetivo: que todo el teléfono se pueda usar sin tocarlo. La referencia es el **Acceso por voz** de Google (Voice Access); encima de eso, el agente con LLM resuelve los pedidos complejos.

## Cómo está armado

Hay dos caminos, y el asistente elige solo:

1. **Comandos directos** (`brain/DirectCommands` + `DirectCommandRunner`): frases cortas y fijas ("atrás", "bajá", "tocá 5", "subí el volumen", "sacá una foto"). Se resuelven en el teléfono, **sin el modelo**: son al instante, no gastan tokens y andan aunque se termine el cupo gratis de Groq. Los que se ven en pantalla no se dicen en voz alta: aparece un texto corto, una vibración, y sigue escuchando el próximo comando (en modo conversación, sin repetir el nombre).
2. **El agente** (LLM con herramientas): todo lo demás ("buscá zapatillas en Mercado Libre y mandale el link a Juan"). Tiene las mismas capacidades como herramientas (`phone`, `camera`, `call`, `send_audio`…).

Solo entra como comando directo la **frase entera**: "subí el volumen" sí, "subí el volumen y mandale a Juan…" va al modelo. Si un comando directo no se puede resolver acá (no hay una app con ese nombre, el botón no está en pantalla, el contacto es ambiguo), también pasa al modelo.

## Comandos directos

| Qué | Cómo decirlo (ejemplos) |
|---|---|
| Sistema | "atrás", "inicio", "apps recientes", "bajá las notificaciones", "ajustes rápidos", "bloqueá el teléfono", "sacá una captura", "menú de apagado", "pantalla dividida" |
| Desplazar | "bajá", "subí", "seguí bajando", "deslizá a la izquierda / derecha" |
| Tocar cualquier cosa | "mostrá los números" → aparece un número sobre cada cosa tocable → "tocá 5" (o solo "5"), "mantené apretado 5", "ocultá los números". También "tocá Guardar", "tocá el botón de enviar" |
| Escribir | Con un campo abierto: "escribí hola, ¿cómo estás?", "borrá todo", "enter" |
| Volumen | "subí / bajá el volumen", "subí el volumen un poco / bastante", "volumen al máximo / a la mitad / al 30", "silenciá", "activá el sonido" |
| Música y videos | "pausa", "pará el video", "dale play", "siguiente canción", "canción anterior" |
| Linterna | "prendé / apagá la linterna", "linterna" |
| Conexiones | "activá / apagá el wifi", "prendé el bluetooth", "activá el modo avión", "apagá los datos" |
| Brillo | "subí / bajá el brillo", "brillo al máximo / al 40" |
| Apps | "abrí Instagram", "entrá a YouTube" |
| Cámara | "abrí la cámara", "cámara frontal", "sacá una foto", "sacame una selfie", "empezá a grabar", "terminá el video", "cambiá de cámara" |
| Llamadas | "llamá a mamá", "llamá al 11 5555 4444", "atendé", "colgá", "rechazá la llamada" |
| Frenarlo | "pará", "basta", "cancelá" (ver [WAKE_WORD.md](WAKE_WORD.md#frenarlo-pará)) |
| Ayuda | "¿qué puedo decir?" |

Se toleran el nombre del asistente adelante ("Beto, atrás"), "che", "dale", "por favor", y números dichos en palabras ("tocá el veinticinco").

### Cómo funcionan por dentro

- **Botones del sistema** (atrás, inicio, notificaciones, bloquear, captura): acciones globales del servicio de accesibilidad.
- **Wi-Fi, Bluetooth, modo avión, datos**: desde Android 10 las apps no pueden prenderlos por código. Se abre el panel del sistema y el control de apps toca el interruptor si no está como se pidió.
- **Brillo**: se abren los ajustes rápidos y se mueve la barra con el control de apps (no hace falta el permiso especial de "modificar ajustes").
- **Música y videos**: las mismas teclas que unos auriculares (play, pausa, siguiente); le llegan a la app que esté reproduciendo.
- **Números en pantalla**: una ventana del servicio de accesibilidad dibuja los números (no necesita permisos extra, no se puede tocar) y se actualizan cuando cambia la pantalla.

## Cámara

Usa la app de cámara del teléfono, así las fotos y videos quedan en la galería como siempre. El botón de disparo se busca por su nombre o id ("obturador", "shutter", "grabar") y por estar abajo al centro; si la app no lo nombra, se toca ese lugar. Con el teléfono bloqueado, "sacá una foto" abre la cámara sobre el bloqueo.

**Terminar un video por voz.** Mientras otra app graba, Android le da silencio al micrófono de las demás, salvo a un servicio de accesibilidad que tenga una ventana a la vista: según la documentación de Android, *"esto permite controlar una llamada o una grabación de video con comandos de voz"*. Por eso, mientras se graba aparece el aviso rojo "● Grabando" (del servicio de accesibilidad) y el motor de voz escucha, sin internet, "terminá el video" (también "pará de grabar", "cortá el video"…). El reconocedor de Google no recibe audio durante la grabación, así que el nombre solo no alcanza: hay que decir la frase. Tocar el aviso también termina el video.

## Audios de WhatsApp

- "Mandale un audio a Juan": dice "Hablá después del tono", suena un tono, graba hasta que te quedás callado ~2 segundos (o 2 minutos como máximo), y pregunta "¿Le mando a Juan el audio de 6 segundos?".
- "Mandale un audio a Juan que diga feliz cumpleaños": el audio lo dice la voz del asistente.
- Con el "sí", se pasa a WhatsApp: directo al chat si el contacto tiene número, o eligiendo el chat por nombre en la pantalla "Enviar a…" (grupos), y se toca enviar.

Se graba con el micrófono propio (no apretando el botón de WhatsApp) porque mientras WhatsApp graba, el asistente no puede oír cuándo terminaste. El audio se guarda como **OGG/Opus**, el formato de las notas de voz de WhatsApp (Android 10+; si no hay codificador Opus, M4A/AAC).

## Pausa con las manos

Ajustes → Asistente y activación → **Pausa con las manos**. Mientras ves un video o escuchás música, levantá las dos manos con las palmas hacia el teléfono: pone pausa y pregunta "¿Qué necesitás?".

- Usa el **reconocedor de gestos de MediaPipe** (Google, Apache 2.0) con la cámara frontal. Cuenta como "manos arriba" dos palmas abiertas ("Open_Palm") en la parte de arriba de la imagen durante 3 cuadros seguidos (~0,6 s); después espera 8 s antes de volver a disparar.
- La cámara se prende **solo mientras algo se reproduce** y se apaga a los 8 s de que pare (Android muestra el punto verde de cámara mientras está prendida). Analiza ~5 cuadros por segundo en el teléfono: no se guardan ni se mandan imágenes.
- El modelo (8 MB) se baja la primera vez que se activa, como el modelo de voz.
- Android solo deja usar la cámara en segundo plano si el servicio arrancó con la app a la vista: se activa desde la app, y al abrirla se reactiva si quedó apagado.

## Llamadas

"Llamá a mamá" llama directo si hay un solo contacto con ese nombre o apodo (si hay dudas, el modelo pregunta cuál). Cuando lo pide el modelo, se confirma antes ("¿Llamo a Lucía?"). "Atendé" y "colgá" usan el permiso de administrar llamadas; se piden junto con contactos y SMS en el panel.

## Qué miramos en GitHub

Búsqueda de octubre de 2026 (las estrellas son las que mostraba GitHub):

| Proyecto | Qué tomamos |
|---|---|
| [Acceso por voz de Google](https://support.google.com/accessibility/android/answer/6151854?hl=es-419) (no es open source) | La lista de comandos: botones del sistema, "mostrar números" + "tocar N", desplazar, volumen, Wi-Fi/Bluetooth, escribir. Es la vara de "manejar el teléfono 100 % con la voz" |
| [DicioTeam/dicio-android](https://github.com/DicioTeam/dicio-android) (1,5k ★, GPL-3.0) | Asistente Android open source, también con Vosk. Habilidades simples sin nube: linterna, multimedia, llamadas, temporizador |
| [droidrun/mobilerun](https://github.com/droidrun/mobilerun) y su Portal | Agente LLM que maneja Android con el árbol de accesibilidad; el Portal dibuja los elementos numerados en pantalla |
| [sannabotdev/sannabotapp](https://github.com/sannabotdev/sannabotapp) (MIT) | Asistente de voz Android con agente: mensajes, llamadas, notificaciones, automatización de apps |
| [Ayush0Chaudhary/blurr](https://github.com/Ayush0Chaudhary/blurr) (990 ★) | Agente que usa Android "como una persona" con accesibilidad, palabra de activación y disparadores |
| [yashab-cyber/opendroid](https://github.com/yashab-cyber/opendroid) | Agente autónomo con LLM local o remoto y control de pantalla por accesibilidad |

Lo que más rinde para "100 % voz" no es otro agente sino **comandos directos sin LLM** (como el Acceso por voz), con el agente para lo complejo. Eso es lo que se implementó.

## Límites conocidos

- Todo esto se probó con pruebas automáticas (el analizador de comandos: 113 frases; el bucle del agente con un modelo simulado), **no en un teléfono real**. Las partes que tocan otras apps (botón de la cámara, interruptores de Wi-Fi, "Enviar a…" de WhatsApp) dependen de cómo dibuja cada fabricante su pantalla: si algo falla, el **Registro del agente** dice en qué paso.
- "Terminá el video" depende de que el teléfono respete la regla de audio compartido para accesibilidad.
- Atender y cortar llamadas por voz: con el teléfono sonando el micrófono está libre; en plena llamada, el audio lo tiene la llamada.
- El APK para el teléfono se arma solo para arm64 (casi todos los teléfonos de los últimos años): con MediaPipe para las dos arquitecturas superaba el tamaño que se puede mandar por chat. Para otros equipos: `./gradlew assembleDebug -Pcontrolxia.abis=arm64-v8a,armeabi-v7a`.
