package cc.bridgit.app.lan

object DeviceLastSeen {
    fun relative(seen: Long, now: Long = System.currentTimeMillis()): String {
        if (seen <= 0) return "Not yet seen"
        val minutes = (now - seen).coerceAtLeast(0) / 60_000
        return when {
            minutes == 0L -> "Just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 1440 -> "${minutes / 60} hr ago"
            else -> "${minutes / 1440} d ago"
        }
    }
}
