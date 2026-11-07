package cc.bridgit.app.dog

/**
 * Which camera the Pet monitor uses (owner, 2 Nov 2026: "figure out the right camera, test all
 * cameras and use the best one or more"). A scan takes one test photo per camera and asks the model
 * whether a dog is visible; [pick] chooses from those answers. Pure (no Android), unit-tested.
 *
 * Bridgit's camera code opens the phone's default rear and default front cameras, so those are the
 * cameras a scan can test.
 */
object DogCamera {
    const val REAR = "rear"
    const val FRONT = "front"
    val ALL = listOf(REAR, FRONT)
    fun isCamera(id: String) = id in ALL || id.matches(Regex("lan:[a-zA-Z0-9-]{1,60}"))
    fun isMode(id: String) = id == AUTO || id == MANUAL || isCamera(id)

    /** The owner's choice on the screen. */
    const val AUTO = "auto"
    const val MANUAL = "manual"
    /** Auto may rescan the tablet cameras, which cannot capture while it is locked. */
    fun requiresScreen(mode: String, cameras: List<String> = emptyList()): Boolean = if (mode == MANUAL) cameras.any { !it.startsWith("lan:") } else !mode.startsWith("lan:")
    fun toggle(mode: String, cameras: List<String>, id: String, checked: Boolean): Pair<String,List<String>> {
        require(id == AUTO || isCamera(id))
        if (id == AUTO) return if (checked) AUTO to emptyList() else mode to cameras
        val selected = if (mode == AUTO) emptyList() else cameras
        val next = (if (checked) selected + id else selected - id).distinct()
        return if (next.isEmpty()) AUTO to emptyList() else MANUAL to next
    }
    val MODES = listOf(AUTO, REAR, FRONT)

    /** A challenger must beat the camera in use by this much to take over; cameras this close to the best take turns. */
    const val MARGIN = 0.15

    enum class Why { BEST, SHARED, NO_DOG, ONLY_ONE }

    /**
     * What a scan decided. [cameras]: the ones to use, best first; more than one take turns photo by photo.
     * [scores]: dog_present per camera tested (null = that camera couldn't take a photo or doesn't exist).
     */
    data class Choice(val cameras: List<String>, val why: Why, val scores: Map<String, Double?>, val at: Long) {
        val primary get() = cameras.first()
    }

    /**
     * [scores]: dog_present per camera (a probability, from a noul question). [current]: the camera in
     * use before the scan, kept when no camera sees a dog and preferred when it is about as good as the best.
     */
    fun pick(scores: Map<String, Double?>, current: String?, at: Long, min: Double = DogJev.DOG_MIN, margin: Double = MARGIN): Choice {
        val seen = scores.filterValues { it != null }.mapValues { it.value!! }
        // Keep the camera in use, unless it couldn't take a photo and another one could.
        val keep = current?.takeIf { isCamera(it) && (it in seen || seen.isEmpty()) } ?: seen.maxByOrNull { it.value }?.key ?: REAR
        if (seen.size == 1 && seen.values.first() >= min) return Choice(listOf(seen.keys.first()), Why.ONLY_ONE, scores, at)
        val best = seen.maxByOrNull { it.value } ?: return Choice(listOf(keep), Why.NO_DOG, scores, at)
        if (best.value < min) return Choice(listOf(keep), Why.NO_DOG, scores, at)
        // Stay on the camera in use unless the other one is clearly better (no flapping between near-equal views).
        val cur = seen[keep]
        val lead = if (cur != null && cur >= min && best.value - cur < margin) keep else best.key
        val top = seen.getValue(lead)
        val others = seen.filter { it.key != lead && it.value >= min && top - it.value < margin }
            .entries.sortedByDescending { it.value }.map { it.key }
        return Choice(listOf(lead) + others, if (others.isEmpty()) Why.BEST else Why.SHARED, scores, at)
    }

    /** The camera for photo number [n] when [cameras] take turns. */
    fun forPhoto(cameras: List<String>, n: Int): String = cameras[Math.floorMod(n, cameras.size)]

    /** Search once every active view has been missing the dog for its own delay.
     * A completed search starts a fresh wait, bounding retries even when nobody sees the dog.
     */
    fun rescanDue(now: Long, lastDogAt: Map<String, Long>, lastScanAt: Long,
                  cameras: List<String>, delayMs: (String) -> Long): Boolean =
        cameras.isNotEmpty() && cameras.all { camera ->
            now - maxOf(lastDogAt[camera] ?: lastScanAt, lastScanAt) >= delayMs(camera)
        }

}
