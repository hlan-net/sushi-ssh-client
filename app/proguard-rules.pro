# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to keep.
# -keepclassmembers class fqcn.of.javascript.interface.for.webview {
#     public *;
# }

# Silence missing platform classes referenced by Apache HTTP/GSS code paths.
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn org.apache.http.**

# JSch loads crypto providers by reflection; keep these classes for release builds.
-keep class com.jcraft.jsch.DHEC* { *; }
-keep class com.jcraft.jsch.UserAuth* { *; }
-keep class com.jcraft.jsch.jce.** { *; }
-keep class com.jcraft.jsch.jcraft.** { *; }

# AndroidX test runner references Kotlin lazy helpers at runtime in minified builds.
-keep class kotlin.LazyKt { *; }
-keep class kotlin.LazyKt__* { *; }

# Kotlin instrumentation tests in minifiedDebug rely on string helper runtime classes.
-keep class kotlin.text.StringsKt { *; }
-keep class kotlin.text.StringsKt__* { *; }
