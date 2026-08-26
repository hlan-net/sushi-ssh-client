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

# JSch resolves nearly everything — ciphers, key exchange, KDFs, auth methods, channel
# types, compression — by Class.forName() on names held in its config map, so R8 sees no
# reference to any of it and strips the lot. Enumerating the classes was tried and kept
# losing races with it: the previous list missed com.jcraft.jsch.jbcrypt (encrypted
# OpenSSH keys failed with "kdf bcrypt is not available"), DHG14, DHGEX256, DHG16,
# DHEC256MLKEM768, DH25519MLKEM768 and CipherNone, and carried a rule for
# com.jcraft.jsch.jcraft, a package that does not exist in the artifact at all. The
# missing key-exchange classes only stayed hidden because current servers negotiate
# DH25519/DHEC256, which happened to be on the list — an older sshd offering
# diffie-hellman-group14-sha256 would have failed to connect in release builds only.
#
# Keeping the whole package costs some shrinking of a library this app is built around,
# and buys immunity to that entire class of bug.
-keep class com.jcraft.jsch.** { *; }
# Keeping the package also retains JSch's optional integrations, which reference libraries
# this app does not ship: slf4j/log4j logger adapters, the JNA-based Windows Pageant agent
# connector, and junixsocket for Unix-domain agent sockets. None are reachable on Android —
# the app never selects those loggers and never uses an external SSH agent.
-dontwarn org.slf4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn com.sun.jna.**
-dontwarn org.newsclub.net.unix.**
# JSch BC adapter references optional PQC classes (ML-KEM) that are not required for
# current host-key authentication paths used by the app.
-dontwarn org.bouncycastle.pqc.crypto.mlkem.**

# ML Kit GenAI Prompt API — keep all classes to prevent stripping of AICore bindings.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Native methods must keep their JNI-resolvable names.
-keepclasseswithmembernames class * { native <methods>; }

# LocalShellBackend is referenced by JNI symbol names (Java_net_hlan_sushi_LocalShellBackend_native*).
-keep class net.hlan.sushi.LocalShellBackend { *; }

# HostKind is serialized/deserialized by Moshi from persisted JSON ("SSH"/"LOCAL").
# Keep enum constant names stable in minified builds.
-keepclassmembers enum net.hlan.sushi.HostKind {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public static final net.hlan.sushi.HostKind SSH;
    public static final net.hlan.sushi.HostKind LOCAL;
}

