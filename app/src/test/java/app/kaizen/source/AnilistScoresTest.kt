package app.kaizen.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** La única nota de la app es la de AniList (0 a 100), pasada a estrellas (0 a 5). */
class AnilistScoresTest {
    @Test
    fun `la nota de AniList sobre cien pasa a estrellas sobre cinco`() {
        assertEquals(3.9, AnilistScores.stars(78)!!, 1e-9)
        assertEquals(5.0, AnilistScores.stars(100)!!, 1e-9)
        assertEquals(0.05, AnilistScores.stars(1)!!, 1e-9)
    }

    @Test
    fun `sin nota, o con una de cero, no hay nota ni se inventa una`() {
        assertNull(AnilistScores.stars(null))
        assertNull(AnilistScores.stars(0))
        assertNull(AnilistScores.stars(-5))
    }

    @Test
    fun `una nota fuera de rango no pasa de cinco estrellas`() {
        assertEquals(5.0, AnilistScores.stars(250)!!, 1e-9)
    }
}
