package cc.bridgit.app.ui

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import cc.bridgit.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BrandHeaderLayoutTest {
    @Test fun iconAndWordmarkMatchHeightAndFitPhoneAndTabletWidths() {
        for (width in listOf(320, 412, 800)) for (scale in listOf(1f, 1.5f, 2f)) {
            val app = RuntimeEnvironment.getApplication()
            val configuration = Configuration(app.resources.configuration).apply { fontScale = scale }
            val context = ContextThemeWrapper(app.createConfigurationContext(configuration), R.style.Theme_Bridgit)
            val root = LayoutInflater.from(context).inflate(R.layout.fragment_home, null)
            val brand = root.findViewById<View>(R.id.brand)
            val wordmark = root.findViewById<View>(R.id.brand_wordmark)
            val icon = root.findViewById<View>(R.id.brand_icon)
            val available = ((width - 32) * context.resources.displayMetrics.density).toInt()
            brand.measure(View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            brand.layout(0, 0, available, brand.measuredHeight)
            assertTrue("Wordmark follows the icon", wordmark.left > icon.right)
            assertEquals("Equal visible height", icon.height, wordmark.height)
            assertEquals("Top alignment", icon.top, wordmark.top)
            assertEquals("Bottom alignment", icon.bottom, wordmark.bottom)
            assertTrue("Header must fit at $width/$scale", wordmark.right <= available)
        }
    }
}
