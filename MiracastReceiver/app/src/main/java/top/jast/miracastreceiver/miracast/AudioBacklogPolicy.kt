package top.jast.miracastreceiver.miracast

/** PES count is not a duration: a short network burst must not be mistaken for clock drift. */
internal object AudioBacklogPolicy {
    private const val MAX_BACKLOG_US = 600_000L
    private const val KEEP_US = 200_000L

    fun dropCount(pts: List<Long>): Int {
        if (pts.size < 2 || pts.any { it <= 0 } || pts.zipWithNext().any { (a, b) -> b < a }) return 0
        val latest = pts.last()
        if (latest - pts.first() <= MAX_BACKLOG_US) return 0
        return pts.indexOfFirst { latest - it <= KEEP_US }.coerceAtLeast(0)
    }
}
