package com.dirtyredz.gvmled.protocol

/** Shared constants and arithmetic for the legacy wire frame. */
internal object LegacyFrame {
    const val HEADER_FIRST = 0x4c
    const val HEADER_SECOND = 0x54
    const val TRAILER = 0xff

    const val PREFIX_SIZE = 3
    const val CRC_SIZE = 2
    private const val IDENTITY_SIZE = 2

    const val HELLO_REPLY_LENGTH = 10
    const val SHORT_STATE_REPLY_LENGTH = 11
    const val FULL_STATE_REPORT_LENGTH = 14
    val REPLY_LENGTHS = setOf(
        HELLO_REPLY_LENGTH,
        SHORT_STATE_REPLY_LENGTH,
        FULL_STATE_REPORT_LENGTH,
    )

    const val MAX_FRAME_SIZE = FULL_STATE_REPORT_LENGTH + PREFIX_SIZE

    fun lengthForBody(bodySize: Int): Int = IDENTITY_SIZE + bodySize + CRC_SIZE
    fun frameSize(length: Int): Int = PREFIX_SIZE + length
}
