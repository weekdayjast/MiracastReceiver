package top.jast.miracastreceiver.ui

import org.junit.Assert.*
import org.junit.Test

class MirrorSurfaceRegistryTest {
    @Test fun androidSessionCannotConsumeThePreviousIphoneTarget() {
        val registry = MirrorSurfaceRegistry<Any>()
        val screen = Any()
        registry.publish(Any(), screen, MirrorSurfaceSource.AIRPLAY)
        assertSame(screen, registry.surfaceFor(MirrorSurfaceSource.AIRPLAY))
        assertNull(registry.surfaceFor(MirrorSurfaceSource.MIRACAST))
    }

    @Test fun reusingTheSameActivityAndSurfaceStillNotifiesAProtocolSwitch() {
        val registry = MirrorSurfaceRegistry<Any>()
        val owner = Any()
        val screen = Any()
        registry.publish(owner, screen, MirrorSurfaceSource.AIRPLAY)
        assertTrue(registry.publish(owner, screen, MirrorSurfaceSource.MIRACAST))
        assertNull(registry.surfaceFor(MirrorSurfaceSource.AIRPLAY))
        assertSame(screen, registry.surfaceFor(MirrorSurfaceSource.MIRACAST))
    }

    @Test fun previousActivityDestructionCannotClearTheReplacementTarget() {
        val registry = MirrorSurfaceRegistry<Any>()
        val old = Any()
        val replacement = Any()
        val screen = Any()
        registry.publish(old, Any(), MirrorSurfaceSource.AIRPLAY)
        registry.publish(replacement, screen, MirrorSurfaceSource.MIRACAST)
        assertFalse(registry.publish(old, null, null))
        assertSame(screen, registry.surfaceFor(MirrorSurfaceSource.MIRACAST))
    }

    @Test fun endingTheCurrentSessionClearsBothProtocolViews() {
        val registry = MirrorSurfaceRegistry<Any>()
        val owner = Any()
        registry.publish(owner, Any(), MirrorSurfaceSource.AIRPLAY)
        assertTrue(registry.publish(owner, null, null))
        assertNull(registry.surface)
        assertNull(registry.surfaceFor(MirrorSurfaceSource.AIRPLAY))
        assertNull(registry.surfaceFor(MirrorSurfaceSource.MIRACAST))
    }

    @Test fun unchangedSurfaceDoesNotRepeatedlyRebindTheRenderer() {
        val registry = MirrorSurfaceRegistry<Any>()
        val owner = Any()
        val screen = Any()
        assertTrue(registry.publish(owner, screen, MirrorSurfaceSource.AIRPLAY))
        assertFalse(registry.publish(owner, screen, MirrorSurfaceSource.AIRPLAY))
    }
}
