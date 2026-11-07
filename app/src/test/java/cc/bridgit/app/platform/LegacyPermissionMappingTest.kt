package cc.bridgit.app.platform

import android.Manifest
import android.app.Application
import cc.bridgit.app.capability.Perms
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class LegacyPermissionMappingTest {
    @Test fun mediaRequestUsesStorageAndOmitsUnavailablePermissionsOnPixel2Android10() {
        assertEquals(listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE), Perms.forRequest(arrayOf(
            Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)).toList())
    }
}
