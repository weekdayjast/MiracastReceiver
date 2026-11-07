package top.jast.miracastreceiver.miracast

import org.junit.Assert.*
import org.junit.Test

class P2pDeviceNameTest {
    @Test fun roomNamesAndSpacesArePreserved() {
        assertEquals("客厅电视", P2pDeviceName.fromDisplayName(" 客厅电视 "))
        assertEquals("Living Room TV", P2pDeviceName.fromDisplayName("Living Room TV"))
    }

    @Test fun wpsLimitDoesNotSplitUtf8OrEmoji() {
        assertEquals("客".repeat(10), P2pDeviceName.fromDisplayName("客".repeat(16)))
        assertEquals("📺".repeat(8), P2pDeviceName.fromDisplayName("📺".repeat(12)))
        assertEquals("a".repeat(32), P2pDeviceName.fromDisplayName("a".repeat(48)))
    }

    @Test fun invalidControlDataCannotBecomeASupplicantCommand() {
        assertNull(P2pDeviceName.command("/bin/helper", "/socket", "TV\nSET wifi_display 0"))
        assertNull(P2pDeviceName.fromDisplayName("\u0000TV"))
        assertNull(P2pDeviceName.fromDisplayName("bad\uD800"))
        assertNull(P2pDeviceName.fromDisplayName(" "))
    }

    @Test fun shellMetacharactersAreQuotedAsSingleArguments() {
        val name = "TV'\"\u0024(test)`id`; &"
        assertEquals("'/bin/helper' '/socket' 'SET device_name TV'\"'\"'\"\u0024(test)`id`; &'",
            P2pDeviceName.command("/bin/helper", "/socket", name))
    }
}
