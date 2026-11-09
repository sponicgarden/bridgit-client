package cc.bridgit.app.platform

import org.json.JSONObject

/**
 * Which agent operations wait for an owner tap before they run (A0053).
 *
 * Owner approval belongs at grant time: an agent holding the scope runs the action
 * with no human step. Only actions with a privacy or security reason still park as
 * `awaiting_owner`.
 */
object OwnerApprovalPolicy {
    private val alwaysReviewed = setOf(
        "ui.action", "ui.sequence", "notification.reply", "peripheral.action",
        "speech.transcribe", "stream.start", "alert.trigger", "owner.configure",
    )

    /** Phone controls gated only by the phone.control scope; the v1 routes run them unattended too. */
    val unattendedDeviceActions = setOf(
        "flashlight", "volume", "dnd", "brightness", "ring", "media", "battery_saver", "screen_timeout",
    )

    fun needsOwnerTap(command: String, args: JSONObject): Boolean = when (command) {
        in alwaysReviewed -> true
        // Camera, microphone, clipboard, speech, links, apps and drafts keep their review.
        "device.action" -> args.optString("type") !in unattendedDeviceActions
        // translation.download fetches an on-device model over Wi-Fi; no owner data leaves the phone.
        else -> false
    }
}
