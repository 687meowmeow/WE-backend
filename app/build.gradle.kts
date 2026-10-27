import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.wepwawet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wepwawet"
        minSdk = 26  // Android 8.0 — required for the WE pairing protocol + ImageDecoderDecoder GIF path
        targetSdk = 35
        versionCode = 69
        versionName = "0.5.30-alpha"
        // Native ABI filter — only build for arm64-v8a (WE app targets Snapdragon 8 Gen 2+)
        ndk {
            abiFilters += "arm64-v8a"
        }
        // External native ETC2 encoder CMake args
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
                cppFlags += "-std=c++11 -fexceptions -frtti"
            }
        }
        // Docker image (35.0.0) — AGP 8.7.0 defaults to 34.0.0 which isn't
        // installed, and the builder user can't install it (no write access
        // to /opt/android-sdk/).
        // WE app targets Snapdragon 8 Gen 2+ — only arm64-v8a makes sense
        // Custom Application ID field so we can carry version metadata into the APK
        buildConfigField("String", "GIT_SHA", "\"${getGitSha()}\"")
        buildConfigField("String", "BUILD_TIME", "\"${getBuildTime()}\"")
        // Multidex not needed since we don't have 65k+ methods, but explicit
        multiDexEnabled = true
        // Required for very large APKs that include assets > 200 MB
        // (the Play Store cap doesn't apply to sideloaded APKs)
        // Use APK splits for asset packs in the future
    }

    // APK signing in CI: keystore is base64-decoded from a GitHub Secret
    // For local dev, debug signing is automatic via AGP.
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("WEPWAWET_KEYSTORE_PATH")
            val keystorePass = System.getenv("WEPWAWET_KEYSTORE_PASS") ?: ""
            val keyAlias = System.getenv("WEPWAWET_KEY_ALIAS") ?: ""
            val keyPass = System.getenv("WEPWAWET_KEY_PASS") ?: ""
            if (keystorePath != null && keystorePath.isNotBlank()) {
                storeFile = file(keystorePath)
                storePassword = keystorePass
                this.keyAlias = keyAlias
                keyPassword = keyPass
            }
        }
        // Stable debug keystore committed to the repo so all builds (local + CI)
        // are signed with the SAME key. Without this, each CI runner generates a
        // fresh debug keystore, so users would have to uninstall before each
        // upgrade — losing Steam credentials + refresh tokens.
        // The keystore is committed at app/debug.keystore with the standard
        // Android debug password "android" (well-known, not a secret).
        create("stableDebug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Use release signing if keystore is set; otherwise stable debug
            // (debug-signed APK is fine for sideloading — just not for Play Store)
            signingConfig = if (System.getenv("WEPWAWET_KEYSTORE_PATH") != null) {
                signingConfigs.findByName("release")
            } else {
                signingConfigs.findByName("stableDebug")
            }
            // Native libs are extracted at install time so they can be loaded directly
            // (required for the bundled ETC2 encoder and any future native components)
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Use the stable debug keystore so CI + local builds match
            signingConfig = signingConfigs.findByName("stableDebug")
            // Multi-APK not needed for debug builds
        }
    }

    // Compose
    packaging {
        resources {
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "/META-INF/*.SF"
            excludes += "/META-INF/*.DSA"
            excludes += "/META-INF/*.RSA"
            excludes += "/META-INF/MANIFEST.MF"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Java/Kotlin target — matches the JavaSteam + Android Gradle Plugin Java 17 baseline
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
                "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
                "-opt-in=androidx.media3.common.util.UnstableApi",
            )
        }
    }

    // Asset / jniLibs source sets (the bundled ETC2 encoder .so lives under jniLibs)
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    // Native build for ETC2 RGBA8 encoder (matches WE's resourcecompiler64.exe)
    // Builds Google's etc2comp as libwepwawet_etc2.so for arm64-v8a
    // Used by MpkgPacker.convertTexPngToEtc2() to encode PNG → ETC2 RGBA8
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Only build native for arm64-v8a (matches our APK ABI filter)
    ndkVersion = "27.0.12077973"  // NDK r27
}

dependencies {
    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Lifecycle / Activity / Navigation
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)

    // Image loading
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation("com.google.code.gson:gson:2.10.1")

    // JavaSteam — Java/Kotlin port of SteamKit2 for direct Steam protocol access
    // Downloads real workshop .pkg files via Steam's binary TCP protocol — NO PC required
    // (runs entirely on-device via Java/Kotlin, no server-side component)
    implementation(libs.javasteam.core)
    implementation(libs.javasteam.depotdownloader)
    implementation(libs.bouncycastleProvider)  // required by JavaSteam for crypto
    // protobuf-java — JavaSteam's generated message classes (e.g. CPublishedFile_QueryFiles_Response)
    // extend com.google.protobuf.GeneratedMessage / MessageOrBuilder / ProtocolMessageEnum. AGP doesn't
    // expose these supertypes transitively at compile time, so we add them explicitly. Without this,
    // WorkshopBrowseSession.kt fails with 'Cannot access GeneratedMessage which is a supertype of ...'.
    implementation(libs.protobufJava)
    // zstd-jni — required by JavaSteam for Zstd chunk decompression
    // MUST use AAR (not JAR) — the JAR only has Linux/macOS/Windows .so files
    // (compiled with glibc), which fail on Android with UnsatisfiedLinkError.
    // The AAR includes Android-compatible .so files for arm64-v8a.
    implementation(libs.zstdJni) { artifact { type = "aar" } }
    implementation(libs.xz)      // required by JavaSteam for LZMA chunk decompression
    implementation(libs.lz4Java)  // LZ4 compression for .tex files (WE's compression=1 format)

    // NOTE: FFmpegKit was removed (com.arthenica:ffmpeg-kit-full is no longer on
    // Maven Central — the project was archived in 2024). Video transcoding is
    // now done using Android's built-in MediaCodec API (hardware-accelerated,
    // no external dependency, handles VP8/VP9 → H.264 transcoding natively).
    // See VideoTranscoder.kt for the MediaCodec-based implementation.

    // WorkManager (for log persistence + background tasks)
    implementation(libs.androidx.work.runtime.ktx)

    // DataStore + encrypted prefs (for Steam credential storage)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}

// Helpers
fun getGitSha(): String {
    // Use GITHUB_SHA env var (always available in CI) — avoids Gradle 8.10+
    // blocking external process execution during configuration time.
    return System.getenv("GITHUB_SHA")?.take(7) ?: "unknown"
}

fun getBuildTime(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX")
    return sdf.format(Date())
}
