package com.dirtyredz.gvmled.data.local

import android.content.SharedPreferences
import androidx.core.content.edit
import com.dirtyredz.gvmled.connection.normalizeAddress
import com.dirtyredz.gvmled.data.repository.LightNameStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Versioned SharedPreferences store for the configured light addresses and their names. Version 1
 * seeds the owner's two lights on first run. Wanted settings join this store in a later slice.
 */
class LightPreferences(private val prefs: SharedPreferences) : LightNameStore {
    init {
        if (prefs.getInt(KEY_VERSION, 0) < VERSION) {
            prefs.edit {
                putInt(KEY_VERSION, VERSION)
                putString(KEY_ADDRESSES, SEEDED_ADDRESSES.joinToString(","))
            }
        }
    }

    val addresses: List<String> =
        prefs.getString(KEY_ADDRESSES, null)?.split(",")?.filter { it.isNotBlank() }?.map(::normalizeAddress)
            ?: SEEDED_ADDRESSES

    private val mutableNames = MutableStateFlow(readNames())
    override val names: StateFlow<Map<String, String>> = mutableNames.asStateFlow()

    override fun rename(address: String, name: String?) {
        val key = KEY_NAME_PREFIX + normalizeAddress(address)
        prefs.edit { if (name == null) remove(key) else putString(key, name) }
        mutableNames.value = readNames()
    }

    private fun readNames(): Map<String, String> = addresses.mapNotNull { address ->
        prefs.getString(KEY_NAME_PREFIX + address, null)?.let { address to it }
    }.toMap()

    companion object {
        private const val VERSION = 1
        private const val KEY_VERSION = "version"
        private const val KEY_ADDRESSES = "addresses"
        private const val KEY_NAME_PREFIX = "name."

        /** The owner's lights (protocol.md, PC scan results): light 1, then light 2. */
        val SEEDED_ADDRESSES = listOf("94:C9:60:E5:D7:6F", "94:C9:60:EB:67:E6")
    }
}
