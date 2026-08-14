plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "io.tapper.firetv"

    /**
     * A fixed debug keystore, committed to the repository.
     *
     * Every CI run previously generated a fresh throwaway debug key, so each
     * build was signed by a different identity. Android refuses to upgrade an
     * app whose signature changed, which is why installing a new build over an
     * old one failed and only a full uninstall worked. Pinning the keystore
     * keeps the signature stable, so upgrades install over the top and keep
     * their data.
     *
     * This is a debug key only. It is not a release key and grants nothing
     * beyond signing sideloadable debug builds of this app.
     */
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    compileSdk = 34
    defaultConfig {
        applicationId = "io.tapper.firetv"
        // API 25 = Fire OS 6, which covers the Fire TV Stick 4K. Raising this to
        // 26 would drop that device. It is also why the app ships static font
        // files rather than the variable Archivo TTF: FontVariation needs 26.
        minSdk = 25
        targetSdk = 34
        versionCode = 31
        versionName = "0.14.3"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // BuildConfig.VERSION_NAME/VERSION_CODE power the "About" line in
    // Settings - the only reliable way to tell from the running app which
    // build is actually installed, short of an adb pull.
    buildFeatures { compose = true; buildConfig = true }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation(project(":shared"))
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.preview)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.hls)
    implementation(libs.media3.dash)
    implementation(libs.media3.ui)
    implementation(libs.coil.compose)
    implementation(libs.coroutines.android)
    // Keystore-backed storage for Xtream credentials. They are embedded in every
    // stream URL, so a plaintext copy on disk is a resellable subscription.
    implementation(libs.security.crypto)
    // Periodic background guide refresh: runs on its own schedule independent
    // of whether the app is open, with retry/backoff and network/battery
    // constraints handled by the platform instead of a coroutine tied to an
    // Activity's lifecycle.
    implementation(libs.work.runtime)
    // Reads a user-picked SAF folder's contents (custom logo folder override).
    implementation(libs.documentfile)
}
