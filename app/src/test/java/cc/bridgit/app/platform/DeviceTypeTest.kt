package cc.bridgit.app.platform

import android.content.pm.PackageManager
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceTypeTest {
    private fun detect(features: Set<String> = emptySet(), ui: Int = Configuration.UI_MODE_TYPE_NORMAL, sw: Int = 411,
                       model: String = "Pixel 10", device: String = "") = DeviceType.detect(features, ui, sw, model, device)

    @Test fun phoneByDefault() = assertEquals(DeviceType.PHONE, detect())
    @Test fun tabletByWidth() = assertEquals(DeviceType.TABLET, detect(sw = 600))
    // The A11 with display zoom reports under 600 dp; its model still makes it a tablet.
    @Test fun tabletByModel() = assertEquals(DeviceType.TABLET, detect(sw = 534, model = "SM-X230"))
    @Test fun tabletBySamsungFeature() = assertEquals(DeviceType.TABLET, detect(setOf("com.sec.feature.tablet"), sw = 500))
    @Test fun watch() = assertEquals(DeviceType.WATCH, detect(setOf(PackageManager.FEATURE_WATCH), sw = 200))
    @Test fun tvByFeature() = assertEquals(DeviceType.TV, detect(setOf(PackageManager.FEATURE_LEANBACK), sw = 540))
    @Test fun tvByUiMode() = assertEquals(DeviceType.TV, detect(ui = Configuration.UI_MODE_TYPE_TELEVISION, sw = 540))
    @Test fun chromebook() = assertEquals(DeviceType.COMPUTER, detect(setOf("org.chromium.arc"), sw = 800))
    @Test fun androidDesktop() = assertEquals(DeviceType.COMPUTER, detect(setOf(PackageManager.FEATURE_PC), sw = 800))

    @Test fun parse() {
        assertEquals(DeviceType.TABLET, DeviceType.parse("tablet"))
        assertEquals(DeviceType.TV, DeviceType.parse(" TV "))
        assertNull(DeviceType.parse(null))
        assertNull(DeviceType.parse("toaster"))
    }
}
