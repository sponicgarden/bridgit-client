package cc.bridgit.app.interact

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import cc.bridgit.app.BridgeApp

/**
 * Answers a question from its notification (CAP-33): a button action, or the inline reply of a text
 * control. Not exported: only Bridgit's own PendingIntents reach it. Never logs the reply text.
 */
class InteractionActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ACTION_ANSWER && action != ACTION_REPLY) return
        val id = intent.getLongExtra(InteractionNotifier.EXTRA_INTERACTION_ID, -1L)
        val controlId = intent.getStringExtra(InteractionNotifier.EXTRA_CONTROL_ID)
        if (id < 0 || controlId == null) return
        val reply = if (action == ACTION_REPLY)
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.trim() else null
        val app = context.applicationContext as BridgeApp
        val pending = goAsync()
        Thread {
            try {
                val answered = runCatching {
                    if (action == ACTION_ANSWER) app.interactions.answer(id, controlId, emptyMap())
                    else if (!reply.isNullOrEmpty()) app.interactions.answer(id, null, mapOf(controlId to reply))
                    else null
                }
                answered.getOrNull()?.let { app.log.system("audit", "interaction", "You answered ${it.agent}'s question from the notification") }
                answered.exceptionOrNull()?.let {
                    app.log.system("diagnostic", "interaction", "Couldn't answer interaction $id from the notification: ${it.message}", level = "warn")
                }
                // Clears the notification (and the inline-reply spinner) whether or not the answer was taken;
                // an empty reply leaves the question open.
                if (answered.isFailure || answered.getOrNull() != null) InteractionNotifier.cancel(context, id)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_ANSWER = "cc.bridgit.app.INTERACTION_ANSWER"
        const val ACTION_REPLY = "cc.bridgit.app.INTERACTION_REPLY"
        const val KEY_REPLY = "reply"

        fun answerIntent(ctx: Context, id: Long, buttonId: String) = Intent(ctx, InteractionActionReceiver::class.java)
            .setAction(ACTION_ANSWER).putExtra(InteractionNotifier.EXTRA_INTERACTION_ID, id).putExtra(InteractionNotifier.EXTRA_CONTROL_ID, buttonId)

        fun replyIntent(ctx: Context, id: Long, textId: String) = Intent(ctx, InteractionActionReceiver::class.java)
            .setAction(ACTION_REPLY).putExtra(InteractionNotifier.EXTRA_INTERACTION_ID, id).putExtra(InteractionNotifier.EXTRA_CONTROL_ID, textId)
    }
}
