package com.dirtyredz.gvmled.data.repository

import com.dirtyredz.gvmled.data.local.WantedStateCodec
import com.dirtyredz.gvmled.data.local.WantedStateFormatException
import com.dirtyredz.gvmled.data.model.ControlTarget
import com.dirtyredz.gvmled.data.model.LightCatalog
import com.dirtyredz.gvmled.data.model.BluetoothAddress
import com.dirtyredz.gvmled.light.control.CctRange
import com.dirtyredz.gvmled.light.control.EditPlan
import com.dirtyredz.gvmled.light.control.LightEdit
import com.dirtyredz.gvmled.light.control.TargetEditPlanner
import com.dirtyredz.gvmled.light.control.WantedRecord
import com.dirtyredz.gvmled.light.control.WantedState
import com.dirtyredz.gvmled.light.protocol.FullStateReport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface ControlResult {
    /** Saved and published for every member; each member's synchronizer now applies it. */
    data class Accepted(val members: List<String>) : ControlResult
    data class Rejected(val reason: String) : ControlResult
}

/**
 * Per-light wanted state: the only store of intent. A group is resolved to a snapshot of its members
 * when an action is submitted; each member's own state is patched, the whole batch is validated,
 * then written durably before it is published (and so before any light is written). Mutations are
 * serialized. Unreadable saved data pauses every change until [resetUnreadable].
 *
 * [ranges] gives each light's current hello CCT range (null until it has reported one).
 */
class LightControlRepository(
    private val store: WantedStateStore,
    private val io: CoroutineDispatcher,
    private val catalog: StateFlow<LightCatalog>,
    private val ranges: () -> Map<String, CctRange?>,
) {
    private val mutex = Mutex()
    private val mutableWanted: MutableStateFlow<Map<String, WantedRecord>>
    private val mutableProblem: MutableStateFlow<String?>

    init {
        val (records, problem) = try {
            WantedStateCodec.decode(store.read()) to null
        } catch (failure: WantedStateFormatException) {
            emptyMap<String, WantedRecord>() to (failure.message ?: "Saved light settings are unreadable")
        }
        mutableWanted = MutableStateFlow(records)
        mutableProblem = MutableStateFlow(problem)
    }

    /** Every light's latest wanted record, by normalized address. */
    val wanted: StateFlow<Map<String, WantedRecord>> = mutableWanted.asStateFlow()

    /** Why saved settings could not be used; null when healthy. */
    val problem: StateFlow<String?> = mutableProblem.asStateFlow()

    /** Applies [edit] to every member of [target], all or nothing. */
    suspend fun apply(target: ControlTarget, edit: LightEdit): ControlResult = mutex.withLock {
        if (mutableProblem.value != null) return ControlResult.Rejected(PAUSED)
        val known = catalog.value
        val members = target.members(known)
        val current = mutableWanted.value
        val plan = TargetEditPlanner.expand(
            members = members,
            wanted = current.mapValues { it.value.state },
            ranges = ranges(),
            name = { address -> known.light(address)?.name ?: address },
            edit = edit,
        )
        when (plan) {
            is EditPlan.Rejected -> ControlResult.Rejected(plan.reason)
            is EditPlan.Accepted -> {
                val next = current + plan.states.mapValues { (address, state) ->
                    WantedRecord((current[address]?.revision ?: 0) + 1, state)
                }
                if (commit(next)) ControlResult.Accepted(members) else ControlResult.Rejected(NOT_SAVED)
            }
        }
    }

    /**
     * Adopts a light's first valid full report as its intent when nothing is stored for it yet.
     * Returns the stored record, or null when the light already had intent or the save failed.
     */
    suspend fun seed(address: String, report: FullStateReport, range: CctRange): WantedRecord? = mutex.withLock {
        val key = BluetoothAddress.normalize(address)
        if (mutableProblem.value != null || key in mutableWanted.value) return null
        val state: WantedState = WantedState.fromReport(report, range) ?: return null
        val record = WantedRecord(1, state)
        if (commit(mutableWanted.value + (key to record))) record else null
    }

    /** Deliberately discards unreadable saved settings; lights are then seeded from their next reports. */
    suspend fun resetUnreadable(): Boolean = mutex.withLock {
        if (mutableProblem.value == null) return false
        commit(emptyMap())
    }

    private suspend fun commit(next: Map<String, WantedRecord>): Boolean {
        val written = withContext(io) { store.write(WantedStateCodec.encode(next)) }
        if (!written) return false
        mutableWanted.value = next
        mutableProblem.value = null
        return true
    }

    private companion object {
        const val PAUSED = "Saved light settings could not be read, so changes are paused"
        const val NOT_SAVED = "The change could not be saved, so nothing was sent"
    }
}
