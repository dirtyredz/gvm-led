package com.dirtyredz.gvmled.data.local

import android.annotation.SuppressLint
import android.content.SharedPreferences
import com.dirtyredz.gvmled.data.model.LightCatalog
import com.dirtyredz.gvmled.data.repository.LightCatalogStore

/**
 * SharedPreferences adapter for the light catalog (file `lights`, shared with version 1). Rules live
 * in [LightCatalogCodec]; this only reads raw keys and writes complete snapshots with `commit()` so a
 * mutation is durable before it is published.
 */
class LightCatalogPreferences(private val prefs: SharedPreferences) : LightCatalogStore {
    override fun read(): StoredCatalog = StoredCatalog(
        catalogJson = prefs.getString(KEY_CATALOG, null),
        legacyVersion = prefs.getInt(KEY_VERSION, 0),
        legacyAddresses = prefs.getString(KEY_ADDRESSES, null),
        legacyNames = prefs.all.mapNotNull { (key, value) ->
            if (key.startsWith(KEY_NAME_PREFIX) && value is String) key.removePrefix(KEY_NAME_PREFIX) to value else null
        }.toMap(),
    )

    /** Writes the whole catalog and retires the version-1 keys in the same commit. */
    @SuppressLint("UseKtx") // the KTX edit {} drops commit()'s result, which reports a failed durable write
    override fun write(catalog: LightCatalog): Boolean {
        val editor = prefs.edit()
            .putString(KEY_CATALOG, LightCatalogCodec.encode(catalog))
            .putInt(KEY_VERSION, LightCatalogCodec.VERSION)
            .remove(KEY_ADDRESSES)
        prefs.all.keys.filter { it.startsWith(KEY_NAME_PREFIX) }.forEach(editor::remove)
        return editor.commit()
    }

    private companion object {
        const val KEY_CATALOG = "catalog"
        const val KEY_VERSION = "version"
        const val KEY_ADDRESSES = "addresses"
        const val KEY_NAME_PREFIX = "name."
    }
}
