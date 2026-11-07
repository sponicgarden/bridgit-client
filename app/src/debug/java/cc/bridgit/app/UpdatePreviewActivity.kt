package cc.bridgit.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import cc.bridgit.app.update.UpdateDialog
import cc.bridgit.app.update.UpdateLogic

/**
 * Debug builds only: shows one screen of the update dialog with sample data, over whatever is
 * on screen, so a device check can see every state without a real update. Finishes when it closes.
 *
 *   adb shell am start -n <package>/cc.bridgit.app.UpdatePreviewActivity --es screen UP_TO_DATE
 *
 * Add --es night yes|no for dark or light, and --ez auto true for the daily check's own offer (with Don't show again). Screens: CHECKING, UP_TO_DATE, LOCAL_AHEAD, NEWER, DOWNLOADING, READY, ERROR, DEBUG.
 */
class UpdatePreviewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // --es night yes|no previews dark or light without changing the shared device's theme.
        when (intent.getStringExtra("night")) {
            "yes" -> delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES
            "no" -> delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_NO
        }
        super.onCreate(savedInstanceState)
        // Shows over the lock screen (like an alarm), so a locked test tablet can still be checked.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
    }

    // After onCreate, so the dialog sees a created activity.
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        val screen = runCatching { UpdateLogic.Screen.valueOf(intent.getStringExtra("screen") ?: "") }
            .getOrDefault(UpdateLogic.Screen.UP_TO_DATE)
        // A theme switch recreates this activity and closes the dialog; the new instance shows it again.
        UpdateDialog.preview(this, screen, intent.getBooleanExtra("auto", false)) { if (!isFinishing && !isChangingConfigurations) finish() }
    }
}
