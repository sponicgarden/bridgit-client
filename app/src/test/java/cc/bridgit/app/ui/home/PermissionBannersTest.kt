package cc.bridgit.app.ui.home

import cc.bridgit.app.ui.home.PermissionBanners.Blocked
import cc.bridgit.app.ui.home.PermissionBanners.Group
import cc.bridgit.app.ui.home.PermissionBanners.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionBannersTest {
    private val mic = "android.permission.RECORD_AUDIO"
    private val cam = "android.permission.CAMERA"
    private val img = "android.permission.READ_MEDIA_IMAGES"
    private val vid = "android.permission.READ_MEDIA_VIDEO"
    private val sel = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
    private val loc = "android.permission.ACCESS_FINE_LOCATION"

    /** What Pixel 2 XL showed after "Max agentic power" with every prompt denied: 3x mic, 2x photos, location, camera. */
    @Test fun oneBannerPerPermissionAfterMaxPower() {
        val blocked = listOf(
            Blocked("CAMERA", listOf(cam)),
            Blocked("RECORD_AUDIO", listOf(mic)),
            Blocked("READ_MEDIA_IMAGES", listOf(img, sel)),
            Blocked("RECORD_AUDIO", listOf(mic)),
            Blocked("READ_MEDIA_VIDEO", listOf(vid, sel)),
            Blocked("RECORD_AUDIO", listOf(mic)),
            Blocked("ACCESS_FINE_LOCATION", listOf(loc)),
        )
        assertEquals(listOf(
            Group(Kind.CAMERA, listOf(cam)),
            Group(Kind.MIC, listOf(mic)),
            Group(Kind.PHOTOS, listOf(img, sel, vid)),
            Group(Kind.LOCATION, listOf(loc)),
        ), PermissionBanners.group(blocked))
    }

    @Test fun nothingBlockedNoBanners() = assertEquals(emptyList<Group>(), PermissionBanners.group(emptyList()))

    @Test fun locationIsNotLabelledPhotos() {
        assertEquals(Kind.LOCATION, PermissionBanners.kindOf("ACCESS_FINE_LOCATION"))
        assertEquals(Kind.LOCATION, PermissionBanners.kindOf(loc))
        assertEquals(Kind.PHOTOS, PermissionBanners.kindOf("READ_MEDIA_VIDEO"))
        assertEquals(Kind.MIC, PermissionBanners.kindOf("RECORD_AUDIO"))
    }
}
