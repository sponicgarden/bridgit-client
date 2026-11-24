package cc.bridgit.app.ui.common

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class WebViewsTest {
    @After fun reset() = WebViews.resetForTest()

    private fun providerFailure(): RuntimeException {
        // As on the Echo Dot (C0888): WebViewFactory.getProvider throws, wrapped by the inflater's reflection.
        val inner = UnsupportedOperationException()
        inner.stackTrace = arrayOf(StackTraceElement("android.webkit.WebViewFactory", "getProvider", "WebViewFactory.java", 242))
        return RuntimeException("Error inflating class android.webkit.WebView", InvocationTargetException(inner))
    }

    @Test fun missingProviderReturnsNullAndIsRemembered() {
        var made = 0
        assertNull(WebViews.create<Any> { made++; throw providerFailure() })
        assertFalse(WebViews.usable)
        assertNull(WebViews.create { made++; "web" })
        assertEquals(1, made)
    }

    @Test fun missingPackageByNameIsAProviderFailure() {
        class MissingWebViewPackageException : RuntimeException()
        assertTrue(WebViews.isProviderFailure(RuntimeException(MissingWebViewPackageException())))
    }

    @Test fun ourOwnBugsStillCrash() {
        try {
            WebViews.create<Any> { throw IllegalStateException("bug in setup") }
            fail("expected the exception")
        } catch (e: IllegalStateException) {
            assertTrue(WebViews.usable)
        }
    }

    @Test fun usableProviderBuildsTheView() {
        assertEquals("web", WebViews.create { "web" })
        assertTrue(WebViews.usable)
    }
}
