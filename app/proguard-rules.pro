# WepWawet ProGuard rules

# Compose — recommended by AndroidX
-keep class androidx.compose.** { *; }

# Gson — data classes used for JSON serialization (CRITICAL: without these,
# R8 renames the classes and Gson can't find the no-arg constructor, causing
# "Abstract classes can't be instantiated" errors at runtime)
-keep class com.wepwawet.pairing.** { *; }
-keep class com.wepwawet.data.model.** { *; }
-keep class com.wepwawet.data.api.** { *; }
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod, InnerClasses

# Gson TypeToken — anonymous subclasses (e.g. `object : TypeToken<List<T>>() {}`)
# require the generic Signature attribute to be preserved, otherwise Gson
# throws "TypeToken must be created with a type argument" at <clinit> time.
# Keep ALL TypeToken subclasses (anonymous or named) and their generic
# signatures, no matter what package they live in.
-keep class * extends com.google.gson.reflect.TypeToken
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class com.google.gson.reflect.TypeToken$* { *; }
-keepclasseswithmembers class * {
    extends com.google.gson.reflect.TypeToken;
}
-keep,allowobfuscation class * {
    extends com.google.gson.reflect.TypeToken;
}

# Kotlinx Serialization — required for the JSON converter
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keep class kotlinx.serialization.json.** { *; }

# Retrofit — service interfaces (used by SteamApiService)
-keepattributes InnerClasses, EnclosingMethod
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# OkHttp — platform-specific (don't strip)
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# Tink crypto (transitively pulled in via androidx.security.crypto for
# EncryptedSharedPreferences). Tink's runtime classes use compile-time-only
# annotations from com.google.errorprone.annotations which are NOT packaged
# in the runtime APK. R8's "Missing class" report (new in AGP 8.x) treats
# references to missing classes as build-breaking errors. We use -dontwarn
# to silence them — the annotations are not needed at runtime.
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.crypto.tink.**

# Kotlin coroutines — keep internal state machine
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# JavaSteam (SteamKit2 Java port) — keep all classes
-keep class `in`.dragonbra.javasteam.** { *; }
-dontwarn `in`.dragonbra.javasteam.**
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class `in`.dragonbra.javasteam.depotdownloader.** { *; }
-dontwarn `in`.dragonbra.javasteam.depotdownloader.**
