# R8 rules for the shipped (release) build. Libraries in use - Compose,
# Media3, Coil, OkHttp, Ktor, kotlinx.serialization, WorkManager,
# security-crypto - ship their own consumer rules; this file only covers
# what they don't, plus the app's own choices.

# Strip logging from shipped builds. Logcat is readable over adb, and stream
# URLs carry subscription credentials.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# Obfuscate more aggressively: move everything into one package and drop
# source file names, keeping line numbers for crash reports only.
-repackageclasses ''
-allowaccessmodification
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# Annotations referenced by Tink (inside security-crypto) and by Ktor that
# aren't on the runtime classpath. Compile-time only; safe to ignore.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# kotlinx.serialization: keep generated serializers for @Serializable types
# in the shared module (the library's own rules cover most of this; these
# make it explicit for the companion lookups).
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class io.tapper.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# smbj (recordings on a network share). Its event bus (mbassador) finds
# handlers by annotation at runtime, and its packet classes are looked up by
# reflection, so keep both whole. Kerberos/GSS-API is JVM-only and unused here
# (we sign in with NTLM user/password or guest).
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-dontwarn com.hierynomus.**
-dontwarn net.engio.mbassy.**
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.kerberos.**
-dontwarn javax.security.auth.login.**
-dontwarn javax.security.auth.callback.**
