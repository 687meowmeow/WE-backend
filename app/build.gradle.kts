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
        minSdk = 26  // Android 8.0 — matches Winlator v11.2 requirement
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1-alpha"
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
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Use release signing if keystore is set; otherwise debug-sign
            // (debug-signed APK is fine for sideloading — just not for Play Store)
            signingConfig = if (System.getenv("WEPWAWET_KEYSTORE_PATH") != null) {
                signingConfigs.findByName("release")
            } else {
                signingConfigs.findByName("debug")
            }
            // Native libs are extracted at install time so they can be loaded directly
            // (Winlator requires this for the Box64 binary that's exec'd)
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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

    // Java/Kotlin target — Winlator v11.2 uses Java 17
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

    // Large assets — the bundled rootfs, Wine prefix, Box64 binary, etc. all live here
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
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
    implementation("com.google.code.gson:gson:2.10.1")

    // JavaSteam — Java/Kotlin port of SteamKit2 for direct Steam protocol access
    // Downloads real workshop .pkg files via Steam's binary TCP protocol — NO PC required
    // (runs entirely on-device via Java/Kotlin, no server-side component)
    implementation(libs.javasteam.core)
    implementation(libs.javasteam.depotdownloader)
    implementation(libs.bouncycastleProvider)  // required by JavaSteam for crypto

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
