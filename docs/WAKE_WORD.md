# Palabra de activación con Vosk

La app se despierta cuando decís el **nombre del asistente** (por defecto "Xia"). El reconocimiento corre entero en el teléfono con [Vosk](https://github.com/alphacep/vosk-api) (Apache 2.0) y su modelo en español: no hace falta cuenta, clave ni internet, y el audio no sale del teléfono.

## Cómo funciona

1. **Modelo.** Gradle descarga `vosk-model-small-es-0.42` (39 MB) al compilar y lo empaqueta como asset. La primera vez que se enciende la escucha, la app lo copia al almacenamiento interno (unos segundos) y lo carga en memoria (~110 MB mientras el servicio está activo).
2. **Escucha liviana.** `WakePhraseDetector` le pasa el micrófono a un reconocedor limitado a una gramática mínima: el nombre y "[unk]" para todo lo demás. Así reconoce el nombre aunque se pronuncie distinto: en las pruebas, "Xia", "Sía", "Shía" y "Chía" dieron todas confianza 1.0.
3. **Doble verificación.** La gramática fuerza todo a "nombre o nada", así que el final de "fantasía" o "policía" también le suena a "xia". Cuando la gramática detecta el nombre, ese tramo de audio se vuelve a decodificar con el vocabulario completo. Si en ese momento se dijo una palabra que empezó claramente antes que el nombre, era el final de otra palabra y se descarta.

## Ajustes

En el panel → **Palabra de activación → Ajustar**:

- **Nombre**: cómo se llama el asistente; también es la palabra para despertarlo.
- **Cómo suena (opcional)**: si el nombre no está en el vocabulario del modelo o no te reconoce, escribilo como lo decís (por ejemplo "sia"). También acepta una frase corta ("oye xia"). Si la palabra no está en el vocabulario, el panel lo avisa.
- **Sensibilidad**: más alta acepta detecciones con menos confianza.

## Resultados de las pruebas

Se probó el mismo código Kotlin de la app (`WakePhraseDetector`) con el modelo real y voz sintetizada (espeak-ng) a tres velocidades:

| Caso | Resultado |
|---|---|
| "Xia", "Sía", "Shía", "Chía", "oye Xia", "Xia, qué hora es", "hola Xia", "che Xia" | 24 de 24 detectadas |
| Frases difíciles con "-sía" / "-cía" (fantasía, cortesía, policía, vacía, Lucía, Asia, hacía…) y frases comunes | 4 de 42 activaciones falsas |
| Flujo continuo de 70 s con 9 nombres entre 21 frases | 9 de 9, en el momento correcto |

La voz sintetizada no reemplaza una prueba con voces reales y ruido de ambiente; los números sirven para comparar cambios, no como precisión final. Si se activa sola con frecuencia, conviene un nombre más distintivo, de dos o tres sílabas.

## Compilar sin internet

Descargá `https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip` y copialo en `.gradle/vosk/` dentro del proyecto. La tarea `prepareVoskModel` lo usa sin volver a descargarlo.
