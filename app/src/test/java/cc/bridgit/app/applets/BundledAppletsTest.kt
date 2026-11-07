package cc.bridgit.app.applets

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class BundledAppletsTest {
    @Test fun apkDoesNotBundleAppletPackages() {
        val files = File("src/main/assets/applets").walkTopDown().filter { it.isFile }.toList()
        assertFalse("Applet packages must be downloaded from the registry", files.isNotEmpty())
    }
}
