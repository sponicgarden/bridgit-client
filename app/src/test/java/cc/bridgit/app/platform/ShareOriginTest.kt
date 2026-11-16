package cc.bridgit.app.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareOriginTest {
    private val pkg = "cc.bridgit.app"

    @Test fun bothOwnProvidersAreRefused() {
        assertTrue(ShareOrigin.isOwnAuthority("cc.bridgit.app.fileprovider", pkg))
        assertTrue(ShareOrigin.isOwnAuthority("cc.bridgit.app.files", pkg))
    }

    @Test fun suffixedBuildsAreRefusedForTheirOwnPackage() {
        val test = "cc.bridgit.app.test_claude_cam"
        assertTrue(ShareOrigin.isOwnAuthority("$test.files", test))
        assertTrue(ShareOrigin.isOwnAuthority("$test.fileprovider", test))
    }

    @Test fun userIdPrefixAndCaseDoNotHideAnOwnAuthority() {
        assertTrue(ShareOrigin.isOwnAuthority("10@cc.bridgit.app.files", pkg))
        assertTrue(ShareOrigin.isOwnAuthority("CC.Bridgit.App.Files", pkg))
    }

    @Test fun otherAppsProvidersAreAllowed() {
        assertFalse(ShareOrigin.isOwnAuthority("com.android.providers.media.documents", pkg))
        assertFalse(ShareOrigin.isOwnAuthority("media", pkg))
        assertFalse(ShareOrigin.isOwnAuthority("cc.bridgit.apple.files", pkg))
        assertFalse(ShareOrigin.isOwnAuthority("cc.bridgit.appx", pkg))
        assertFalse(ShareOrigin.isOwnAuthority(null, pkg))
    }
}
