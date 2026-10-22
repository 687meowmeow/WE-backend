# WepWawet ProGuard rules

# Compose — recommended by AndroidX
-keep class androidx.compose.** { *; }

# Kotlinx Serialization — required for the JSON converter
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keep class kotlinx.serialization.json.** { *; }

# Retrofit — service interfaces (used by SteamApiService)
-keepattributes Signature, InnerClasses, EnclosingMethod
-keep class com.wepwawet.data.api.** { *; }
-keep class com.wepwawet.data.model.** { *; }
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# OkHttp — platform-specific (don't strip)
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# Wine / native bridge — JNI classes
-keep class com.wepwawet.wine.** { *; }
-keep class com.wepwawet.proxy.** { *; }
-keep class com.winlator.** { *; }   # if we bundle Winlator classes

# Kotlin coroutines — keep internal state machine
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
