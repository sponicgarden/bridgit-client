package cc.bridgit.app.ui.firstrun

import android.Manifest
import android.content.res.Configuration
import android.os.Bundle
import android.text.TextUtils
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.R as M
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.R
import cc.bridgit.app.capability.Perms
import cc.bridgit.app.capability.granted
import cc.bridgit.app.databinding.FirstrunCapRowBinding
import cc.bridgit.app.databinding.FirstrunPageBinding
import cc.bridgit.app.databinding.FragmentFirstRunBinding
import cc.bridgit.app.ui.common.themeColor
import cc.bridgit.app.ui.home.UiActions

/**
 * First run (UI-08, ui-spec 5.1): four pages, each sized to fit one phone screen
 * without scrolling: an HTML intro with ideas, what Bridgit can do, a live Tailscale
 * check with the notifications request, then the key warning with Run diagnostic or Add agent.
 */
class FirstRunFragment : Fragment() {
    private var _b: FragmentFirstRunBinding? = null
    private val b get() = _b!!
    private val app get() = requireActivity().application as BridgeApp

    /** null = still checking; empty = not connected. */
    private var tsAddresses: List<String>? = null
    private var notifDenied = false
    /** Android's own Allow dialog opens by itself once, when the setup page first shows (owner, 4 Nov 2026). */
    private var notifAutoAsked = false
    private var page2: FirstrunPageBinding? = null

    private val notifRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifDenied = !granted
        bindNotifications()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentFirstRunBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom); insets
        }
        b.pager.adapter = Pages()
        TabLayoutMediator(b.dots, b.pager) { tab, _ -> tab.view.isClickable = false }.attach()
        b.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (_b == null) return
                b.skip.visibility = if (position < PAGES - 1) View.VISIBLE else View.INVISIBLE
                b.next.isVisible = position < PAGES - 1
                if (position == SETUP_PAGE) autoAskNotifications()
                @Suppress("DEPRECATION")
                b.pager.announceForAccessibility(getString(R.string.firstrun_page, position + 1, PAGES))
            }
        })
        b.skip.setOnClickListener { b.pager.currentItem = PAGES - 1 }
        b.next.setOnClickListener { b.pager.currentItem = (b.pager.currentItem + 1).coerceAtMost(PAGES - 1) }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val p = _b?.pager ?: return
                if (p.currentItem > 0) p.currentItem = p.currentItem - 1 else requireActivity().finish()
            }
        })

        // Live Tailscale check while the screen is visible.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val found = withContext(Dispatchers.IO) { BridgeService.tailscaleAddresses() }
                    if (found != tsAddresses) { tsAddresses = found; bindTailscale() }
                    delay(3000)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        bindNotifications() // the user may have changed it in Android settings
    }

    override fun onDestroyView() {
        super.onDestroyView()
        page2 = null
        _b = null
    }

    private fun finish(addAgent: Boolean) {
        app.settings.firstRunDone = true
        val nav = findNavController()
        if (addAgent) nav.navigate(R.id.addAgent, null, NavOptions.Builder().setPopUpTo(R.id.firstRun, true).build())
        else if (!nav.popBackStack(R.id.home, false))
            nav.navigate(R.id.home, null, NavOptions.Builder().setPopUpTo(R.id.firstRun, true).build())
    }

    private fun bindTailscale() {
        val p = page2 ?: return
        val ctx = requireContext()
        val ts = tsAddresses
        // Only shown when Tailscale needs fixing; "connected" and "checking" need no line (owner, 4 Nov 2026).
        p.tsCard.isVisible = ts != null && ts.isEmpty()
        p.tsProgress.isVisible = ts == null
        p.tsIcon.isVisible = ts != null
        p.openTailscale.isVisible = ts != null && ts.isEmpty()
        when {
            ts == null -> p.tsText.text = getString(R.string.firstrun_ts_checking)
            ts.isEmpty() -> {
                val installed = cc.bridgit.app.ui.home.UiActions.isTailscaleInstalled(ctx)
                p.tsText.text = getString(if (installed) R.string.firstrun_ts_missing else R.string.home_banner_tailscale_missing)
                p.openTailscale.text = getString(
                    if (!installed && cc.bridgit.app.update.TailscaleInstaller.available()) R.string.firstrun_install_tailscale
                    else R.string.home_banner_setup_tailscale)
                p.tsIcon.setImageResource(R.drawable.ic_warning)
                p.tsIcon.setColorFilter(ctx.themeColor(M.attr.colorError))
            }
            else -> {
                val ip = ts.firstOrNull { !it.contains(':') } ?: ts.first()
                val text = getString(R.string.firstrun_ts_connected, ip)
                val start = text.indexOf(ip)
                p.tsText.text = SpannableString(text).apply {
                    if (start >= 0) setSpan(TypefaceSpan("monospace"), start, start + ip.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                p.tsIcon.setImageResource(R.drawable.ic_check)
                p.tsIcon.setColorFilter(ctx.themeColor(R.attr.colorSuccess))
            }
        }
    }

    /** Skips the extra tap on "Allow notifications": the system dialog comes up as the page opens. */
    private fun autoAskNotifications() {
        if (notifAutoAsked || notifDenied) return
        val ctx = context ?: return
        if (ctx.granted(Manifest.permission.POST_NOTIFICATIONS)) return
        if (Perms.platform(Manifest.permission.POST_NOTIFICATIONS) == null) return // before Android 13 there's no dialog
        notifAutoAsked = true
        notifRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun bindNotifications() {
        val p = page2 ?: return
        val ctx = context ?: return
        val granted = ctx.granted(Manifest.permission.POST_NOTIFICATIONS) && app.captureNotificationsOn()
        // Nothing to say when they're on: the block only shows when the owner has something to do.
        p.notifBlock.isVisible = !granted
        p.allowNotif.isVisible = !granted && !notifDenied
        p.notifOn.isVisible = granted
        if (granted) {
            val check = ContextCompat.getDrawable(ctx, R.drawable.ic_check)?.mutate()?.apply { setTint(ctx.themeColor(R.attr.colorSuccess)) }
            p.notifOn.setCompoundDrawablesRelativeWithIntrinsicBounds(check, null, null, null)
        }
        // Denied: the line becomes a warning banner with Open settings; Next stays enabled.
        val warn = !granted && notifDenied
        p.notifSettings.isVisible = warn
        p.notifCard.setCardBackgroundColor(if (warn) ctx.themeColor(M.attr.colorSecondaryContainer) else 0)
        p.notifInner.setPadding(if (warn) ctx.resources.getDimensionPixelSize(R.dimen.firstrun_banner_pad) else 0, if (warn) ctx.resources.getDimensionPixelSize(R.dimen.firstrun_banner_pad) else 0,
            if (warn) ctx.resources.getDimensionPixelSize(R.dimen.firstrun_banner_pad) else 0, 0)
        p.notifLine.setTextColor(ctx.themeColor(if (warn) M.attr.colorOnSecondaryContainer else M.attr.colorOnSurface))
    }

    /** Page 1 is a styled HTML page; strings and theme colours are filled in here so it matches the app. */
    private fun showIntro(p: FirstrunPageBinding) {
        val web = p.introWeb
        if (web.tag == INTRO_LOADED) return
        val ctx = web.context
        web.setBackgroundColor(ctx.themeColor(M.attr.colorSurface))
        web.settings.javaScriptEnabled = false
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.isVerticalScrollBarEnabled = false
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        fun hex(attr: Int) = String.format("#%06X", ctx.themeColor(attr) and 0xFFFFFF)
        val headline = TextUtils.htmlEncode(getString(R.string.firstrun_p1_headline))
            .replace("\n", "<br>")
            .replace("super-human", "<span class=\"accent\">super-human</span>")
        val ideas = resources.getStringArray(R.array.firstrun_ideas).joinToString("") { "<li><span class=\"q\">“</span>${TextUtils.htmlEncode(it)}<span class=\"q\">”</span></li>" }
        val html = ctx.assets.open("firstrun/intro.html").bufferedReader().use { it.readText() }
            .replace("{{MODE}}", if (night) "dark" else "light")
            .replace("{{BG}}", hex(M.attr.colorSurface))
            .replace("{{FG}}", hex(M.attr.colorOnSurface))
            .replace("{{MUTED}}", hex(M.attr.colorOnSurfaceVariant))
            .replace("{{PRIMARY}}", hex(M.attr.colorPrimary))
            .replace("{{CARD}}", hex(M.attr.colorSurfaceContainer))
            .replace("{{LINE}}", hex(M.attr.colorOutlineVariant))
            .replace("{{HEADLINE}}", headline)
            .replace("{{COMPACT_TAGLINE}}", TextUtils.htmlEncode(getString(R.string.app_tagline)))
            .replace("{{TAGLINE}}", TextUtils.htmlEncode(getString(R.string.firstrun_p1_tagline)).replace("\n", "<br>"))
            .replace("{{AGENT_LOGOS}}", TextUtils.htmlEncode(getString(R.string.firstrun_agent_logos)))
            .replace("{{BODY}}", TextUtils.htmlEncode(getString(R.string.firstrun_p1_body)))
            .replace("{{IDEAS_HEADING}}", TextUtils.htmlEncode(getString(R.string.firstrun_ideas_heading)))
            .replace("{{IDEAS}}", ideas)
        web.loadDataWithBaseURL("file:///android_asset/firstrun/", html, "text/html", "utf-8", null)
        web.tag = INTRO_LOADED
    }

    private inner class Holder(val p: FirstrunPageBinding) : RecyclerView.ViewHolder(p.root)

    private inner class Pages : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = PAGES
        override fun getItemViewType(position: Int) = position
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(FirstrunPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val p = h.p
            p.introWeb.isVisible = position == INTRO
            p.nativePage.isVisible = position != INTRO
            if (position == INTRO) { showIntro(p); return }
            p.title.setText(TITLES[position])
            p.capRows.isVisible = position == CAPS_PAGE
            if (position == CAPS_PAGE && p.capRows.childCount == 0) {
                val inflater = LayoutInflater.from(p.root.context)
                for ((icon, title, body) in CAPS) {
                    val row = FirstrunCapRowBinding.inflate(inflater, p.capRows, true)
                    row.icon.setImageResource(icon)
                    row.capTitle.setText(title)
                    row.capBody.setText(body)
                }
            }
            val body = BODIES[position]
            p.body.isVisible = body != 0
            if (body != 0) p.body.setText(body)
            p.root.contentDescription = null
            p.tsCard.isVisible = position == SETUP_PAGE
            p.notifBlock.isVisible = position == SETUP_PAGE
            p.finishBlock.isVisible = position == FINISH_PAGE
            // The last page sits centred on the screen, not pinned to the top (owner, 4 Nov 2026).
            p.pageContent.gravity = if (position == FINISH_PAGE) android.view.Gravity.CENTER_VERTICAL else android.view.Gravity.NO_GRAVITY
            if (position == SETUP_PAGE) {
                page2 = p
                p.openTailscale.setOnClickListener {
                    // Missing on a private build: the setup screen starts installing Tailscale at once.
                    val install = !UiActions.isTailscaleInstalled(requireContext()) && cc.bridgit.app.update.TailscaleInstaller.available()
                    findNavController().navigate(R.id.tailscaleSetup,
                        androidx.core.os.bundleOf(cc.bridgit.app.ui.tailscale.TailscaleSetupFragment.ARG_AUTO_INSTALL to install))
                }
                p.allowNotif.setOnClickListener {
                    // Before Android 13 there's no permission to ask for; notifications are turned on in settings.
                    if (Perms.platform(Manifest.permission.POST_NOTIFICATIONS) == null) UiActions.openAppNotificationSettings(requireContext())
                    else notifRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                p.notifSettings.setOnClickListener { UiActions.openAppNotificationSettings(requireContext()) }
                bindTailscale()
                bindNotifications()
            }
            if (position == FINISH_PAGE) {
                p.addFirst.setOnClickListener { finish(addAgent = true) }
                p.runDiagnostic.setOnClickListener { finish(addAgent = false) }
                p.privacy.setOnClickListener { UiActions.openPrivacyPolicy(requireContext()) }
            }
        }
    }

    companion object {
        private const val PAGES = 4
        private const val INTRO = 0
        private const val CAPS_PAGE = 1
        private const val SETUP_PAGE = 2
        private const val FINISH_PAGE = 3
        private const val INTRO_LOADED = "intro-loaded"
        private val TITLES = intArrayOf(0, R.string.firstrun_caps_heading, R.string.firstrun_p2_title, R.string.firstrun_p3_title)
        /** Page 2 rows: only features the What-can screen lists as available now. */
        private val CAPS = listOf(
            Triple(R.drawable.ic_camera, R.string.firstrun_cap_see_title, R.string.firstrun_cap_see_body),
            Triple(R.drawable.ic_mic, R.string.firstrun_cap_hear_title, R.string.firstrun_cap_hear_body),
            Triple(R.drawable.ic_search, R.string.firstrun_cap_identify_title, R.string.firstrun_cap_identify_body),
            Triple(R.drawable.ic_photo, R.string.firstrun_cap_files_title, R.string.firstrun_cap_files_body),
            Triple(R.drawable.ic_place, R.string.firstrun_cap_context_title, R.string.firstrun_cap_context_body),
            Triple(R.drawable.ic_network_discovery, R.string.firstrun_cap_network_title, R.string.firstrun_cap_network_body),
            Triple(R.drawable.ic_nav_interactions, R.string.firstrun_cap_ask_title, R.string.firstrun_cap_ask_body),
        )
        /** 0 = no body text on that page. */
        private val BODIES = intArrayOf(0, R.string.firstrun_caps_body, R.string.firstrun_p2_body, 0)
    }
}
