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

# The specific Compose/coroutines/kotlin-stdlib symbols ConversationScreenTest's instrumented
# run needed (interface default-method implementations, the Compose compiler's synthetic
# $stable field, etc.) turned out to be production surface ConversationScreen itself depends on
# at runtime, in any build type, so the targeted keeps for those now live in proguard-rules.pro
# instead of here. See proguard-rules.pro for the full history.
#
# What stays here, test-only: compose-ui-test's AndroidComposeUiTestEnvironment both
# virtual-dispatches into and Class.forName()-probes the compile-time shape of kotlin-stdlib,
# kotlinx.coroutines and androidx.compose more broadly than the narrow, by-name keeps in
# proguard-rules.pro cover — e.g. reflective probing that doesn't go through a $DefaultImpls
# class or one of the specific classes listed there. Shipping this blanket keep in a release
# build would undo the whole point of narrowing proguard-rules.pro (Copilot review, PR #197:
# it prevents R8 from shrinking these libraries at all), so it lives only in the variant that
# never ships. If a future dexdump/printusage pass on app-release-unsigned.apk shows the narrow
# rules are sufficient without this, remove it here too.
-keep class kotlin.** { *; }
-dontwarn kotlin.**
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

