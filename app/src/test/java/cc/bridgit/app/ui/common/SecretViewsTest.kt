package cc.bridgit.app.ui.common

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import cc.bridgit.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * AGT-08a: keys and secrets stay out of the accessibility tree. The node-level
 * sensitive flag is filled in by the system, so it's checked on a device, not here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class SecretViewsTest {
    private val ctx get() = RuntimeEnvironment.getApplication() as Context
    private val key = "brg_0123456789abcdefSECRETKEY"
    private val prompt = "You are agent Test.\nAuthorization: Bearer $key\nCall GET /v1/guide."
    private val mask get() = ctx.getString(R.string.secret_hidden)

    private fun view(text: String) = TextView(ctx).apply { this.text = text }

    @Test fun redactReplacesEveryCopyAndLeavesOtherTextAlone() {
        assertEquals("a [x] b [x]", SecretViews.redact("a $key b $key", key, "[x]").toString())
        assertEquals("no key here", SecretViews.redact("no key here", key, "[x]").toString())
        assertEquals("text", SecretViews.redact("text", null, "[x]").toString())
        assertEquals("text", SecretViews.redact("text", "", "[x]").toString())
    }

    @Test @Config(sdk = [34]) fun hideSecretOnApi34MarksSensitiveAndRedactsNode() {
        val v = view(prompt)
        SecretViews.hideSecret(v) { key }
        assertTrue(v.isAccessibilityDataSensitive)
        val node = v.createAccessibilityNodeInfo()
        val t = node.text.toString()
        assertFalse(t.contains(key))
        assertTrue(t.contains(mask))
        assertTrue(t.contains("Call GET /v1/guide."))
        assertEquals(prompt, v.text.toString()) // what's on screen is unchanged
    }

    @Test @Config(sdk = [30]) fun hideSecretOnApi30RedactsNode() {
        val v = view(prompt)
        SecretViews.hideSecret(v) { key }
        val t = v.createAccessibilityNodeInfo().text.toString()
        assertFalse(t.contains(key))
        assertTrue(t.contains(mask))
    }

    @Test @Config(sdk = [34]) fun hideSecretRedactsAccessibilityEvents() {
        val v = view(prompt)
        SecretViews.hideSecret(v) { key }
        val e = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_FOCUSED)
        v.onInitializeAccessibilityEvent(e)
        v.onPopulateAccessibilityEvent(e)
        val all = e.text.joinToString(" ")
        assertFalse(all.contains(key))
    }

    @Test @Config(sdk = [34]) fun hideSecretReadsTheSecretEachTime() {
        var current: String? = null
        val v = view(prompt)
        SecretViews.hideSecret(v) { current }
        assertTrue(v.createAccessibilityNodeInfo().text.toString().contains(key)) // nothing to hide yet
        current = key
        assertFalse(v.createAccessibilityNodeInfo().text.toString().contains(key))
    }

    @Test @Config(sdk = [34]) fun markSensitiveKeepsTextForTalkBack() {
        val v = view("731 582")
        SecretViews.markSensitive(v)
        assertTrue(v.isAccessibilityDataSensitive)
        val node = v.createAccessibilityNodeInfo()
        assertEquals("731 582", node.text.toString())
    }

    @Test @Config(sdk = [30]) fun markSensitiveIsANoOpBeforeApi34() {
        val v = view("731 582")
        SecretViews.markSensitive(v)
        assertEquals("731 582", v.createAccessibilityNodeInfo().text.toString())
    }
}
