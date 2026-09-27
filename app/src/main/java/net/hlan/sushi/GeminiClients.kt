package net.hlan.sushi

import android.annotation.SuppressLint
import android.content.Context

/**
 * Process-wide Gemini Nano client, built once from the application context and never explicitly
 * closed — its lifecycle is the process's, reclaimed when the process dies.
 *
 * `MainActivity` is recreated on every rotation; a client built from its own `by lazy` would be
 * a new object each time, while `ConversationViewModel` — which survives rotation via
 * `AppConversationEnvironment` — keeps whichever instance existed when it was first built. Two
 * Activity-scoped clients then diverge: the Activity's own status/warmup calls would run
 * against one `GeminiNanoClient` while conversation requests run against another. A single
 * instance, built once and shared by every caller, means there is nothing to diverge.
 *
 * Nothing here closes [nano]'s result, and nothing should: an earlier version had
 * `AppConversationEnvironment.close()` close it from `ConversationViewModel.onCleared()`, on the
 * assumption that the environment was its sole owner. It isn't — the same singleton is shared
 * with whatever `MainActivity` resolves it next, including a second `MainActivity` in the same
 * process after the first is finished (not just rotated) rather than recreated. Closing it from
 * one caller left every later caller in that process with an already-closed `GenerativeModel`,
 * since [nano] has no way to tell the two apart and keeps returning the same instance either way.
 *
 * `GeminiClient` (the cloud path) is not given the same treatment: it holds no closeable
 * resource and reads its settings fresh on every call, so a new instance per rotation is
 * harmless.
 */
object GeminiClients {
    // Lint's StaticFieldLeak can't see past GeminiNanoClient's constructor to know the Context
    // it stores is always applicationContext: [nano] is this object's one call site, and it
    // passes nothing else. Storing an application (not Activity) context statically for the
    // process's lifetime is exactly the safe case that check exists to distinguish.
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var nanoClient: GeminiNanoClient? = null

    fun nano(context: Context): GeminiNanoClient =
        nanoClient ?: synchronized(this) {
            nanoClient ?: GeminiNanoClient(context.applicationContext).also { nanoClient = it }
        }
}
