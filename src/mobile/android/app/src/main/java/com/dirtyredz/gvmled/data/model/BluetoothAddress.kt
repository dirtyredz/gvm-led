package com.dirtyredz.gvmled.data.model

import java.util.Locale

/** Canonical identity rules for Bluetooth device addresses across storage, BLE and connection code. */
object BluetoothAddress {
    private val NORMALIZED = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")

    fun normalize(address: String): String = address.trim().uppercase(Locale.ROOT)

    fun isNormalized(address: String): Boolean = NORMALIZED.matches(address)
}
