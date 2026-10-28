package com.dirtyredz.gvmled.connection

import java.util.Locale

/**
 * Decides what an advertisement means against the current configured set. Identity is the address:
 * the lights all advertise the same name `BT_LED`, and they advertise 0x180F/0x1812 rather than the
 * legacy service, so the custom service is never required here (it is validated after connecting).
 */
class DiscoveryFilter(knownAddresses: Collection<String>) {
    private val known = knownAddresses.map(::normalizeAddress).toSet()

    fun isKnown(address: String): Boolean = normalizeAddress(address) in known

    fun classify(advertisement: Advertisement): Sighting? {
        val address = normalizeAddress(advertisement.address)
        return when {
            address in known -> Sighting.Known(address)
            advertisement.name?.contains(LEGACY_NAME) == true -> Sighting.OtherLegacyLight(address)
            else -> null
        }
    }

    companion object {
        const val LEGACY_NAME = "BT_LED"
    }
}

sealed interface Sighting {
    val address: String

    /** One of the configured lights; eligible for a connection. */
    data class Known(override val address: String) : Sighting

    /** A `BT_LED` device that is not configured; offered for enrollment, never connected. */
    data class OtherLegacyLight(override val address: String) : Sighting
}

fun normalizeAddress(address: String): String = address.trim().uppercase(Locale.ROOT)
