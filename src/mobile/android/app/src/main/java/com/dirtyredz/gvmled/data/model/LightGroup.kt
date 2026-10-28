package com.dirtyredz.gvmled.data.model

/**
 * A named selection of known lights. [id] is generated once and never changes; [name] is an editable
 * label. A group holds addresses only: no sessions, connections or wanted settings of its own.
 */
data class LightGroup(val id: String, val name: String, val members: List<String>)
