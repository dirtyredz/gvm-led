package com.dirtyredz.gvmled.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** Wait before searching again: short after a live link ended, doubling (capped) after failures. */
class ReconnectBackoff(private val baseMs: Long = 1_000, private val maxMs: Long = 30_000) {
    fun delayFor(consecutiveFailures: Int): Long =
        if (consecutiveFailures <= 0) baseMs
        else (baseMs shl (consecutiveFailures.coerceAtMost(16))).coerceAtMost(maxMs)
}

/**
 * Coordinates the configured lights while the app is in the foreground and Bluetooth is ready:
 * one shared scan (running only while some light is searching), one [LightSession] per address,
 * sequential establishment, and reconnection after a sighting with backoff. Stopping cancels
 * everything and closes every transport.
 */
class ConnectionCoordinator(
    addresses: List<String>,
    private val scanner: LightScanner,
    transports: LightTransportFactory,
    private val radio: StateFlow<RadioState>,
    private val scope: CoroutineScope,
    monotonicMillis: () -> Long,
    wallMillis: () -> Long = System::currentTimeMillis,
    timing: SessionTiming = SessionTiming(),
    private val backoff: ReconnectBackoff = ReconnectBackoff(),
    private val scanRetryMs: Long = 5_000,
    private val log: (String) -> Unit = {},
) {
    val sessions: List<LightSession> =
        addresses.map { LightSession(it, transports, monotonicMillis, wallMillis, timing, log) }

    private val filter = DiscoveryFilter(addresses)
    private val sighted = MutableStateFlow<Set<String>>(emptySet())
    private val establish = Mutex()
    private var job: Job? = null

    private val others = MutableStateFlow<Set<String>>(emptySet())

    /** `BT_LED` devices seen that are not configured lights. Never connected. */
    val otherLights: StateFlow<Set<String>> = others.asStateFlow()

    val lights: StateFlow<List<LightConnection>> =
        combine(sessions.map { it.state }) { it.toList() }
            .stateIn(scope, SharingStarted.Eagerly, sessions.map { it.state.value })

    /** Foreground entry. Idempotent. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            radio.collectLatest { state ->
                if (state == RadioState.Ready) runReady() else idleAll()
            }
        }
    }

    /** Background entry: cancels scanning and sessions; each session closes its transport. */
    fun stop() {
        job?.cancel()
        job = null
        idleAll()
    }

    private suspend fun runReady() = coroutineScope {
        launch { scanWhileSearching() }
        sessions.forEach { session -> launch { maintain(session) } }
    }

    private suspend fun scanWhileSearching() {
        lights.map { list -> list.any { it.status == LinkStatus.Searching } }
            .distinctUntilChanged()
            .collectLatest { searching ->
                if (!searching) return@collectLatest
                scanner.scan()
                    .retryWhen { cause, _ ->
                        log("scan failed: ${cause.message}")
                        delay(scanRetryMs)
                        true
                    }
                    .collect { record(it) }
            }
    }

    private fun record(advertisement: Advertisement) {
        when (val sighting = filter.classify(advertisement)) {
            is Sighting.Known -> sighted.update { it + sighting.address }
            is Sighting.OtherLegacyLight -> others.update { it + sighting.address }
            null -> Unit
        }
    }

    private suspend fun maintain(session: LightSession) {
        var failures = 0
        while (true) {
            session.markSearching()
            sighted.first { session.address in it }
            val outcome = try {
                session.connectOnce(establish)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unexpected: Exception) {
                // A platform surprise must not end the loop (or crash the process); treat it as a failure.
                log("${session.address} unexpected failure: $unexpected")
                SessionOutcome(reachedLive = false, reason = unexpected.message ?: unexpected.javaClass.simpleName)
            }
            // A fresh sighting is required before the next attempt.
            sighted.update { it - session.address }
            failures = if (outcome.reachedLive) 0 else failures + 1
            session.markWaiting(outcome.reason)
            delay(backoff.delayFor(failures))
        }
    }

    private fun idleAll() {
        sighted.value = emptySet()
        sessions.forEach { it.markIdle() }
    }
}
