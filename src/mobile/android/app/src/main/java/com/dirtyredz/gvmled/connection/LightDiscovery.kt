package com.dirtyredz.gvmled.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** An unknown `BT_LED` device, identified only by address, with when it was last advertised. */
data class Candidate(val address: String, val lastSeenMillis: Long)

/**
 * The one shared scan. It runs while any known light is searching (reconnect demand) or during a
 * bounded owner-requested Find window, and never otherwise: Android silently returns nothing after
 * more than 5 scan starts in 30 s. Advertisements are classified against the current known set:
 * known lights become fresh sightings for their sessions; unknown `BT_LED` devices become
 * [candidates] that expire when not seen for [candidateTtlMs].
 */
class LightDiscovery(
    private val scanner: LightScanner,
    private val monotonicMillis: () -> Long,
    private val scanRetryMs: Long = 5_000,
    private val candidateTtlMs: Long = 30_000,
    private val findWindowMs: Long = 30_000,
    private val log: (String) -> Unit = {},
) {
    @Volatile private var filter = DiscoveryFilter(emptyList())
    private val sighted = MutableStateFlow<Set<String>>(emptySet())
    private val seen = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val findDeadline = MutableStateFlow<Long?>(null)
    private val mutableFinding = MutableStateFlow(false)
    private val mutableCandidates = MutableStateFlow<List<Candidate>>(emptyList())

    /** True while an owner-requested Find window is scanning. */
    val finding: StateFlow<Boolean> = mutableFinding.asStateFlow()

    /** Fresh unknown `BT_LED` devices, most recently seen first. Never connected until enrolled. */
    val candidates: StateFlow<List<Candidate>> = mutableCandidates.asStateFlow()

    /** The configured set changed: classification follows it, and enrolled addresses stop being candidates. */
    fun setKnown(addresses: Collection<String>) {
        filter = DiscoveryFilter(addresses)
        publishCandidates()
    }

    /** Starts (or extends) a bounded Find window; scanning begins once Bluetooth is ready. */
    fun find() {
        findDeadline.value = monotonicMillis() + findWindowMs
    }

    suspend fun awaitSighting(address: String) {
        sighted.first { address in it }
    }

    /** A fresh sighting is used up by one connection attempt. */
    fun consumeSighting(address: String) = sighted.update { it - address }

    /** Runs while Bluetooth is ready; cancelled (scan stopped) when it is not. */
    suspend fun run(reconnectDemand: Flow<Boolean>) = coroutineScope {
        launch {
            while (true) {
                delay(candidateTtlMs / 6)
                publishCandidates()
            }
        }
        val findWindow = findDeadline.transformLatest { deadline ->
            val remaining = deadline?.let { it - monotonicMillis() } ?: 0
            if (remaining > 0) {
                emit(true)
                delay(remaining)
            }
            emit(false)
        }.onEach { mutableFinding.value = it }
        combine(reconnectDemand, findWindow) { reconnect, finding -> reconnect || finding }
            .distinctUntilChanged()
            .collectLatest { scanning ->
                if (!scanning) return@collectLatest
                scanner.scan()
                    .retryWhen { cause, _ ->
                        if (cause is CancellationException) return@retryWhen false
                        log("scan failed: ${cause.message}")
                        delay(scanRetryMs)
                        true
                    }
                    .collect(::record)
            }
    }

    /** Bluetooth stopped or the app left the foreground: nothing seen earlier counts as fresh. */
    fun reset() {
        sighted.value = emptySet()
        seen.value = emptyMap()
        findDeadline.value = null
        mutableFinding.value = false
        publishCandidates()
    }

    private fun record(advertisement: Advertisement) {
        when (val sighting = filter.classify(advertisement)) {
            is Sighting.Known -> sighted.update { it + sighting.address }
            is Sighting.OtherLegacyLight -> {
                seen.update { it + (sighting.address to monotonicMillis()) }
                publishCandidates()
            }
            null -> Unit
        }
    }

    private fun publishCandidates() {
        val now = monotonicMillis()
        val fresh = seen.updateAndGet { all -> all.filterValues { now - it <= candidateTtlMs } }
        val known = filter
        mutableCandidates.value = fresh
            .filterKeys { !known.isKnown(it) }
            .map { (address, at) -> Candidate(address, at) }
            .sortedByDescending { it.lastSeenMillis }
    }
}

