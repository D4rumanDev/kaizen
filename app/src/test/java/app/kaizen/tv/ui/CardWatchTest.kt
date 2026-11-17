package app.kaizen.tv.ui

import app.kaizen.commons.KaizenProgress
import org.junit.Assert.assertEquals
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
}
