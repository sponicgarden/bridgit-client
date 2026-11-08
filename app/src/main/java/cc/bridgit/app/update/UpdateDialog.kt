package cc.bridgit.app.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BuildConfig
import cc.bridgit.app.R
import cc.bridgit.app.capability.appVersion
import cc.bridgit.app.ui.common.Fmt
import cc.bridgit.app.ui.common.qrBitmap
import cc.bridgit.app.ui.common.dp
import cc.bridgit.app.ui.common.themeColor
import cc.bridgit.app.update.UpdateLogic.Screen
import cc.bridgit.app.update.UpdateLogic.Problem

/**
 * The update dialogs (UPD-02, UPD-05): Checking · Up to date · Newer (Download / Not now) ·
 * Downloading (progress, Cancel) · Ready (Install) · Error (reason, OK). One flow at a time;
 * it is tied to the activity, so closing the activity cancels a check or download (and the
 * installer deletes the partial file). Rotation mid-download also cancels it.
 *
 * Every screen is the same card ([Card]): the Bridgit logo with a status badge, a heading, a line
 * of text and the version. "Up to date" offers the latest published APK as an install QR.
 * The informational "ahead" card fades away after [UpdateLogic.AUTO_CLOSE_MS].
 */
object UpdateDialog {
    private const val PREFS = "update"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_RESUME_AT = "resume_after_permission_at"
    private const val KEY_AUTO_OFFERS_OFF = "auto_offers_off"

    private var active: Flow? = null

    /**
     * Every debug build: the plain .debug one and each .test_claude_<feature> one. They are a
     * different package from the published cc.bridgit.app, so the published update would be
     * refused as "not valid"; they don't self-update at all (UPD-06).
     */
    val isDebugBuild: Boolean get() = BuildConfig.DEBUG

    /** The Google Play build never updates itself: Play does, and Play policy forbids self-updates. */
    val playUpdates: Boolean get() = BuildConfig.PLAY_STORE

    /** More → Check for updates and Settings → About (UPD-02). */
    fun checkForUpdates(activity: FragmentActivity, manual: Boolean) {
        if (playUpdates) {
            if (manual) openPlayListing(activity)
            return
        }
        if (isDebugBuild) {
            if (manual) MaterialAlertDialogBuilder(activity).also { b ->
                b.setView(Card.inflate(b.context, Badge.INFO, activity.getString(R.string.update_title),
                    activity.getString(R.string.update_debug), null).root)
            }.setPositiveButton(R.string.ok, null).show()
            return // UPD-06
        }
        if (!manual && active != null) return
        active?.close()
        prefs(activity).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        Flow(activity, manual).also { active = it }.start()
    }

    /** Bridgit's page in the Play Store app, or on the web when the Play Store app is missing. */
    private fun openPlayListing(activity: FragmentActivity) {
        val pkg = activity.packageName
        val market = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$pkg"))
        val web = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
        runCatching { activity.startActivity(market) }.recoverCatching { activity.startActivity(web) }
    }

    /**
     * Whether the daily check may offer a newer version by itself. The owner turns it off with
     * "Don't show again" on that offer, and back on in Settings. Check for updates always works.
     */
    fun autoOffersOn(ctx: Context): Boolean = !prefs(ctx).getBoolean(KEY_AUTO_OFFERS_OFF, false)

    fun setAutoOffers(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_OFFERS_OFF, !on).apply()
    }

    /** At most once a day when the app is opened; silent unless a newer version exists. */
    fun autoCheckIfDue(activity: FragmentActivity) {
        if (playUpdates || isDebugBuild || active != null) return
        val last = prefs(activity).getLong(KEY_LAST_CHECK, 0L)
        if (!UpdateLogic.isAutoCheckDue(System.currentTimeMillis(), last)) return
        // Automatic updates install without a tap when the phone allows it; otherwise offer as before.
        val app = activity.application as BridgeApp
        if (AutoUpdate.enabled(app) && AutoUpdate.canInstallSilently(app)) {
            prefs(activity).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            AutoUpdate.runInBackground(app, "app opened")
            return
        }
        if (!autoOffersOn(activity)) return
        checkForUpdates(activity, manual = false)
    }

    /**
     * Turning on "Install unknown apps" can restart Bridgit, which loses the Ready dialog.
     * If the owner went to that page from the dialog in the last 30 minutes and installs are now
     * allowed, run the check again (the download is verified again before Install).
     */
    fun resumeAfterPermission(activity: FragmentActivity) {
        if (playUpdates || isDebugBuild || active != null) return
        val at = prefs(activity).getLong(KEY_RESUME_AT, 0L)
        if (at == 0L) return
        if (System.currentTimeMillis() - at > 30 * 60_000L) { prefs(activity).edit().remove(KEY_RESUME_AT).apply(); return }
        if (!UpdateInstaller.canRequestInstalls(activity)) return
        prefs(activity).edit().remove(KEY_RESUME_AT).apply()
        checkForUpdates(activity, manual = true)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Shows one screen with sample data and no network, for device checks of the design.
     * Called only by the debug build's UpdatePreviewActivity (src/debug); [onClosed] runs when it closes.
     */
    fun preview(activity: FragmentActivity, screen: Screen, auto: Boolean = false, onClosed: () -> Unit) {
        active?.close()
        Flow(activity, manual = !auto, onClosed).also { active = it }.preview(screen)
    }

    private class Flow(val activity: FragmentActivity, val manual: Boolean,
                       val onClosed: () -> Unit = {}) : DefaultLifecycleObserver {
        private val app = activity.application as BridgeApp
        private var dialog: AlertDialog? = null
        private var job: Job? = null
        private var closed = false

        fun start() {
            activity.lifecycle.addObserver(this)
            UpdateInstaller.cleanStale(activity)
            if (manual) showProgress(Badge.NONE, activity.getString(R.string.update_title),
                activity.getString(R.string.update_checking), indeterminate = true) { close() }
            job = activity.lifecycleScope.launch {
                when (val r = UpdateInstaller.check(app)) {
                    is UpdateInstaller.Check.Newer -> showNewer(r.manifest)
                    is UpdateInstaller.Check.UpToDate ->
                        if (manual) showNothingToDo(Screen.UP_TO_DATE, activity.getString(R.string.update_current_title),
                            activity.getString(R.string.update_current_body),
                            activity.getString(R.string.update_version_pill, app.appVersion()), r.manifest)
                        else close()
                    is UpdateInstaller.Check.LocalAhead ->
                        if (manual) showNothingToDo(Screen.LOCAL_AHEAD, activity.getString(R.string.update_local_ahead_title),
                            activity.getString(R.string.update_local_ahead, app.appVersion(), r.manifest.versionName),
                            activity.getString(R.string.update_version_pill, app.appVersion()))
                        else close()
                    is UpdateInstaller.Check.Refused -> if (manual) showError(r.problem) else close()
                }
            }
        }

        fun close() {
            if (closed) return
            closed = true
            job?.cancel()
            dismissDialog()
            activity.lifecycle.removeObserver(this)
            if (active === this) active = null
            onClosed()
        }

        fun preview(screen: Screen) {
            activity.lifecycle.addObserver(this)
            val v = app.appVersion()
            val sample = UpdateLogic.Manifest(app.packageName, "9.9.999", 999_999L, "https://example.invalid/b.apk",
                "0".repeat(64), 31_400_000L, null, "Faster pairing and a clearer update screen.")
            when (screen) {
                Screen.CHECKING -> showProgress(Badge.NONE, activity.getString(R.string.update_title),
                    activity.getString(R.string.update_checking), indeterminate = true) { close() }
                Screen.UP_TO_DATE -> showNothingToDo(screen, activity.getString(R.string.update_current_title),
                    activity.getString(R.string.update_current_body), activity.getString(R.string.update_version_pill, v), sample)
                Screen.LOCAL_AHEAD -> showNothingToDo(screen, activity.getString(R.string.update_local_ahead_title),
                    activity.getString(R.string.update_local_ahead, v, "1.0.0"), activity.getString(R.string.update_version_pill, v))
                Screen.NEWER -> showNewer(sample)
                Screen.DOWNLOADING -> {
                    showProgress(Badge.DOWNLOAD, activity.getString(R.string.update_title),
                        activity.getString(R.string.update_downloading_pct, sample.versionName, 42), indeterminate = false,
                        version = activity.getString(R.string.update_version_change, v, sample.versionName),
                        hint = activity.getString(R.string.update_downloading_hint)) { close() }
                    progressBar?.setProgressCompat(42, false)
                }
                Screen.READY -> show(builder(Badge.CHECK, activity.getString(R.string.update_ready_title),
                    activity.getString(readyText(), sample.versionName),
                    activity.getString(R.string.update_version_pill, sample.versionName))
                    .setPositiveButton(R.string.update_install) { _, _ -> close() }
                    .setNegativeButton(R.string.update_not_now) { _, _ -> close() })
                Screen.ERROR -> showError(Problem.NETWORK)
                Screen.DEBUG -> show(builder(Badge.INFO, activity.getString(R.string.update_title),
                    activity.getString(R.string.update_debug), null).setPositiveButton(R.string.ok) { _, _ -> close() })
            }
        }

        override fun onDestroy(owner: LifecycleOwner) = close()

        private fun dismissDialog() {
            stopAutoClose()
            dialog?.setOnCancelListener(null)
            dialog?.setOnDismissListener(null)
            dialog?.dismiss()
            dialog = null
        }

        private fun show(b: MaterialAlertDialogBuilder, onCancel: () -> Unit = { close() }) {
            if (closed || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED) || activity.isFinishing) {
                close(); return
            }
            dismissDialog()
            card = pendingCard; pendingCard = null
            dialog = b.setOnCancelListener { onCancel() }.create().also { it.show() }
        }

        /** A builder whose content is a [Card]; [setup] fills the card. */
        private fun builder(badge: Badge, heading: String, text: String, version: String?,
                            setup: (Card) -> Unit = {}): MaterialAlertDialogBuilder {
            val b = MaterialAlertDialogBuilder(activity)
            val card = Card.inflate(b.context, badge, heading, text, version)
            setup(card)
            pendingCard = card
            return b.setView(card.root)
        }

        /** The card on screen, and the one [builder] made for the next [show]. */
        private var card: Card? = null
        private var pendingCard: Card? = null

        private fun message(title: Int, text: String) = show(
            builder(Badge.ERROR, activity.getString(title), text, null)
                .setPositiveButton(R.string.ok) { _, _ -> close() })

        private fun showError(p: Problem) = show(
            builder(Badge.ERROR, activity.getString(R.string.update_error_title), reason(p), null)
                .setPositiveButton(R.string.ok) { _, _ -> close() })

        /** Keep the install action available; only the informational "ahead" card auto-closes. */
        private fun showNothingToDo(screen: Screen, heading: String, text: String, version: String,
                                    manifest: UpdateLogic.Manifest? = null) {
            if (screen == Screen.UP_TO_DATE && manifest != null) {
                show(builder(Badge.CHECK, heading, text, version)
                    .setPositiveButton(R.string.update_install_qr) { _, _ -> showInstallQr(manifest) }
                    .setNegativeButton(R.string.ok) { _, _ -> close() })
                return
            }
            show(builder(Badge.CHECK, heading, text, version) { c ->
                c.root.contentDescription = "$heading. $text ${activity.getString(R.string.update_tap_to_close)}"
            })
            val c = card ?: return
            dialog?.setCanceledOnTouchOutside(true)
            c.root.setOnClickListener { close() }
            val after = UpdateLogic.autoCloseAfterMs(screen) ?: return
            // Screen-reader users can ask Android for longer timeouts; respect that.
            val am = activity.getSystemService(AccessibilityManager::class.java)
            val total = am?.getRecommendedTimeoutMillis(after.toInt(),
                AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_ICONS)?.toLong() ?: after
            startAutoClose(c, total)
        }

        /** The just-checked official manifest supplies the published APK, never a local version URL. */
        private fun showInstallQr(manifest: UpdateLogic.Manifest) {
            show(builder(Badge.DOWNLOAD, activity.getString(R.string.update_install_qr), "",
                activity.getString(R.string.update_version_pill, manifest.versionName)) { c ->
                c.text.isVisible = false
                c.root.findViewById<ImageView>(R.id.update_install_qr).apply {
                    setImageBitmap(qrBitmap(context, manifest.url, 720))
                    isVisible = true
                }
            }.setPositiveButton(R.string.ok) { _, _ -> close() })
        }

        private var countdownTick: Runnable? = null
        private var fading = false

        /** Ticks the countdown bar, then fades the card and its dim away and closes. */
        private fun startAutoClose(c: Card, totalMs: Long) {
            val bar = c.countdown
            bar.isVisible = true
            bar.progress = bar.max
            val startedAt = SystemClock.uptimeMillis()
            val tick = object : Runnable {
                override fun run() {
                    if (closed || card !== c) return
                    val elapsed = SystemClock.uptimeMillis() - startedAt
                    bar.progress = (UpdateLogic.countdownFraction(elapsed, totalMs) * bar.max).toInt()
                    if (elapsed >= totalMs) fadeOut() else bar.postDelayed(this, 40)
                }
            }
            countdownTick = tick
            bar.postDelayed(tick, 40)
        }

        private fun fadeOut() {
            val d = dialog ?: return close()
            val window = d.window ?: return close()
            if (fading) return
            fading = true
            val decor = window.decorView
            val startDim = window.attributes.dimAmount
            decor.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(UpdateLogic.FADE_MS)
                .setInterpolator(DecelerateInterpolator())
                .setUpdateListener { va ->
                    val lp = window.attributes
                    lp.dimAmount = startDim * (1f - va.animatedFraction)
                    window.attributes = lp
                }
                .withEndAction { close() }
                .start()
        }

        private fun stopAutoClose() {
            countdownTick?.let { card?.countdown?.removeCallbacks(it) }
            countdownTick = null
            if (fading) dialog?.window?.decorView?.animate()?.setUpdateListener(null)?.withEndAction(null)?.cancel()
            fading = false
            card = null
        }

        private fun showNewer(m: UpdateLogic.Manifest) {
            val text = buildString {
                append(activity.getString(R.string.update_available, m.versionName, app.appVersion()))
                m.notes?.let { append("\n\n").append(activity.getString(R.string.update_notes, it)) }
            }
            show(builder(Badge.DOWNLOAD, activity.getString(R.string.update_available_title), text,
                activity.getString(R.string.update_version_change, app.appVersion(), m.versionName)) { c ->
                c.hint.text = activity.getString(R.string.update_size, Fmt.bytes(m.sizeBytes)); c.hint.isVisible = true
            }
                .setPositiveButton(R.string.update_download) { _, _ -> download(m) }
                .setNegativeButton(R.string.update_not_now) { _, _ -> close() }
                .apply {
                    // Only the automatic offer can be silenced; a manual check is something the owner asked for.
                    if (!manual) setNeutralButton(R.string.update_dont_show_again) { _, _ ->
                        setAutoOffers(activity, false)
                        android.widget.Toast.makeText(activity, R.string.update_auto_off_toast, android.widget.Toast.LENGTH_LONG).show()
                        close()
                    }
                })
        }

        private var progressText: TextView? = null
        private var progressBar: LinearProgressIndicator? = null

        /** The card with a progress bar, an optional hint and [Cancel]. */
        private fun showProgress(badge: Badge, heading: String, text: String, indeterminate: Boolean,
                                 version: String? = null, hint: String? = null, onCancel: () -> Unit) {
            val b = builder(badge, heading, text, version) { c ->
                c.progress.isIndeterminate = indeterminate
                c.progress.isVisible = true
                c.hint.isVisible = hint != null
                c.hint.text = hint
                progressText = c.text; progressBar = c.progress
            }
            show(b.setNegativeButton(R.string.cancel) { _, _ -> onCancel() }, onCancel)
            dialog?.setCanceledOnTouchOutside(false)
        }

        private fun download(m: UpdateLogic.Manifest) {
            job?.cancel()
            showProgress(Badge.DOWNLOAD, activity.getString(R.string.update_title),
                activity.getString(R.string.update_downloading, m.versionName), indeterminate = false,
                version = activity.getString(R.string.update_version_change, app.appVersion(), m.versionName),
                hint = activity.getString(R.string.update_downloading_hint)) { close() }
            job = activity.lifecycleScope.launch {
                val r = UpdateInstaller.download(app, m) { pct ->
                    progressBar?.post {
                        if (closed) return@post
                        progressBar?.setProgressCompat(pct.coerceAtLeast(0), true)
                        progressText?.text = activity.getString(R.string.update_downloading_pct, m.versionName, pct)
                    }
                }
                when (r) {
                    is UpdateInstaller.Download.Ready -> showReady(r)
                    is UpdateInstaller.Download.Refused -> showError(r.problem)
                }
            }
        }

        private fun showReady(r: UpdateInstaller.Download.Ready) {
            progressText = null; progressBar = null
            show(builder(Badge.CHECK, activity.getString(R.string.update_ready_title),
                activity.getString(readyText(), r.manifest.versionName),
                activity.getString(R.string.update_version_pill, r.manifest.versionName))
                .setPositiveButton(R.string.update_install, null)
                .setNegativeButton(R.string.update_not_now) { _, _ -> r.file.delete(); close() }) {
                r.file.delete(); close()
            }
            // Keep the dialog open on Install, so a first tap that only opens the permission page can be repeated.
            dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { install(r) }
        }

        /** The Ready card's text: how this phone will install (ADB link, Android 12+ without Android's box, or Android's box). */
        private fun readyText(): Int = when {
            UpdateInstaller.adbInstallAvailable(app) -> R.string.update_ready_adb
            UpdateInstaller.sessionInstallAvailable() -> R.string.update_ready_direct
            else -> R.string.update_ready
        }

        private var adbTried = false
        private var sessionTried = false

        private fun install(r: UpdateInstaller.Download.Ready) {
            // With Bridgit's own ADB link, install without Android's confirm; any failure falls through to it.
            if (!adbTried && UpdateInstaller.adbInstallAvailable(app)) {
                adbTried = true
                showProgress(Badge.DOWNLOAD, activity.getString(R.string.update_title),
                    activity.getString(R.string.update_installing, r.manifest.versionName), indeterminate = true,
                    version = activity.getString(R.string.update_version_pill, r.manifest.versionName)) { close() }
                job = activity.lifecycleScope.launch {
                    when (UpdateInstaller.installViaAdb(app, r.file, r.manifest)) {
                        is UpdateInstaller.AdbInstall.Installed -> close()
                        is UpdateInstaller.AdbInstall.Fallback -> { showReady(r); install(r) }
                    }
                }
                return
            }
            if (!UpdateInstaller.canRequestInstalls(activity)) {
                UpdateInstaller.logInstallBlocked(app)
                MaterialAlertDialogBuilder(activity).also { b ->
                    b.setView(Card.inflate(b.context, Badge.INFO, activity.getString(R.string.update_allow_title),
                        activity.getString(R.string.update_allow), null).root)
                }
                    .setPositiveButton(R.string.update_open_settings) { _, _ ->
                        prefs(activity).edit().putLong(KEY_RESUME_AT, System.currentTimeMillis()).apply()
                        runCatching { activity.startActivity(UpdateInstaller.unknownSourcesIntent(activity)) }
                    }
                    .setNegativeButton(R.string.update_not_now, null)
                    .show()
                return
            }
            if (!r.file.exists()) { showError(Problem.STORAGE); return }
            // Android 12+: install without Android's own "Update this app?" box, so this branded card is the
            // only confirm. If Android insists on its box, show it; any failure falls through to the old handoff.
            if (!sessionTried && UpdateInstaller.sessionInstallAvailable()) {
                sessionTried = true
                showProgress(Badge.DOWNLOAD, activity.getString(R.string.update_title),
                    activity.getString(R.string.update_installing_direct, r.manifest.versionName), indeterminate = true,
                    version = activity.getString(R.string.update_version_pill, r.manifest.versionName)) { close() }
                job = activity.lifecycleScope.launch {
                    when (val s = UpdateInstaller.installViaSession(app, r.file, r.manifest)) {
                        is UpdateInstaller.SessionInstall.Installed -> close()
                        is UpdateInstaller.SessionInstall.NeedsConfirm -> {
                            runCatching { activity.startActivity(s.confirm) }
                            UpdateInstaller.logInstallHandoff(app, r.manifest)
                            dismissDialog(); close()
                        }
                        is UpdateInstaller.SessionInstall.Fallback -> { showReady(r); install(r) }
                    }
                }
                return
            }
            try {
                activity.startActivity(UpdateInstaller.installIntent(activity, r.file))
                UpdateInstaller.logInstallHandoff(app, r.manifest)
                // The file stays for the installer to read; it's removed after an hour (UPD-05).
                dismissDialog(); close()
            } catch (e: ActivityNotFoundException) {
                message(R.string.update_error_title, activity.getString(R.string.update_no_installer))
            } catch (e: IllegalArgumentException) {
                showError(Problem.STORAGE)
            }
        }

        private fun reason(p: Problem): String = activity.getString(when (p) {
            Problem.NETWORK -> R.string.update_err_network
            Problem.HTTP_STATUS -> R.string.update_err_http
            Problem.FORBIDDEN -> R.string.update_err_forbidden
            Problem.REDIRECT -> R.string.update_err_redirect
            Problem.BAD_MANIFEST, Problem.WRONG_PACKAGE, Problem.UNSAFE_URL, Problem.BAD_SHA256,
            Problem.BAD_SIZE -> R.string.update_err_manifest
            Problem.SIZE_MISMATCH, Problem.CHECKSUM -> R.string.update_err_mismatch
            Problem.NOT_AN_APK -> R.string.update_err_not_apk
            Problem.ARCHIVE_PACKAGE, Problem.ARCHIVE_VERSION -> R.string.update_err_wrong_build
            Problem.NOT_NEWER -> R.string.update_err_not_newer
            Problem.UNSIGNED, Problem.CERT_MISMATCH -> R.string.update_err_signature
            Problem.STORAGE -> R.string.update_err_storage
        })
    }

    /** The small round badge on the logo: what the card is about at a glance. */
    private enum class Badge(@DrawableRes val icon: Int, @AttrRes val fill: Int, @AttrRes val tint: Int) {
        CHECK(R.drawable.ic_check, R.attr.colorSuccess, com.google.android.material.R.attr.colorSurface),
        DOWNLOAD(R.drawable.ic_download, androidx.appcompat.R.attr.colorPrimary, com.google.android.material.R.attr.colorOnPrimary),
        ERROR(R.drawable.ic_error, androidx.appcompat.R.attr.colorError, com.google.android.material.R.attr.colorOnError),
        INFO(R.drawable.ic_info, androidx.appcompat.R.attr.colorPrimary, com.google.android.material.R.attr.colorOnPrimary),
        NONE(0, 0, 0),
    }

    /** The views of layout/update_card.xml. */
    private class Card(val root: View) {
        val text: TextView = root.findViewById(R.id.update_text)
        val hint: TextView = root.findViewById(R.id.update_hint)
        val progress: LinearProgressIndicator = root.findViewById(R.id.update_progress)
        val countdown: LinearProgressIndicator = root.findViewById(R.id.update_countdown)

        companion object {
            fun inflate(ctx: Context, badge: Badge, heading: String, text: String, version: String?): Card {
                val c = Card(LayoutInflater.from(ctx).inflate(R.layout.update_card, null, false))
                c.root.findViewById<TextView>(R.id.update_heading).text = heading
                c.text.text = text
                c.root.findViewById<TextView>(R.id.update_version).apply { isVisible = version != null; this.text = version }
                val b = c.root.findViewById<ImageView>(R.id.update_badge)
                if (badge == Badge.NONE) b.isVisible = false else {
                    b.setImageResource(badge.icon)
                    b.imageTintList = ColorStateList.valueOf(ctx.themeColor(badge.tint))
                    b.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(ctx.themeColor(badge.fill))
                        // A ring in the dialog's own colour so the badge sits "cut out" of the logo.
                        setStroke(ctx.dp(3), ctx.themeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh))
                    }
                }
                return c
            }
        }
    }
}
