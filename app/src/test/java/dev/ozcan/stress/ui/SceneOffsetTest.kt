package dev.ozcan.stress.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class SceneOffsetTest {
    private val surface = Rect(left = 0f, top = 40f, right = 1080f, bottom = 2440f)

    @Test
    fun movesTheSubjectToTheWindowCentre() {
        assertEquals(-0.125f, sceneOffsetY(surface, 940f), 0f)
        assertEquals(0.125f, sceneOffsetY(surface, 1540f), 0f)
        assertEquals(0f, sceneOffsetY(surface, 1240f), 0f)
    }

    @Test
    fun centresTheSubjectWhenThePanelsAreHiddenOrNothingIsMeasured() {
        assertEquals(0f, sceneOffsetY(surface, null), 0f)
        assertEquals(0f, sceneOffsetY(Rect.Zero, 940f), 0f)
    }
}
