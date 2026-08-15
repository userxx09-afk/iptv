plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // shared/ is now a Kotlin Multiplatform module (Fire TV/Android + iOS
    // targets) so the iPad app can link its parser/client logic; firetv/
    // itself is untouched and still uses plain kotlin-android above.
    alias(libs.plugins.kotlin.multiplatform) apply false
}
