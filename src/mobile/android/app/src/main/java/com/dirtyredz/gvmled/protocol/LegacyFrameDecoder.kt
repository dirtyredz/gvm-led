package com.dirtyredz.gvmled.protocol

/**
 * One decoder per connection, used serially by its owner. Notifications may split or join frames.
 * Only the three known inbound shapes are accepted. Bytes are ingested incrementally so even an
 * arbitrarily large notification cannot grow retained input beyond the maximum frame size.
 */
class LegacyFrameDecoder {
    private val buffer = ArrayList<Byte>(LegacyFrame.MAX_FRAME_SIZE)
    val bufferedByteCount: Int get() = buffer.size

    fun reset() = buffer.clear()

    fun feed(notification: ByteArray): List<LegacyMessage> {
        val messages = mutableListOf<LegacyMessage>()
        for (byte in notification) {
            buffer.add(byte)
            drain(messages)
        }
        return messages
    }

    private fun drain(messages: MutableList<LegacyMessage>) {
        while (buffer.isNotEmpty()) {
            if (unsigned(0) != LegacyFrame.HEADER_FIRST) { discard(1); continue }
            if (buffer.size < 2) return
            if (unsigned(1) != LegacyFrame.HEADER_SECOND) { discard(1); continue }
            if (buffer.size < LegacyFrame.PREFIX_SIZE) return
            val length = unsigned(2)
            if (length !in LegacyFrame.REPLY_LENGTHS) { discard(1); continue }
            val total = LegacyFrame.frameSize(length)
            if (buffer.size < total) {
                // A truncated valid-length prefix must not hide a later complete valid message.
                val next = (1 until buffer.size).firstOrNull { decodeAt(it) != null }
                if (next == null) return
                discard(next)
                continue
            }
            val message = decodeAt(0)
            if (message == null) discard(1) else {
                messages.add(message)
                discard(total)
            }
        }
    }

    private fun decodeAt(offset: Int): LegacyMessage? {
        if (buffer.size - offset < LegacyFrame.PREFIX_SIZE ||
            unsigned(offset) != LegacyFrame.HEADER_FIRST ||
            unsigned(offset + 1) != LegacyFrame.HEADER_SECOND
        ) return null
        val length = unsigned(offset + 2)
        val frameSize = LegacyFrame.frameSize(length)
        if (length !in LegacyFrame.REPLY_LENGTHS || buffer.size - offset < frameSize) return null
        val bytes = buffer.subList(offset, offset + frameSize).toByteArray()
        if (Crc16Xmodem.calculate(bytes) != 0) return null
        return LegacyMessageParser.parse(bytes)
    }

    private fun unsigned(index: Int): Int = buffer[index].toInt() and 0xff
    private fun discard(count: Int) { buffer.subList(0, count).clear() }

}
