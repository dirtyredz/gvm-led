package com.dirtyredz.gvmled.ble

import java.util.UUID

/**
 * The legacy `BT_LED` GATT layout (protocol.md, PC scan results). It is not advertised, so it is
 * validated after service discovery instead of being used as a scan filter.
 */
object LegacyGattProfile {
    val SERVICE: UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1910")
    val CHARACTERISTIC: UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d2b10")
    val CLIENT_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE and PROPERTY_NOTIFY. */
    private const val PROPERTY_WRITE_NO_RESPONSE = 0x04
    private const val PROPERTY_NOTIFY = 0x10

    /** The characteristic must notify and accept write-without-response, the only write type used. */
    fun isUsable(properties: Int): Boolean =
        properties and PROPERTY_NOTIFY != 0 && properties and PROPERTY_WRITE_NO_RESPONSE != 0
}
