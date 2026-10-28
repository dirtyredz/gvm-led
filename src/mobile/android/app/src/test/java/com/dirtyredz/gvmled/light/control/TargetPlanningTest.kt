package com.dirtyredz.gvmled.light.control

import com.dirtyredz.gvmled.light.protocol.FullStateReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TargetEditPlanner and TargetStateReducer over three lights with distinct settings and ranges. */
class TargetPlanningTest {
    private val a = "A"
    private val b = "B"
    private val c = "C"
    private val names = mapOf(a to "Key", b to "Fill", c to "Back")
    private val name: (String) -> String = { names.getValue(it) }

    private val key = WantedState(power = true, mode = LightMode.Hsi, brightness = 50, hue = 48, saturation = 100, cct = 44)
    private val fill = WantedState(power = false, mode = LightMode.Cct, brightness = 30, cct = 30)
    private val back = WantedState(power = true, mode = LightMode.Scene, brightness = 70, scene = 19, cct = 40, effectTime = 20)
    private val wanted = mapOf(a to key, b to fill, c to back)
    private val ranges = mapOf(a to CctRange(32, 56), b to CctRange(27, 65), c to CctRange(30, 50))

    private fun expand(members: List<String>, edit: LightEdit, r: Map<String, CctRange?> = ranges, w: Map<String, WantedState> = wanted) =
        TargetEditPlanner.expand(members, w, r, name, edit)

    private fun accepted(plan: EditPlan): Map<String, WantedState> {
        assertTrue("expected Accepted, got $plan", plan is EditPlan.Accepted)
        return (plan as EditPlan.Accepted).states
    }

    @Test fun aFieldEditPreservesEveryUntouchedPerMemberSetting() {
        val next = accepted(expand(listOf(a, b, c), LightEdit(brightness = 20)))
        assertEquals(key.copy(brightness = 20), next[a])
        assertEquals(fill.copy(brightness = 20), next[b])
        assertEquals(back.copy(brightness = 20), next[c])
    }

    @Test fun groupPowerKeepsEachMembersModeAndValues() {
        val next = accepted(expand(listOf(a, b), LightEdit(power = true)))
        assertEquals(LightMode.Hsi, next.getValue(a).mode)
        assertEquals(fill.copy(power = true), next[b])
    }

    @Test fun onlyTheSnapshotMembersAreInTheResult() {
        assertEquals(setOf(a, c), accepted(expand(listOf(a, c, a), LightEdit(brightness = 1))).keys)
    }

    @Test fun aMemberWithoutSettingsBlocksTheWholeEditByName() {
        val plan = expand(listOf(a, b), LightEdit(brightness = 5), w = mapOf(a to key))
        assertEquals(EditPlan.Rejected("Fill has no settings yet; wait until it has connected once"), plan)
    }

    @Test fun switchingModeFillsOnlyMissingValuesAndNamesAMemberStillMissingOne() {
        val toHsi = LightEdit(mode = LightMode.Hsi, hue = 12, saturation = 40, fillOnly = setOf(Field.Hue, Field.Saturation))
        val next = accepted(expand(listOf(a, b), toHsi))
        assertEquals(key, next[a]) // already HSI with its own hue: nothing overwritten
        assertEquals(fill.copy(mode = LightMode.Hsi, hue = 12, saturation = 40), next[b])

        val bare = expand(listOf(a, b), LightEdit(mode = LightMode.Hsi))
        assertEquals(EditPlan.Rejected("Fill needs a value for hue"), bare)
    }

    @Test fun anExplicitEditOverwritesEveryMembersValue() {
        val next = accepted(expand(listOf(a, b), LightEdit(mode = LightMode.Hsi, hue = 12, saturation = 40)))
        assertEquals(12, next.getValue(a).hue)
        assertEquals(12, next.getValue(b).hue)
    }

    @Test fun hueWrapsAt360DegreesForAdoptionAndEditing() {
        val report = checkNotNull(FullStateReport.decode(0, 0x33, listOf(1, 1, 50, 44, 72, 100, 2, 0, 0)))
        assertEquals(0, WantedState.fromReport(report, ranges.getValue(a))?.hue)
        assertEquals(0, LightEdit(brightness = 60).applyTo(key.copy(hue = 72)).hue)
        assertEquals(0..71, Field.Hue.limits)
    }

    @Test fun cctEditsNeedEveryMembersRangeAndMustFitEachOne() {
        assertEquals(EditPlan.Rejected("Key has not reported its colour temperature range yet"), expand(listOf(a, b), LightEdit(cct = 40), r = mapOf(b to ranges[b])))
        assertEquals(EditPlan.Rejected("Key supports only 3200-5600 K"), expand(listOf(a, b), LightEdit(cct = 60)))
        assertEquals(EditPlan.Rejected("Back supports only 3000-5000 K"), expand(listOf(b, c), LightEdit(cct = 55)))
        val next = accepted(expand(listOf(a, b, c), LightEdit(cct = 45)))
        assertTrue(next.values.all { it.cct == 45 })
    }

    @Test fun anOfflineMemberWithStoredIntentAcceptsEditsThatNeedNoRange() {
        val next = accepted(expand(listOf(a, b), LightEdit(brightness = 60), r = mapOf(a to ranges[a])))
        assertEquals(60, next.getValue(b).brightness)
    }

    @Test fun selectingAnUnsupportedOrIncompleteSceneIsRejected() {
        assertEquals(EditPlan.Rejected("Key scene 21 is not supported"), expand(listOf(a), LightEdit(mode = LightMode.Scene, scene = 21)))
        assertEquals(EditPlan.Rejected("Key needs a value for effect time"), expand(listOf(a), LightEdit(mode = LightMode.Scene, scene = 6)))
        val next = accepted(expand(listOf(a), LightEdit(mode = LightMode.Scene, scene = 6, effectTime = 10, fillOnly = setOf(Field.EffectTime))))
        assertEquals(10, next.getValue(a).effectTime)
    }

    @Test fun noMembersIsRejected() {
        assertEquals(EditPlan.Rejected("No lights are selected"), expand(emptyList(), LightEdit(power = true)))
    }

    @Test fun reducerShowsSameMixedAndUnknownHonestly() {
        val values = TargetStateReducer.reduce(listOf(a, b, c), wanted, ranges, name)
        assertEquals(Shared.Mixed, values.power)
        assertEquals(Shared.Mixed, values.mode)
        assertEquals(Shared.Mixed, values.brightness)
        assertEquals(Shared.Unknown, values.hue) // b and c have no hue: agreement cannot be established
        val same = TargetStateReducer.reduce(listOf(a, c), wanted + (c to back.copy(brightness = 50)), ranges, name)
        assertEquals(Shared.Same(true), same.power)
        assertEquals(Shared.Same(50), same.brightness)
        assertEquals(Shared.Unknown, TargetStateReducer.reduce(listOf(a, b), mapOf(a to key), ranges, name).brightness)
        assertEquals(listOf(b), TargetStateReducer.reduce(listOf(a, b), mapOf(a to key), ranges, name).withoutSettings)
    }

    @Test fun cctIntersectionAcrossThreeRangesAndItsReasons() {
        assertEquals(CctRange(32, 50), TargetStateReducer.reduce(listOf(a, b, c), wanted, ranges, name).cctRange)
        val missing = TargetStateReducer.reduce(listOf(a, b, c), wanted, ranges - c, name)
        assertNull(missing.cctRange)
        assertEquals("Back has not reported its colour temperature range yet", missing.cctUnavailable)
        val disjoint = TargetStateReducer.reduce(listOf(a, b), wanted, mapOf(a to CctRange(32, 40), b to CctRange(45, 65)), name)
        assertNull(disjoint.cctRange)
        assertEquals("The selected lights share no colour temperature", disjoint.cctUnavailable)
        assertNull(TargetStateReducer.reduce(listOf(a), wanted, ranges, name).cctUnavailable)
        assertEquals(CctRange(40, 40), CctRange.intersection(listOf(CctRange(32, 40), CctRange(40, 65))))
    }
}
