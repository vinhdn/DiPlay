package com.shilapi.xcertplay

import com.shilapi.xcertplay.SecondDisplayPolicy.AUTO
import com.shilapi.xcertplay.SecondDisplayPolicy.DisplayInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecondDisplayPolicyTest {
    private val builtIn = DisplayInfo(0, "Built-in", 1920, 1080, isPresentation = false, isUsable = true)
    private val virtual = DisplayInfo(2, "launcher-pip", 800, 480, isPresentation = false, isUsable = true)
    private val hdmi = DisplayInfo(5, "HDMI", 1920, 720, isPresentation = true, isUsable = true)
    private val usbc = DisplayInfo(7, "USB-C", 2560, 1440, isPresentation = true, isUsable = true)

    @Test fun autoTakesTheFirstPresentationDisplayNeverTheBuiltInOne() {
        assertEquals(5, SecondDisplayPolicy.choose(AUTO, listOf(builtIn, virtual, usbc, hdmi), emptySet())?.displayId)
    }

    @Test fun autoNeverPicksANonPresentationDisplay() {
        assertNull(SecondDisplayPolicy.choose(AUTO, listOf(builtIn, virtual), emptySet()))
    }

    @Test fun aSavedDisplayWinsWhileAttachedEvenWithoutThePresentationFlag() {
        assertEquals(2, SecondDisplayPolicy.choose(2, listOf(builtIn, virtual, hdmi), emptySet())?.displayId)
        assertEquals(7, SecondDisplayPolicy.choose(7, listOf(builtIn, hdmi, usbc), emptySet())?.displayId)
    }

    @Test fun aStaleSavedIdFallsBackToAutomatic() {
        val choice = SecondDisplayPolicy.choose(9, listOf(builtIn, hdmi), emptySet())
        assertEquals(5, choice?.displayId)
    }

    @Test fun carPlaysOwnDisplayAndUnusableDisplaysAreExcluded() {
        assertEquals(7, SecondDisplayPolicy.choose(AUTO, listOf(builtIn, hdmi, usbc), setOf(5))?.displayId)
        assertEquals(7, SecondDisplayPolicy.choose(5, listOf(builtIn, hdmi, usbc), setOf(5))?.displayId)
        assertNull(SecondDisplayPolicy.choose(AUTO, listOf(builtIn, hdmi.copy(isUsable = false)), emptySet()))
        assertNull(SecondDisplayPolicy.choose(0, listOf(builtIn), emptySet()))
    }

    @Test fun streamSizeKeepsShapeAndCapsAt1080p() {
        assertEquals(1920 to 720, SecondDisplayPolicy.streamSize(1920, 720))
        assertEquals(1920 to 1080, SecondDisplayPolicy.streamSize(3840, 2160))
        assertEquals(1440 to 1080, SecondDisplayPolicy.streamSize(2048, 1536))
        assertNull(SecondDisplayPolicy.streamSize(1920, 100))
    }
}
