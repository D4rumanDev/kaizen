package app.kaizen.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenDimmerTest {
    @Test fun `oscurece al cumplirse el tiempo sin gestos`() {
        assertFalse(shouldDim(idleMs = 59_999, timeoutSeconds = 60, busy = false))
        assertTrue(shouldDim(idleMs = 60_000, timeoutSeconds = 60, busy = false))
        assertTrue(shouldDim(idleMs = 3_600_000, timeoutSeconds = 60, busy = false))
    }

    @Test fun `con el tiempo en nunca no oscurece jamas`() {
        assertFalse(shouldDim(idleMs = Long.MAX_VALUE, timeoutSeconds = 0, busy = false))
    }

    @Test fun `con un video sonando no oscurece aunque pase el tiempo`() {
        assertFalse(shouldDim(idleMs = 3_600_000, timeoutSeconds = 30, busy = true))
    }
}
