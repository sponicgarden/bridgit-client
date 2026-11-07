package cc.bridgit.app.ui.applets

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.applets.AppletPortals
import cc.bridgit.app.ui.common.dp
import cc.bridgit.app.ui.common.themeColor
import cc.bridgit.app.ui.interactions.CardWebView
import cc.bridgit.app.ui.interactions.PiecesCardView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.R as M
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * An applet's portal (docs/spec-portals-and-cards.md): its page, full screen, run as the applet's own agent. The
 * person at the device uses it by touch; a controlling agent opens, reads and drives the same screen through
 * /v1/applets/{id}/portal ([AppletPortals]). An HTML page gets Bridgit.portal; a pieces page is drawn natively.
 */
class AppletPortalActivity : AppCompatActivity(), AppletPortals.Host {
    private val app get() = application as BridgeApp
    private lateinit var toolbar: MaterialToolbar
    private lateinit var content: FrameLayout
    private var instanceId: String? = null
    private var web: CardWebView? = null
    private var pieces: View? = null
    /** "An agent is using this page" (design pass, 5 Nov 2026): under the toolbar while calls wait, then 5 s. */
    private lateinit var agentStrip: TextView
    private val hideStrip = Runnable { agentStrip.visibility = View.GONE }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        enableEdgeToEdge()
        toolbar = MaterialToolbar(this).apply {
            minimumHeight = dp(64)
            setNavigationIcon(R.drawable.ic_back)
            setNavigationContentDescription(R.string.cd_back)
            setNavigationOnClickListener { finish() }
        }
        agentStrip = TextView(this).apply {
            setTextAppearance(cc.bridgit.app.R.style.TextAppearance_Bridgit_Label)
            textSize = 14f
            setTextColor(themeColor(M.attr.colorOnSurfaceVariant))
            setBackgroundColor(themeColor(M.attr.colorSurfaceContainer))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        // The page column: full width on phones, 600dp centred on tablets (list-screens.md).
        content = object : FrameLayout(this) {
            override fun onMeasure(w: Int, h: Int) {
                val cap = dp(MAX_WIDTH_DP)
                super.onMeasure(if (MeasureSpec.getSize(w) > cap) MeasureSpec.makeMeasureSpec(cap, MeasureSpec.EXACTLY) else w, h)
            }
        }
        val column = FrameLayout(this).apply { addView(content, FrameLayout.LayoutParams(-1, -1, android.view.Gravity.CENTER_HORIZONTAL)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColor(M.attr.colorSurface))
            addView(toolbar, LinearLayout.LayoutParams(-1, -2))
            addView(agentStrip, LinearLayout.LayoutParams(-1, -2))
            addView(column, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            v.updatePadding(left = bars.left, right = bars.right, bottom = maxOf(bars.bottom, ime))
            toolbar.updatePadding(top = bars.top)
            insets
        }
        setContentView(root)
        show(intent.getStringExtra(EXTRA_INSTANCE))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val id = intent.getStringExtra(EXTRA_INSTANCE)
        if (id != instanceId) show(id)
    }

    override fun onResume() { super.onResume(); instanceId?.let { app.appletPortals.onScreen(it, this, true) } }
    override fun onPause() { instanceId?.let { app.appletPortals.onScreen(it, this, false) }; super.onPause() }
    override fun onDestroy() { agentStrip.removeCallbacks(hideStrip); tearDown(); super.onDestroy() }

    private fun tearDown() {
        instanceId?.let { app.appletPortals.detach(it, this) }
        web?.release(); web = null
        pieces = null
        content.removeAllViews()
    }

    /** Shows the portal of [id]: its card is posted again first when it was closed. */
    private fun show(id: String?) {
        tearDown()
        instanceId = id
        val r = id?.let { app.applets.get(it) }
        val kind = r?.let { app.appletPortals.kind(it) }
        if (r == null || kind == null) { gone(); return }
        toolbar.title = r.optString("name")
        state(null, getString(R.string.applet_portal_loading), null)
        lifecycleScope.launch {
            val card = withContext(Dispatchers.IO) { runCatching { app.applets.openCard(id)?.let { app.interactions.get(it) } }.getOrNull() }
            if (instanceId != id || isFinishing) return@launch
            if (card == null) { gone(); return@launch }
            content.removeAllViews()
            app.appletPortals.attach(id, this@AppletPortalActivity, kind)
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) app.appletPortals.onScreen(id, this@AppletPortalActivity, true)
            if (kind == "html") {
                val html = card.html ?: run { gone(); return@launch }
                val v = CardWebView(this@AppletPortalActivity, onRespond = { data -> answer(card.id, data) }, onCrash = { crashed() },
                    interactionId = card.id, portal = app.appletPortals.bridge(id) { this@AppletPortalActivity.takeIf { web != null } })
                v.contentDescription = getString(R.string.applet_portal_desc, r.optString("name"))
                web = v
                content.addView(v, ViewGroup.LayoutParams(-1, -1))
                v.load(html)
            } else {
                val scroll = object : NestedScrollView(this@AppletPortalActivity) {
                    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                        if (ev.actionMasked == MotionEvent.ACTION_DOWN) app.appletPortals.touched(id)
                        return super.onInterceptTouchEvent(ev)
                    }
                }
                val v = PiecesCardView(this@AppletPortalActivity, app, card)
                val p = dp(16)
                scroll.addView(FrameLayout(this@AppletPortalActivity).apply { setPadding(p, p / 2, p, p); addView(v) })
                pieces = v
                content.addView(scroll, ViewGroup.LayoutParams(-1, -1))
            }
        }
    }

    /** Bridgit.respond on a portal answers the applet's card as it did in the Inbox; the page stays usable. */
    private fun answer(cardId: Long, data: Any) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { app.interactions.answerCard(cardId, data) } }
            web?.retryAnswer()
        }
    }

    private fun crashed() {
        web?.release(); web = null
        instanceId?.let { app.appletPortals.detach(it, this) }
        val id = instanceId
        state(getString(R.string.applet_portal_crashed_title), getString(R.string.applet_portal_crashed), getString(R.string.applet_portal_retry) to { show(id) })
    }

    private fun gone() = state(getString(R.string.applet_portal_gone_title), getString(R.string.applet_portal_gone), null)

    /**
     * Loading, unavailable and stopped (design pass): a centred 40dp neutral icon, a 16sp medium [title] (none while
     * loading), 14sp [body] in colorOnSurfaceVariant, then an optional tonal [action] and a text Back.
     */
    private fun state(title: String?, body: String, action: Pair<String, () -> Unit>?) {
        content.removeAllViews()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        col.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_nav_applets)
            imageTintList = android.content.res.ColorStateList.valueOf(themeColor(M.attr.colorOnSurfaceVariant))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        fun text(t: String, sp: Float, medium: Boolean, color: Int, top: Int) = col.addView(TextView(this).apply {
            text = t; textSize = sp; gravity = android.view.Gravity.CENTER; setTextColor(themeColor(color))
            if (medium) typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
        title?.let { text(it, 16f, true, M.attr.colorOnSurface, 16) }
        text(body, 14f, false, M.attr.colorOnSurfaceVariant, if (title == null) 16 else 4)
        if (title != null) {
            val bar = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER }
            action?.let { (label, go) -> bar.addView(com.google.android.material.button.MaterialButton(this, null, R.attr.bridgitPillTonalStyle).apply { text = label; setOnClickListener { go() } }) }
            bar.addView(com.google.android.material.button.MaterialButton(this, null, R.attr.bridgitPillTextStyle).apply {
                text = getString(R.string.applet_portal_back); setOnClickListener { finish() } },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            col.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        }
        content.addView(col, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.CENTER))
    }

    // ---- AppletPortals.Host -----------------------------------------------------------------------------------------

    override fun call(id: String, name: String, argsJson: String) { web?.callPortal(id, name, argsJson) }

    override fun text(done: (String) -> Unit) {
        web?.let { it.readText(done); return }
        done(pieces?.let { words(it) }.orEmpty())
    }

    override fun close() = finish()

    override fun agentBusy(busy: Boolean) {
        agentStrip.removeCallbacks(hideStrip)
        agentStrip.text = getString(if (busy) R.string.applet_portal_agent_busy else R.string.applet_portal_agent_done)
        agentStrip.visibility = View.VISIBLE
        if (!busy) agentStrip.postDelayed(hideStrip, AGENT_DONE_MS)
    }

    /** The words of a native pieces page, in reading order. */
    private fun words(v: View): String = buildString {
        fun walk(x: View) {
            if (x.visibility != View.VISIBLE) return
            if (x is TextView && x.text.isNotBlank()) appendLine(x.text)
            if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        walk(v)
    }.trim()

    companion object {
        const val EXTRA_INSTANCE = "instance_id"
        private const val MAX_WIDTH_DP = 600
        private const val AGENT_DONE_MS = 5000L
        fun intent(ctx: Context, instanceId: String): Intent = Intent(ctx, AppletPortalActivity::class.java)
            .putExtra(EXTRA_INSTANCE, instanceId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
