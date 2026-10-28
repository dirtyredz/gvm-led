package com.dirtyredz.gvmled.light.connection

/**
 * A type-33 light behind a [FakeLightTransport]: set packets change its stored state (as the
 * 2026-10-26 proof showed, including while off), and [report] pushes a full 0x03 report of that
 * state. [dropSets] models writes lost on the air. Reports are only sent when a test asks, so
 * timing is explicit.
 */
internal class SimulatedLight(
    var power: Int = 1,
    var brightness: Int = 50,
    var cct: Int = 32,
    var hue: Int = 0,
    var saturation: Int = 0,
    var mode: Int = 1,
    var scene: Int = 1,
    var effectTime: Int = 1,
) {
    /** The next this-many set packets are lost. */
    var dropSets = 0

    /** Every set packet that reached the light, as (command, value). */
    val applied = mutableListOf<Pair<Int, Int>>()

    /** Every set packet written, delivered or not, as (command, value). */
    val written = mutableListOf<Pair<Int, Int>>()

    fun attach(transport: FakeLightTransport) {
        transport.onWrite = ::receive
    }

    private fun receive(packet: ByteArray) {
        val bytes = packet.map { it.toInt() and 0xff }
        if (bytes.size != 13 || bytes[5] != 0x57) return
        val command = bytes[7]
        val value = bytes[9]
        written += command to value
        if (dropSets > 0) {
            dropSets--
            return
        }
        applied += command to value
        when (command) {
            0 -> power = value
            2 -> brightness = value
            3 -> cct = value
            4 -> hue = value
            5 -> saturation = value
            6 -> mode = value
            7 -> scene = value
            8 -> effectTime = value
        }
    }

    /** The current state as a framed full report (pick 11), CRC from the test framing helper. */
    fun reportFrame(): ByteArray {
        val payload = listOf(power, 11, brightness, cct, hue, saturation, mode, scene, effectTime)
        return Vectors.framed("4C 54 0E 00 33 03 " + payload.joinToString(" ") { "%02X".format(it) })
    }

    fun report(transport: FakeLightTransport, receivedAt: Long? = null) {
        if (receivedAt == null) transport.notify(reportFrame()) else transport.notify(reportFrame(), receivedAt)
    }
}
