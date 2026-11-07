package cc.bridgit.app.ui.common

import android.os.Build
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import cc.bridgit.app.R

/**
 * AGT-08a: FLAG_SECURE stops screenshots, but not accessibility. Any app with an
 * accessibility service (and UiAutomation tools) can read a view's text, so views
 * that show a key or secret go through here.
 */
object SecretViews {

    /**
     * Android 14+: only accessibility tools such as TalkBack (isAccessibilityTool)
     * see this view. Earlier versions have no such flag, so this does nothing there.
     */
    fun markSensitive(view: View) {
        if (Build.VERSION.SDK_INT >= 34) view.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
    }

    /**
     * For a key or secret nobody needs to hear (the owner copies or shares it):
     * marks the view sensitive and, on every Android version, replaces the secret
     * in its accessibility text with "hidden". The text on screen is unchanged.
     * [secret] is read each time, so it can change after the view is set up.
     */
    fun hideSecret(view: View, secret: () -> String?) {
        markSensitive(view)
        view.accessibilityDelegate = Redacting(view.context.getString(R.string.secret_hidden), secret)
    }

    /** [text] with every [secret] replaced by [mask]; unchanged when there's nothing to hide. */
    fun redact(text: CharSequence?, secret: String?, mask: String): CharSequence? {
        if (text == null || secret.isNullOrEmpty()) return text
        val s = text.toString()
        return if (s.contains(secret)) s.replace(secret, mask) else text
    }

    private class Redacting(private val mask: String, private val secret: () -> String?) : View.AccessibilityDelegate() {
        private fun r(t: CharSequence?) = redact(t, secret(), mask)

        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.text = r(info.text)
            info.contentDescription = r(info.contentDescription)
        }

        override fun onInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) {
            super.onInitializeAccessibilityEvent(host, event)
            event.beforeText = r(event.beforeText)
            event.contentDescription = r(event.contentDescription)
        }

        override fun onPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) {
            super.onPopulateAccessibilityEvent(host, event)
            val texts = event.text
            for (i in texts.indices) texts[i] = r(texts[i])
        }
    }
}
