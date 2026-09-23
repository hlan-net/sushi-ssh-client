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

# The app APK's own code only calls a fraction of kotlinx.coroutines' public API, so R8 drops
# interface methods and internal helper classes it sees no reference to (first surfaced by
# ConversationScreenTest, the first createComposeRule() instrumented test: its compose-ui-test
# dependency still virtual-dispatches into the compile-time shape at the app APK's runtime
# copy). Chasing each stripped member one at a time just surfaces the next one —
# "NoSuchMethodError: No interface method complete()Z in class Lkotlinx/coroutines/CompletableJob",
# then "ClassNotFoundException: kotlinx.coroutines.DelayWithTimeoutDiagnostics" — so keep the
# whole package whole, the same trade CLAUDE.md documents for JSch in proguard-rules.pro.
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
