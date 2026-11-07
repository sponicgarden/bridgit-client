package cc.bridgit.app.ui.applets

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Verified settings links open the same authenticated settings as the embedded portal. */
class AppletSettingsActivity : AppCompatActivity() {
    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = AppletLists.settingsTarget(intent.data) ?: run { finish(); return }
        enableEdgeToEdge()
        val paper = androidx.core.content.ContextCompat.getColor(this, R.color.portal_paper)
        val ink = androidx.core.content.ContextCompat.getColor(this, R.color.portal_ink)
        val toolbar = MaterialToolbar(this).apply {
            title = "Applet settings"
            setBackgroundColor(paper)
            setTitleTextColor(ink)
            setNavigationIcon(R.drawable.ic_back)
            navigationIcon?.setTint(ink)
            setNavigationContentDescription(R.string.cd_back)
            setNavigationOnClickListener { finish() }
        }
        val browser = WebView(this).apply {
            setBackgroundColor(paper)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.userAgentString += " BridgitApp/${cc.bridgit.app.BuildConfig.VERSION_NAME}"
            CookieManager.getInstance().setAcceptCookie(true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url
                    if (u.scheme == "https" && u.host == "bridgit.cc") return false
                    if (u.scheme in setOf("https", "mailto")) runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }
                    return true
                }
            }
        }
        web = browser
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paper)
            addView(toolbar, LinearLayout.LayoutParams(-1, -2))
            addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = bars.left, right = bars.right, top = bars.top, bottom = maxOf(bars.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom))
            insets
        }
        setContentView(root)
        lifecycleScope.launch {
            val app = application as BridgeApp
            val code = if (AppletLists.hasPortalSession(CookieManager.getInstance().getCookie(AppletLists.PORTAL_PAGE))) null
                else withTimeoutOrNull(6000) { app.remoteView.portalLoginCode() }
            if (!isFinishing) browser.loadUrl(code?.let { AppletLists.withLogin(target, it) } ?: target)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A warm link must revalidate and navigate to its own device/applet, even if this screen is already open.
        recreate()
    }

    override fun onDestroy() { web?.destroy(); web = null; super.onDestroy() }
}
