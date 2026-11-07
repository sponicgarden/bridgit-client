package cc.bridgit.app.ui.applets

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.widget.ArrayAdapter
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.databinding.FragmentAppletPortalsBinding

/**
 * Applets › Portals (owner, 4 Nov 2026): a web view into each applet's portal on bridgit.cc/myapps, the same
 * page the owner sees from a computer. A picker at the top switches portals when there is more than one.
 * Sign-in: when the phone is linked to an account and has no bridgit.cc session yet, the app fetches a one-use code
 * and the page signs in with it (see [cc.bridgit.app.remoteview.RemoteView.portalLoginCode]); otherwise the page
 * offers its email code. The web view keeps its cookies like a browser would, so one sign-in covers every portal. The web view
 * adds "BridgitApp/<version>" to its user agent so the page shows its compact in-app layout. Pages off
 * bridgit.cc open in the browser. With no applet yet, the tab says how to set one up. The list is this device's
 * applets, or its hub's while Home is switched to the hub (owner, 5 Nov 2026); agent activity is on the Agents tab. Reload and Open in browser
 * sit beside the portal picker, shown only while this tab has a portal open.
 * Account circle (owner, 4 Nov 2026): in the app the page hides its own header row (Set up a new device + profile
 * chip) to save height. The top-right toolbar circle takes over the chip: it shows the signed-in account's letter,
 * read from the page, and its menu drives the page's own (hidden) account link and Sign out.
 */
class AppletPortalsFragment : Fragment() {
    private var _b: FragmentAppletPortalsBinding? = null
    private val b get() = _b!!
    private val app get() = requireActivity().application as BridgeApp
    private var web: WebView? = null
    private var portals: List<AppletLists.Portal> = emptyList()
    private var current: String? = null
    private var opens = 0
    private var visibleIdentity: String? = null
    private val portalNames = mutableMapOf<String, String>()
    var accountLetter: String? = null
        private set

    /** Back steps back inside the portal page first, while this tab is showing. */
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val w = web
            // The Inbox subtab hides this whole screen without telling child fragments: pass Back on then.
            if (w != null && view?.isShown == true && w.canGoBack()) { w.goBack(); return }
            isEnabled = false
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentAppletPortalsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        current = savedInstanceState?.getString(KEY_PORTAL)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, back)
        b.portalOpenApplets.setOnClickListener {
            (parentFragment as? cc.bridgit.app.ui.interactions.InteractionsFragment)?.showApplets()
        }
        b.portalLink.setOnClickListener {
            startActivity(Intent(requireContext(), cc.bridgit.app.remoteview.LinkWebViewActivity::class.java))
        }
        b.portalReload.setOnClickListener { reload() }
        b.portalOpenBrowser.setOnClickListener { openInBrowser() }
        b.portalChoice.setOnItemClickListener { _, _, position, _ -> portals.getOrNull(position)?.let { open(it) } }
    }

    override fun onResume() { super.onResume(); foreground = java.lang.ref.WeakReference(this); refresh(); web?.let { readAccount(it) }; refreshHubs() }

    /** On a satellite: re-reads the hub's portals in the background and redraws when they changed. */
    private fun refreshHubs() {
        if (app.hubLinks.hubs().isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val before = list(app)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.hubLinks.refreshFromHubs() }
            if (_b != null && list(app) != before) refresh()
        }
    }

    /** Whether a portal is open, so the toolbar can offer its account menu. */
    fun hasPortal(): Boolean = portals.isNotEmpty()

    /** Picker row › Reload portal. */
    fun reload() { web?.reload() }

    /** Picker row › Open in browser: the page as it is now, in the phone's browser. */
    fun openInBrowser() {
        val url = web?.url ?: portals.firstOrNull { it.id == current }?.url ?: return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) { refresh(); web?.let { readAccount(it) } }
        sync()
    }

    /** Re-reads which portals exist (they're switched on elsewhere) and keeps the open one when it still exists. */
    fun refresh() {
        val b = _b ?: return
        portals = list(app).map { p -> portalNames[p.id]?.let { p.copy(name = it) } ?: p }
        val has = portals.isNotEmpty()
        b.portalEmpty.isVisible = !has
        // Linking is what brings the account's applets here; once linked the button has nothing left to do.
        b.portalLink.isVisible = app.remoteView.linked.value == null
        b.portalBar.isVisible = has
        b.portalFrame.isVisible = has
        (parentFragment as? cc.bridgit.app.ui.interactions.InteractionsFragment)?.portalMenuChanged()
        if (!has) { accountLetter = null; current = null; web?.let { b.portalFrame.removeView(it); it.destroy() }; web = null; sync(); return }
        val many = portals.size > 1
        b.portalPicker.isVisible = many
        b.portalName.isVisible = !many
        b.portalChoice.setAdapter(choices(portals))
        // Installed applets › Open asked for one portal: shown once, then the owner's own pick holds.
        val want = wanted?.let { w -> portals.firstOrNull { it.id == w } }
        wanted = null
        open(want ?: portals.firstOrNull { it.id == current } ?: portals.first(), onlyIfChanged = true)
        sync()
    }

    /** The picker's menu (design pass, 5 Nov 2026): one 48dp row per portal, the open one checked. No filtering. */
    private fun choices(list: List<AppletLists.Portal>) = object : android.widget.ArrayAdapter<String>(requireContext(), R.layout.item_portal_choice, list.map { it.name }) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            (super.getView(position, convertView, parent) as android.widget.TextView).apply {
                val on = list.getOrNull(position)?.id == current
                setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, if (on) R.drawable.ic_check else 0, 0)
                compoundDrawableTintList = android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(context, R.color.portal_ink))
                isSelected = on
            }
        override fun getFilter() = object : android.widget.Filter() {
            override fun performFiltering(c: CharSequence?) = FilterResults().apply { values = list; count = list.size }
            override fun publishResults(c: CharSequence?, r: FilterResults?) = notifyDataSetChanged()
        }
    }

    private fun open(p: AppletLists.Portal, onlyIfChanged: Boolean = false) {
        val b = _b ?: return
        b.portalName.text = p.name
        b.portalChoice.setText(p.name, false)
        if (onlyIfChanged && p.id == current && web != null) return
        current = p.id
        visibleIdentity = null
        val w = web ?: makeWebView().also { web = it; b.portalFrame.addView(it, ViewGroup.LayoutParams(-1, -1)) }
        // Signed in to the app, signed in to the portal (owner, 4 Nov 2026): with no bridgit.cc session yet, ask the
        // backend for a one-use code first and hand it to the page; without one the page shows its sign-in card.
        val opening = ++opens
        b.portalProgress.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            val pkg = p.id.removePrefix("pkg.")
            val state = app.remoteView.packages.status(pkg)
            if (p.id.startsWith("pkg.") && state.optBoolean("personal_backend")) {
                // Installation enables the portal asynchronously. Finish its first registration/report
                // before the page decides this phone has never installed the applet.
                try { withTimeoutOrNull(6_000) { app.remoteView.preparePackagePortal() } }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { /* Offline: the page retains its normal connection/retry state. */ }
            }
            if (opening != opens || web !== w) return@launch
            if (AppletLists.hasPortalSession(CookieManager.getInstance().getCookie(AppletLists.PORTAL_PAGE))) {
                w.loadUrl(p.url); return@launch
            }
            // A satellite not linked to the account itself signs in through its hub, which is.
            val code = withTimeoutOrNull(6_000) { app.remoteView.portalLoginCode() }
                ?: if (app.hubLinks.hubs().isEmpty()) null else withTimeoutOrNull(10_000) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.hubLinks.portalLoginCode() } }
            if (opening != opens || web !== w) return@launch // another portal was picked meanwhile
            w.loadUrl(code?.let { AppletLists.withLogin(p.url, it) } ?: p.url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun makeWebView(): WebView = WebView(requireContext()).apply {
        setBackgroundColor(androidx.core.content.ContextCompat.getColor(context, R.color.portal_paper))
        settings.javaScriptEnabled = true // the portal page is a script app
        settings.domStorageEnabled = true // it keeps its session token in storage
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportMultipleWindows(false)
        // Tells bridgit.cc it is inside the app, so the page drops the site header and offers the email code
        // (Google won't sign in inside an app web view).
        settings.userAgentString = "${settings.userAgentString} BridgitApp/${cc.bridgit.app.BuildConfig.VERSION_NAME}"
        CookieManager.getInstance().setAcceptCookie(true)
        contentDescription = getString(R.string.applets_tab_portals)
        // An applet whose portal is its interface (PortalTables) presses its buttons and edits its rows on this
        // device through window.BridgitApp: a message channel only bridgit.cc's main frame gets.
        val browser = this
        cc.bridgit.app.remoteview.PortalBridge.attach(this, cc.bridgit.app.remoteview.PortalBridge(app, requireActivity(), viewLifecycleOwner.lifecycleScope) { identity ->
            val id = current
            if (web === browser && _b != null && id != null) {
                val name = AppletLists.portalIdentity(identity.toString(), browser.url?.let(Uri::parse), id)
                if (name != null) visibleIdentity = id
                if (name != null && portalNames[id] != name) {
                    portalNames[id] = name
                    portals = portals.map { if (it.id == id) it.copy(name = name) else it }
                    b.portalChoice.setAdapter(choices(portals))
                    b.portalChoice.setText(name, false)
                    b.portalName.text = name
                }
            }
        })
        webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                if (view !== web || _b == null) return
                val id = current ?: return
                val address = view.url ?: return
                // A title callback may belong to the page we just left. Read the live DOM identity
                // instead, then fence the asynchronous reply against another portal switch.
                view.evaluateJavascript(PORTAL_IDENTITY) { raw ->
                    if (view !== web || _b == null || current != id || view.url != address) return@evaluateJavascript
                    val name = AppletLists.portalIdentity(raw, Uri.parse(address), id) ?: return@evaluateJavascript
                    visibleIdentity = id
                    if (portalNames[id] == name) return@evaluateJavascript
                    portalNames[id] = name
                    portals = portals.map { if (it.id == id) it.copy(name = name) else it }
                    b.portalChoice.setAdapter(choices(portals))
                    b.portalChoice.setText(name, false)
                    b.portalName.text = name
                }
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (AppletLists.staysInPortal(request.url)) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { visibleIdentity=null; _b?.portalProgress?.isVisible = true }
            override fun onPageFinished(view: WebView, url: String?) {
                _b?.portalProgress?.isVisible = false; sync()
                watchAccount(view)
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { sync() }
        }
    }

    /**
     * The page fills in its profile chip after an async session check, and a first sign-in from the app's one-use
     * code happens in place without a reload, so keep looking: every 2 s for 30 s after each page load.
     */
    private var accountLoad = 0
    private fun watchAccount(w: WebView) {
        val load = ++accountLoad
        for (delay in longArrayOf(0, 900) + LongArray(15) { 2_000L * (it + 1) })
            w.postDelayed({ if (load == accountLoad) readAccount(w) }, delay)
    }

    /** Shows the account circle with the page's signed-in letter, or hides it while the page is signed out. */
    private fun readAccount(w: WebView) {
        if (w !== web) return
        w.evaluateJavascript(ACCOUNT_LETTER) { raw ->
            if (_b == null) return@evaluateJavascript
            if (w !== web) return@evaluateJavascript
            val letter = raw?.trim('"')?.take(1)?.takeIf { it.isNotBlank() && it != "null" }
            if (accountLetter != letter) {
                accountLetter = letter
                (parentFragment as? cc.bridgit.app.ui.interactions.InteractionsFragment)?.portalMenuChanged()
            }
        }
    }

    fun accountMenu(anchor: View) {
        val w = web ?: return
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, 1, 0, R.string.applets_portal_account_open)
            menu.add(0, 2, 1, R.string.applets_portal_sign_out)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> w.evaluateJavascript(OPEN_ACCOUNT) { if (it?.trim('"') != "ok") w.loadUrl(ACCOUNT_PAGE) }
                    2 -> w.evaluateJavascript(SIGN_OUT, null)
                }
                true
            }
        }.show()
    }

    private fun sync() { back.isEnabled = !isHidden && _b != null && web?.canGoBack() == true }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        current?.let { outState.putString(KEY_PORTAL, it) }
    }

    override fun onDestroyView() {
        web?.destroy(); web = null
        super.onDestroyView(); _b = null
    }

    companion object {
        private var foreground = java.lang.ref.WeakReference<AppletPortalsFragment>(null)
        fun foregroundId(): String? = foreground.get()?.takeIf { it.isResumed && !it.isHidden && it.view?.isShown == true }?.visibleIdentity

        private const val PORTAL_IDENTITY = """(function(){
          var root=document.getElementById('pkApplet'), title=null;
          if(root && !root.hidden) title=document.getElementById('pkTitle');
          else {root=document.getElementById('wvApplet');if(root && !root.hidden)title=document.getElementById('apTitle');}
          if(!title)return null;
          var image=title.querySelector('img');
          return {name:(image?image.alt:title.textContent).trim(),applet:location.hash.slice(1).split('&')[0]};
        })()"""

        private const val KEY_PORTAL = "applets.portal"
        private const val ACCOUNT_PAGE = "https://bridgit.cc/account/"
        private const val ACCOUNT_LETTER =
            "(function(){var a=document.querySelector('.site-profile-menu .site-avatar');return a?a.textContent.trim():''})()"
        private const val OPEN_ACCOUNT =
            "(function(){var l=document.querySelector('.site-profile-panel a');if(!l)return '';l.click();return 'ok'})()"
        private const val SIGN_OUT =
            "(function(){var b=document.querySelector('.site-profile-panel button')||document.getElementById('wvSignOut');if(b)b.click()})()"

        /**
         * This phone's portals. [accountWide]: also the applets other devices of its account run (what a hub tells its
         * satellites); false for this phone's own Portals tab, which lists only applets running here (owner, 5 Nov 2026).
         */
        fun own(app: BridgeApp, accountWide: Boolean = true): List<AppletLists.Portal> {
            val installed = app.applets.all().associate { it.optString("package_id") to it.optString("name") }
            return AppletLists.portals(
                dogHere = app.remoteView.dogBackend, dogOnAccount = accountWide && "dog" in app.remoteView.accountApplets,
                device = runCatching { cc.bridgit.app.feedback.InstallIdentity.status(app)?.installId }.getOrNull(),
                packagesHere = app.remoteView.packages.enabledIds().associateWith { installed[it].orEmpty() },
                accountApplets = if (accountWide) app.remoteView.accountApplets else emptySet(), names = installed)
        }

        /** A portal to open the next time the Portals tab shows (Installed applets › Open), by id ("pkg.<id>"). */
        @Volatile var wanted: String? = null

        /**
         * The applets running on this device; while Home is switched to the hub, that hub's portals instead (owner,
         * 5 Nov 2026: a device's own Portals tab lists only its own, not its hub's or other account devices').
         */
        fun list(app: BridgeApp): List<AppletLists.Portal> {
            cc.bridgit.app.portal.PortalLinks(app).selectedHub()?.let { h ->
                return h.portals.map { AppletLists.Portal("hub:${h.linkId}:${it.id}", it.name, it.url) }
            }
            return own(app, accountWide = false)
        }

        /** Whether the Portals tab has anything to show, so Applets can open there first. */
        fun available(context: Context): Boolean {
            val app = context.applicationContext as BridgeApp
            return list(app).isNotEmpty()
        }
    }
}
