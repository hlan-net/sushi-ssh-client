# Keep only Kotlin runtime helpers required by minified instrumentation tests.
-keep class kotlin.LazyKt { *; }
-keep class kotlin.LazyKt__* { *; }
-keep class kotlin.text.StringsKt { *; }
-keep class kotlin.text.StringsKt__* { *; }
-keep class kotlin.collections.CollectionsKt { *; }
-keep class kotlin.collections.CollectionsKt__* { *; }

# Required by Espresso internals in minified instrumentation tests.
-keep class com.google.common.util.concurrent.** { *; }
