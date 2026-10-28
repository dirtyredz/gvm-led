package com.dirtyredz.gvmled.data.local

import android.annotation.SuppressLint
import android.content.SharedPreferences
import com.dirtyredz.gvmled.data.repository.WantedStateStore

/**
 * SharedPreferences adapter for per-light wanted state (its own file, separate from the catalog).
 * Rules live in [WantedStateCodec]; this reads and writes the raw document with `commit()`, so a
 * batch is durable before it is published or sent to a light.
 */
class WantedStatePreferences(private val prefs: SharedPreferences) : WantedStateStore {
    override fun read(): String? = prefs.getString(KEY_WANTED, null)

    @SuppressLint("UseKtx") // the KTX edit {} drops commit()'s result, which reports a failed durable write
    override fun write(text: String): Boolean = prefs.edit().putString(KEY_WANTED, text).commit()

    private companion object {
        const val KEY_WANTED = "wanted"
    }
}
