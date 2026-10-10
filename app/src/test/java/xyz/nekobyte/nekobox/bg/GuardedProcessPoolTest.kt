package xyz.nekobyte.nekobox.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardedProcessPoolTest {
    @Test
    fun earlyExitsFailInsteadOfRestartingInALoop() {
        assertTrue(shouldFailAfterProcessExit(0))
        assertTrue(shouldFailAfterProcessExit(999))
        assertFalse(shouldFailAfterProcessExit(1_000))
        assertFalse(shouldFailAfterProcessExit(60_000))
    }
}
