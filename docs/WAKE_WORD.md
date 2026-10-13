# Palabra de activación con Vosk

La app se despierta cuando decís el **nombre que le pusiste** (por defecto "Xia"). El reconocimiento corre entero en el teléfono con [Vosk](https://github.com/alphacep/vosk-api) (Apache 2.0) y su modelo en español: no hace falta cuenta ni clave, y el audio no sale del teléfono. Solo se necesita internet una vez, para bajar el modelo de voz.

## Cómo funciona

1. **Modelo.** La app descarga `vosk-model-small-es-0.42` (39 MB) desde el sitio de Vosk la primera vez (botón en el onboarding, el panel o los ajustes) y lo instala en el almacenamiento interno (~60 MB). Mientras la escucha está activa lo mantiene en memoria (~110 MB).
2. **Nombre → palabras del modelo.** Vosk solo reconoce palabras de su vocabulario. `WakePhraseResolver` busca el nombre tal cual y, si no está, prueba escrituras que suenan igual (sin tilde, x→s, k→c, v→b, ll→y, th→t…) o lo parte en dos palabras conocidas. De 57 nombres comunes de asistentes y personas, 56 están tal cual; ejemplos resueltos: "Xiomara" → "sio mara", "Mikaela" → "micaela", "Cristhian" → "cristian", "Yazmín" → "yasmin". Si no encuentra forma (ej. "Xochitl"), los ajustes piden escribirlo como suena.
3. **Escucha liviana.** `WakePhraseDetector` le pasa el micrófono a un reconocedor limitado a una gramática mínima: el nombre y "[unk]" para todo lo demás. Así reconoce el nombre aunque se pronuncie distinto: en las pruebas, "Xia", "Sía", "Shía" y "Chía" dieron todas confianza 1.0.
4. **Doble verificación.** La gramática fuerza todo a "nombre o nada", así que el final de "fantasía" o "policía" también le suena a "xia". Cuando la gramática detecta el nombre, ese tramo de audio se vuelve a decodificar con el vocabulario completo. Si en ese momento se dijo una palabra que empezó claramente antes que el nombre, era el final de otra palabra y se descarta.

## Ajustes

En el panel → **Palabra de activación → Ajustar**:

- **Nombre**: cómo se llama el asistente; también es la palabra para despertarlo. Debajo se ve al instante si lo va a reconocer ("Va a responder cuando digas «Luna»").
- **Cómo suena (opcional)**: solo si no te reconoce, escribí el nombre como lo decís (por ejemplo "sia"). También acepta una frase corta ("oye xia").
- **Sensibilidad**: más alta acepta detecciones con menos confianza.

## Modo conversación

Activo por defecto (Ajustar → Conversación). Después de cada respuesta el asistente sigue escuchando unos segundos (8 por defecto, de 4 a 20) para el próximo pedido, sin decir el nombre de nuevo. Si no decís nada, vuelve en silencio a esperar el nombre. Cuando hace una pregunta ("¿Le mando…?") espera al menos 10 segundos.

Para cerrar antes: "gracias" (responde "De nada"), "chau", "listo", "nada más", "eso es todo". Tiene que ser la frase entera: "gracias, ahora poné una alarma" sigue siendo un pedido. Hay un tope de 15 turnos seguidos sin el nombre, por si la tele o una charla de fondo lo mantienen escuchando.

## Resultados de las pruebas

Se probó el mismo código Kotlin de la app (`WakePhraseDetector`) con el modelo real y voz sintetizada (espeak-ng) a tres velocidades:

| Caso | Resultado |
|---|---|
| "Xia", "Sía", "Shía", "Chía", "oye Xia", "Xia, qué hora es", "hola Xia", "che Xia" | 24 de 24 detectadas |
| Frases difíciles con "-sía" / "-cía" (fantasía, cortesía, policía, vacía, Lucía, Asia, hacía…) y frases comunes | 4 de 42 activaciones falsas |
| Flujo continuo de 70 s con 9 nombres entre 21 frases | 9 de 9, en el momento correcto |
| Nombre partido en dos palabras ("Xiomara" → "sio mara"): 4 formas de decirlo × 3 velocidades | 12 de 12 detectadas; 3 de 15 falsas, todas con "sí, mañana" |

La voz sintetizada no reemplaza una prueba con voces reales y ruido de ambiente; los números sirven para comparar cambios, no como precisión final. Si se activa sola con frecuencia, conviene un nombre más distintivo, de dos o tres sílabas.

## Incluir el modelo dentro del APK (opcional)

Por defecto el modelo no viaja en el APK (queda en ~25-45 MB según arquitecturas). Para un APK que funcione sin internet desde el primer uso:

```bash
./gradlew assembleDebug -Pcontrolxia.bundleVoskModel=true
```

Gradle descarga el zip una vez y lo cachea en `.gradle/vosk/` (o copialo ahí a mano). Para un APK más liviano se pueden limitar las arquitecturas: `-Pcontrolxia.abis=arm64-v8a`.
