package com.dirtyredz.gvmled.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyGattProfileTest {
    @Test fun uuidsMatchTheScannedGattTable() {
        assertEquals("00010203-0405-0607-0809-0a0b0c0d1910", LegacyGattProfile.SERVICE.toString())
        assertEquals("00010203-0405-0607-0809-0a0b0c0d2b10", LegacyGattProfile.CHARACTERISTIC.toString())
    }

    @Test fun characteristicNeedsNotifyAndWriteWithoutResponse() {
        assertTrue(LegacyGattProfile.isUsable(0x16)) // read | write-no-response | notify (as scanned)
        assertFalse(LegacyGattProfile.isUsable(0x12)) // read | notify
        assertFalse(LegacyGattProfile.isUsable(0x06)) // read | write-no-response
    }
}
