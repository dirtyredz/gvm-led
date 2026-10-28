package com.dirtyredz.gvmled.light.control

import com.dirtyredz.gvmled.light.connection.LightCommandPort
import com.dirtyredz.gvmled.light.connection.LightConnection
import com.dirtyredz.gvmled.light.connection.LinkStatus
import com.dirtyredz.gvmled.light.connection.ReconnectBackoff
import com.dirtyredz.gvmled.light.connection.TransportException
import com.dirtyredz.gvmled.light.protocol.FullStateReport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Confirmation timing: about one and a half report intervals (reports come every ~4 s) per round. */
data class SyncTiming(val confirmTimeoutMs: Long = 6_000, val rounds: Int = 3)

/**
 * Converges one light (one address) on its latest wanted revision, independent of which target or
 * group the edit came from. Each time the revision or the live connection changes, the in-flight
 * work is cancelled and the complete transaction is replayed mode first, so a resume, reconnect or
 * newer edit always re-sends the whole intent (the colour-bug fix). A transaction is confirmed only
 * by a full report that arrived, by its receipt stamp, after the final write of that round in the
 * same connection generation. Timeouts and mismatches retry the whole transaction; after
 * [SyncTiming.rounds] rounds it reports Not confirmed and drops the connection after a capped
 * backoff so the coordinator reconnects. Once confirmed nothing more is written until the next
 * edit or connection: there are no perpetual corrective writes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LightSynchronizer(
    val address: String,
    private val connection: StateFlow<LightConnection>,
    private val port: StateFlow<LightCommandPort?>,
    private val wanted: Flow<WantedRecord?>,
    /** Stores the first report as intent when none exists; returns the stored record. */
    private val seed: suspend (FullStateReport, CctRange) -> WantedRecord?,
    private val timing: SyncTiming = SyncTiming(),
    private val backoff: ReconnectBackoff = ReconnectBackoff(),
    private val log: (String) -> Unit = {},
) {
    private val mutableStatus = MutableStateFlow<ApplyStatus>(ApplyStatus.NoSettings)
    val status: StateFlow<ApplyStatus> = mutableStatus.asStateFlow()

    private var unconfirmedStreak = 0
    private var streakRevision: Long? = null
    /** The generation whose own report was adopted as intent: the light already shows that record. */
    private var seededGeneration: Int? = null
    private var seededRevision: Long? = null

    suspend fun run() {
        combine(wanted, port) { record, link -> record to link }
            .distinctUntilChanged { old, new -> old.first?.revision == new.first?.revision && old.second === new.second }
            .collectLatest { (record, link) -> converge(record, link) }
    }

    private suspend fun converge(record: WantedRecord?, link: LightCommandPort?) {
        if (link == null) {
            mutableStatus.value = record?.let { ApplyStatus.WaitingForLight(it.revision) } ?: ApplyStatus.NoSettings
            return
        }
        val range = CctRange.of(link.hello)
        if (record == null) {
            mutableStatus.value = ApplyStatus.NoSettings
            val report = connection.first { it.isCurrentIn(link.generation) }.report ?: return
            seededGeneration = link.generation // set before the record can re-enter through [wanted]
            seededRevision = seed(report, range)?.revision
            return // the stored record arrives through [wanted] and re-enters here
        }
        if (seededGeneration == link.generation && seededRevision == record.revision) {
            mutableStatus.value = ApplyStatus.Confirmed(record.revision)
            return
        }
        if (streakRevision != record.revision) {
            streakRevision = record.revision
            unconfirmedStreak = 0
        }
        val plan = ModeCommandPlanner.plan(record.state, range)
        if (plan is CommandPlan.Blocked) {
            mutableStatus.value = ApplyStatus.Blocked(record.revision, plan.reason)
            return
        }
        val commands = (plan as CommandPlan.Ready).commands
        repeat(timing.rounds) { round ->
            mutableStatus.value = ApplyStatus.Pending(record.revision, round + 1)
            val sentAt = try {
                link.send(commands)
            } catch (ended: TransportException) {
                log("$address revision ${record.revision}: ${ended.message}")
                return // the generation is ending; the next port re-enters with a full replay
            }
            val fresh = withTimeoutOrNull(timing.confirmTimeoutMs) {
                connection.first { it.isCurrentIn(link.generation) && (it.reportReceivedAtMillis ?: Long.MIN_VALUE) > sentAt }
            }
            val report = fresh?.report
            if (report != null && StateMatcher.matches(record.state, report)) {
                unconfirmedStreak = 0
                mutableStatus.value = ApplyStatus.Confirmed(record.revision)
                return
            }
            val why = report?.let { "report differs: " + StateMatcher.mismatches(record.state, it).joinToString() } ?: "no fresh report"
            log("$address revision ${record.revision} round ${round + 1}: $why")
        }
        unconfirmedStreak++
        mutableStatus.value = ApplyStatus.NotConfirmed(record.revision, "The light did not show it after ${timing.rounds} attempts")
        delay(backoff.delayFor(unconfirmedStreak))
        link.reconnect("Not confirmed")
    }

    private fun LightConnection.isCurrentIn(generation: Int): Boolean =
        status == LinkStatus.Live && this.generation == generation && reportGeneration == generation && report != null
}
