package com.dirtyredz.gvmled.data.repository

import com.dirtyredz.gvmled.data.local.StoredCatalog
import com.dirtyredz.gvmled.data.model.LightCatalog

/** Durable catalog storage (`data/local/LightCatalogPreferences`); tests use an in-memory fake. */
interface LightCatalogStore {
    fun read(): StoredCatalog

    /** Writes the complete snapshot durably; false when the write failed. */
    fun write(catalog: LightCatalog): Boolean
}
