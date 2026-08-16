import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    // Powers kotlinx.serialization's Json { } below — needed once JSON
    // parsing has to run in commonMain, where org.json (Android-only)
    // doesn't exist.
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // Fire TV / Android target. Behaves exactly like the old kotlin-android
    // setup this replaces — same source (now under src/androidMain/kotlin
    // instead of src/main/kotlin, which is the Kotlin Multiplatform plugin's
    // convention), same JVM target, nothing else changes for firetv/.
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // iOS targets: physical devices (arm64) and the Apple Silicon simulator.
    // No iosX64 (Intel simulator) target — every Mac capable of running
    // current Xcode is Apple Silicon at this point, and adding it back later
    // if ever needed is a one-line change.
    iosArm64()
    iosSimulatorArm64()

    // Bundles both iOS targets into a single .xcframework so the Xcode
    // project links against one artifact regardless of whether it's building
    // for a device or the simulator. This is what ipad/project.yml points at
    // (shared/build/XCFrameworks/release/TapperCore.xcframework). Named after
    // the shared module's package (io.tapper.core) rather than "shared",
    // since inside the Xcode project it's just a framework name, and
    // "shared" reads as meaningless there.
    val xcf = XCFramework("TapperCore")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "TapperCore"
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
            // Phase 2: TmdbClient's networking + JSON, now shared (XtreamClient
            // is still androidMain-only, on HttpURLConnection/org.json — a
            // later round). Ktor's client API and kotlinx.serialization's
            // JSON tree API are both pure-Kotlin/multiplatform, unlike the
            // java.net.HttpURLConnection + org.json they replace here (which
            // only ever existed on the JVM/Android). The actual socket/TLS
            // work is still done by a platform-native engine — see
            // net/HttpEngine.kt — this is just the one commonMain API both
            // engines plug into.
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            implementation(libs.coroutines.android)
            // OkHttp-backed engine. Chosen over Ktor's other JVM engines
            // (CIO, Java) because OkHttp already ships transitively via
            // Media3/ExoPlayer in firetv/, so this adds no new native code
            // to the APK, only Ktor's own (small) Kotlin wrapper around it.
            implementation(libs.ktor.client.okhttp)
        }
        // iosMain is auto-created by Kotlin's default source set hierarchy
        // template because both iosArm64() and iosSimulatorArm64() targets
        // are declared above — no manual dependsOn wiring needed for it to
        // exist or for iosArm64Main/iosSimulatorArm64Main to inherit from it.
        iosMain.dependencies {
            // Wraps NSURLSession. This is what makes TmdbClient (and,
            // eventually, XtreamClient) actually able to make a real network
            // call from Swift/iOS — there is no iOS equivalent of
            // java.net.HttpURLConnection, so without this (or hand-written
            // Objective-C interop, which is much harder to get right without
            // a compiler in this sandbox to check it against) that code
            // could never compile for iosArm64/iosSimulatorArm64 at all.
            implementation(libs.ktor.client.darwin)
        }
    }
}

android {
    namespace = "io.tapper.core"
    compileSdk = 34
    defaultConfig { minSdk = 25 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
