package com.dirtyredz.gvmled.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseVersionTest {
    @Test fun versionsFollowTheReleaseSequenceAndRollOver() {
        assertEquals("0.1.1", ReleaseVersion.versionForSequence(1))
        assertEquals("0.1.59999", ReleaseVersion.versionForSequence(59_999))
        assertEquals("0.2.0", ReleaseVersion.versionForSequence(60_000))
        assertEquals("0.2.1", ReleaseVersion.versionForSequence(60_001))
        assertNull(ReleaseVersion.versionForSequence(0))
        assertNull(ReleaseVersion.versionForSequence(ReleaseVersion.MAX_SEQUENCE + 1))
    }

    @Test fun sequencesRoundTripAndRejectNonCanonicalVersions() {
        for (sequence in listOf(1L, 2L, 59_999L, 60_000L, 60_001L, 3_600_000L)) {
            assertEquals(sequence, ReleaseVersion.sequenceForVersion(ReleaseVersion.versionForSequence(sequence)!!))
        }
        for (bad in listOf("0.1.0", "0.0.5", "01.1.1", "0.1.60000", "0.1", "0.1.1-rc1", "v0.1.1")) {
            assertNull(bad, ReleaseVersion.sequenceForVersion(bad))
        }
    }

    @Test fun versionCodeIsSequencePlusOneNotThePatch() {
        assertEquals("0.1.1", ReleaseVersion.versionNameForCode(2))
        assertEquals("0.2.0", ReleaseVersion.versionNameForCode(60_001))
        assertNull(ReleaseVersion.versionNameForCode(1))
    }
}
