# Diseño: estilo Apple / Liquid Glass

La interfaz se rehízo tomando como referencia el diseño de iOS (Human Interface Guidelines), el "Liquid Glass" de iOS 26 y las pantallas de ejemplo de un asistente tipo "Aura". Todo está en Jetpack Compose, sin imágenes: la esfera, el fondo y las ondas se dibujan en el teléfono.

## Qué hay

| Pieza | Dónde | Cómo |
|---|---|---|
| Colores | `ui/theme/XiaTheme.kt` | Los colores del sistema de iOS (azul `#007AFF`, verde `#34C759`, rojo, naranja, violeta, rosa, índigo…), texto principal/secundario/terciario, separadores. **Claro y oscuro** según el teléfono. |
| Tipografía | idem | Escala de iOS (Large Title 34, Title 28/22/20, Headline 17, Body 17, Callout 15, Footnote 13). La letra es **Inter**, la libre más parecida a SF Pro (Apple no permite usar SF Pro fuera de sus equipos); se baja con Google Fonts, sin pesar en el APK. Sin Google Play queda la del sistema. |
| Vidrio | `ui/components/Glass.kt` | `Modifier.glass()`: relleno translúcido, borde con brillo (más claro arriba), reflejo arriba y sombra difusa en claro. `Modifier.frostedGlass()`: desenfoque real de lo que pasa por detrás (librería Haze, Android 12+; antes, vidrio opaco). |
| Fondo | idem, `AuraBackground` | Cuatro luces de color grandes y difusas que se mueven despacio, como el fondo Liquid Glass. Se avivan mientras el asistente escucha. |
| La esfera | `ui/components/AuraOrb.kt` | Bola de vidrio azul con luces violeta, rosa y celeste girando adentro, brillo arriba a la izquierda, borde de luz que gira y halo. En reposo respira; escuchando **crece con tu voz**; pensando gira rápido; hablando late. |
| Onda de voz | idem, `VoiceWave` | Tres curvas de colores que se encienden en el centro y crecen con el volumen (como Siri). El volumen sale del reconocedor de voz (`AssistantState.level`). |
| Movimiento | `ui/theme/Motion.kt` | Resortes en vez de curvas fijas: "push" lateral con paralaje entre pantallas, fundido con escala al cambiar de pestaña, apariciones escalonadas, botones que se achican al tocarlos (0,96) y vuelven con rebote, "tic" háptico. Si el teléfono tiene las animaciones apagadas (accesibilidad), nada se mueve. |
| Componentes | `ui/components/AuraComponents.kt`, `TechComponents.kt` | Botones cápsula, íconos en cuadraditos de color (como Ajustes de iOS), filas con flecha, interruptores verdes, chips, barra para escribir pedidos, globos de conversación, barra de pestañas flotante de vidrio con indicador que se desliza. |
| Burbuja flotante | `voice/ListeningOverlay.kt` | Sobre cualquier app: cápsula de vidrio oscuro con la esfera girando, lo que va entendiendo y una ✕. Entra con rebote y sale achicándose. Con ControlXIA abierta no aparece (la esfera de Inicio muestra lo mismo). |
| Sobre el bloqueo | `ui/AssistantActivity.kt` | Siempre oscuro, como Siri: esfera grande, onda, lo que entendió y la respuesta; en horizontal, esfera y texto lado a lado. |

## Apariencia: claro, oscuro y paletas

Ajustes → **Apariencia**: modo **Automático** (como el teléfono), **Claro** u **Oscuro**, y tres paletas. Cambian en vivo, también la esfera, la burbuja flotante y la barra de estado (`ui/theme/ThemeSettings.kt`, `auraColors()` en `XiaTheme.kt`).

| Paleta | Colores | Claro | Oscuro |
|---|---|---|---|
| **Aura** (la de siempre) | Azul de iOS `#007AFF`, violeta, rosa, celeste | Fondo `#F2F3F8` | Fondo `#05060D` |
| **Studio** | Canvas `#FBFBF8`, Mist `#DBE3E9`, Cornflower `#6AA6DA`, Pear `#E1E5AC`, Black `#000000` | Fondo Canvas, luces Mist/Cornflower/Pear, acento Cornflower (un poco más profundo para que se lea) | Fondo Black, acento Cornflower, botón destacado Pear |
| **Toscana** | Tuscan Red `#A91B18`, Pale Cerulean `#CEE7F3`, Noir Black `#181717`, Satin Linen `#F8FAED` | Fondo Satin Linen, luces Pale Cerulean, acento Tuscan Red | Fondo Noir, acento rojo más claro (`#D9443D`) para que se lea sobre negro |

Las pantallas sobre el bloqueo (el asistente y la alarma) van siempre en oscuro, con la paleta elegida.

## Pantallas

- **Inicio**: saludo, la esfera (tocala para hablar sin decir el nombre), la barra para pedir escribiendo, sugerencias, la última charla, el interruptor de la escucha y avisos (permisos que faltan, el cerebro).
- **Explorar**: todo lo que se puede pedir, por tema, con ejemplos; tocar uno lo deja escrito en Inicio.
- **Actividad**: historial como conversación (por día) y el registro del agente.
- **Ajustes**: el asistente (nombre y palabra), voz, cerebro, permisos, ayuda.
- Las pantallas internas (permisos, voz, cerebro, palabra, sugerencias) entran desde la derecha y esconden la barra de pestañas.

## Responsive

- De borde a borde: el fondo pasa por detrás de las barras del sistema y todo respeta la barra de estado, el recorte de la cámara, la navegación y el teclado.
- **Teléfono vertical**: barra de pestañas flotante abajo; la esfera ocupa hasta un tercio de la altura.
- **Horizontal y tablet** (720 dp o más de ancho): las pestañas pasan a una barra vertical a la izquierda; Inicio se divide en dos columnas (esfera a la izquierda, tarjetas a la derecha); Explorar arma 2 o 3 columnas.
- El contenido no se estira más de 720 dp: en tablets queda centrado.
- Los tamaños salen de la pantalla (la esfera, por ejemplo, entre 150 y 300 dp) y los textos pueden crecer con el tamaño de letra del sistema sin cortarse.

Capturas automáticas (vertical, chico, horizontal, tablet, claro y oscuro): `./gradlew testDebugUnitTest --tests '*ScreenshotTest*'` → `app/build/screenshots`.

## De dónde sacar más animaciones así

| Qué | Para qué | Nota |
|---|---|---|
| [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) (`io.github.kyant0:backdrop`, Apache 2.0) | El Liquid Glass de verdad (refracción, lente, desenfoque) en Compose | Pide Compose 1.10+ y Kotlin 2.3+: hay que actualizar el proyecto antes de usarlo |
| [chrisbanes/haze](https://github.com/chrisbanes/haze) | Desenfoque del fondo (vidrio esmerilado) | **Ya se usa** (versión 1.1.1, la que anda con este Compose) |
| [Mesh gradients de Compose](https://developer.android.com/develop/ui/compose/graphics/draw/mesh-gradient) y [ComposeMeshGradient](https://proandroiddev.com/compose-mesh-gradient-apple-swiftui-like-mesh-gradients-for-jetpack-compose-a9f48177c4f7) | Fondos de degradé "malla" como los de SwiftUI | La API oficial está en alpha |
| [AGSL en Compose](https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl), [drinkthestars/shady](https://github.com/drinkthestars/shady), [vishal2376/vortex](https://github.com/vishal2376/vortex), [artículo de shaders animados](https://proandroiddev.com/fancy-animated-ui-with-agsl-shaders-in-jetpack-compose-d962ba297bbb) | Esferas y fondos con shaders (ruido, refracción) | Android 13+; se pueden portar shaders de Shadertoy |
| [kopiro/siriwave](https://github.com/kopiro/siriwave) | La onda de Siri (iOS 9 y clásica) | JavaScript; la lógica se porta a Canvas (la `VoiceWave` sigue esa idea) |
| [LottieFiles: AI orb](https://lottiefiles.com/free-animations/ai-orb), [Siri](https://lottiefiles.com/free-animations/siri), [asistente IA](https://lottiefiles.com/free-animations/ai-assistant) | Animaciones listas (esferas, ondas) | Con `lottie-compose`; revisar la licencia de cada archivo |
| [Esfera para Rive](https://surgestudio.gumroad.com/l/orb) (paga, USD 9) y el componente "Persona" de ai-elements | Esfera con estados (reposo, escuchando, pensando, hablando) | Rive para Android |
| [Apple HIG: Motion](https://developer.apple.com/design/human-interface-guidelines/motion) | Las reglas de movimiento de Apple | Referencia |
