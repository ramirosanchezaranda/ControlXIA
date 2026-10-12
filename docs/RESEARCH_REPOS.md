# Investigación: repos de asistentes de voz y qué mejorar en ControlXIA

Búsqueda hecha en octubre de 2026. Las estrellas son las que mostraba GitHub al consultarlas; donde no pude verificar el número, no lo pongo.

## Repos revisados

| Repo | Estrellas | Qué es | Plataforma |
|---|---|---|---|
| leon-ai/leon | 17.6k | Asistente personal open source modular, con skills | Escritorio / servidor |
| MycroftAI/mycroft-core | 6.6k | Asistente de voz "hackable", con skills (proyecto discontinuado) | Linux / Pi |
| rhasspy/rhasspy | 2.7k | Asistente de voz offline y privado en muchos idiomas | Servidor / Pi |
| dscripka/openWakeWord | sin verificar | Detección de palabra de activación open source, fácil de entrenar | Multi |
| wassgha/opendex | 68 | Asistente de voz para escritorio con agente que usa herramientas, hecho en Electron | Win / Mac / Linux |
| panpenek/jarvisai | sin verificar | Asistente local para Windows con wake word, visión de pantalla y 31 herramientas | Windows |
| sannabotdev/sannabotapp | 39 | Asistente de voz para Android que ejecuta acciones, con bucle de agente | Android |
| oleksiiyeromenko/voice_assistant | sin verificar | Asistente local en Raspberry Pi con enrutado de LLM y TTS en streaming | Pi |

Los repos más populares (Leon, Mycroft, Rhasspy) son de escritorio o servidor. Los de Android son mucho más chicos, pero el de Sanna es el más parecido a lo que queremos hacer.

## Qué hacen bien y nos conviene copiar

**1. Pedir permiso antes de acciones riesgosas (OpenDex).**
Cada skill tiene un nivel: *Permitir una vez / Siempre / Nunca*, y la elección queda guardada por skill. Es imprescindible antes de sumar "mandá un SMS" o "llamá a X", porque un error del LLM no puede mandar un mensaje sin confirmar.

**2. Bucle de agente de varios pasos (Sanna, JarvisAI).**
Hoy `CommandProcessor` hace una sola llamada a una tool y habla el resultado. Sanna y JarvisAI encadenan pasos: el resultado de una tool vuelve al LLM, que decide el siguiente (JarvisAI llega a 15 llamadas por pedido). Con eso se resuelve "leé mis últimos 3 mensajes y resumímelos".

**3. Respuesta en streaming, frase por frase (voice_assistant).**
El TTS empieza a hablar la primera frase mientras el LLM sigue generando. Baja mucho la espera percibida.

**4. Cortar al asistente hablando (OpenDex).**
Si decís la palabra de activación mientras responde, se calla y te escucha. Hoy tenemos que esperar a que termine.

**5. Modo conversación (propuesta de OpenClaw).**
Distinguen *Voice Wake* (un pedido y vuelve a esperar) de *Talk Mode* (seguir hablando sin repetir la palabra). Nosotros solo tenemos el primero.

**6. Lección sobre la palabra de activación (OpenClaw).**
Detectar la palabra transcribiendo el audio es poco confiable con nombres cortos o inventados ("Xia" puede salir "Sia", "Chía"). Hace falta un modelo acústico dedicado. Ya usamos Porcupine, así que vamos bien. **openWakeWord** es la alternativa gratuita y sin cuenta, y permite entrenar "Xia" sin AccessKey.

**7. Enrutado y respaldo entre LLM (voice_assistant).**
Prueba un modelo local, si falla otro remoto y después uno en la nube. Nosotros ya tenemos varios proveedores; falta el respaldo automático cuando uno falla o se queda sin crédito.

**8. Memoria persistente (JarvisAI, voice_assistant, Sanna).**
Guardan datos del usuario entre sesiones ("mi pareja es Lucía") y resumen conversaciones largas para no pasarse de tokens. Hoy nuestra memoria es solo de la sesión.

**9. Skills como archivos (Sanna, Leon, Mycroft).**
Sanna extiende capacidades con archivos `SKILL.md` en Markdown. Hace fácil sumar acciones sin tocar el código.

**10. Personalidad editable (Sanna "SOUL").**
Un texto que define cómo habla el asistente. Nosotros ya tenemos el nombre; falta el tono.

**11. Cosas específicas de Android (Sanna).**
- Leer notificaciones en voz alta y responderlas automáticamente.
- Automatizar apps sin API con el servicio de accesibilidad.
- Tareas programadas ("todas las mañanas a las 8, resumime la agenda").
- Modo manejo.
- Exportar un archivo de log para reportar errores.

## Qué agregaría a la app de Android, en orden

| # | Mejora | Por qué primero |
|---|---|---|
| 1 | Confirmación y permisos por acción | Bloquea el riesgo antes de sumar mensajería |
| 2 | Bucle de agente de varios pasos | Habilita todo lo que sigue |
| 3 | Mensajería: SMS, contactos, leer notificaciones | Es lo que pediste desde el principio |
| 4 | Respuesta en streaming y cortar con la palabra | Se nota mucho en la experiencia |
| 5 | Memoria persistente y apodos ("mi pareja") | Hace útil el asistente |
| 6 | Respaldo automático entre LLM | Evita quedarse mudo |
| 7 | Skills en Markdown y personalidad editable | Extensibilidad |
| 8 | Log exportable | Ayuda a arreglar fallas en teléfonos reales |
| 9 | openWakeWord como alternativa a Porcupine | Quita la cuenta de Picovoice |
| 10 | Automatización con accesibilidad, tareas programadas | Fase avanzada |

## Fuentes

- https://github.com/leon-ai/leon
- https://github.com/MycroftAI/mycroft-core
- https://github.com/rhasspy/rhasspy
- https://github.com/dscripka/openWakeWord
- https://github.com/wassgha/opendex
- https://github.com/panpenek/jarvisai
- https://github.com/sannabotdev/sannabotapp
- https://github.com/oleksiiyeromenko/voice_assistant
- https://github.com/openclaw/openclaw-windows-node/issues/1124
