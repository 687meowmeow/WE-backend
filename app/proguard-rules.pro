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

# Kotlin coroutines — keep internal state machine
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
