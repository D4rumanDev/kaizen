package app.kaizen.commons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los plazos ajustables: sin Android valen los de siempre, y cada valor por defecto es una de sus opciones. */
class TimingsTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test fun `sin preferencias valen los plazos de siempre`() {
        assertEquals(60 * minute, Timings.fichaAiringMs())
        assertEquals(30 * day, Timings.fichaSettledMs())
        assertEquals(14 * day, Timings.catalogCheckMs())
        assertEquals(30 * day, Timings.catalogWalkMs())
        assertEquals(24 * hour, Timings.scheduleMs())
        assertEquals(6 * hour, Timings.episodesMs())
        assertEquals(15 * day, Timings.ratingMs())
        assertEquals(7 * day, Timings.pagesMs())
    }

    @Test fun `la dispersion reparte el plazo entre mas y menos el porcentaje, estable por clave`() {
        val ms = 30 * day
        val values = (1..200).map { Timings.spread(ms, "clave$it", 20) }
        assertTrue(values.all { it in (ms * 0.8).toLong()..(ms * 1.2).toLong() })
        assertTrue(values.distinct().size > 100)
        assertEquals(Timings.spread(ms, "una", 20), Timings.spread(ms, "una", 20))
        assertTrue(values.any { it < ms } && values.any { it > ms })
    }

    @Test fun `sin porcentaje o con un plazo corto no se reparte nada`() {
        assertEquals(30 * day, Timings.spread(30 * day, "x", 0))
        assertEquals(hour, Timings.spread(hour, "x", 50))
        assertEquals(30 * day, Timings.spread(30 * day, "x"))
    }

    @Test fun `cada valor de siempre es una opcion del menu`() {
        assertTrue(60 in KaizenSettings.FICHA_AIRING_OPTIONS && 30 in KaizenSettings.FICHA_SETTLED_OPTIONS)
        assertTrue(14 in KaizenSettings.CATALOG_CHECK_OPTIONS && 30 in KaizenSettings.CATALOG_WALK_OPTIONS)
        assertTrue(24 in KaizenSettings.SCHEDULE_HOURS_OPTIONS && 6 in KaizenSettings.EPISODES_HOURS_OPTIONS)
        assertTrue(15 in KaizenSettings.RATING_DAYS_OPTIONS && 7 in KaizenSettings.PAGES_DAYS_OPTIONS)
    }
}
