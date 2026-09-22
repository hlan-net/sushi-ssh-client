package net.hlan.sushi

import android.annotation.SuppressLint
import android.content.Context

/**
 * Process-wide Gemini Nano client, built once from the application context.
 *
 * `MainActivity` is recreated on every rotation; a client built from its own `by lazy` would be
 * a new object each time, while `ConversationViewModel` — which survives rotation via
 * `AppConversationEnvironment` — keeps whichever instance existed when it was first built. Two
 * Activity-scoped clients then diverge: the Activity's own status/warmup calls would run
 * against one `GeminiNanoClient` while conversation requests run against another, and the
 * discarded per-rotation instances are never closed (closing happens once, from
 * `ConversationViewModel.onCleared`, per `AppConversationEnvironment.close`). A single instance,
 * built once and shared by every caller, means there is only ever one to close and nothing to
 * diverge.
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
