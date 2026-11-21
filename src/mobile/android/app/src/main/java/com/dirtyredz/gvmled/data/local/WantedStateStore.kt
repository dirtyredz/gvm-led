package com.dirtyredz.gvmled.data.local

/** Durable wanted-state storage (`WantedStatePreferences`); tests use an in-memory fake. */
interface WantedStateStore {
    /** The saved document, or null when nothing has been saved yet. */
    fun read(): String?

    /** Writes the complete document durably; false when the write failed. */
    fun write(text: String): Boolean
}
