package app.kaizen.pojos

import app.kaizen.pojos.av1.SeriesKind
import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesKindTest {

    @Test
    fun `el texto y el numero de cada tipo se traducen en los dos sentidos`() {
        SeriesKind.entries.forEach { kind ->
            assertEquals(kind, SeriesKind.fromSlug(kind.slug))
            assertEquals(kind, SeriesKind.fromCode(kind.code))
        }
    }

    @Test
    fun `un tipo desconocido es un especial`() {
        assertEquals(SeriesKind.SPECIAL, SeriesKind.fromSlug("otra-cosa"))
        assertEquals(SeriesKind.SPECIAL, SeriesKind.fromSlug(null))
        assertEquals(SeriesKind.SPECIAL, SeriesKind.fromCode(99))
    }

    @Test
    fun `los numeros coinciden con los que ya guardaba el catalogo`() {
        assertEquals(1, SeriesKind.fromSlug("tv-anime").code)
        assertEquals(2, SeriesKind.fromSlug("pelicula").code)
        assertEquals(3, SeriesKind.fromSlug("ova").code)
        assertEquals("Película", SeriesKind.fromCode(2).label)
    }
}
