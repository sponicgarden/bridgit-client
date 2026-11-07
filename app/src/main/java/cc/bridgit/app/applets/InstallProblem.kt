package cc.bridgit.app.applets

/**
 * Plain words for an install that didn't go through, for the owner's review screen. Pure, so it is unit-tested.
 * The raw message stays in the API; the owner sees what happened and, where one fits, a fix they can tap.
 * Wording from the Sol 6.1 design pass (4 Nov 2026): no API paths or field names, title and body never repeat.
 */
object InstallProblem {
    /** [fixInterval]: seconds between checks that would fit the phone's limit, offered as "Use every N seconds". */
    data class Words(val title: String, val body: String, val fixInterval: Int? = null)

    /**
     * [extra] is the watch refusal's details (checks_per_hour_used: the phone's other watches, checks_per_hour_limit).
     * [interval], [intervalMin] and [intervalMax] describe the applet's "seconds between checks" choice, null when it has none.
     */
    fun words(code: String?, message: String?, extra: Map<String, Any?>, interval: Int?, intervalMin: Int?, intervalMax: Int?, update: Boolean = false): Words {
        val limit = (extra["checks_per_hour_limit"] as? Number)?.toInt()
        val used = (extra["checks_per_hour_used"] as? Number)?.toInt() ?: 0
        if (code == "rate_limited" && limit != null) {
            val room = limit - used
            if (room <= 0) return Words("No room for more checks",
                "This phone's watches already use its allowance of $limit paid checks an hour. Change or remove another watch, then try again.")
            val allowance = "This phone allows $limit paid checks an hour across all watches." +
                (if (used > 0) " Your other watches use $used, leaving $room." else "")
            val fix = if (interval != null && intervalMin != null && intervalMax != null) fitInterval(room, interval, intervalMin, intervalMax) else null
            return if (fix != null) Words("This applet checks too often", "$allowance Checking every $fix seconds fits.", fix)
                else Words("This applet checks too often", "$allowance Choose a longer time between checks, then try again.")
        }
        val title = if (update) "Couldn't update applet" else "Couldn't install applet"
        val kept = if (update) "The installed applet keeps running as before." else "Nothing was changed on this phone."
        // A switch turned off after the review: the blocker sentence says which, minus its codes in brackets.
        val text = message?.removePrefix("Nothing was installed: ")?.trim().orEmpty()
            .let { if (code == "capability_disabled") plain(it) else it }
        return Words(title, if (text.isEmpty() || technical(text)) "Something went wrong. $kept Please try again." else if (update) text else "$text $kept")
    }

    /** A blocker sentence without its codes in brackets ("… in Device tools (feature_disabled)."). */
    fun plain(text: String): String = text.replace(Regex("""\s*\([a-z_, ]+\)"""), "")

    /** Messages written for agents (paths, field names, codes) aren't shown to the owner. */
    fun technical(text: String): Boolean = Regex("""/v\d|\b[a-z]+_[a-z_]+\b|[{}\[\]]|Exception""").containsMatchIn(text)

    /** The shortest round number of seconds, above [current], that makes at most [room] checks an hour; null if none fits. */
    fun fitInterval(room: Int, current: Int, min: Int, max: Int): Int? {
        if (room <= 0) return null
        val need = (3600 + room - 1) / room
        val step = if (need <= 300) 15 else 60
        val v = maxOf(((need + step - 1) / step) * step, min)
        return v.takeIf { it in min..max && it > current }
    }
}
