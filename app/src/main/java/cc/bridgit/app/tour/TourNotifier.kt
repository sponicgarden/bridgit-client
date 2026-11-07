package cc.bridgit.app.tour

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R

/**
 * The ongoing City tour notification (8.25 step 5): its own silent channel, "City tour with <Agent> · <kinds>",
 * tap opens the tour screen; actions Ask (inline reply, typed), Pause/Resume and End. A separate notification,
 * not the listener's foreground-service one. Updated on pause/resume at once and on caption changes at most
 * every [THROTTLE_MS]; removed when the tour ends.
 */
object TourNotifier {
    const val CHANNEL = "tour"
    const val NOTIF_ID = 6100
    /** Same string as MainActivity.EXTRA_OPEN_TOUR. */
    const val EXTRA_OPEN_TOUR = "open_tour"
    private const val THROTTLE_MS = 3_000L

    private data class Shown(val id: String, val agent: String, val kinds: List<String>, val paused: Boolean, val caption: String?)

    private fun shown(t: Tour) = Shown(t.id, t.agent, t.kinds, t.paused, t.caption)

    /** Runs for the app's lifetime (TourManager's scope). */
    suspend fun follow(app: BridgeApp, state: StateFlow<Tour?>) {
        var last: Shown? = null
        state.map { it?.let(::shown) }.distinctUntilChanged().collectLatest { s ->
            if (s == null) { cancel(app); last = null; return@collectLatest }
            val prev = last
            // Only the caption changed: wait, so a burst of narration updates the notification once.
            if (prev != null && prev.id == s.id && prev.paused == s.paused) delay(THROTTLE_MS)
            post(app, s)
            last = s
        }
    }

    /** Re-posts the current state now (clears the inline-reply spinner after Ask). */
    fun refresh(app: BridgeApp) {
        val t = app.tours.current()
        if (t == null) cancel(app) else post(app, shown(t))
    }

    fun cancel(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
    }

    private fun ensureChannel(ctx: Context, nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.tour_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = ctx.getString(R.string.tour_channel_desc)
            setSound(null, null)
            enableVibration(false)
        })
    }

    private fun post(ctx: Context, s: Shown) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(ctx, nm)
        if (!nm.areNotificationsEnabled()) return
        val kinds = s.kinds.joinToString(", ") { TourKinds.labels[it] ?: it }
        val open = PendingIntent.getActivity(ctx, NOTIF_ID,
            Intent().setClassName(ctx, "cc.bridgit.app.ui.MainActivity").putExtra(EXTRA_OPEN_TOUR, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = when {
            s.paused -> ctx.getString(R.string.tour_notif_paused)
            !s.caption.isNullOrBlank() -> s.caption
            else -> ctx.getString(R.string.tour_notif_waiting, s.agent)
        }
        // RemoteInput needs a mutable PendingIntent; only this one is.
        val ask = NotificationCompat.Action.Builder(0, ctx.getString(R.string.tour_notif_ask),
            PendingIntent.getBroadcast(ctx, NOTIF_ID + 1, TourActionReceiver.intent(ctx, TourActionReceiver.ACTION_ASK, s.id),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addRemoteInput(RemoteInput.Builder(TourActionReceiver.KEY_QUESTION)
                .setLabel(ctx.getString(R.string.tour_notif_ask_label, s.agent)).build())
            .setAllowGeneratedReplies(false)
            .build()
        val pauseAction = if (s.paused) TourActionReceiver.ACTION_RESUME else TourActionReceiver.ACTION_PAUSE
        val pause = PendingIntent.getBroadcast(ctx, NOTIF_ID + 2, TourActionReceiver.intent(ctx, pauseAction, s.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val end = PendingIntent.getBroadcast(ctx, NOTIF_ID + 3, TourActionReceiver.intent(ctx, TourActionReceiver.ACTION_END, s.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif).setColor(ctx.getColor(R.color.brand_primary))
            .setContentTitle(ctx.getString(R.string.tour_notif_title, s.agent, kinds))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(ask)
            .addAction(0, ctx.getString(if (s.paused) R.string.tour_resume else R.string.tour_pause), pause)
            .addAction(0, ctx.getString(R.string.tour_end), end)
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
    }
}

/**
 * The tour notification's actions. Not exported: only Bridgit's own explicit PendingIntents reach it.
 * Never logs the question text.
 */
class TourActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as BridgeApp
        val tourId = intent.getStringExtra(EXTRA_TOUR_ID) ?: return
        val action = intent.action
        val pending = goAsync()
        Thread {
            try {
                // A stale notification for an older tour never touches the current one.
                if (app.tours.current()?.id != tourId) { TourNotifier.refresh(app); return@Thread }
                when (action) {
                    ACTION_PAUSE -> app.tours.setPaused(true, tourId)
                    ACTION_RESUME -> app.tours.setPaused(false, tourId)
                    ACTION_END -> app.tours.endIfCurrent(tourId, context.getString(R.string.tour_end_reason))
                    ACTION_ASK -> {
                        val q = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_QUESTION)?.toString()?.trim()
                        if (!q.isNullOrEmpty()) runCatching { runBlocking { app.tours.ask(q, spoken = false) } }.onFailure {
                            app.log.system("diagnostic", "service", "Couldn't send the tour question: ${it.javaClass.simpleName}", level = "warn")
                        }
                        TourNotifier.refresh(app)
                    }
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_ASK = "cc.bridgit.app.TOUR_ASK"
        const val ACTION_PAUSE = "cc.bridgit.app.TOUR_PAUSE"
        const val ACTION_RESUME = "cc.bridgit.app.TOUR_RESUME"
        const val ACTION_END = "cc.bridgit.app.TOUR_END"
        const val EXTRA_TOUR_ID = "tour_id"
        const val KEY_QUESTION = "question"

        fun intent(ctx: Context, action: String, tourId: String) =
            Intent(ctx, TourActionReceiver::class.java).setAction(action).putExtra(EXTRA_TOUR_ID, tourId)
    }
}
