# PRD — ControlXIA para Windows (.exe)

Estado: borrador para aprobación. Fecha: octubre de 2026.

## 1. Resumen

ControlXIA para Windows es el mismo asistente de voz que la app de Android, pero para la PC. Vive en la bandeja del sistema, escucha una palabra de activación, entiende pedidos en lenguaje natural con el LLM que elija el usuario y ejecuta acciones en la computadora. Se entrega como un instalador `.exe`.

## 2. Problema y objetivo

**Problema.** Controlar la PC con la voz hoy depende de Windows Voice Access (limitado, sin LLM) o de proyectos open source difíciles de instalar. La app de Android ya demuestra la idea, pero no hay equivalente de escritorio con el mismo cerebro.

**Objetivo.** Que alguien instale un `.exe`, cargue una API key, y pueda decir "Xia, abrí Spotify" o "Xia, ponete un temporizador de 10 minutos" sin tocar el teclado.

**No objetivos de la v1.** Mac y Linux, control total del mouse y teclado por visión de pantalla, llamadas y SMS, y publicación en la Microsoft Store.

## 3. Usuarios

- **Usuario principal:** quien ya usa o va a usar la app de Android y quiere el mismo asistente en la PC.
- **Usuario secundario:** personas con las manos ocupadas o con dificultad para usar el teclado.

## 4. Decisiones de producto

| # | Decisión | Elección | Alternativa descartada |
|---|---|---|---|
| D1 | Tecnología | Kotlin + Compose Desktop, para compartir el módulo del cerebro con Android | Electron: tiene más referencias (OpenDex) pero obliga a reescribir el cerebro en JavaScript |
| D2 | Palabra de activación | Porcupine (JVM), mismo AccessKey y mismo `.ppn` que en Android | openWakeWord: sin cuenta, pero requiere Python o ONNX; queda como plan B |
| D3 | Voz a texto | Vosk offline para empezar; Whisper local como mejora | Servicio en la nube: costo y privacidad |
| D4 | Texto a voz | Voz de Windows (SAPI) con selección de voz, tono y velocidad | ElevenLabs: queda como opción posterior |
| D5 | LLM | Los mismos proveedores que Android (Anthropic, OpenAI, Gemini, compatibles) | — |
| D6 | Claves | Guardadas con el Administrador de credenciales de Windows, no en texto plano | Archivo de configuración |

Si el equipo prefiere Electron, D1 cambia y los requisitos funcionales se mantienen; solo cambia el plan de construcción.

## 5. Requisitos funcionales

**Activación y escucha**
- RF-01. La app arranca con Windows y queda en la bandeja del sistema, con un ícono que indica si está escuchando.
- RF-02. Detecta la palabra de activación en segundo plano, sin internet.
- RF-03. Se puede cambiar el nombre del asistente y elegir entre palabras de fábrica o un modelo propio.
- RF-04. Atajo de teclado global como alternativa a la palabra (por ejemplo, Ctrl+Alt+X).
- RF-05. Se puede pausar la escucha desde la bandeja con un clic.

**Interacción**
- RF-06. Al activarse, muestra un indicador flotante siempre visible con el estado (escuchando, entendiendo, respondiendo) y el texto que va entendiendo.
- RF-07. Responde en voz alta con la voz, el tono y la velocidad elegidos.
- RF-08. Si el usuario dice la palabra mientras el asistente habla, este se calla y escucha.
- RF-09. Mantiene el contexto de la conversación entre pedidos seguidos.

**Acciones de la v1**
- RF-10. Decir la hora y la fecha.
- RF-11. Abrir y cerrar programas por nombre.
- RF-12. Abrir una página web o buscar en internet.
- RF-13. Subir, bajar y silenciar el volumen.
- RF-14. Temporizadores y recordatorios con aviso sonoro y notificación de Windows.
- RF-15. Bloquear la PC, apagar la pantalla, suspender y apagar.
- RF-16. Responder preguntas generales con el LLM.

**Seguridad y confirmación**
- RF-17. Las acciones riesgosas (apagar, cerrar programas, borrar) piden confirmación por voz o con un botón antes de ejecutarse.
- RF-18. Cada tipo de acción se puede poner en *Preguntar siempre*, *Permitir* o *Bloquear*, y la elección se guarda.
- RF-19. Historial local de comandos y respuestas, que el usuario puede borrar.

**Configuración**
- RF-20. Pantalla de configuración con la misma estructura que Android: asistente, LLM, voz, privacidad.
- RF-21. Botón "Probar conexión" para el LLM con el error real si falla.
- RF-22. Asistente de primera vez que guía la carga de claves y permisos de micrófono.

## 6. Requisitos no funcionales

- RNF-01. Con la escucha activa, el uso de CPU en reposo debe ser bajo (objetivo: menos del 3 % en una PC de gama media).
- RNF-02. La palabra de activación y la transcripción pueden funcionar sin internet; solo la llamada al LLM sale de la PC.
- RNF-03. Latencia objetivo desde que termina de hablar hasta que empieza a responder: menos de 3 segundos con un LLM en la nube.
- RNF-04. El audio no se guarda en disco ni se envía a ningún servicio salvo que el usuario elija un STT en la nube.
- RNF-05. Instalable en Windows 10 y 11 de 64 bits, sin requerir permisos de administrador.
- RNF-06. Interfaz en español rioplatense, con textos preparados para otros idiomas.

## 7. Arquitectura

```
Micrófono → Wake word (Porcupine) → STT (Vosk) → Cerebro (módulo común) → Acción de Windows → TTS (SAPI)
                                                     ↑
                                      Proveedores LLM + tools + memoria
```

- **Módulo común `brain`:** proveedores de LLM, registro de tools, procesador de comandos y memoria. Se extrae del proyecto de Android a un módulo JVM que usan las dos apps.
- **Capa de Windows:** implementaciones de wake word, STT, TTS y acciones, detrás de las mismas interfaces que ya existen en Android (`WakeWordEngine`, `SpeechToText`).
- **Interfaz:** Compose Desktop, con el mismo sistema visual (papel, tinta, acento azul, etiquetas en monospace) y la ventana de configuración.
- **Bandeja:** ícono con menú (pausar, configurar, salir) y ventana flotante de escucha.
- **Empaquetado:** `packageExe` de Compose Desktop (jpackage) genera el instalador.

## 8. Plan de entrega

| Hito | Contenido | Resultado visible |
|---|---|---|
| M0 | Extraer `brain` a un módulo común sin romper Android | Android sigue compilando con el módulo compartido |
| M1 | Esqueleto de la app de escritorio: ventana, bandeja, configuración, LLM | Se abre, se carga la clave y "Probar conexión" funciona |
| M2 | Wake word + STT + TTS | Decís la palabra, hablás y te responde |
| M3 | Acciones RF-10 a RF-16 | "Abrí Spotify", "subí el volumen", "temporizador de 5 minutos" |
| M4 | Confirmaciones, permisos por acción e historial (RF-17 a RF-19) | Las acciones riesgosas piden confirmar |
| M5 | Indicador flotante, atajo global, instalador `.exe` | Se instala y arranca con Windows |
| M6 | Pulido: respuesta en streaming, cortar al asistente, respaldo entre LLM | Se siente fluido |

## 9. Criterios de aceptación de la v1

1. Se instala con el `.exe` en una PC con Windows 11 limpia.
2. Tras cargar la API key y el AccessKey, decir la palabra de activación y "qué hora es" produce la respuesta hablada correcta.
3. "Abrí [programa instalado]" lo abre y "silenciá el volumen" lo silencia.
4. "Apagá la PC" pide confirmación y no apaga sin ella.
5. Con la escucha pausada, no se procesa audio.
6. Cambiar de proveedor de LLM no requiere reiniciar la app.

## 10. Riesgos

| Riesgo | Efecto | Mitigación |
|---|---|---|
| Empaquetar `.exe` exige una PC con Windows; el CI de GitHub está bloqueado por facturación | No se puede generar el instalador desde este entorno | Documentar el comando y compilar en la PC del usuario, o resolver el billing y usar un runner de Windows |
| Calidad de Vosk en español rioplatense | Entiende mal algunos comandos | Whisper local como alternativa; modelo configurable |
| Falsos positivos de la palabra de activación en una PC con música o videollamadas | Se activa solo | Sensibilidad ajustable y atajo de teclado como respaldo |
| Acciones destructivas ejecutadas por error del LLM | Pérdida de datos | Confirmación obligatoria y lista de acciones permitidas (RF-17, RF-18) |
| Dependencia de Porcupine y su AccessKey | Fricción de alta | openWakeWord como plan B |
| El código de Android nunca se compiló todavía | El módulo compartido podría heredar errores | Compilar y corregir Android antes de M0 |

## 11. Métricas de éxito

- Porcentaje de comandos entendidos y ejecutados correctamente en una lista fija de 30 pedidos de prueba (objetivo: 85 % o más).
- Tiempo medio de respuesta.
- Falsos positivos de activación por hora de uso.
- Cantidad de instalaciones que llegan a ejecutar un primer comando.

## 12. Preguntas abiertas

1. ¿Se confirma Compose Desktop (D1) o preferís Electron?
2. ¿La v1 debe funcionar sin internet (STT local y LLM local con Ollama) o alcanza con LLM en la nube?
3. ¿Querés que el asistente pueda manejar el mouse y el teclado en una versión posterior?
4. ¿Solo uso personal o también distribución a otros usuarios? Eso define si hace falta firmar el instalador.
