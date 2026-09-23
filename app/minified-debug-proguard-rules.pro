# Keep test-only reset methods used by DeviceQaSuiteTest.
-keep class net.hlan.sushi.PhraseDatabaseHelper$Companion { void resetInstance(); }
-keep class net.hlan.sushi.PlayDatabaseHelper$Companion { void resetInstance(); }

# TerminalSessionHolder.getActiveSshClient() and getActiveConfig() have no production callers;
# R8 removes them as dead code. Keep for instrumented test access.
-keepclassmembers class net.hlan.sushi.TerminalSessionHolder {
    public net.hlan.sushi.SshClient getActiveSshClient();
    public net.hlan.sushi.SshConnectionConfig getActiveConfig();
}

# ConversationResult.userMessage is only written (constructor), never read, in production code;
# R8 removes its generated getter. Keep all public members for instrumented test assertions.
-keepclassmembers class net.hlan.sushi.ConversationResult {
    public *;
}

# GeminiTranscriptSessionSummary.lastActivityAt has no production reader (only startedAt is
# displayed); R8 removes the getter. Keep all public members for test assertions.
-keepclassmembers class net.hlan.sushi.GeminiTranscriptSessionSummary {
    public *;
}

# GeminiTranscriptDatabaseHelper.resetInstance() (companion) and clearAll() (instance) have no
# production callers; R8 removes them. Keep for test setUp/tearDown.
-keepclassmembers class net.hlan.sushi.GeminiTranscriptDatabaseHelper {
    public int clearAll();
}
-keep class net.hlan.sushi.GeminiTranscriptDatabaseHelper$Companion { void resetInstance(); }

# CommandHistoryDatabaseHelper.resetInstance() (companion) and countForHost() have no
# production callers; R8 removes them. Keep for test setUp/tearDown and assertions.
-keepclassmembers class net.hlan.sushi.CommandHistoryDatabaseHelper {
    public int clearAll();
    public int countForHost(java.lang.String);
}
-keep class net.hlan.sushi.CommandHistoryDatabaseHelper$Companion { void resetInstance(); }

# CommandHistoryRecord/CommandHistoryHost accessors are read by instrumented test assertions;
# R8 inlines or drops the ones production only writes. Keep all public members.
-keepclassmembers class net.hlan.sushi.CommandHistoryRecord {
    public *;
}
-keepclassmembers class net.hlan.sushi.CommandHistoryHost {
    public *;
}

# The test APK resolves app R classes at runtime (androidTest R fields are non-final
# field references, not inlined constants). R8 strips R classes from the app APK,
# breaking e.g. LayoutInflationTest with NoClassDefFoundError: R$style.
-keep class net.hlan.sushi.R$* { *; }

# The app APK's own code only calls a fraction of kotlin-stdlib's, kotlinx.coroutines' and
# androidx.compose's public API, so R8 drops interface default-method implementations
# ($DefaultImpls classes), bare top-level utility files (e.g. kotlin.ExceptionsKt) and internal
# helper classes it sees no direct reference to (first surfaced by ConversationScreenTest, the
# first createComposeRule() instrumented test: its compose-ui-test dependency — specifically
# kotlinx-coroutines-test's runTest, which it uses internally — both virtual-dispatches into and
# Class.forName()-probes the compile-time shape at the app APK's runtime copy; androidTest APKs
# don't duplicate classes already shipped in the tested app APK, so compose-ui-test's calls
# resolve against this module's own shrunk copies of these libraries). Chasing each stripped
# member one at a time just surfaced the next one, each unlocked by fixing the last and letting
# the test run further before crashing: CompletableJob.complete(),
# kotlinx.coroutines.DelayWithTimeoutDiagnostics, kotlin.time.AbstractLongTimeSource,
# androidx.compose.ui.platform.InfiniteAnimationPolicy$DefaultImpls,
# kotlin.coroutines.intrinsics.IntrinsicsKt once runTest() itself started executing, then
# kotlin.ExceptionsKt once setContent() itself started executing — narrowing to individual
# kotlin.* subpackages wasn't converging, so keep the whole kotlin.** stdlib (this also
# subsumes the narrower kotlin.LazyKt/StringsKt/CollectionsKt rules this replaces), plus
# kotlinx.coroutines.** and androidx.compose.** (a printusage report, `-printusage`,
# temporarily, showed 83 more androidx.compose $DefaultImpls classes across the
# animation/foundation/runtime/ui artifacts in the same situation). The same trade CLAUDE.md
# documents for JSch in proguard-rules.pro. Verified via `dexdump` on app-minifiedDebug.apk
# that all six previously-stripped classes come back with full definitions, and via a
# follow-up printusage report that kotlin., kotlinx.coroutines. and androidx.compose. have
# nothing left fully removed.
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
# androidx.lifecycle.runtime.compose (collectAsStateWithLifecycle), androidx.lifecycle.viewmodel.compose
# (viewModel()), androidx.savedstate.compose (rememberSaveable's Saver serializers) and
# androidx.navigationevent.compose (predictive back, which androidx.activity's BackHandler now
# delegates to). The app's own code uses these libraries' non-"*.compose" surface directly
# (ComponentActivity, ViewModel, SavedStateHandle), so only the bridge packages needed keeping —
# first surfaced as NoClassDefFoundError on androidx.activity.compose.ComponentActivityKt.
# Verified via dexdump and a follow-up -printusage report (not committed) that none of these
# six bridge packages have anything left fully removed.
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
# but Compose's runtime composer does read it to decide whether to skip recomposition. First
# surfaced as NoSuchFieldError: No field $stable of type I in class
# Lnet/hlan/sushi/conversation/ConversationScreenActions once ConversationScreenTest actually
# composed ConversationScreen — the eighth stripped-class round in this same class of issue,
# and the first in the app's own code rather than a library's.
-keepclassmembers class ** {
    public static final int $stable;
}


