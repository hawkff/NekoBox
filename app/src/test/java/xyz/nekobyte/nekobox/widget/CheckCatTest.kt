package xyz.nekobyte.nekobox.widget

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CheckCatTest {
    @Test
    fun pathDataBecomesCommands() {
        val commands = pathCommands("M94 54 C108 58 116 84 114 108 L66 108 Z M1 2 3 4 Q-5 .5 6.25 -7")
        assertEquals(listOf('M', 'C', 'L', 'Z', 'M', 'L', 'Q'), commands.map { it.first })
        assertArrayEquals(floatArrayOf(108f, 58f, 116f, 84f, 114f, 108f), commands[1].second, 0f)
        // Coordinates after a move continue as a line.
        assertArrayEquals(floatArrayOf(3f, 4f), commands[5].second, 0f)
        assertArrayEquals(floatArrayOf(-5f, 0.5f, 6.25f, -7f), commands[6].second, 0f)
    }

    @Test
    fun pathDataOutsideTheSubsetIsRefused() {
        assertThrows(IllegalStateException::class.java) { pathCommands("M0 0 Z 1 2") }
        assertThrows(IllegalArgumentException::class.java) { pathCommands("M0 0 C1 2 3") }
    }
}
