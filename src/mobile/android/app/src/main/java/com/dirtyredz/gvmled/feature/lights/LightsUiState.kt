package com.dirtyredz.gvmled.feature.lights

import com.dirtyredz.gvmled.connection.LinkStatus
import com.dirtyredz.gvmled.connection.RadioState
import com.dirtyredz.gvmled.data.repository.LightEntry
import com.dirtyredz.gvmled.protocol.FullStateReport
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class LightsUiState(
    val radio: RadioState = RadioState.Ready,
    val lights: List<LightCardModel> = emptyList(),
    /** Every light, then every named group; exactly one is selected unless there are no lights. */
    val targets: List<TargetChip> = emptyList(),
    /** Members of a selected group, e.g. "Key and Fill: 2 lights". Null for an individual light. */
    val groupSummary: String? = null,
    /** True when no light is saved yet: Lights shows how to add one. */
    val isEmpty: Boolean = false,
)

/** Display-ready values for one light. Wire units are converted here, nowhere else in the UI. */
data class LightCardModel(
    val address: String,
    val addressSuffix: String,
    val name: String,
    val status: String,
    val isLive: Boolean,
    val observed: ObservedLightState?,
    /** True when [observed] came from an earlier connection or the link is down. */
    val isStale: Boolean,
    val cctRange: String?,
)

data class ObservedLightState(
    val power: String,
    val mode: String,
    val colourLabel: String,
    val colour: String,
    val brightness: String,
    val updated: String,
)

/** The last two address bytes; enough to tell the two lights apart on screen. */
fun addressSuffix(address: String): String = address.takeLast(5)

fun LightEntry.toCardModel(zone: ZoneId): LightCardModel {
    val hello = connection.hello
    return LightCardModel(
        address = connection.address,
        addressSuffix = addressSuffix(connection.address),
        name = name,
        status = statusText(connection.status, connection.lastError),
        isLive = connection.status == LinkStatus.Live,
        observed = connection.report?.let { observed(it, connection.reportAtMillis, zone) },
        isStale = connection.report != null && !connection.reportIsCurrent,
        cctRange = hello?.let { "${it.minCctKelvin}-${it.maxCctKelvin} K" },
    )
}

internal fun statusText(status: LinkStatus, lastError: String?): String = when (status) {
    LinkStatus.Idle -> "Not connected"
    LinkStatus.Searching -> "Searching"
    LinkStatus.Connecting -> "Connecting"
    LinkStatus.Handshaking -> "Handshaking"
    LinkStatus.Live -> "Live"
    LinkStatus.Waiting -> "Retrying soon" + (lastError?.let { ": $it" } ?: "")
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

internal fun observed(report: FullStateReport, atMillis: Long?, zone: ZoneId): ObservedLightState {
    val (mode, colourLabel, colour) = when (report.mode) {
        1 -> Triple("CCT", "Colour temperature", "${report.cct * 100} K")
        2 -> Triple("HSI", "Colour", "Hue ${report.hue * 5}°, saturation ${report.saturation} %")
        else -> Triple("Scene", "Scene", "Scene ${report.scene}, effect time ${report.effectTime * 100} ms")
    }
    return ObservedLightState(
        power = if (report.power == 1) "On" else "Off",
        mode = mode,
        colourLabel = colourLabel,
        colour = colour,
        brightness = "${report.brightness} %",
        updated = atMillis?.let { "Updated " + TIME.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "",
    )
}
