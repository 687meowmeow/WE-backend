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
        // v0.7.1-alpha: UI cleanup pass — swap Settings card order, .pkg cache toggle,
        // square wallpaper preview, smaller favorite heart, favorites route placeholder,
        // verbose-logging now actually filters debug/info messages.
        // v0.7.1-alpha build 86: FavoritesScreen now shows creator avatars
        // + display names, WallpaperDetailScreen heart button replaced with
        // clickable Icon (no longer displaces text), WorkshopBrowserScreen
        // preserves search query when sort changes (re-runs search instead
        // of switching to browse), SteamApiService caches avatar URLs.
        // v0.7.1-alpha build 87: SEARCH BACKEND FALLBACK CHAIN — search now
        // uses the same 3-step chain as browse (JavaSteam → HTTP API → HTML
        // scrape) so the two paths return consistent results for the same
        // filters. Search also tracks consecutiveEmptySearchPages (separate
        // counter) so a restrictive filter that strips every page's items
        // doesn't stop the search on page 1. Creator row restructured so
        // the name + heart sit on the right (not all the way left). Heart
        // has its OWN clickable (parent Row no longer intercepts the tap,
        // so the heart actually turns red and shows a "favorited (user)"
        // snackbar at the bottom). Pagination bar compacted to 44dp (was
        // ~56dp+) with a clear "Go to page:" label.
        // v0.7.1-alpha build 88: XRAY GRAY IMAGE FIX + HEART ABOVE TEXT +
        // PAGINATION ARROWS FIX.
        //   1. MpkgPacker.fixShaders PHASE 0.5 condition was broken — only
        //      checked for cbuffer/SamplerState/Texture2D (full HLSL
        //      constructs) but NOT saturate()/lerp()/mul() (HLSL function
        //      calls). When the native transcoder (ShaderTranscoder.
        //      transcodeShader) failed (UnsatisfiedLinkError because
        //      glslang isn't compiled into the .so), PHASE 0.5 was SKIPPED
        //      entirely → shader still had `mul(vec4(...), matrix)` and
        //      `saturate(unprojectedUVs)` calls → WE Android's renderer
        //      couldn't compile the shader → wallpaper rendered as gray
        //      "invalid image". Fix: extend the condition to also include
        //      saturate(/lerp(/mul( detection (matching PHASE 0's isHlsl
        //      detection). Also wrote a NEW balanced-parens scanner
        //      (convertHlslFunctionCall) for mul/saturate/lerp because the
        //      old regex was broken for nested parens (`mul(vec4(...), mat)`
        //      would match arg1="vec4(...", arg2="...)..." — wrong).
        //   2. Heart layout — user said "the heart is displacing the text
        //      again. just make it above the text and to the left side of
        //      the creator name and next to it." Now the heart is on its
        //      OWN ROW ABOVE the Creator metadata row, aligned LEFT — so
        //      it doesn't push any other row's text. The Creator metadata
        //      row goes back to the standard MetadataRow layout (label
        //      left, value right, no heart).
        //   3. Pagination arrows — user said "the arrows to go to next
        //      page dont work. fix." Found TWO bugs:
        //      A) Pagination bar read `browseState` directly instead of
        //         `displayState`. In search mode, browseState is stale
        //         (last browse loaded) while searchState has the actual
        //         current page + hasMore for the search session. Clicking
        //         Next used stale browse state — search page 2 might return
        //         0 items → searchState=Done, but browseState was still
        //         Loaded(1, hasMore=true) so the bar STILL showed "Page 1"
        //         and Next was still enabled. Page number never advanced.
        //         Fix: use `displayState` (which accounts for search mode).
        //      B) Round 4's Box.clickable approach replaced IconButton to
        //         avoid the 48dp minimum touch target. In some Compose
        //         versions, Modifier.clickable on a Box without explicit
        //         interactionSource/indication may not propagate clicks
        //         reliably inside a Surface with shadowElevation. Revert
        //         to IconButton (built-in ripple + interaction source)
        //         with Modifier.size(36.dp) override. IconButton's
        //         minimumInteractiveComponentSize() adds INVISIBLE padding
        //         OUTSIDE the visual bounds — doesn't make the row taller.
        // v0.7.1-alpha build 89: PAIRING PERSISTENCE + XRAY STILL GRAY + HEART SMALLER.
        //   1. PAIRING PERSISTENCE — PairingIdentity.kt (new object):
        //      persistent GUID + RSA keypair in SharedPreferences. Was
        //      previously generating fresh GUID + RSA on every service
        //      restart → phone saw brand-new computer after every app
        //      launch/update and re-prompted for PIN. Now identity is
        //      stable across restarts/updates — the phone recognizes
        //      the same computer.
        //      WeDiscoveryBroadcaster.start(hostname, guid) — accepts
        //      persistent guid (was randomUUID()).
        //      WePairingServer.start(pin, keyPair?) — accepts a KeyPair
        //      (was always generating fresh); also adds reuseAddress +
        //      5x1s BindException retry for foreground-service restarts;
        //      onDisconnected callback fires after commandLoop returns
        //      ONLY IF currentClient === client (so newer pairings
        //      aren't cleared by older disconnect events).
        //      PairingService.onStartCommand — SMART RESTART: if the
        //      server is already running with the SAME PIN (e.g.
        //      MainActivity.onCreate + PairingScreen appearance),
        //      DON'T tear down the live phone connection — only restart
        //      the discovery broadcaster if it died. Full (re)start
        //      only on PIN change or first start. Pass persistent
        //      keypair from PairingIdentity. Wrap startForeground in
        //      try/catch (ForegroundServiceStartNotAllowedException on
        //      background restarts) and log instead of crashing.
        //      Notification text shows "Paired: <name>" if already
        //      paired (was always showing "WepWawet proxy running").
        //      onDisconnected clears pairedDeviceName + updates the
        //      notification to "WepWawet proxy running. PIN: <pin>"
        //      (persisted name in SharedPreferences is KEPT so the
        //      phone can reconnect without re-PIN-prompting).
        //   2. BootReceiver.kt (new BroadcastReceiver): listens for
        //      BOOT_COMPLETED + MY_PACKAGE_REPLACED, sets
        //      PairingService.appContext, calls PairingService.start.
        //      Combined with the RECEIVE_BOOT_COMPLETED permission and
        //      the `specialUse` foreground service type (NOT in the
        //      Android 15+ restricted-types list), the pairing service
        //      now auto-restarts after device reboot or app update.
        //   3. XRAY STILL GRAY — the round 5 fix only worked for
        //      xray.frag (has saturate but no mix). xray.vert has BOTH
        //      `mul()` (HLSL matrix mult — needs conversion) AND
        //      `mix()` (native GLSL lerp — no conversion needed).
        //      The old guard `(contains("mul(") && !contains("mix("))`
        //      evaluated to (true && !true) = false → PHASE 0 didn't
        //      detect xray.vert as HLSL → PHASE 0.5 also skipped →
        //      `mul(vec4(...), matrix)` calls left unconverted →
        //      shader failed to compile → gray "invalid image".
        //      Fix: remove the `!contains("mix(")` guard in BOTH
        //      PHASE 0 (isHlsl) and PHASE 0.5 (hasHlslSyntax). Just
        //      check `contains("mul(")` directly — the token is
        //      HLSL-specific, so a shader with `mul(` has HLSL syntax
        //      that needs conversion regardless of whether it also
        //      has `mix(`.
        //   4. HEART SMALLER (TASK 2): shrink heart icon per user
        //      request — Icon 28dp→20dp, inner tap Box requiredSize
        //      36dp→32dp, slot width 36dp→30dp, slot height stays 20dp.
        //      The structure (slot Box + inner tap Box with requiredSize
        //      that overflows + Icon centered) is preserved exactly —
        //      only the sizes change. Creator row stays exactly as
        //      tall as the other MetadataRow rows.
        // v0.7.1-alpha build 90: REVERT MpkgPacker.kt shader conversion —
        //   restore round 4 hotfix packing logic. My round 5/6 changes to
        //   fixShadersForMobile (convertHlslFunctionCall balanced-parens
        //   scanner + removing the !contains("mix(") guard) BROKE the
        //   Mordred (X-Ray) wallpaper which was working before. The WE
        //   Android renderer handles HLSL mul()/saturate() natively (it
        //   has an HLSL compatibility shim). Converting them to GLSL
        //   (vec*mat / clamp) made the shader fail to compile on WE
        //   Android → wallpaper rendered as gray "invalid image".
        //   Reverting to round 4 hotfix state: mul/saturate/lerp NOT
        //   converted (the !contains("mix(") guard prevents the regex
        //   from running on shaders that have both mul() and mix()).
        //   The other xray wallpaper (id=2347762662) remains gray — it
        //   was already gray before round 5, so the shader conversion
        //   was NOT the issue for that one. The pairing persistence,
        //   BootReceiver, heart-smaller, and all other round 6 changes
        //   are KEPT (only MpkgPacker.kt is reverted).
        versionCode = 92
        versionName = "0.7.1-alpha"
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

    // Media3 (ExoPlayer) — for video preview playback in the VideoTrimScreen.
    // The trim screen embeds a PlayerView via AndroidView to show a live video
    // preview while the user drags range sliders to set the trim start/end.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.common)

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
