package com.dirtyredz.gvmled.light.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SceneCatalogTest {
    @Test fun scenesOneThroughTwentyAreSupported() {
        for (scene in 1..20) assertTrue("scene $scene", SceneCatalog.isSupported(scene))
        assertEquals(1..20, SceneCatalog.IDS)
    }

    @Test fun sceneZeroAndTwentyOneAreNotSupported() {
        assertFalse(SceneCatalog.isSupported(0))
        assertFalse(SceneCatalog.isSupported(21))
        assertFalse(SceneCatalog.isSupported(-1))
    }

    @Test fun cctIsUsedOnlyByTheCctScenes() {
        val expected = setOf(5, 9, 10, 12, 19)
        assertEquals(expected, SceneCatalog.IDS.filter { SceneCatalog.fields(it).cct }.toSet())
    }

    @Test fun effectTimeIsUsedOnlyByTheTimedScenes() {
        val expected = setOf(6, 7, 14, 15, 19, 20)
        assertEquals(expected, SceneCatalog.IDS.filter { SceneCatalog.fields(it).effectTime }.toSet())
    }

    @Test fun colourIsUsedOnlyBySceneTwenty() {
        assertEquals(setOf(20), SceneCatalog.IDS.filter { SceneCatalog.fields(it).colour }.toSet())
    }

    @Test fun sceneNineteenUsesCctAndEffectTimeButNoColour() {
        assertEquals(SceneFields(cct = true, colour = false, effectTime = true), SceneCatalog.fields(19))
    }

    @Test fun sceneOneUsesNothingBesidesBrightness() {
        assertEquals(SceneFields(cct = false, colour = false, effectTime = false), SceneCatalog.fields(1))
    }

    @Test fun fieldsRejectsUnsupportedScenes() {
        for (scene in listOf(0, 21)) {
            try {
                SceneCatalog.fields(scene)
                fail("expected IllegalArgumentException for $scene")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test fun effectTimesAreOneThroughFifty() {
        assertEquals(1..50, SceneCatalog.EFFECT_TIMES)
    }
}
