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

# ConversationScreen (net.hlan.sushi.conversation) is this app's first real Compose UI, so
# these rules belong in the shared file, not minified-debug-proguard-rules.pro: this is
# production surface a release build renders for real users, not just what CI's
# ConversationScreenTest happens to exercise. Confirmed live production call sites exist
# already — MainActivity.kt calls androidx.lifecycle.compose.collectAsStateWithLifecycle()
# to collect ConversationViewModel.state — so a release-only proguard-rules.pro without these
# rules risks the same crashes a real user could hit, not just CI.
#
# The app's own code only calls a fraction of kotlin-stdlib's, kotlinx.coroutines' and
# androidx.compose's public API, so R8 drops interface default-method implementations
# ($DefaultImpls classes), bare top-level utility files (e.g. kotlin.ExceptionsKt) and internal
# helper classes it sees no direct reference to. First surfaced by ConversationScreenTest, the
# first createComposeRule() instrumented test, whose compose-ui-test dependency (running only
# in the minifiedDebug variant, never shipped) both virtual-dispatches into and
# Class.forName()-probes the compile-time shape at the app APK's runtime copy — but the actual
# gaps are in libraries ConversationScreen itself also depends on at runtime, in any variant.
# Chasing each stripped member one at a time just surfaced the next one, each unlocked by
# fixing the last and letting the test run further before crashing: CompletableJob.complete(),
# kotlinx.coroutines.DelayWithTimeoutDiagnostics, kotlin.time.AbstractLongTimeSource,
# androidx.compose.ui.platform.InfiniteAnimationPolicy$DefaultImpls,
# kotlin.coroutines.intrinsics.IntrinsicsKt once runTest() itself started executing, then
# kotlin.ExceptionsKt once setContent() itself started executing — narrowing to individual
# kotlin.* subpackages wasn't converging, so keep the whole kotlin.** stdlib, plus
# kotlinx.coroutines.** and androidx.compose.** (a printusage report, `-printusage`,
# temporarily, showed 83 more androidx.compose $DefaultImpls classes across the
# animation/foundation/runtime/ui artifacts in the same situation). The same trade already made
# above for JSch. Verified via `dexdump` on app-minifiedDebug.apk that all previously-stripped
# classes come back with full definitions, and via follow-up printusage reports that none of
# these packages have anything left fully removed.
-keep class kotlin.** { *; }
-dontwarn kotlin.**
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# createComposeRule()'s environment (AndroidComposeUiTestEnvironment.setContent) also hosts a
# full Activity+ViewModel+SavedState+back-handling integration, so it further needs the
# "*.compose" bridge subpackage each of those libraries ships — androidx.activity.compose
# (ComponentActivityKt.setContent, LocalActivity, BackHandler), androidx.lifecycle.compose /
# androidx.lifecycle.runtime.compose (collectAsStateWithLifecycle — used directly by
# MainActivity.kt in production), androidx.lifecycle.viewmodel.compose (viewModel()),
# androidx.savedstate.compose (rememberSaveable's Saver serializers) and
# androidx.navigationevent.compose (predictive back, which androidx.activity's BackHandler now
# delegates to). Verified via dexdump and a follow-up -printusage report that none of these six
# bridge packages have anything left fully removed.
-keep class androidx.activity.compose.** { *; }
-dontwarn androidx.activity.compose.**
-keep class androidx.lifecycle.compose.** { *; }
-dontwarn androidx.lifecycle.compose.**
-keep class androidx.lifecycle.runtime.compose.** { *; }
-dontwarn androidx.lifecycle.runtime.compose.**
-keep class androidx.lifecycle.viewmodel.compose.** { *; }
-dontwarn androidx.lifecycle.viewmodel.compose.**
-keep class androidx.savedstate.compose.** { *; }
-dontwarn androidx.savedstate.compose.**
-keep class androidx.navigationevent.compose.** { *; }
-dontwarn androidx.navigationevent.compose.**

# The Compose compiler generates a synthetic $stable static field on every class it processes
# for stability inference — including this app's own classes used as @Composable parameters
# (ConversationScreenActions, ConversationUiState, TranscriptItem, PendingConfirmation).
# Nothing in the app's own code reads $stable directly, so R8 drops it as apparently unused,
# but Compose's runtime composer reads it, in any build type, to decide whether to skip
# recomposition. First surfaced as NoSuchFieldError: No field $stable of type I in class
# Lnet/hlan/sushi/conversation/ConversationScreenActions once ConversationScreenTest actually
# composed ConversationScreen — but the composer reads this in production too, whenever a real
# user opens the conversation screen.
-keepclassmembers class ** {
    public static final int $stable;
}

# androidx.collection (IntSet, ScatterMap etc.) is Compose's own specialized-collection
# library. Compose's pointer-input dispatch machinery uses it to track active pointer IDs —
# both compose-ui-test's AndroidInputDispatcher (constructed by every performClick()/
# performTouchInput() call in tests) and, per Compose's own implementation, real gesture
# handling in production. The app's own code doesn't reference it, so R8 drops the methods
# dispatch needs — first surfaced as NoSuchMethodError: No static method
# intSetOf([I)Landroidx/collection/IntSet; once ConversationScreenTest's click-driving tests
# actually got far enough to simulate a click.
-keep class androidx.collection.** { *; }
-dontwarn androidx.collection.**

