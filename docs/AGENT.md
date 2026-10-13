# Agente: usar apps y mensajería por voz

Desde la versión 0.3.0 el asistente resuelve los pedidos en **varios pasos**: el modelo pide una herramienta, ve el resultado y decide el siguiente paso, hasta responder. Así puede hacer cosas como:

- "Entrá a Instagram y mostrame los reels" → abre Instagram directo en Reels.
- "Buscá en Mercado Libre las zapatillas Nike más baratas" → abre la búsqueda ordenada por menor precio, lee los resultados y te dice el más barato con su precio.
- "Leeme los mensajes de WhatsApp" / "Respondele que sí" / "Mandale a mi pareja que llego en 10".

## Herramientas

| Grupo | Herramientas | Qué hacen |
|---|---|---|
| Atajos | `search_in_app`, `open_app_section` | Abren una app en una búsqueda o sección con enlaces directos (Mercado Libre con orden por precio, Instagram Reels/mensajes/perfil, YouTube, Spotify, Maps, TikTok, Netflix, Play Store). Rápido y sin tocar la pantalla. |
| Pantalla | `screen_read`, `screen_tap`, `screen_type`, `screen_scroll`, `screen_back` | Con el **control de apps** (accesibilidad) lee la pantalla como una lista numerada y toca, escribe o desplaza. Cada acción devuelve la pantalla nueva. |
| Cualquier app | `app_search` | Busca dentro de cualquier otra app instalada usando su propio buscador (toca la lupa, escribe y busca). |
| Mensajes | `read_messages`, `reply_to_message`, `send_message`, `save_contact_alias` | Lee los mensajes desde las notificaciones, los responde con la acción "Responder" de la notificación, manda mensajes nuevos por WhatsApp o SMS a contactos, apodos ("mi pareja") o grupos, o por Telegram, Messenger, Instagram o Signal buscando el chat en la app. |
| Notificaciones | `notifications` | Lee las notificaciones de la barra (todas, agrupadas por app, filtrables), abre la más nueva o las borra. |
| WhatsApp por nombre | `whatsapp_open_chat` | Abre un chat o **grupo** buscándolo por nombre dentro de WhatsApp, en un solo paso (`actions/WhatsAppUi`). |
| Teléfono | `phone`, `camera`, `call` | Botones y ajustes del sistema, cámara y llamadas: lo mismo que los comandos directos ([VOICE_CONTROL.md](VOICE_CONTROL.md)), para pedidos compuestos ("sacate una selfie y mandásela a Juan"). |
| Audios | `send_audio` | Graba la voz del usuario o genera el audio con la voz del asistente y lo manda por WhatsApp (con confirmación). |
| Básicas | `get_time`, `get_date`, `open_app`, `set_alarm`, `set_timer` | En pedidos de una sola cosa se responden sin volver al modelo. |

## WhatsApp: grupos y nombres mal entendidos

Los grupos no tienen número, así que `send_message` a "el grupo Fútbol 2" (o a alguien que no está en la agenda, o cuyo número no se puede armar) busca el chat por nombre en WhatsApp con el control de apps:

1. Busca el nombre tal cual, sin importar tildes ni emojis ("futbol 2" encuentra «Fútbol 2 ⚽»).
2. Si no aparece, busca por las primeras letras y elige el chat **más parecido** (distancia de edición, con los números dichos en palabras pasados a cifras). Así "fulgo 2", como lo entendió el reconocimiento de voz, encuentra «Fútbol 2 ⚽». Por debajo de 60 % de parecido no elige ninguno.
3. Abre el chat y **recién ahí pregunta, con el nombre real**: "¿Le mando a «Fútbol 2 ⚽» por WhatsApp: «hola»?". Si eligió mal, decís que no y no se manda nada.
4. Con el "sí" escribe y toca Enviar. Si mientras tanto el chat dejó de estar en pantalla, lo vuelve a abrir.

## Control de que hizo todo lo pedido

Los modelos chicos (sobre todo `openai/gpt-oss-20b`) a veces dicen "listo, te lo mandé" sin haber usado ninguna herramienta, o hacen la primera parte de "abrí WhatsApp y mandale hola a Juan" y cortan. Por eso el código (`brain/TaskCheck`) lleva el registro de lo que se hizo **de verdad** (según las herramientas, no según lo que dice el modelo) y, antes de responder:

- Si el modelo **dice haber hecho algo** ("mandé", "abrí", "puse"…) sin haber usado ninguna herramienta, o
- si el pedido tenía **varias acciones** (se cuentan los verbos: "buscá… y después poneme…") y se hicieron menos,

le muestra al modelo el pedido, lo que se hizo de verdad y lo que iba a responder, y le pide que complete lo que falte (o que conteste "OK" si no falta nada). Se hace **una sola vez por pedido** y solo en esos casos: si el modelo hizo todo, no hay llamada extra.

Además:

- El atajo de "decir el resultado sin volver al modelo" (abrir una app, poner una alarma) solo se usa en pedidos de **una sola cosa**; en "abrí WhatsApp y mandale…" el modelo sigue después de abrir.
- Si en medio de un pedido de varias cosas hace falta confirmar ("mandale hola a Juan y poneme una alarma"), después del "sí" **sigue con lo que falta** (la alarma) en vez de terminar ahí.
- Todo queda en el **Registro del agente** (líneas `CONTROL:` y `SIGO`).

## Cuando se termina el cupo

Antes del modelo se prueban los comandos directos y las habilidades locales (sin LLM). Si el modelo se queda sin cupo, sigue solo con el próximo de la cadena: los otros modelos gratis del mismo proveedor y después los otros proveedores con clave (ver [VOICE_CONTROL.md](VOICE_CONTROL.md#sin-el-modelo-sin-créditos-ni-cupo)). En el registro aparece `SIN CUPO en … : sigo con …`.

## Comandos directos

Antes de llamar al modelo, las frases cortas y fijas ("atrás", "tocá 5", "subí el volumen", "sacá una foto", "llamá a mamá") se resuelven en el teléfono: al instante, sin tokens. Lista completa en [VOICE_CONTROL.md](VOICE_CONTROL.md).

## Correcciones y despedidas

- "No, mandale este otro mensaje" a una confirmación: no se manda lo anterior y se atiende lo nuevo (antes solo cancelaba).
- "Bueno, gracias Beto" cierra la conversación aunque tenga el nombre o una muletilla adelante.
- En mensajes y audios, el modelo manda el texto ya redactado y no la orden: "decile que llego tarde" → «Llego tarde».

## Frenarlo

Decí **"pará"**, "basta" o "cancelá" mientras trabaja o habla, o tocá la píldora flotante / el botón **Frenar** de la notificación. Corta el pedido al modelo y lo que esté haciendo en la app; lo que esperaba confirmación se descarta. Queda en la memoria del asistente qué alcanzó a hacer ("[Me frenaste antes de terminar. Ya había hecho: …]") para que el próximo pedido lo sepa y no lo retome solo. Detalles de cómo se distingue "¡pará!" de "para mí": [WAKE_WORD.md](WAKE_WORD.md#frenarlo-pará).

## Seguridad

- **Confirmación por voz obligatoria** antes de mandar un mensaje, responder uno o tocar botones como Enviar, Comprar, Pagar, Publicar o Borrar. La decide el código, no el modelo: la acción queda pendiente, el asistente pregunta ("¿Le mando a Lucía por WhatsApp: «llego en 10»?") y se queda escuchando la respuesta. Solo con "sí" / "dale" / "mandalo" se ejecuta.
- El texto de las pantallas y de los mensajes se trata como datos de terceros: el modelo tiene la instrucción de no seguir órdenes que aparezcan ahí, y aunque lo hiciera, enviar o comprar sigue pidiendo tu confirmación.
- El agente no completa compras ni pagos.
- Lo que se ve en pantalla y los mensajes que pedís leer se mandan al proveedor del LLM que elegiste.

## Permisos (panel → Sistema)

- **Control de apps**: Ajustes → Accesibilidad → ControlXIA. En Android 13+, si dice "Ajuste restringido": info de la app → ⋮ → "Permitir ajustes restringidos".
- **Leer mensajes**: acceso a notificaciones (misma nota sobre ajustes restringidos).
- **Contactos y SMS**: para buscar contactos y mandar SMS.
- **Mostrar sobre otras apps**: para abrir apps desde segundo plano.

## Con Groq

Groq usa la API compatible con OpenAI. El plan gratis tiene límites bajos para un agente: para `gpt-oss-120b` y `gpt-oss-20b`, **8.000 tokens por minuto**, 200.000 por día, 30 pedidos por minuto y 1.000 por día. Cada paso del agente reenvía el prompt, las herramientas y lo hecho hasta ahí, así que un pedido de varios pasos puede pasar los 8.000 por minuto. El agente:

- usa un prompt y descripciones de herramientas cortos, y recuerda solo los últimos 4 intercambios,
- recorta las lecturas de pantalla viejas en cada paso (con Anthropic no se puede: exige el historial sin cambios) y limita cada lectura a ~2500 caracteres,
- prefiere herramientas que hacen todo en un paso (`search_in_app`, `open_app_section`, `whatsapp_open_chat`) en vez de leer y tocar la pantalla,
- si Groq responde 429 con "esperá N segundos" (hasta 30 s), **espera y reintenta solo** (hasta 3 veces) mostrando "El proveedor pide esperar N s…",
- reintenta una vez cuando Groq responde `tool_use_failed`,
- distingue en voz alta el límite por minuto ("probá de nuevo en un minuto") del cupo diario ("se renueva mañana").

Modelo recomendado: **`openai/gpt-oss-120b`**: mismo límite gratis que el 20b y bastante más confiable para pedidos de varios pasos. `openai/gpt-oss-20b` es más rápido pero se saltea pasos (la app avisa al elegirlo). En preview: `qwen/qwen3.8-27b`. Si el límite por minuto molesta seguido, el plan Developer de Groq (pago por uso) lo sube mucho.

## Qué modelo elegir

En ⚙️ → Proveedor y modelo, arriba aparecen los **Recomendados** (un toque elige proveedor y modelo; la API key se carga aparte) y cada modelo del menú dice para qué sirve. Revisado en octubre de 2026 en las páginas oficiales de precios; los precios cambian, por eso la app los muestra en comparación y no en dólares.

| Proveedor | Recomendado | Por qué | Alternativas |
|---|---|---|---|
| Groq (gratis: 8.000 tokens/min, 1.000 pedidos/día) | `openai/gpt-oss-120b` | El más confiable gratis para manejar el teléfono | `qwen/qwen3.8-27b` (preview, mismo límite); `openai/gpt-oss-20b` (rápido, se saltea pasos) |
| Google Gemini (gratis con límite diario) | `gemini-3.8-flash` | El Flash más nuevo y capaz; pago: US$0,75 / 3,75 por millón de tokens (precio de lanzamiento hasta el 31/12/2026) | `gemini-3.5-flash-lite`, `gemini-3.1-flash-lite` (más baratos, menos confiables en varios pasos) |
| Anthropic (pago) | `claude-haiku-5-5` | El más eficiente: US$0,10 / 0,50 por millón; un pedido típico cuesta una fracción de centavo | `claude-sonnet-5-5` (US$2 / 10, más confiable en pedidos largos dentro de apps); `claude-opus-5-5` (US$4 / 20, no hace falta para voz) |
| OpenAI (pago) | `gpt-6-luna` | El más eficiente: US$0,10 / 0,50 por millón | `gpt-6-sol` (US$2 / 10); `gpt-5.4-mini` (generación anterior) |

Detalle técnico: GPT-6 Luna y Sol solo aceptan herramientas en Chat Completions con `reasoning_effort: "none"` (documentación de OpenAI); la app lo manda sola para los modelos `gpt-6*`. `gemini-3.5-flash`, que estaba en la lista anterior, ya no figura en la página de precios de Google y se sacó.

## Límites conocidos

- Las apps cambian sus pantallas: el control de pantalla puede necesitar algún paso de más o fallar en apps con interfaces poco accesibles.
- Los enlaces directos dependen de cada app; si una no abre la sección, el agente sigue con el control de pantalla.
- El número de WhatsApp se arma desde la agenda; con números argentinos guardados sin "+54 9" se intenta normalizar, y si no se puede, busca el chat por nombre en WhatsApp.
- La búsqueda por nombre en WhatsApp depende de su pantalla (ids internos y textos "Buscar"/"Enviar"); si WhatsApp cambia, puede fallar y el registro del agente dice en qué paso.
- Un pedido largo tiene un tope de 15 pasos y 2 minutos y medio.

## Con el teléfono bloqueado

Las herramientas que manejan la pantalla (`open_app`, `search_in_app`, `open_app_section`, `app_search`, `whatsapp_open_chat`, `screen_*`, mandar por WhatsApp u otra app) primero piden desbloquear (`actions/UnlockGate`: una actividad invisible sobre el bloqueo llama a `requestDismissKeyguard`, aparece la huella o el PIN) y siguen solas apenas se desbloquea. El modelo no tiene que hacer nada distinto: el prompt le dice que use las herramientas igual. Si no se desbloquea en 30 s, la herramienta lo informa y el pedido termina ahí.
