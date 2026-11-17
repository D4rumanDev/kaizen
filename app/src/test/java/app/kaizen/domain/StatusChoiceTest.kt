package app.kaizen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Elegir «Completada» a mano: marca todo, y una serie en emisión queda «Viendo» con aviso. */
class StatusChoiceTest {
    @Test fun `completada de una serie finalizada se queda completada sin aviso`() {
        val r = StatusChoice.decide(SeriesStatus.COMPLETED, airing = false)
        assertEquals(SeriesStatus.COMPLETED, r.status); assertNull(r.message)
    }

    @Test fun `completada de una serie en emision queda viendo y avisa`() {
        val r = StatusChoice.decide(SeriesStatus.COMPLETED, airing = true)
        assertEquals(SeriesStatus.WATCHING, r.status); assertNotNull(r.message)
    }

    @Test fun `cualquier otro estado se pone tal cual`() {
        SeriesStatus.entries.filter { it != SeriesStatus.COMPLETED }.forEach {
            assertEquals(it, StatusChoice.decide(it, airing = true).status); assertNull(StatusChoice.decide(it, airing = true).message)
        }
    }

    @Test fun `los nombres de los estados`() {
        assertEquals("Descartada", SeriesStatus.DROPPED.label)
    }
}
