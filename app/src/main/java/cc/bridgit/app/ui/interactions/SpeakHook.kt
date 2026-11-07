package cc.bridgit.app.ui.interactions

import android.content.Context

/**
 * Play aloud (UI-16). BridgeApp.onCreate points it at the shared speaker queue (SpeakerQueue.speakText).
 * While it is null, the detail screen hides Play aloud.
 */
object SpeakHook {
    /** (context, agent name, title, text) */
    @Volatile var speak: ((Context, String, String, String?) -> Unit)? = null
}
