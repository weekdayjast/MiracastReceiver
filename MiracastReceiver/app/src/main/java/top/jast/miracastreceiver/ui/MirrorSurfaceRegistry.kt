package top.jast.miracastreceiver.ui

internal enum class MirrorSurfaceSource { AIRPLAY, MIRACAST }

/** Publishes one output target with both its Activity owner and receiving protocol. */
internal class MirrorSurfaceRegistry<T : Any> {
    private data class Publication<T>(val owner: Any, val surface: T, val source: MirrorSurfaceSource)
    @Volatile private var current: Publication<T>? = null

    val surface: T? get() = current?.surface

    fun surfaceFor(source: MirrorSurfaceSource): T? =
        current?.takeIf { it.source == source }?.surface

    /** A stale Activity may clear only its own publication, never its successor's. */
    @Synchronized fun publish(owner: Any, surface: T?, source: MirrorSurfaceSource?): Boolean {
        val previous = current
        if (surface == null || source == null) {
            if (previous?.owner !== owner) return false
            current = null
            return true
        }
        if (previous?.owner === owner && previous.surface === surface && previous.source == source) return false
        current = Publication(owner, surface, source)
        return true
    }
}
