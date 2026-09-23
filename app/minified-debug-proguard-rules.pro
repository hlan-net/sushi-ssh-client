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

# Keep Kotlin helpers required by AndroidX instrumentation startup in minifiedDebug.
-keep class kotlin.LazyKt { *; }
-keep class kotlin.LazyKt__* { *; }
-keep class kotlin.text.StringsKt { *; }
-keep class kotlin.text.StringsKt__* { *; }
-keep class kotlin.collections.CollectionsKt { *; }
-keep class kotlin.collections.CollectionsKt__* { *; }

# The app APK's own code only calls a fraction of kotlinx.coroutines', kotlin.time's, and
# androidx.compose's public API, so R8 drops interface default-method implementations
# ($DefaultImpls classes) and internal helper classes it sees no direct reference to (first
# surfaced by ConversationScreenTest, the first createComposeRule() instrumented test: its
# compose-ui-test dependency both virtual-dispatches into and Class.forName()-probes the
# compile-time shape at the app APK's runtime copy — androidTest APKs don't duplicate classes
# already shipped in the tested app APK, so compose-ui-test's calls resolve against this
# module's own shrunk copies of these three libraries). Chasing each stripped member one at a
# time just surfaced the next one across three separate libraries — CompletableJob.complete(),
# then kotlinx.coroutines.DelayWithTimeoutDiagnostics, then kotlin.time.AbstractLongTimeSource,
# then androidx.compose.ui.platform.InfiniteAnimationPolicy$DefaultImpls — and a printusage
# report (`-printusage`, temporarily) showed 83 more androidx.compose $DefaultImpls classes
# across the animation/foundation/runtime/ui artifacts in the same situation. Keep all three
# packages whole rather than keep discovering this one CI run at a time, the same trade
# CLAUDE.md documents for JSch in proguard-rules.pro. Verified via `dexdump` on
# app-minifiedDebug.apk that all four previously-stripped classes come back with full
# definitions.
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-keep class kotlin.time.** { *; }
-dontwarn kotlin.time.**
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
