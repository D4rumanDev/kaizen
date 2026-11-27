package app.kaizen.tv.ui

import app.kaizen.commons.KaizenProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardWatchTest {
    private val midway = KaizenProgress.Progress(positionMs = 600_000, durationMs = 1_440_000)

    @Test
    fun `un capitulo guardado como visto pero a medias es seguir viendo y no lleva el check`() {
        assertEquals(CardWatch.RESUMING, cardWatchOf(seen = true, progress = midway))
    }

    @Test
    fun `solo el acabado lleva el check`() {
        assertEquals(CardWatch.SEEN, cardWatchOf(seen = true, progress = null))
    }

    @Test
    fun `sin visto ni progreso no hay marca`() {
        assertEquals(CardWatch.NONE, cardWatchOf(seen = false, progress = null))
    }

    @Test
    fun `con progreso a medias y sin visto tambien es seguir viendo`() {
        assertEquals(CardWatch.RESUMING, cardWatchOf(seen = false, progress = midway))
    }

    @Test
    fun `una tarjeta sin marca con la base diciendo visto esta desfasada`() {
        assertTrue(isStaleCard(CardWatch.NONE, ready = true, seenInDb = true))
    }

    @Test
    fun `no esta desfasada si la marca existe, si la base no lo tiene visto o si aun no esta todo cargado`() {
        assertFalse(isStaleCard(CardWatch.SEEN, ready = true, seenInDb = true))
        assertFalse(isStaleCard(CardWatch.RESUMING, ready = true, seenInDb = true))
        assertFalse(isStaleCard(CardWatch.NONE, ready = true, seenInDb = false))
        assertFalse(isStaleCard(CardWatch.NONE, ready = false, seenInDb = true))
    }
}
