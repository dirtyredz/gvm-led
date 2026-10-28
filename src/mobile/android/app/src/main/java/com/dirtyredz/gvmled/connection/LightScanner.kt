package com.dirtyredz.gvmled.connection

import kotlinx.coroutines.flow.Flow

/** One advertisement seen during a scan. Advertised service UUIDs are informational only. */
data class Advertisement(
    val address: String,
    val name: String?,
    val rssi: Int,
    val serviceUuids: List<String> = emptyList(),
)

/** Platform discovery seam. The flow scans while collected and stops when collection ends. */
fun interface LightScanner {
    fun scan(): Flow<Advertisement>
}
