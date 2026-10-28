package com.dirtyredz.gvmled.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Coordinates the configured lights while the app is in the foreground and Bluetooth is ready: one
 * [LightSession] per configured address, reconciled as the catalog changes without restarting
 * unaffected links; one shared [LightDiscovery] scan; establishment (connect, subscribe, hello)
 * serialized across all lights by one mutex while established links run concurrently; and
 * reconnection after a fresh sighting with backoff. Stopping cancels everything and closes every
 * transport. A removed address's work is cancelled and joined before that address can get a new
 * session, so a retired session can never publish into the current list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionCoordinator(
    private val addresses: StateFlow<List<String>>,
    private val discovery: LightDiscovery,
    private val transports: LightTransportFactory,
    private val radio: StateFlow<RadioState>,
    private val scope: CoroutineScope,
    private val monotonicMillis: () -> Long,
    private val wallMillis: () -> Long = System::currentTimeMillis,
    private val timing: SessionTiming = SessionTiming(),
    private val backoff: ReconnectBackoff = ReconnectBackoff(),
    private val log: (String) -> Unit = {},
) {
    private val sessions = MutableStateFlow(reconciled(emptyList(), addresses.value))
    private val establish = Mutex()
    private var job: Job? = null
    private val lifecycle = Channel<Boolean>(Channel.UNLIMITED)

    init {
        discovery.setKnown(sessions.value.map { it.address })
        scope.launch { addresses.collect { reconcile(it) } }
        scope.launch {
            for (running in lifecycle) {
                if (running) startNow() else stopNow()
            }
        }
    }

    /** One entry per configured light, in catalog order. */
    val lights: StateFlow<List<LightConnection>> = sessions
        .flatMapLatest { list ->
            if (list.isEmpty()) flowOf(emptyList()) else combine(list.map { it.state }) { it.toList() }
        }
        .stateIn(scope, SharingStarted.Eagerly, sessions.value.map { it.state.value })

    /** Foreground entry. Idempotent. */
    fun start() {
        lifecycle.trySend(true)
    }

    /** Background entry: cancels scanning and sessions; each session closes its transport. */
    fun stop() {
        lifecycle.trySend(false)
    }

    private fun startNow() {
        if (job?.isActive == true) return
        job = scope.launch {
            radio.collectLatest { state ->
                if (state == RadioState.Ready) runReady() else idleAll()
            }
        }
    }

    private suspend fun stopNow() {
        job?.cancelAndJoin()
        job = null
        idleAll()
    }

    private fun reconcile(configured: List<String>) {
        val next = reconciled(sessions.value, configured)
        if (next != sessions.value) {
            discovery.setKnown(next.map { it.address })
            sessions.value = next
        }
    }

    /** Keeps the existing session object for every address still configured; creates the rest. */
    private fun reconciled(current: List<LightSession>, configured: List<String>): List<LightSession> {
        val byAddress = current.associateBy { it.address }
        return configured.map(::normalizeAddress).distinct().map { address ->
            byAddress[address] ?: LightSession(address, transports, monotonicMillis, wallMillis, timing, log)
        }
    }

    private suspend fun runReady(): Nothing = coroutineScope {
        launch { discovery.run(lights.map { list -> list.any { it.status == LinkStatus.Searching } }) }
        val running = HashMap<LightSession, Job>() // identity keys: a re-added address is a new session
        sessions.collect { current ->
            for (retired in running.keys - current.toSet()) {
                running.remove(retired)?.cancelAndJoin()
                retired.markIdle()
            }
            for (session in current) if (session !in running) running[session] = launch { maintain(session) }
        }
    }

    private suspend fun maintain(session: LightSession) {
        var failures = 0
        while (true) {
            session.markSearching()
            discovery.awaitSighting(session.address)
            // A fresh sighting is used up by this attempt, even if the session is retired mid-attempt.
            discovery.consumeSighting(session.address)
            val outcome = try {
                session.connectOnce(establish)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unexpected: Exception) {
                // A platform surprise must not end the loop (or crash the process); treat it as a failure.
                log("${session.address} unexpected failure: $unexpected")
                SessionOutcome(reachedLive = false, reason = unexpected.message ?: unexpected.javaClass.simpleName)
            }
            discovery.consumeSighting(session.address) // and so is anything seen while it ran
            failures = if (outcome.reachedLive) 0 else failures + 1
            session.markWaiting(outcome.reason)
            delay(backoff.delayFor(failures))
        }
    }

    private fun idleAll() {
        discovery.reset()
        sessions.value.forEach { it.markIdle() }
    }
}
