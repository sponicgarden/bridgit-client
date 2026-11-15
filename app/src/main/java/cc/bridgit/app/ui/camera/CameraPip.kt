package cc.bridgit.app.ui.camera

/**
 * A0504 (lineage A0433, A0406, A0427, A0430): the Camera tab's live preview shrinks into a picture-in-picture
 * window when the owner leaves Bridgit (Home, Recents), instead of the camera closing. Pure rules, so they are
 * unit-tested; [cc.bridgit.app.ui.MainActivity] applies them.
 */
object CameraPip {
    /** Android refuses a PiP aspect ratio wider than 2.39:1 or taller than 1:2.39. */
    private const val MAX_RATIO = 2.39

    /**
     * Whether leaving the app now should open the PiP window: only from the Camera tab while its preview is
     * live (permission granted, camera bound), on a device that supports PiP, and not when already in PiP or
     * finishing (Back closes the app as before).
     */
    fun shouldEnter(supported: Boolean, onCameraTab: Boolean, previewLive: Boolean, inPip: Boolean, finishing: Boolean) =
        supported && onCameraTab && previewLive && !inPip && !finishing

    /**
     * The PiP window's shape as a numerator and denominator: the preview's own shape, clamped just inside what Android
     * accepts. A preview not laid out yet (0 size) gets the camera's usual portrait 3:4.
     */
    fun aspect(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 3 to 4
        val ratio = width.toDouble() / height
        return when {
            ratio > MAX_RATIO -> 238 to 100
            ratio < 1 / MAX_RATIO -> 100 to 238
            else -> width to height
        }
    }
}
