package com.dirtyredz.gvmled.feature.lights

import com.dirtyredz.gvmled.light.connection.LinkStatus
import com.dirtyredz.gvmled.light.connection.RadioState
import com.dirtyredz.gvmled.data.model.ControlTarget
import com.dirtyredz.gvmled.data.repository.LightEntry
import com.dirtyredz.gvmled.light.control.ApplyStatus
import com.dirtyredz.gvmled.light.control.TargetValues
import com.dirtyredz.gvmled.light.protocol.FullStateReport
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
    /** The resolved selection that controls act on; null when there are no lights. */
    val target: ControlTarget? = null,
    /** The selected target's wanted values (Mixed/Unknown honestly); null when there are no lights. */
    val controls: TargetValues? = null,
    /** Per-member apply outcome, e.g. "2 of 3 confirmed; Fill waiting for connection". */
    val outcome: String? = null,
    /** Why the last action was refused; cleared by the next accepted one. */
    val message: String? = null,
    /** Saved light settings are unreadable: controls are paused until they are reset. */
    val settingsProblem: String? = null,
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
    /** This light's apply status for its latest wanted settings. */
    val apply: String,
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
    val hello = connection.currentHello
    return LightCardModel(
        address = connection.address,
        addressSuffix = addressSuffix(connection.address),
        name = name,
        status = statusText(connection.status, connection.lastError),
        isLive = connection.status == LinkStatus.Live,
        observed = connection.report?.let { observed(it, connection.reportAtMillis, zone) },
        isStale = connection.report != null && !connection.reportIsCurrent,
        cctRange = hello?.let { "${it.minCctKelvin}-${it.maxCctKelvin} K" },
        apply = applyText(apply),
    )
}

/** One light's apply status in words. "Confirmed" only ever means a fresh matching report was seen. */
internal fun applyText(status: ApplyStatus): String = when (status) {
    ApplyStatus.NoSettings -> "No settings yet (adopted from its first report)"
    is ApplyStatus.WaitingForLight -> "Waiting for connection"
    is ApplyStatus.Pending -> if (status.attempt == 1) "Applying" else "Applying (attempt ${status.attempt})"
    is ApplyStatus.Confirmed -> "Confirmed"
    is ApplyStatus.NotConfirmed -> "Not confirmed; reconnecting"
    is ApplyStatus.Blocked -> "Cannot apply: ${status.reason}"
}

/**
 * The outcome across a target's members, counted per light. A group never reads as confirmed while
 * any member is not, and the members still outstanding are named.
 */
internal fun outcomeText(members: List<LightEntry>): String? {
    if (members.isEmpty()) return null
    if (members.size == 1) return applyText(members.single().apply)
    val confirmed = members.count { it.apply is ApplyStatus.Confirmed }
    val others = members.filter { it.apply !is ApplyStatus.Confirmed }
        .joinToString("; ") { "${it.name} " + applyText(it.apply).replaceFirstChar(Char::lowercaseChar) }
    return "$confirmed of ${members.size} confirmed" + if (others.isEmpty()) "" else "; $others"
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
