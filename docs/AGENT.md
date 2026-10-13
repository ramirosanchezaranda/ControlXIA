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
| Mensajes | `read_messages`, `reply_to_message`, `send_message`, `save_contact_alias` | Lee los mensajes desde las notificaciones, los responde con la acción "Responder" de la notificación, manda mensajes nuevos por WhatsApp o SMS a contactos o apodos ("mi pareja"). |
| Básicas | `get_time`, `get_date`, `open_app`, `set_alarm`, `set_timer` | Como antes; se responden sin volver al modelo. |

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

Groq usa la API compatible con OpenAI y tiene límite de tokens por minuto. El agente:

- recorta las lecturas de pantalla viejas en cada paso (con Anthropic no se puede: exige el historial sin cambios),
- limita cada lectura a ~3500 caracteres,
- reintenta una vez cuando Groq responde `tool_use_failed`.

Modelo recomendado: `openai/gpt-oss-120b`. Más rápido: `openai/gpt-oss-20b`. En preview: `qwen/qwen3.8-27b`.

## Límites conocidos

- Las apps cambian sus pantallas: el control de pantalla puede necesitar algún paso de más o fallar en apps con interfaces poco accesibles.
- Los enlaces directos dependen de cada app; si una no abre la sección, el agente sigue con el control de pantalla.
- El número de WhatsApp se arma desde la agenda; con números argentinos guardados sin "+54 9" se intenta normalizar, y si no se puede, propone mandarlo por SMS.
- Un pedido largo tiene un tope de 15 pasos y 2 minutos y medio.
