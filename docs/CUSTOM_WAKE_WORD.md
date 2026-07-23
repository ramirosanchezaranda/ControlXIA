# Entrenar el wake word custom ("Xia")

Por defecto la app usa una **palabra de activación de fábrica** de Porcupine
(Jarvis, Computer, etc.). Para que responda a un nombre propio como **"Xia"**,
hay que entrenar un modelo `.ppn` en la consola de Picovoice. Es gratis para uso
personal y lleva un par de minutos.

## 1. Entrenar la palabra en Picovoice Console

1. Entrá a <https://console.picovoice.ai> y creá una cuenta (gratis).
2. Andá a **Porcupine** → **Create Wake Word**.
3. Escribí la palabra: `Xia` (o el nombre que quieras).
4. Elegí el idioma **Spanish (es)** y la plataforma **Android**.
5. Descargá el `.ppn` generado, por ejemplo `Xia_es_android_v3_0_0.ppn`.

> El `.ppn` está atado a la versión del SDK. Este proyecto usa
> `porcupine-android:3.0.2`, así que descargá el modelo para **v3.0.x**.

## 2. Descargar el modelo de idioma español

Los wake words en español necesitan además el **modelo de parámetros** en español
(los de fábrica en inglés vienen embebidos, el español no):

- En la consola, sección de modelos, o desde el repo de Picovoice:
  `porcupine_params_es.pv`

## 3. Poner los archivos en la app

Copiá ambos archivos a la carpeta de assets:

```
app/src/main/assets/
├── xia.ppn                  (renombrá el .ppn descargado)
└── porcupine_params_es.pv
```

Creá la carpeta `assets` si no existe.

## 4. Activarlo en la app

El motor (`voice/PorcupineWakeWordEngine`) ya soporta modelos custom: si en
**Ajustes → Palabra de activación** hay un keyword custom configurado, usa
`setKeywordPath("xia.ppn")` + `setModelPath("porcupine_params_es.pv")` en lugar
de la palabra de fábrica. Además, en **Ajustes → Palabra de activación** podés
poner el **nombre del asistente** (cómo se llama a sí mismo y cómo figura en el
overlay de "escuchando").

> Nota: el AccessKey de Picovoice sigue siendo necesario (es el mismo, gratuito),
> también para modelos custom.

## Resumen

| Elemento | De fábrica | Custom "Xia" |
|---|---|---|
| Palabra que decís | Jarvis / Computer / … | Xia (entrenada) |
| Archivo `.ppn` | embebido en el SDK | lo entrenás vos |
| Modelo de idioma | inglés embebido | `porcupine_params_es.pv` |
| AccessKey | requerido | requerido |
| Nombre del asistente | configurable en Ajustes | configurable en Ajustes |
