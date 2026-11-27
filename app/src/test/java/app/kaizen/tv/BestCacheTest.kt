package app.kaizen.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class BestCacheTest {
    private val now = 1_800_000_000_000L

    @Test fun `lo guardado hace menos de una semana vale`() {
        assertTrue(BestCache.isFresh(now - 6.days.inWholeMilliseconds, now))
        assertTrue(BestCache.isFresh(now, now))
    }

    @Test fun `lo guardado hace una semana o mas se rehace`() {
        assertFalse(BestCache.isFresh(now - BestCache.MAX_AGE.inWholeMilliseconds, now))
        assertFalse(BestCache.isFresh(now - 8.days.inWholeMilliseconds, now))
    }

    @Test fun `un guardado en el futuro por un reloj retrasado no vale`() {
        assertFalse(BestCache.isFresh(now + 1.hours.inWholeMilliseconds, now))
    }
}
