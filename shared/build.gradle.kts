import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
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
        }
        androidMain.dependencies {
            implementation(libs.coroutines.android)
        }
        // No iosMain sources yet — every existing file (XtreamClient,
        // TmdbClient, M3uParser, PlaybackDiagnosis, Redact, models) still
        // lives under androidMain because it's built on java.net.
        // HttpURLConnection and org.json, neither of which exist on
        // Kotlin/Native. Migrating that logic into commonMain (behind an
        // expect/actual HTTP + JSON boundary so it compiles for iOS too) is
        // the next phase of work, not part of this scaffolding step.
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
