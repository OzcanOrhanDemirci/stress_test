package dev.ozcan.stress.ui

import dev.ozcan.stress.ui.theme.textScaleFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Text follows the window's width: smaller on tiny phones, as designed on normal phones, larger on tablets. */
class TextScaleTest {

    @Test
    fun referencePhonesStayExactlyAsDesigned() {
        listOf(360f, 384f, 393f, 411f, 480f).forEach { assertEquals("at $it dp", 1f, textScaleFor(it), 0f) }
    }

    @Test
    fun smallPhonesShrinkAndTabletsGrow() {
        assertEquals(0.9f, textScaleFor(280f), 0.0001f)
        assertEquals(0.9f, textScaleFor(320f), 0.0001f)
        assertEquals(0.95f, textScaleFor(340f), 0.0001f)
        assertEquals(1.06f, textScaleFor(540f), 0.0001f)
        assertEquals(1.12f, textScaleFor(600f), 0.0001f)
        assertEquals(1.12f, textScaleFor(1280f), 0.0001f)
    }

    @Test
    fun neverJumpsAsTheWindowWidens() {
        var previous = textScaleFor(200f)
        var width = 200f
        while (width <= 1000f) {
            val scale = textScaleFor(width)
            assertTrue("falls at $width dp", scale >= previous)
            assertTrue("jumps at $width dp", scale - previous < 0.01f)
            previous = scale
            width += 1f
        }
    }
}
