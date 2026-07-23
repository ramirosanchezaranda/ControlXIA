# Reglas ProGuard/R8 para el build release (minify + shrink).

# --- Picovoice Porcupine: usa JNI y carga clases por reflexión ---
-keep class ai.picovoice.** { *; }
-dontwarn ai.picovoice.**

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
