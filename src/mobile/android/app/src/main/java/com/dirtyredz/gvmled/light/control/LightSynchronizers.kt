package com.dirtyredz.gvmled.light.control

import com.dirtyredz.gvmled.light.connection.LightSession
import com.dirtyredz.gvmled.light.connection.ReconnectBackoff
import com.dirtyredz.gvmled.light.protocol.FullStateReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Exactly one [LightSynchronizer] per current session, reconciled as sessions come and go (a
 * re-added address gets a new session and so a new synchronizer). Synchronizers run for the life of
 * [scope]; they act only while their session has a live port, which exists only in the foreground,
 * so lifecycle changes never start duplicates. One light failing never blocks another: there is no
 * shared command queue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LightSynchronizers(
    sessions: StateFlow<List<LightSession>>,
    private val wanted: StateFlow<Map<String, WantedRecord>>,
    private val seed: suspend (String, FullStateReport, CctRange) -> WantedRecord?,
    private val scope: CoroutineScope,
    private val timing: SyncTiming = SyncTiming(),
    private val backoff: ReconnectBackoff = ReconnectBackoff(),
    private val log: (String) -> Unit = {},
) {
    private val running = MutableStateFlow<List<LightSynchronizer>>(emptyList())
    private val jobs = HashMap<LightSession, Pair<LightSynchronizer, Job>>()

    init {
        scope.launch { sessions.collect(::reconcile) }
    }

    /** Apply status by normalized address. */
    val statuses: StateFlow<Map<String, ApplyStatus>> = running
        .flatMapLatest { list ->
            if (list.isEmpty()) flowOf(emptyMap())
            else combine(list.map { sync -> sync.status.map { sync.address to it } }) { it.toMap() }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private fun reconcile(current: List<LightSession>) {
        for (retired in jobs.keys - current.toSet()) jobs.remove(retired)?.second?.cancel()
        for (session in current) {
            if (session in jobs) continue
            val sync = LightSynchronizer(
                address = session.address,
                connection = session.state,
                port = session.port,
                wanted = wanted.map { it[session.address] }.distinctUntilChanged(),
                seed = { report, range -> seed(session.address, report, range) },
                timing = timing,
                backoff = backoff,
                log = log,
            )
            jobs[session] = sync to scope.launch { sync.run() }
        }
        running.value = current.mapNotNull { jobs[it]?.first }
    }
}
