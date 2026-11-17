package app.kaizen.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchInsightsTest {
    private val series = listOf(
        WatchedSeries(24, studio = "MAPPA", year = 2021, format = "TV", minutes = 24),
        WatchedSeries(12, studio = "MAPPA", year = 2023, format = "TV", minutes = 24),
        WatchedSeries(50, studio = "Bones", year = 2009, format = "TV", minutes = 24),
        WatchedSeries(1, studio = "Ghibli", year = 1997, format = "MOVIE", minutes = 120),
        WatchedSeries(10, format = null)
    )

    @Test
    fun `los estudios se ordenan por capitulos vistos y cuentan sus series`() {
        val studios = WatchInsights.compute(series).studios
        assertEquals(listOf("Bones", "MAPPA", "Ghibli"), studios.map { it.name })
        assertEquals(StudioWatch("MAPPA", 36, 2), studios[1])
    }

    @Test
    fun `dice en cuantas series se conoce el estudio`() {
        val insights = WatchInsights.compute(series)
        assertEquals(4, insights.withStudio)
        assertEquals(5, insights.total)
    }

    @Test
    fun `las epocas se agrupan por decada de la mas antigua a la mas reciente`() {
        assertEquals(listOf(1990 to 1, 2000 to 50, 2020 to 36), WatchInsights.compute(series).decades)
    }

    @Test
    fun `los formatos van en espanol y lo desconocido como sin datos`() {
        val formats = WatchInsights.compute(series).formats
        assertEquals("Serie" to 86, formats.first())
        assertEquals(setOf("Serie", "Película", "Sin datos"), formats.map { it.first }.toSet())
    }

    @Test
    fun `las horas usan la duracion real y 24 minutos cuando no se conoce`() {
        // 86 capítulos de 24 min + 1 de 120 + 10 de 24 (sin dato) = 2064 + 120 + 240 = 2424 min = 40 h.
        assertEquals(40, WatchInsights.compute(series).hours)
    }

    @Test
    fun `un ano fuera de rango o una duracion absurda no cuentan y el estudio se recorta`() {
        val insights = WatchInsights.compute(listOf(
            WatchedSeries(2, studio = " MAPPA ", year = 1800, minutes = 0),
            WatchedSeries(3, studio = "MAPPA", year = 2200, minutes = 9999)
        ))
        assertEquals(emptyList<Pair<Int, Int>>(), insights.decades)
        assertEquals(StudioWatch("MAPPA", 5, 2), insights.studios.single())
        // Duraciones inválidas: 24 min por capítulo (5 × 24 = 120 min = 2 h).
        assertEquals(2, insights.hours)
    }

    @Test
    fun `sin series no hay nada y no falla`() {
        val empty = WatchInsights.compute(emptyList())
        assertEquals(0, empty.hours)
        assertEquals(emptyList<StudioWatch>(), empty.studios)
    }
}
