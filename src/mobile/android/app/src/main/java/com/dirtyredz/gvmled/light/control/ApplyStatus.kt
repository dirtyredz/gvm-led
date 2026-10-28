package com.dirtyredz.gvmled.light.control

/**
 * Where one light is in applying its latest wanted revision. Only [Confirmed] means the light was
 * seen showing it: a write, a GATT completion or a short reply never gets there.
 */
sealed interface ApplyStatus {
    /** No wanted state yet: it is adopted from the light's first valid full report. */
    data object NoSettings : ApplyStatus

    /** Intent is saved; the light is not connected, so nothing is sent until it is. */
    data class WaitingForLight(val revision: Long) : ApplyStatus

    /** Writing [revision], or waiting for a fresh report; [attempt] counts rounds from 1. */
    data class Pending(val revision: Long, val attempt: Int) : ApplyStatus

    /** A full report received after the final write showed [revision], in this connection. */
    data class Confirmed(val revision: Long) : ApplyStatus

    /** Every round ended without a matching fresh report; reconnecting and trying again. */
    data class NotConfirmed(val revision: Long, val reason: String) : ApplyStatus

    /** The saved intent cannot be sent to this light as it stands (for example, a CCT outside its range). */
    data class Blocked(val revision: Long, val reason: String) : ApplyStatus
}
