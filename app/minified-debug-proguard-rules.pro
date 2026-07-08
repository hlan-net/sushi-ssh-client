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

# Keep Kotlin helpers required by AndroidX instrumentation startup in minifiedDebug.
-keep class kotlin.LazyKt { *; }
-keep class kotlin.LazyKt__* { *; }
-keep class kotlin.text.StringsKt { *; }
-keep class kotlin.text.StringsKt__* { *; }
-keep class kotlin.collections.CollectionsKt { *; }
-keep class kotlin.collections.CollectionsKt__* { *; }
