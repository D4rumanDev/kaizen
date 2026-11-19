package app.kaizen.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class BestAiringCacheTest {
    private val now = 1_800_000_000_000L

    @Test fun `lo guardado hace menos de una semana vale`() {
        assertTrue(BestAiringCache.isFresh(now - 6.days.inWholeMilliseconds, now))
        assertTrue(BestAiringCache.isFresh(now, now))
    }

    @Test fun `lo guardado hace una semana o mas se rehace`() {
        assertFalse(BestAiringCache.isFresh(now - BestAiringCache.MAX_AGE.inWholeMilliseconds, now))
        assertFalse(BestAiringCache.isFresh(now - 8.days.inWholeMilliseconds, now))
    }

    @Test fun `un guardado en el futuro por un reloj retrasado no vale`() {
        assertFalse(BestAiringCache.isFresh(now + 1.hours.inWholeMilliseconds, now))
    }
}
