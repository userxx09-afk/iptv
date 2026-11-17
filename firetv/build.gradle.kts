plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
/**
 * OAuth client IDs for "Sign in with Google / Microsoft" under Shared watch
 * history. Read from environment variables (CI passes them from repository
 * secrets - see the workflow) or from ~/.gradle/gradle.properties for a local
 * build. Left empty, the app still builds; the sign-in buttons just explain
 * that cloud sign-in isn't set up. Setup steps: README, "Cloud sync setup".
 */
fun oauthSetting(name: String): String =
    (System.getenv(name)?.takeIf { it.isNotBlank() } ?: project.findProperty(name)?.toString() ?: "")
        .trim().replace("\"", "")

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
        /**
         * The private release key, when CI has one (repository secrets
         * RELEASE_KEYSTORE_BASE64 / RELEASE_KEYSTORE_PASSWORD /
         * RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD - see the workflow).
         * The committed debug key below is public, so anyone could sign an
         * APK that Android would accept as an update to this app; a private
         * key closes that. Switching keys needs one uninstall/reinstall on
         * each device, which is why it is opt-in.
         */
        val releaseStore = System.getenv("RELEASE_KEYSTORE_PATH")?.let { file(it) }
        if (releaseStore != null && releaseStore.exists()) {
            create("release") {
                storeFile = releaseStore
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
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
        // The build number is the GitHub Actions run number, so it rises on
        // every build by itself and matches the "build-N" release the APK is
        // published under. (It was a hand-edited constant, which is why
        // Settings kept saying build 32.) A local build outside CI falls back
        // to the constant.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 33
        // Bump this by hand when features change.
        versionName = "2.1.0"
        buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"${oauthSetting("GOOGLE_OAUTH_CLIENT_ID")}\"")
        buildConfigField("String", "GOOGLE_OAUTH_CLIENT_SECRET", "\"${oauthSetting("GOOGLE_OAUTH_CLIENT_SECRET")}\"")
        buildConfigField("String", "MICROSOFT_OAUTH_CLIENT_ID", "\"${oauthSetting("MICROSOFT_OAUTH_CLIENT_ID")}\"")
        // Trakt and OpenSubtitles stay hidden in the app until these are set
        // as repository secrets (see README, "Optional service keys").
        buildConfigField("String", "TRAKT_CLIENT_ID", "\"${oauthSetting("TRAKT_CLIENT_ID")}\"")
        buildConfigField("String", "TRAKT_CLIENT_SECRET", "\"${oauthSetting("TRAKT_CLIENT_SECRET")}\"")
        buildConfigField("String", "OPENSUBTITLES_API_KEY", "\"${oauthSetting("OPENSUBTITLES_API_KEY")}\"")
    }
    buildTypes {
        /**
         * What CI ships. Unlike the debug build it used to ship, this one is
         * not debuggable (no attaching a debugger or `run-as` to read the
         * app's files over adb), and R8 shrinks and obfuscates the code -
         * class and method names become meaningless letters, unused code is
         * removed, and Log calls are stripped (proguard-rules.pro).
         */
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    // Robolectric screenshot tests (src/test, SkinScreenshots). Only run by
    // the "Fire TV skin screenshots" workflow; the release build ignores them.
    testOptions.unitTests {
        isIncludeAndroidResources = true
        all { it.systemProperty("roborazzi.test.record", "true") }
    }
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
    // Activity's lifecycleget status.
    implementation(libs.work.runtime)
    // Reads a user-picked SAF folder's contents (custom logo folder override).
    implementation(libs.documentfile)

    // Screenshot tests only (see testOptions above); nothing here ships.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.26.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.26.0")
    testImplementation(platform(libs.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
