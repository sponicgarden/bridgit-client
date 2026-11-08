package cc.bridgit.app.ui.interactions

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import cc.bridgit.app.interact.ActGate
import cc.bridgit.app.interact.CardActionRunner
import cc.bridgit.app.interact.CardActions
import cc.bridgit.app.interact.CardRules
import cc.bridgit.app.interact.RespondGate
import java.io.ByteArrayInputStream

/**
 * CAP-34: the WebView an agent's HTML card runs in. Configured here in code, never by the page; the page is
 * untrusted. Checklist (each line is enforced below):
 * - JavaScript on; no file or content access, no file-URL access flags; no DOM storage, database, cookies,
 *   geolocation, camera or mic (every permission request denied); Safe Browsing on; mixed content never;
 *   network loads blocked.
 * - No new windows (setSupportMultipleWindows(false), onCreateWindow false); every navigation blocked;
 *   every request answered with an empty 403 (the card itself comes from loadDataWithBaseURL, so it is never
 *   fetched); Bridgit's forced CSP is the first thing in the page ([CardRules.prepare]).
 * - No JavaScript dialogs, no WebView fullscreen API (Bridgit's own full-screen presentation, A0074, is drawn by the
 *   card screen around this view), no file chooser, no downloads, no autofill, no console logging
 *   (LOG-02): nothing the page does can draw over Bridgit's own chrome around the card.
 * - Two @JavascriptInterface methods: Bridgit.respond(json), accepted once ([RespondGate]), the answer
 *   handed to [onRespond] on the main thread; and Bridgit.act(json) (feature #0007), which calls one of the
 *   posting agent's own endpoints as that agent through [CardActionRunner], with the agent's grants and the
 *   owner's switches applied as over HTTP. Its result goes back to the page only, as a script.
 *
 * Call [load] once, and [release] when the screen goes away.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class CardWebView(context: Context, private val onRespond: (Any) -> Unit, private val onCrash: () -> Unit,
                  /** The card's interaction id, for Bridgit.act; null turns actions off. */
                  private val interactionId: Long? = null,
                  /** Applet portals (docs/spec-portals-and-cards.md): adds Bridgit.portal; null for Inbox cards. */
                  private val portal: PortalBridge? = null) : WebView(context) {
    /** What a portal page's Bridgit.portal calls reach (on the JavaBridge thread). */
    interface PortalBridge {
        fun expose(name: String?, spec: String?): Boolean
        fun setState(json: String?): Boolean
        fun reply(id: String?, json: String?)
        /** The person at the device touched the page. */
        fun touched()
        /** The page finished loading (its first scripts have run). */
        fun loaded() {}
    }

    private val actGate = ActGate()
    private val presentationJob = kotlinx.coroutines.SupervisorJob()
    private val presentationScope = kotlinx.coroutines.CoroutineScope(presentationJob + kotlinx.coroutines.Dispatchers.IO)
    private val presentationLock = Any()
    private var presentationOwner: String? = null
    @Volatile private var released = false
    @Volatile private var gate = RespondGate()
    private var pageReady = false
    private data class Confirmation(val answer: org.json.JSONObject?, val title: String, val message: String, val delay: Long?, val answered: Boolean)
    private var confirmation: Confirmation? = null
    private var confirmationShown = false
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var accepting = true

    init {
        configure(settings)
        // Process-wide: Bridgit has no other WebView, and a card must never get or set cookies.
        CookieManager.getInstance().setAcceptCookie(false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webViewClient = Client()
        webChromeClient = Chrome()
        setDownloadListener { _, _, _, _, _ -> } // blob: and data: downloads go nowhere
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS // The owner's saved data is never offered to the page
        isLongClickable = false
        setOnLongClickListener { true }
        isHapticFeedbackEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        // One object under both names: window.Bridgit (the documented one), and window.Bridget, an
        // undocumented alias for cards written before the rename (owner, 1 Nov 2026). Same instance, so
        // the once-only answer gate covers both.
        val bridge = Bridge()
        JS_NAMES.forEach { addJavascriptInterface(bridge, it) }
        portal?.let { addJavascriptInterface(PortalNative(it), PORTAL_JS_NAME) }
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) portal?.touched()
        return super.onTouchEvent(event)
    }

    /** Hands an agent's call to the portal page (main thread). */
    fun callPortal(id: String, name: String, argsJson: String) {
        if (released || portal == null) return
        evaluateJavascript("window.__bridgitPortalCall&&window.__bridgitPortalCall(${org.json.JSONObject.quote(id)},${org.json.JSONObject.quote(name)},${org.json.JSONObject.quote(argsJson)})", null)
    }

    /** The page's visible words (main thread); [done] gets "" when the page is gone. */
    fun readText(done: (String) -> Unit) {
        if (released) { done(""); return }
        evaluateJavascript(cc.bridgit.app.applets.PortalRules.TEXT_SCRIPT) { v ->
            done(runCatching { org.json.JSONTokener(v ?: "\"\"").nextValue() as? String }.getOrNull().orEmpty())
        }
    }

    /** The prepared page, served exactly once to the main-frame request for [CARD_URL]. */
    @Volatile private var page: ByteArray? = null

    /**
     * Loads the agent's HTML with Bridgit's head (CSP) in front. The page is answered from memory by
     * shouldInterceptRequest for [CARD_URL] (an .invalid name, so it can never be a real host), once,
     * with the CSP also sent as a response header. Every other request still gets the empty 403.
     * (A data: URL load is itself intercepted on current WebViews, which showed every card blank.)
     */
    fun load(html: String, fullScreen: Boolean = false) {
        source = html
        fitOn = CardRules.fitToViewport(html, fullScreen)
        fitInPage = fitOn
        page = CardRules.prepare(html, portal = portal != null, fit = fitOn).toByteArray(Charsets.UTF_8)
        loadUrl(CARD_URL)
    }

    private var source: String? = null
    /** A0076: whether the page should be scaled to fit, and whether the loaded document already is. */
    private var fitOn = false
    private var fitInPage = false

    private fun applyFit() {
        if (released || fitInPage == fitOn) return
        fitInPage = fitOn
        evaluateJavascript(if (fitOn) "${CardRules.FIT_SCRIPT};window.__bridgitFit(true)" else "window.__bridgitFit&&window.__bridgitFit(false)", null)
    }

    /**
     * A0076: the owner switched this card in or out of full screen. A fixed-size page is scaled to fit whole while full
     * screen ([CardRules.fitToViewport]) and restored to its own layout after. Bridgit's script only, never the page's.
     */
    fun setFullScreen(on: Boolean) {
        if (released) return
        fitOn = CardRules.fitToViewport(source, on)
        val html = source ?: return
        val unserved = synchronized(this) { page != null }
        if (unserved) {
            // Not fetched yet: the document itself carries the choice.
            synchronized(this) { page = CardRules.prepare(html, portal = portal != null, fit = fitOn).toByteArray(Charsets.UTF_8) }
            fitInPage = fitOn
        } else if (pageReady) applyFit() // otherwise onPageFinished applies it
    }

    /** The one response the card's WebView ever gets: the page, for its own main-frame load, the first time. */
    private fun serve(request: WebResourceRequest): WebResourceResponse {
        if (!request.isForMainFrame || request.method != "GET" || request.url.toString() != CARD_URL) return blocked()
        val bytes = synchronized(this) { page.also { page = null } } ?: return blocked()
        return WebResourceResponse("text/html", "utf-8", 200, "OK",
            mapOf("Content-Security-Policy" to HEADER_CSP, "Cache-Control" to "no-store"), ByteArrayInputStream(bytes))
    }

    /** Answers are no longer taken (the card was answered or closed elsewhere). */
    fun stopAccepting() { accepting = false }

    /** Confirm only after persistence; replay when reopening history or after the page finishes loading. */
    fun showConfirmation(answer: org.json.JSONObject?, title: String, message: String, closeAfterMs: Long? = null, answered: Boolean = true) {
        accepting = false
        if (confirmationShown) return
        confirmation = Confirmation(answer, title, message, closeAfterMs, answered)
        if (!pageReady) return
        confirmationShown = true
        evaluateJavascript(cc.bridgit.app.interact.CardConfirmation.script(answer, title, message, closeAfterMs, answered), null)
    }

    fun retryAnswer() {
        gate = RespondGate()
        accepting = true
    }

    fun release() {
        val owner = synchronized(presentationLock) {
            released = true
            presentationJob.cancel()
            presentationOwner.also { presentationOwner = null }
        }
        val app = context.applicationContext as? cc.bridgit.app.BridgeApp
        if (app != null && owner != null) cc.bridgit.app.judgment.ContextServices.stopPresentation(app, owner)
        pendingActions.clear();clearActionProgress()
        accepting = false
        main.removeCallbacksAndMessages(null) // an answer queued during teardown is dropped
        stopLoading()
        JS_NAMES.forEach { removeJavascriptInterface(it) }
        if (portal != null) removeJavascriptInterface(PORTAL_JS_NAME)
        webChromeClient = null
        (parent as? android.view.ViewGroup)?.removeView(this)
        destroy()
    }

    /** Hands an act result back to the page, on the main thread, unless the card is gone. */
    private fun deliver(ref: Int, result: org.json.JSONObject) {
        main.post { if (!released) evaluateJavascript(CardActions.deliverScript(ref, result), null) }
    }

    private val pendingActions=mutableMapOf<Int,Pair<Long,String>>()
    private var actionProgress:android.widget.TextView?=null
    private val progressTick=object:Runnable {
        override fun run() {
            if(released||pendingActions.isEmpty()) { clearActionProgress();return }
            val first=pendingActions.values.minByOrNull { it.first } ?: return
            val seconds=(android.os.SystemClock.uptimeMillis()-first.first)/1000
            if(seconds>=1) {
                val host=parent as? android.widget.FrameLayout
                if(actionProgress==null&&host!=null) {
                    actionProgress=android.widget.TextView(context).apply {
                        setTextColor(android.graphics.Color.WHITE);textSize=15f;setPadding(20,12,20,12)
                        background=android.graphics.drawable.GradientDrawable().apply { cornerRadius=18f;setColor(0xF03A2B49.toInt()) }
                        accessibilityLiveRegion=android.view.View.ACCESSIBILITY_LIVE_REGION_NONE
                    }.also { host.addView(it,android.widget.FrameLayout.LayoutParams(-2,-2,android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply { bottomMargin=12 }) }
                }
                val label="◌ ${first.second} · ${seconds}s"
                if(actionProgress?.text?.toString()!=label)actionProgress?.text=label
            }
            main.postDelayed(this,500)
        }
    }
    private fun clearActionProgress() { actionProgress?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) };actionProgress=null }
    private fun actionStarted(ref:Int,json:String?) {
        val path=runCatching { org.json.JSONObject(json ?: "{}").optString("path") }.getOrDefault("")
        val label=when(path) { "/v1/camera/video","/v1/camera/snapshot"->"Camera open";"/v1/story","/v1/story/generate"->"Creating story";"/v1/story/image","/v1/images/generations"->"Creating illustration";"/v1/speech/speak"->"Preparing narration";else->"Working" }
        pendingActions[ref]=android.os.SystemClock.uptimeMillis() to label
        main.removeCallbacks(progressTick);main.postDelayed(progressTick,700)
    }
    private fun actionFinished(ref:Int) { pendingActions.remove(ref);if(pendingActions.isEmpty()) { main.removeCallbacks(progressTick);clearActionProgress() } }

    /** The methods the page can call. They run on the JavaBridge thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun respond(json: String?) {
            if (!accepting) return
            val v = gate.offer(json) ?: return
            main.post { if (accepting) onRespond(v) }
        }

        /** Bridgit.act: returns the call's ref at once; the result arrives later via [deliver]. */
        @JavascriptInterface
        fun act(json: String?): Int {
            val ref = actGate.nextRef()
            val id = interactionId
            val app = context.applicationContext as? cc.bridgit.app.BridgeApp
            when {
                released -> {}
                id == null || app == null -> deliver(ref, CardActions.refused("not_available", "This card can't run actions."))
                !actGate.start() -> deliver(ref, CardActions.refused("rate_limited",
                    "At most ${CardActions.MAX_IN_FLIGHT} actions can run at once on a card.", 429))
                else -> {
                    main.post { if(!released)actionStarted(ref,json) }
                    val path = if (portal != null) runCatching { org.json.JSONObject(json ?: "{}").optString("path") }.getOrNull() else null
                    val presentation = path?.startsWith("/v1/story/") == true || path in setOf("/v1/story","/v1/speaker/play","/v1/speaker/say","/v1/speech/speak")
                    if (presentation) synchronized(presentationLock) {
                        if (!released) presentationOwner = app.interactions.get(id)?.agentId
                    }
                    CardActionRunner.run(app,id,json, if (presentation) presentationScope else null) { r->actGate.finish();main.post { actionFinished(ref) };deliver(ref,r) }
                }
            }
            return ref
        }
    }

    /** Bridgit.portal's native half; every value is checked again in [PortalBridge]. */
    private inner class PortalNative(private val p: PortalBridge) {
        @JavascriptInterface fun expose(name: String?, spec: String?): Boolean = !released && p.expose(name, spec)
        @JavascriptInterface fun setState(json: String?): Boolean = !released && p.setState(json)
        @JavascriptInterface fun reply(id: String?, json: String?) { if (!released) p.reply(id, json) }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String?) = true
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = serve(request)
        @Deprecated("Deprecated in Java")
        override fun shouldInterceptRequest(view: WebView, url: String?): WebResourceResponse = blocked()
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) = handler.cancel()
        override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) { dontResend.sendToTarget() }
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { pageReady = false }
        override fun onPageFinished(view: WebView, url: String?) {
            view.evaluateJavascript(context.assets.open("applet-ui.js").bufferedReader().use { it.readText() }, null)
            view.evaluateJavascript(context.assets.open("table-sort.js").bufferedReader().use { it.readText() }, null)
            pageReady = true
            applyFit()
            portal?.loaded()
            confirmation?.let { showConfirmation(it.answer, it.title, it.message, it.delay, it.answered) }
        }
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Never take Bridgit down with a card; the screen drops the view and shows a note.
            (view as? CardWebView)?.onCrash?.invoke()
            return true
        }
    }

    private class Chrome : WebChromeClient() {
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message) = false
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()
        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) = callback.invoke(origin, false, false)
        override fun onShowCustomView(view: View?, callback: CustomViewCallback) = callback.onCustomViewHidden() // never fullscreen
        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean { result.cancel(); return true }
        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean { result.cancel(); return true }
        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean { result.cancel(); return true }
        override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean { result.confirm(); return true }
        override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<android.net.Uri>>, params: FileChooserParams): Boolean { cb.onReceiveValue(null); return true }
        override fun onConsoleMessage(consoleMessage: ConsoleMessage) = true // LOG-02: page output never reaches logcat
    }

    companion object {
        /** Every setting CAP-34 names, applied to [s]. */
        @Suppress("DEPRECATION")
        fun configure(s: WebSettings) {
            s.javaScriptEnabled = true
            s.javaScriptCanOpenWindowsAutomatically = false
            s.setSupportMultipleWindows(false)
            s.allowFileAccess = false
            s.allowContentAccess = false
            s.allowFileAccessFromFileURLs = false
            s.allowUniversalAccessFromFileURLs = false
            s.domStorageEnabled = false
            s.databaseEnabled = false
            s.setGeolocationEnabled(false)
            s.safeBrowsingEnabled = true
            s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            s.blockNetworkLoads = true
            s.blockNetworkImage = true
            s.loadsImagesAutomatically = true // data: images only (CSP img-src data:)
            s.mediaPlaybackRequiresUserGesture = true
            s.saveFormData = false
            s.cacheMode = WebSettings.LOAD_NO_CACHE
            s.setSupportZoom(false)
            s.builtInZoomControls = false
            s.displayZoomControls = false
        }

        /** The fail-closed answer to every request a card makes. */
        const val CARD_URL = "https://card.bridgit.invalid/"
        /** The page's JavaScript names for [Bridge]: Bridgit, and the deprecated Bridget alias. */
        val JS_NAMES = listOf("Bridgit", "Bridget")
        /** The native half of Bridgit.portal, only in applet portals. */
        const val PORTAL_JS_NAME = "BridgitPortalNative"
        /**
         * The CSP again as a header, plus `sandbox allow-scripts` (headers only; a meta tag can't sandbox).
         * Without allow-same-origin the card and every frame it makes get fresh opaque origins: no IndexedDB or
         * Cache Storage shared between cards, and frames can't reach unguarded WebRTC constructors (Codex review).
         */
        val HEADER_CSP = CardRules.CSP.trimEnd().trimEnd(';') + "; sandbox allow-scripts"
        fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked by Bridgit", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
}
