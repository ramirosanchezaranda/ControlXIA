# Reglas ProGuard/R8 para el build release (minify + shrink).

# --- Vosk + JNA: la librería nativa se llama por reflexión (JNA) ---
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# --- kotlinx.serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
# Serializadores generados
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
# Modelos serializables de la app (RecentCommand, FeedbackEntry, etc.)
-keep @kotlinx.serialization.Serializable class com.controlxia.app.** { *; }
-keep,includedescriptorclasses class com.controlxia.app.**$$serializer { *; }
-keepclassmembers class com.controlxia.app.** {
    *** Companion;
}

# --- OkHttp / Okio (solo silenciar warnings de dependencias opcionales) ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Modelos de datos que se serializan/deserializan por reflexión ---
-keepclassmembers class com.controlxia.app.brain.** { <fields>; }

# --- Tink (lo usa androidx.security:security-crypto para cifrar las claves) ---
# Referencia anotaciones de Error Prone que no viajan en el APK; sin esto R8
# corta el build release con "Missing class".
-dontwarn com.google.errorprone.annotations.**

# --- MediaPipe (gestos con la cámara): la parte nativa llama a clases Java y
# usa protobuf por reflexión; las AAR no traen reglas propias. ---
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn com.google.auto.value.**
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
