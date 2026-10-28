package com.dirtyredz.gvmled.light.connection

import com.dirtyredz.gvmled.light.protocol.FullStateReport
import com.dirtyredz.gvmled.light.protocol.HelloReply

/** Whether the phone can use Bluetooth at all. Only [Ready] lets the coordinator scan or connect. */
enum class RadioState { Unavailable, PermissionRequired, PermissionDenied, Off, Ready }

/** Link progress for one light, in the order a healthy connection passes through it. */
enum class LinkStatus {
    /** Not running: app in background or Bluetooth not ready. */
    Idle,
    /** Waiting for an advertisement from this address. */
    Searching,
    Connecting,
    /** Subscribed; waiting for a valid hello reply. */
    Handshaking,
    /** Hello accepted; reports are decoded and acknowledged. */
    Live,
    /** The last attempt ended; waiting before searching again. */
    Waiting,
}

/**
 * What the app currently knows about one light. [report] is kept after a disconnect so the screen
 * can show it as stale; it only counts as current while [status] is [LinkStatus.Live] in the same
 * [generation] it arrived in.
 */
data class LightConnection(
    val address: String,
    val status: LinkStatus = LinkStatus.Idle,
    val generation: Int = 0,
    val hello: HelloReply? = null,
    /** The connection generation that supplied [hello]; retained only as historical information. */
    val helloGeneration: Int = 0,
    val report: FullStateReport? = null,
    val reportGeneration: Int = 0,
    val reportAtMillis: Long? = null,
    /** Monotonic receipt time of [report]'s notification; confirmation compares this, never [reportAtMillis]. */
    val reportReceivedAtMillis: Long? = null,
    val lastError: String? = null,
) {
    /** Capabilities of the live generation, or null while this light is offline or reconnecting. */
    val currentHello: HelloReply?
        get() = hello?.takeIf { status == LinkStatus.Live && helloGeneration == generation }

    val reportIsCurrent: Boolean
        get() = report != null && status == LinkStatus.Live && reportGeneration == generation
}
