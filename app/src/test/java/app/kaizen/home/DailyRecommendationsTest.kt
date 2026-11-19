package app.kaizen.home

import org.junit.Assert.assertEquals
import org.junit.Test

class DailyRecommendationsTest {
    @Test fun `lo de ayer pasa delante de lo anterior sin repetidos`() {
        assertEquals(listOf("c", "d", "a", "b"), DailyRecommendations.rotate(listOf("a", "b", "c"), listOf("c", "d")))
    }

    @Test fun `la lista de recientes no crece mas alla del tope`() {
        val recent = (1..40).map { "viejo$it" }
        val yesterday = (1..15).map { "ayer$it" }
        val rotated = DailyRecommendations.rotate(recent, yesterday)
        assertEquals(DailyRecommendations.RECENT_KEEP, rotated.size)
        assertEquals(yesterday, rotated.take(15))
        // Lo más viejo es lo que se olvida.
        assertEquals("viejo30", rotated.last())
    }

    @Test fun `sin historial solo quedan los de ayer`() {
        assertEquals(listOf("x", "y"), DailyRecommendations.rotate(emptyList(), listOf("x", "y")))
        assertEquals(emptyList<String>(), DailyRecommendations.rotate(emptyList(), emptyList()))
    }
}
