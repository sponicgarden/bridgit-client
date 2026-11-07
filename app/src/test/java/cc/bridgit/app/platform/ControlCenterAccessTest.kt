package cc.bridgit.app.platform

import android.Manifest
import android.provider.Settings
import android.widget.TextView
import cc.bridgit.app.BridgeApp
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ControlCenterAccessTest {
    @Test fun alreadyGrantedMediaAndLocationOpenAppSettings() {
        val app = RuntimeEnvironment.getApplication() as BridgeApp
        app.settings.listenerEnabled = false
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION)
        Robolectric.buildActivity(ControlCenterActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val title = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
            fun click(label: String) {
                fun find(view: android.view.View): TextView? {
                    if (view is TextView && view.text.toString() == label) return view
                    if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
                        find(view.getChildAt(i))?.let { return it }
                    }
                    return null
                }
                val text = requireNotNull(find(title)) { label }
                assertTrue((text.parent as android.view.View).performClick())
                val intent = shadowOf(activity).nextStartedActivity
                assertNotNull(label, intent)
                assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
                assertEquals("package:${activity.packageName}", intent.data.toString())
            }
            click("Grant camera, microphone, photos and notifications")
            click("Grant location")
            if (cc.bridgit.app.BuildConfig.PRIVATE_CONTROL) click("Grant Nearby devices")
        }
    }
}
