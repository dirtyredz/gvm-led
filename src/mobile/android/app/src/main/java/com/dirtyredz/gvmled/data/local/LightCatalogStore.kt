package com.dirtyredz.gvmled.data.local

import com.dirtyredz.gvmled.data.model.LightCatalog

/** Durable catalog storage (`LightCatalogPreferences`); tests use an in-memory fake. */
interface LightCatalogStore {
    fun read(): StoredCatalog

    /** Writes the complete snapshot durably; false when the write failed. */
    fun write(catalog: LightCatalog): Boolean
}
