package cc.bridgit.app.platform

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build

/**
 * What kind of device a Bridgit device is (owner, 5 Nov 2026: "This tablet", not "This phone", on the A11). This
 * device works it out from its own hardware ([of]); linked hubs, satellites and portals send theirs as
 * `device_type` ([wire]), and one that doesn't (an older version) reads as null, so the screen says "Device".
 */
enum class DeviceType(val wire: String, val noun: String) {
    PHONE("phone", "phone"), TABLET("tablet", "tablet"), COMPUTER("computer", "computer"),
    TV("tv", "TV"), WATCH("watch", "watch"), CAMERA("camera", "camera");

    /** "Phone", "Tablet", "TV". */
    val title: String get() = noun.replaceFirstChar { it.uppercase() }

    companion object {
        /** The JSON field linked devices send it in. */
        const val FIELD = "device_type"

        /** A wire value from another device; null for a missing or unknown one (an older or newer version). */
        fun parse(s: String?): DeviceType? = s?.trim()?.lowercase()?.let { v -> entries.firstOrNull { it.wire == v } }

        @Volatile private var cached: DeviceType? = null

        /** This device's type; it does not change while the app runs. */
        fun of(context: Context): DeviceType = cached ?: detect(
            features = context.packageManager.let { pm -> FEATURES.filter { pm.hasSystemFeature(it) }.toSet() },
            uiModeType = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK,
            smallestWidthDp = context.resources.configuration.smallestScreenWidthDp,
            model = Build.MODEL.orEmpty(), device = Build.DEVICE.orEmpty()).also { cached = it }

        private val FEATURES = listOf(PackageManager.FEATURE_WATCH, PackageManager.FEATURE_LEANBACK,
            PackageManager.FEATURE_PC, "org.chromium.arc", "org.chromium.arc.device_management", "com.sec.feature.tablet")

        /**
         * Pure, for tests. A watch, TV or computer (Chromebook, Android desktop) by its system feature or UI mode; then a
         * tablet by model or by a smallest width of 600 dp (display zoom and large text can shrink a tablet below
         * 600 dp, so known tablet models count too); otherwise a phone.
         */
        fun detect(features: Set<String>, uiModeType: Int, smallestWidthDp: Int, model: String, device: String = ""): DeviceType {
            if (PackageManager.FEATURE_WATCH in features || uiModeType == Configuration.UI_MODE_TYPE_WATCH) return WATCH
            if (PackageManager.FEATURE_LEANBACK in features || uiModeType == Configuration.UI_MODE_TYPE_TELEVISION) return TV
            if (PackageManager.FEATURE_PC in features || features.any { it.startsWith("org.chromium.arc") } ||
                device.endsWith("_cheets")) return COMPUTER
            val m = model.lowercase()
            val tabletModel = Regex("^sm-[xt]\\d").containsMatchIn(m) || m.contains("tablet") || m.contains(" tab")
            if (tabletModel || "com.sec.feature.tablet" in features || smallestWidthDp >= 600) return TABLET
            return PHONE
        }
    }
}
