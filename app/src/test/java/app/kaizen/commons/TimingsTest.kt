package app.kaizen.commons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Los plazos ajustables: sin Android valen los de siempre, y cada valor por defecto es una de sus opciones. */
class TimingsTest {
    @Test fun `sin preferencias valen los plazos de siempre`() {
        assertEquals(60.minutes, Timings.fichaAiring())
        assertEquals(30.days, Timings.fichaSettled())
        assertEquals(14.days, Timings.catalogCheck())
        assertEquals(30.days, Timings.catalogWalk())
        assertEquals(24.hours, Timings.schedule())
        assertEquals(6.hours, Timings.episodes())
        assertEquals(7.days, Timings.pages())
    }

    @Test fun `los plazos valen lo mismo que antes en milisegundos`() {
        // Red de seguridad de la migracion a Duration: ningun plazo ha cambiado de valor.
        assertEquals(60 * 60_000L, Timings.fichaAiring().inWholeMilliseconds)
        assertEquals(30 * 86_400_000L, Timings.fichaSettled().inWholeMilliseconds)
        assertEquals(14 * 86_400_000L, Timings.catalogCheck().inWholeMilliseconds)
        assertEquals(24 * 3_600_000L, Timings.schedule().inWholeMilliseconds)
        assertEquals(6 * 3_600_000L, Timings.episodes().inWholeMilliseconds)
        assertEquals(7 * 86_400_000L, Timings.pages().inWholeMilliseconds)
    }

    @Test fun `la retencion cubre el plazo mas largo mas la dispersion y nunca baja de 30 dias`() {
        assertEquals(45.days, Timings.retention())
        assertTrue(Timings.retention() >= 30.days)
    }

    @Test fun `la dispersion reparte el plazo entre mas y menos el porcentaje, estable por clave`() {
        val base = 30.days
        val values = (1..200).map { Timings.spread(base, "clave$it", 20) }
        assertTrue(values.all { it >= base * 0.8 && it <= base * 1.2 })
        assertTrue(values.distinct().size > 100)
        assertEquals(Timings.spread(base, "una", 20), Timings.spread(base, "una", 20))
        assertTrue(values.any { it < base } && values.any { it > base })
    }

    @Test fun `sin porcentaje o con un plazo corto no se reparte nada`() {
        assertEquals(30.days, Timings.spread(30.days, "x", 0))
        assertEquals(1.hours, Timings.spread(1.hours, "x", 50))
        assertEquals(30.days, Timings.spread(30.days, "x"))
    }

    @Test fun `cada valor de siempre es una opcion del menu`() {
        assertTrue(60 in KaizenSettings.FICHA_AIRING_OPTIONS && 30 in KaizenSettings.FICHA_SETTLED_OPTIONS)
        assertTrue(14 in KaizenSettings.CATALOG_CHECK_OPTIONS && 30 in KaizenSettings.CATALOG_WALK_OPTIONS)
        assertTrue(24 in KaizenSettings.SCHEDULE_HOURS_OPTIONS && 6 in KaizenSettings.EPISODES_HOURS_OPTIONS)
        assertTrue(7 in KaizenSettings.PAGES_DAYS_OPTIONS)
    }
}
