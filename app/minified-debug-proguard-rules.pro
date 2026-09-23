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

# The Compose/coroutines/kotlin-stdlib keep rules ConversationScreenTest's instrumented run
# needed now live in proguard-rules.pro instead of here: what they fixed (interface
# default-method implementations, the Compose compiler's synthetic $stable field, etc.) turned
# out to be production surface ConversationScreen itself depends on at runtime, in any build
# type — not something specific to CI's compose-ui-test dependency. A release build without
# them would risk the exact same crashes for a real user opening the conversation screen. See
# proguard-rules.pro for the full history.

