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

# JSch loads channel types by reflection; keep SFTP channel for share-to-host uploads.
-keep class com.jcraft.jsch.ChannelSftp { *; }

# JSch loads crypto providers by reflection; keep these classes for release builds.
-keep class com.jcraft.jsch.DHEC256 { *; }
-keep class com.jcraft.jsch.DHEC384 { *; }
-keep class com.jcraft.jsch.DHEC521 { *; }
-keep class com.jcraft.jsch.DHECN { *; }
-keep class com.jcraft.jsch.DH25519 { *; }
-keep class com.jcraft.jsch.DH25519SNTRUP761 { *; }
-keep class com.jcraft.jsch.DH448 { *; }
-keep class com.jcraft.jsch.KeyPairEdDSA { *; }
-keep class com.jcraft.jsch.KeyPairEd25519 { *; }
-keep class com.jcraft.jsch.KeyPairEd448 { *; }
-keep class com.jcraft.jsch.KeyPairGenEdDSA { *; }
-keep class com.jcraft.jsch.SignatureEdDSA { *; }
-keep class com.jcraft.jsch.UserAuthNone { *; }
-keep class com.jcraft.jsch.UserAuthPassword { *; }
-keep class com.jcraft.jsch.UserAuthPublicKey { *; }
-keep class com.jcraft.jsch.UserAuthKeyboardInteractive { *; }
-keep class com.jcraft.jsch.UserAuthGSSAPIWithMIC { *; }
-keep class com.jcraft.jsch.jce.** { *; }
-keep class com.jcraft.jsch.jcraft.** { *; }
# Bouncy Castle implementations used by JSch for Ed25519 on Android API < 33.
-keep class com.jcraft.jsch.bc.** { *; }

# ML Kit GenAI Prompt API — keep all classes to prevent stripping of AICore bindings.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ListAdapter.getCurrentList() is called from instrumented tests against the minified build.
-keepclassmembers class * extends androidx.recyclerview.widget.ListAdapter {
    public java.util.List getCurrentList();
}
