package com.dirtyredz.gvmled.light.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveryFilterTest {
    private val filter = DiscoveryFilter(listOf("94:C9:60:E5:D7:6F", "94:c9:60:eb:67:e6"))

    // The lights advertise Battery/HID UUIDs, never the legacy service.
    private val advertised = listOf("0000180f-0000-1000-8000-00805f9b34fb", "00001812-0000-1000-8000-00805f9b34fb")

    @Test fun knownAddressesMatchWithoutTheLegacyServiceAdvertised() {
        assertEquals(
            Sighting.Known("94:C9:60:E5:D7:6F"),
            filter.classify(Advertisement("94:C9:60:E5:D7:6F", "BT_LED", -71, advertised)),
        )
        assertEquals(
            Sighting.Known("94:C9:60:EB:67:E6"),
            filter.classify(Advertisement("94:C9:60:EB:67:E6", "BT_LED", -73, advertised)),
        )
    }

    @Test fun identityIsTheAddressNotTheNameOrCase() {
        assertEquals(
            Sighting.Known("94:C9:60:E5:D7:6F"),
            filter.classify(Advertisement("94:c9:60:e5:d7:6f", null, -90)),
        )
        assertEquals(
            Sighting.Known("94:C9:60:EB:67:E6"),
            filter.classify(Advertisement("94:C9:60:EB:67:E6", "Something else", -60)),
        )
    }

    @Test fun unknownBtLedIsReportedSeparately() {
        assertEquals(
            Sighting.OtherLegacyLight("11:22:33:44:55:66"),
            filter.classify(Advertisement("11:22:33:44:55:66", "BT_LED", -80, advertised)),
        )
    }

    @Test fun unrelatedDevicesAreIgnored() {
        assertNull(filter.classify(Advertisement("AA:BB:CC:DD:EE:FF", "ELK-BLEDOM", -50)))
        assertNull(filter.classify(Advertisement("AA:BB:CC:DD:EE:F0", null, -50)))
    }
}
