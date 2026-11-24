package net.hlan.sushi.signin

import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import net.hlan.sushi.KeyboardInteractiveRequest
import net.hlan.sushi.conversation.SushiComposeTheme

/**
 * Mounts [KeyboardInteractiveDialog] into whichever activity is connecting, through a
 * `ComposeView` added to its content for as long as the dialog is up — so every screen that
 * connects through [net.hlan.sushi.DialogUserInfo] gets the dialog without UI code of its own.
 */
object KeyboardInteractiveDialogHost {

    /**
     * Shows the dialog for [request]; [onResult] receives the answers, or null on Cancel/Back,
     * exactly once. Must be called on the UI thread. Returns a function, also for the UI thread,
     * that takes the dialog down without a result — for a prompt that timed out.
     */
    fun show(
        activity: AppCompatActivity,
        request: KeyboardInteractiveRequest,
        onResult: (List<String>?) -> Unit
    ): () -> Unit {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val view = ComposeView(activity)
        var finished = false
        val remove = { (view.parent as? ViewGroup)?.removeView(view); Unit }
        val finish = { answers: List<String>? ->
            if (!finished) {
                finished = true
                remove()
                onResult(answers)
            }
        }
        view.setContent {
            SushiComposeTheme {
                KeyboardInteractiveDialog(
                    request = request,
                    onSubmit = { finish(it) },
                    onCancel = { finish(null) }
                )
            }
        }
        content.addView(
            view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        return {
            finished = true
            remove()
        }
    }
}
