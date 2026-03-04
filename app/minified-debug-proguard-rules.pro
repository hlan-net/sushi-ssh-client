# Keep test-only reset methods used by DeviceQaSuiteTest.
-keep class net.hlan.sushi.PhraseDatabaseHelper$Companion { void resetInstance(); }
-keep class net.hlan.sushi.PlayDatabaseHelper$Companion { void resetInstance(); }

# Keep Kotlin helpers required by AndroidX instrumentation startup in minifiedDebug.
-keep class kotlin.LazyKt { *; }
-keep class kotlin.LazyKt__* { *; }
-keep class kotlin.text.StringsKt { *; }
-keep class kotlin.text.StringsKt__* { *; }
-keep class kotlin.collections.CollectionsKt { *; }
-keep class kotlin.collections.CollectionsKt__* { *; }
