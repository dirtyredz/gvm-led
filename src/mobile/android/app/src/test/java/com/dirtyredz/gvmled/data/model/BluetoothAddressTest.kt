package com.dirtyredz.gvmled.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothAddressTest {
    @Test fun normalizationIsSharedByEveryAddressConsumer() {
        assertEquals("94:C9:60:E5:D7:6F", BluetoothAddress.normalize(" 94:c9:60:e5:d7:6f "))
    }

    @Test fun onlyCanonicalSixByteAddressesAreValid() {
        assertTrue(BluetoothAddress.isNormalized("94:C9:60:E5:D7:6F"))
        assertFalse(BluetoothAddress.isNormalized("94:c9:60:e5:d7:6f"))
        assertFalse(BluetoothAddress.isNormalized("BT_LED"))
    }
}
