package com.dirtyredz.gvmled.light.control

import com.dirtyredz.gvmled.light.protocol.LegacyCommand
import com.dirtyredz.gvmled.light.protocol.SetCommand
import com.dirtyredz.gvmled.light.protocol.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Expected bytes are the real type-33 set TX frames from the 2026-10-26 visible proof run
 * (`live-test-20261026-204635.log`), not produced by the encoder under test. The proof sent power
 * first; the app sends it last (either order was proven), so only the order differs.
 */
class ModeCommandPlannerTest {
    private val range = CctRange(32, 56)

    private object Tx {
        val POWER_ON = hex("4c 54 09 00 33 57 00 00 01 01 ec 3f ff")
        val POWER_OFF = hex("4c 54 09 00 33 57 00 00 01 00 fc 1e ff")
        val MODE_CCT = hex("4c 54 09 00 33 57 00 06 01 01 5e 9f ff")
        val MODE_HSI = hex("4c 54 09 00 33 57 00 06 01 02 6e fc ff")
        val MODE_SCENE = hex("4c 54 09 00 33 57 00 06 01 03 7e dd ff")
        val HUE_0 = hex("4c 54 09 00 33 57 00 04 01 00 20 de ff")
        val HUE_240 = hex("4c 54 09 00 33 57 00 04 01 30 16 8d ff")
        val SAT_100 = hex("4c 54 09 00 33 57 00 05 01 64 3b cc ff")
        val SAT_50 = hex("4c 54 09 00 33 57 00 05 01 32 01 ff ff")
        val BRIGHT_50 = hex("4c 54 09 00 33 57 00 02 01 32 84 6f ff")
        val BRIGHT_40 = hex("4c 54 09 00 33 57 00 02 01 28 37 14 ff")
        val CCT_3200 = hex("4c 54 09 00 33 57 00 03 01 20 81 2c ff")
        val CCT_5600 = hex("4c 54 09 00 33 57 00 03 01 38 12 15 ff")
        val SCENE_19 = hex("4c 54 09 00 33 57 00 07 01 13 5b dc ff")
        val SCENE_20 = hex("4c 54 09 00 33 57 00 07 01 14 2b 3b ff")
        val TIME_2000 = hex("4c 54 09 00 33 57 00 08 01 14 07 0a ff")
        val TIME_500 = hex("4c 54 09 00 33 57 00 08 01 05 05 1a ff")
    }

    private fun bytes(state: WantedState): List<ByteArray> {
        val plan = ModeCommandPlanner.plan(state, range)
        assertTrue("expected Ready, got $plan", plan is CommandPlan.Ready)
        return (plan as CommandPlan.Ready).commands.map { it.encode(0, 0x33) }
    }

    private fun assertFrames(expected: List<ByteArray>, actual: List<ByteArray>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertArrayEquals(e, a) }
    }

    @Test fun hsiIsModeFirstThenHueSaturationBrightnessAndPowerLast() {
        val red = WantedState(power = true, mode = LightMode.Hsi, brightness = 50, hue = 0, saturation = 100, cct = 44)
        assertFrames(listOf(Tx.MODE_HSI, Tx.HUE_0, Tx.SAT_100, Tx.BRIGHT_50, Tx.POWER_ON), bytes(red))
        val paleBlue = red.copy(hue = 48, saturation = 50)
        assertFrames(listOf(Tx.MODE_HSI, Tx.HUE_240, Tx.SAT_50, Tx.BRIGHT_50, Tx.POWER_ON), bytes(paleBlue))
    }

    @Test fun cctSendsOnlyItsValueAtBothHelloEndpoints() {
        val low = WantedState(power = true, mode = LightMode.Cct, brightness = 40, cct = 32, hue = 7, saturation = 100)
        assertFrames(listOf(Tx.MODE_CCT, Tx.CCT_3200, Tx.BRIGHT_40, Tx.POWER_ON), bytes(low))
        assertFrames(listOf(Tx.MODE_CCT, Tx.CCT_5600, Tx.BRIGHT_40, Tx.POWER_ON), bytes(low.copy(cct = 56)))
    }

    @Test fun scenesSendSceneThenOnlyTheirApplicableFields() {
        val scene19 = WantedState(power = true, mode = LightMode.Scene, brightness = 50, cct = 32, scene = 19, effectTime = 20, hue = 3)
        assertFrames(listOf(Tx.MODE_SCENE, Tx.SCENE_19, Tx.CCT_3200, Tx.TIME_2000, Tx.BRIGHT_50, Tx.POWER_ON), bytes(scene19))
        val scene20 = WantedState(power = true, mode = LightMode.Scene, brightness = 50, hue = 48, saturation = 100, scene = 20, effectTime = 5)
        assertFrames(
            listOf(Tx.MODE_SCENE, Tx.SCENE_20, Tx.HUE_240, Tx.SAT_100, Tx.TIME_500, Tx.BRIGHT_50, Tx.POWER_ON),
            bytes(scene20),
        )
        val scene1 = WantedState(power = true, mode = LightMode.Scene, brightness = 50, scene = 1)
        // Scene 1 uses only brightness (no captured TX for scene 1, so compare commands, not bytes).
        assertEquals(
            listOf(SetCommand(LegacyCommand.MODE, 3), SetCommand(LegacyCommand.SCENE, 1), SetCommand(LegacyCommand.BRIGHTNESS, 50), SetCommand(LegacyCommand.POWER, 1)),
            (ModeCommandPlanner.plan(scene1, range) as CommandPlan.Ready).commands,
        )
    }

    @Test fun powerOffIsStillACompleteModeFirstTransactionEndingInPower() {
        val off = WantedState(power = false, mode = LightMode.Hsi, brightness = 50, hue = 48, saturation = 100)
        assertFrames(listOf(Tx.MODE_HSI, Tx.HUE_240, Tx.SAT_100, Tx.BRIGHT_50, Tx.POWER_OFF), bytes(off))
    }

    @Test fun incompleteOrIllegalIntentIsBlockedWithAReason() {
        fun blocked(state: WantedState) = (ModeCommandPlanner.plan(state, range) as CommandPlan.Blocked).reason
        assertEquals("needs a value for hue", blocked(WantedState(true, LightMode.Hsi, 50, saturation = 10)))
        assertEquals("needs a value for colour temperature", blocked(WantedState(true, LightMode.Cct, 50)))
        assertEquals("needs a value for effect time", blocked(WantedState(true, LightMode.Scene, 50, scene = 6)))
        assertEquals("scene 21 is not supported", blocked(WantedState(true, LightMode.Scene, 50, scene = 21)))
        assertEquals("brightness must be 0-100 %", blocked(WantedState(true, LightMode.Hsi, 101, hue = 1, saturation = 1)))
        assertEquals("saturation is out of range", blocked(WantedState(true, LightMode.Hsi, 50, hue = 1, saturation = 101)))
        assertTrue(blocked(WantedState(true, LightMode.Cct, 50, cct = 60)).contains("outside its range 3200-5600 K"))
        // A CCT-bearing scene is range-checked too; an inactive CCT never is.
        assertTrue(blocked(WantedState(true, LightMode.Scene, 50, scene = 5, cct = 20)).contains("outside"))
        assertNull(ModeCommandPlanner.problem(WantedState(true, LightMode.Hsi, 50, hue = 1, saturation = 1, cct = 99), range))
    }

    @Test fun withoutARangeAStoredCctIsAcceptedUntilItIsSent() {
        assertNull(ModeCommandPlanner.problem(WantedState(true, LightMode.Cct, 50, cct = 60), null))
        assertTrue(ModeCommandPlanner.plan(WantedState(true, LightMode.Cct, 50, cct = 60), range) is CommandPlan.Blocked)
    }
}
