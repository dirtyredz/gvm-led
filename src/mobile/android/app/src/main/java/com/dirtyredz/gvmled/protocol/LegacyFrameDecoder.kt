package com.dirtyredz.gvmled.protocol

/**
 * One decoder per connection, used serially by its owner. Notifications may split or join frames.
 * Only the three known inbound shapes are accepted. Bytes are ingested incrementally so even an
 * arbitrarily large notification cannot grow retained input beyond the maximum frame size.
 */
class LegacyFrameDecoder {
    private val buffer = ArrayList<Byte>(MAX_FRAME_SIZE)
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
            if (unsigned(0) != 0x4c) { discard(1); continue }
            if (buffer.size < 2) return
            if (unsigned(1) != 0x54) { discard(1); continue }
            if (buffer.size < 3) return
            val length = unsigned(2)
            if (length !in KNOWN_LENGTHS) { discard(1); continue }
            val total = length + 3
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
        if (buffer.size - offset < 3 || unsigned(offset) != 0x4c || unsigned(offset + 1) != 0x54) return null
        val length = unsigned(offset + 2)
        if (length !in KNOWN_LENGTHS || buffer.size - offset < length + 3) return null
        val bytes = buffer.subList(offset, offset + length + 3).toByteArray()
        if (Crc16Xmodem.calculate(bytes) != 0) return null
        val values = bytes.map { it.toInt() and 0xff }
        val id = values[3]
        val type = values[4]
        return when {
            length == 10 && values[5] == 0x53 -> {
                if (values.subList(6, 9) != listOf(0, 0, 2) || values[9] == 0 || values[9] > values[10]) null
                else HelloReply(id, type, values[9] * 100, values[10] * 100)
            }
            length == 14 && values[5] == 3 -> FullStateReport.decode(id, type, values.subList(6, 15))
            length == 11 && values[5] == 2 -> {
                if (values.subList(6, 9) != listOf(0, 0, 1) || values[9] !in 1..12 ||
                    values[10] !in 0..100 || values[11] == 0) null
                else ShortStateReply(id, type, values[9], values[10], values[11])
            }
            else -> null
        }
    }

    private fun unsigned(index: Int): Int = buffer[index].toInt() and 0xff
    private fun discard(count: Int) { buffer.subList(0, count).clear() }

    companion object {
        const val MAX_FRAME_SIZE = 17
        private val KNOWN_LENGTHS = setOf(10, 11, 14)
    }
}
