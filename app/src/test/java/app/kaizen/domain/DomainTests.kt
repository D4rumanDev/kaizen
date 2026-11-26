package app.kaizen.domain

import app.kaizen.commons.KaizenProgress
import app.kaizen.pojos.av1.Chapter
import app.kaizen.pojos.av1.Record
import app.kaizen.pojos.av1.SeriesSummary
import app.kaizen.source.TestLedger
import app.kaizen.source.normalizeTitle
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// ===== NotRecommendedTest.kt =====
class NotRecommendedTest {
    @Before
    fun ledger() = TestLedger.install(mapOf(("animeav1" to "fx-senshi") to 7L, ("monoschinos" to "fx-fighter") to 7L))

    @After
    fun resetLedger() = TestLedger.reset()

    @Test
    fun `excluir una ficha excluye el grupo aunque se llame distinto`() {
        val excluded = setOf("fxsenshi", "#7")
        assertTrue(NotRecommended.isExcluded(excluded, "FX Fighter", "monoschinos", "fx-fighter"))
        assertTrue(NotRecommended.isExcluded(excluded, "FX Senshi"))
    }

    @Test
    fun `otra serie o sin ficha no queda excluida por el id`() {
        val excluded = setOf("#7")
        assertFalse(NotRecommended.isExcluded(excluded, "FX Fighter"))
        assertFalse(NotRecommended.isExcluded(excluded, "Otra", "animeav1", "otra"))
    }
}

// ===== SeriesStatusLookupTest.kt =====
class SeriesStatusLookupTest {
    @Before
    fun ledger() = TestLedger.install(mapOf(("animeav1" to "fx-senshi") to 7L, ("monoschinos" to "fx-fighter") to 7L))

    @After
    fun resetLedger() = TestLedger.reset()

    private fun entry(status: SeriesStatus, name: String, source: String, slug: String, at: Long) =
        StatusEntry(status.id, name, source, slug, 1, at)

    private val map = mapOf(
        "fxsenshi" to entry(SeriesStatus.WATCHING, "FX Senshi", "animeav1", "fx-senshi", at = 100),
        "fxfighter" to entry(SeriesStatus.DROPPED, "FX Fighter", "monoschinos", "fx-fighter", at = 200)
    )

    @Test
    fun `fichas unidas con estados distintos manda el mas reciente en ambas tarjetas`() {
        assertEquals(SeriesStatus.DROPPED, SeriesStatuses.statusIn(map, "FX Senshi", "animeav1", "fx-senshi"))
        assertEquals(SeriesStatus.DROPPED, SeriesStatuses.statusIn(map, "FX Fighter", "monoschinos", "fx-fighter"))
    }

    @Test
    fun `sin fuente ni slug solo cuenta el titulo`() {
        assertEquals(SeriesStatus.WATCHING, SeriesStatuses.statusIn(map, "FX Senshi"))
    }
}

// ===== StoredTasteTest.kt =====
class StoredTasteTest {
    private val taste = StoredTaste(
        listOf("accion", "drama"),
        listOf(StoredRelation(2, 77, "animeav1", "frieren-2", "Frieren; 2ª temporada, «parte» 2"))
    )

    @Test
    fun `se guarda y se lee igual, con titulos que llevan separadores`() {
        val (stamp, back) = StoredTaste.decode(taste.encode(1234L))!!
        assertEquals(1234L, stamp)
        assertEquals(taste, back)
    }

    @Test
    fun `sin relaciones tambien vale`() {
        val plain = StoredTaste(listOf("comedia"), emptyList())
        assertEquals(plain, StoredTaste.decode(plain.encode(5L))!!.second)
    }

    @Test
    fun `el formato antiguo sin relaciones o un texto roto no se acepta`() {
        assertNull(StoredTaste.decode("1234;accion,drama"))
        assertNull(StoredTaste.decode("nada"))
        assertNull(StoredTaste.decode("x;accion;"))
        assertNull(StoredTaste.decode("1234;;"))
    }

    @Test
    fun `una relacion rota se ignora y las demas se conservan`() {
        val good = taste.relations.single()
        val raw = "9;accion;roto\u001e" + listOf(good.type, good.aid, good.source, good.slug, good.name).joinToString("\u001f")
        assertEquals(listOf(good), StoredTaste.decode(raw)!!.second.relations)
    }
}

// ===== TasteTest.kt =====
class TasteTest {

    private fun summary(name: String, score: Double?) = SeriesSummary(name.hashCode(), name, name.lowercase().replace(' ', '-'), 1, "animeav1", score)

    @Test
    fun `los generos se ordenan por peso y cuentan las series`() {
        val profile = TasteProfile.build(
            listOf(
                TasteItem(listOf("accion", "drama"), 3.0),
                TasteItem(listOf("accion"), 1.0),
                TasteItem(listOf("comedia"), 1.5)
            )
        )

        assertEquals(listOf("accion", "drama", "comedia"), profile.genres.map { it.slug })
        assertEquals(4.0, profile.genres[0].weight, 0.0)
        assertEquals(2, profile.genres[0].series)
    }

    @Test
    fun `sin series el perfil esta vacio`() {
        assertTrue(TasteProfile.build(emptyList()).isEmpty)
    }

    @Test
    fun `un genero repetido en una serie cuenta una vez`() {
        val profile = TasteProfile.build(listOf(TasteItem(listOf("accion", "accion"), 2.0)))
        assertEquals(2.0, profile.genres.single().weight, 0.0)
    }

    private val profile = TasteProfile.build(
        listOf(TasteItem(listOf("accion"), 3.0), TasteItem(listOf("drama"), 1.0))
    )

    @Test
    fun `recomienda primero lo que encaja en mas generos y luego lo de mejor nota`() {
        val both = summary("En los dos", 4.8)
        val onlyAction = summary("Solo accion", 4.0)
        val onlyDrama = summary("Solo drama", 5.0)

        val ranked = Taste.rank(
            mapOf("accion" to listOf(onlyAction, both), "drama" to listOf(both, onlyDrama)),
            profile, emptySet(), 10
        )

        assertEquals(listOf("En los dos", "Solo accion", "Solo drama"), ranked.map { it.name })
    }

    @Test
    fun `quita lo que ya tienes y lo de nota por debajo de tres`() {
        val known = summary("Ya la tengo", 4.5)
        val bad = summary("Mala", 2.4)
        val ok = summary("Buena", 4.0)
        val unknownScore = summary("Sin nota", null)

        val ranked = Taste.rank(
            mapOf("accion" to listOf(known, bad, ok, unknownScore)),
            profile, setOf(normalizeTitle("Ya la tengo")), 10
        )

        assertEquals(listOf("Buena", "Sin nota"), ranked.map { it.name })
    }

    @Test
    fun `respeta el limite y sin perfil no recomienda`() {
        val many = (1..10).map { summary("Serie $it", 4.0) }

        assertEquals(3, Taste.rank(mapOf("accion" to many), profile, emptySet(), 3).size)
        assertTrue(Taste.rank(mapOf("accion" to many), TasteProfile.EMPTY, emptySet(), 3).isEmpty())
    }

    @Test
    fun `los generos se sortean sin repetir y con mas opciones los de mas peso`() {
        val wide = TasteProfile.build(listOf(
            TasteItem(listOf("accion"), 100.0), TasteItem(listOf("drama"), 1.0),
            TasteItem(listOf("comedia"), 1.0), TasteItem(listOf("terror"), 1.0)
        ))

        val picks = (1..200).map { Taste.pickGenres(wide, 2, Random(it)).map { g -> g.slug } }

        assertTrue(picks.all { it.distinct().size == 2 })
        assertTrue(picks.count { "accion" in it } > 150)
        assertTrue("también salen los de menos peso", picks.any { "terror" in it })
    }

    @Test
    fun `con azar sale una seleccion distinta cada vez pero siempre entre las mejores y sin lo excluido`() {
        val pool = (1..30).map { summary("Serie $it", 3.0 + (it % 10) / 5.0) }
        val known = setOf(normalizeTitle("Serie 1"))

        val runs = (1..30).map { seed -> Taste.rank(mapOf("accion" to pool), profile, known, 10, Random(seed)) }

        assertTrue(runs.all { it.size == 10 && it.distinct().size == 10 })
        assertTrue(runs.all { run -> run.none { it.name == "Serie 1" } })
        assertTrue("no siempre las mismas", runs.map { run -> run.map { it.name } }.distinct().size > 5)
        assertTrue("más de las 10 primeras", runs.flatten().map { it.name }.distinct().size > 10)
    }

    @Test
    fun `con la misma semilla el resultado es el mismo`() {
        val pool = (1..30).map { summary("Serie $it", 4.0) }
        val first = Taste.rank(mapOf("accion" to pool), profile, emptySet(), 10, Random(7))
        val second = Taste.rank(mapOf("accion" to pool), profile, emptySet(), 10, Random(7))
        assertEquals(first, second)
    }

    @Test
    fun `las precuelas y secuelas van delante, sin repetir y con tope`() {
        val sequels = (1..10).map { summary("Secuela $it", null) }
        val recommended = listOf(summary("Secuela 3", 4.0)) + (1..20).map { summary("Otra $it", 4.0) }

        val result = Taste.withSequels(sequels, recommended, 15, Random(1))

        assertEquals(15, result.size)
        assertTrue(result.take(Taste.SEQUELS_SHOWN).all { it.name.startsWith("Secuela") })
        assertEquals(result.map { normalizeTitle(it.name) }.distinct().size, result.size)
        assertTrue(result.count { it.name == "Secuela 3" } <= 1)
    }

    @Test
    fun `la afinidad premia el solape de generos y la nota`() {
        val own = setOf("accion", "drama")
        val igual = Taste.similarity(own, setOf("accion", "drama"), 4f)
        val parcial = Taste.similarity(own, setOf("accion", "comedia"), 4f)
        val muchosGeneros = Taste.similarity(own, setOf("accion", "drama", "a", "b", "c", "d"), 4f)
        assertTrue(igual > parcial)
        assertTrue(igual > muchosGeneros)
        assertTrue(Taste.similarity(own, setOf("accion", "comedia"), 5f) > Taste.similarity(own, setOf("accion", "comedia"), 3f))
        assertEquals(0.0, Taste.similarity(own, setOf("comedia"), 5f), 0.0)
    }

    @Test
    fun `las series excluidas no se recomiendan`() {
        val items = listOf(summary("Naruto", 4.0), summary("Bleach", 4.0))
        assertEquals(listOf("Bleach"), Taste.withoutExcluded(items, setOf(normalizeTitle("Naruto"))).map { it.name })
        assertEquals(items, Taste.withoutExcluded(items, emptySet()))
    }

    @Test
    fun `la misma serie en dos fuentes con el titulo distinto sale una vez`() {
        // seriesIdentity (título normalizado) las ve distintas; SeriesDedupe las une por el slug sin sufijos.
        val a = summary("Dr Stone", 4.0)
        val b = SeriesSummary(2, "Doctor Piedra", "dr-stone-latino", 1, "animeflv", 4.0)
        val result = Taste.distinctSeries(listOf(a, b, summary("Otra", 4.0)))
        assertEquals(listOf("Dr Stone", "Otra"), result.map { it.name })
    }

    @Test
    fun `sin secuelas salen solo los recomendados`() {
        val recommended = (1..5).map { summary("Otra $it", 4.0) }
        assertEquals(recommended, Taste.withSequels(emptyList(), recommended, 15, Random(1)))
    }
}

// ===== UserStatsTest.kt =====
class UserStatsTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    private fun record(eid: Int, name: String, source: String, ageDays: Int) =
        Record(eid, name, eid.toDouble(), 1, name.lowercase(), now - ageDays * day, source)

    @Test
    fun `cuenta capitulos, series distintas y ventanas de tiempo`() {
        val history = listOf(
            record(1, "Frieren", "animeav1", 0),
            record(2, "Frieren", "animeav1", 3),
            record(3, "Bleach", "jkanime", 20),
            record(4, "One Piece", "animeav1", 60)
        )

        val stats = UserStats.compute(history, favorites = 5, inProgress = 2, now = now)

        assertEquals(4, stats.chapters)
        assertEquals(3, stats.series)
        assertEquals(2, stats.lastWeek)
        assertEquals(3, stats.lastMonth)
        assertEquals(5, stats.favorites)
        assertEquals(2, stats.inProgress)
        assertEquals(listOf("animeav1" to 3, "jkanime" to 1), stats.bySource)
        assertEquals(now - 60 * day, stats.firstWatchedAt)
    }

    @Test
    fun `una serie con el mismo titulo en dos fuentes cuenta como una`() {
        val history = listOf(record(1, "Frieren", "animeav1", 1), record(2, "frieren", "jkanime", 1))
        assertEquals(1, UserStats.compute(history, 0, 0, now).series)
    }

    @Test
    fun `sin historial todo es cero y no hay primer capitulo`() {
        val stats = UserStats.compute(emptyList(), 0, 0, now)
        assertEquals(0, stats.chapters)
        assertEquals(0, stats.estimatedHours)
        assertNull(stats.firstWatchedAt)
    }

    @Test
    fun `las horas son aproximadas a 24 minutos por capitulo`() {
        val history = (1..5).map { record(it, "Serie", "animeav1", 1) }
        assertEquals(2, UserStats.compute(history, 0, 0, now).estimatedHours)
    }

    private val utc = java.util.TimeZone.getTimeZone("UTC")

    @Test
    fun `las series mas vistas salen por numero de capitulos y las unidas por titulo cuentan juntas`() {
        val history = listOf(
            record(1, "Frieren", "animeav1", 1), record(2, "frieren", "jkanime", 2), record(3, "Frieren", "animeav1", 3),
            record(4, "Bleach", "animeav1", 1), record(5, "Bleach", "animeav1", 2)
        )
        assertEquals(listOf("Frieren" to 3, "Bleach" to 2), UserStats.compute(history, 0, 0, now, utc).topSeries)
    }

    @Test
    fun `la racha cuenta dias seguidos hasta hoy o ayer y la mejor racha la mayor`() {
        // Hoy, ayer y anteayer (3 seguidos), un hueco y otros 4 seguidos.
        val ages = listOf(0, 1, 2, 10, 11, 12, 13)
        val history = ages.mapIndexed { i, age -> record(i + 1, "S$i", "animeav1", age) }
        val stats = UserStats.compute(history, 0, 0, now, utc)
        assertEquals(3, stats.currentStreak)
        assertEquals(4, stats.bestStreak)
        // Sin nada hoy, la racha llega hasta ayer.
        assertEquals(2, UserStats.compute(history.filter { it.date <= now - day }, 0, 0, now, utc).currentStreak)
        assertEquals(0, UserStats.compute(emptyList(), 0, 0, now, utc).currentStreak)
    }

    @Test
    fun `capitulos por mes cubre seis meses y deja fuera lo mas antiguo`() {
        // Capítulos de hoy y ayer (el día 100 cae en el mes actual) y uno de hace 400 días, fuera de la ventana.
        val history = listOf(record(1, "A", "animeav1", 0), record(2, "B", "animeav1", 1), record(3, "C", "animeav1", 400))
        val months = UserStats.compute(history, 0, 0, now, utc).byMonth
        assertEquals(UserStats.MONTHS, months.size)
        assertEquals(2, months.sumOf { it.second })
    }
}

// ===== WatchStateTest.kt =====
class WatchStateTest {

    private fun episode(number: Int) = Chapter(eid = 100 + number, number = number.toDouble(), aid = 1, slug = "serie", name = "Serie", source = "jkanime")
    private val episodes = (1..4).map(::episode)

    // El progreso se da por eid del capítulo de la prueba; la app lo busca por su ficha o, si solo hay lo antiguo, por su eid.
    private fun compute(seen: Set<Int> = emptySet(), progress: Map<Int, KaizenProgress.Progress> = emptyMap(), list: List<Chapter> = episodes) =
        WatchState.compute(list, seen, { chapter -> progress[chapter.eid]?.takeIf { it.isInProgress } }, isFavorite = false)

    @Test
    fun `sin episodios no hay nada que ver`() {
        val state = compute(list = emptyList())
        assertNull(state.target)
        assertEquals("Sin episodios", state.playLabel)
        assertEquals("Sin episodios", state.actionLabel)
        assertFalse(state.allSeen)
    }

    @Test
    fun `sin nada visto se empieza por el primero`() {
        val state = compute()
        assertEquals(episodes[0], state.target)
        assertEquals("Ver Episodio 1", state.playLabel)
        assertEquals("Ver Ep. 1", state.actionLabel)
        assertEquals("✓  Marcar todos (0/4)", state.seenLabel)
    }

    @Test
    fun `con capitulos vistos se sigue por el siguiente al ultimo visto`() {
        val state = compute(seen = setOf(101, 102))
        assertEquals(episodes[2], state.target)
        assertEquals("Siguiente: Episodio 3", state.playLabel)
        assertEquals("Ver Ep. 3", state.actionLabel)
    }

    @Test
    fun `un hueco entre los vistos no cambia el ultimo visto`() {
        val state = compute(seen = setOf(101, 103))
        assertEquals(episodes[3], state.target)
    }

    @Test
    fun `el capitulo empezado gana y entre varios el de numero mas alto`() {
        val progress = mapOf(
            101 to KaizenProgress.Progress(60_000, 1_400_000),
            103 to KaizenProgress.Progress(125_000, 1_400_000)
        )
        val state = compute(seen = setOf(101, 102), progress = progress)
        assertEquals(episodes[2], state.target)
        assertEquals("Seguir Episodio 3 · 2:05", state.playLabel)
        assertEquals("Seguir Ep. 3", state.actionLabel)
    }

    @Test
    fun `un capitulo dejado al segundo es el objetivo pero no dice seguir porque empieza de cero`() {
        val state = compute(progress = mapOf(102 to KaizenProgress.Progress(1_500, 1_400_000)))
        assertEquals(episodes[1], state.target)
        assertEquals("Ver Episodio 2", state.playLabel)
        assertEquals("Ver Ep. 2", state.actionLabel)
    }

    @Test
    fun `con todos vistos se vuelve a ver desde el primero`() {
        val state = compute(seen = episodes.map { it.eid }.toSet())
        assertEquals(episodes[0], state.target)
        assertEquals("Ver Episodio 1", state.playLabel)
        assertEquals("Ver Ep. 1", state.actionLabel)
        assertTrue(state.allSeen)
        assertEquals("✓  Desmarcar todos (4/4)", state.seenLabel)
    }

    @Test
    fun `el ultimo capitulo visto no tiene siguiente y se vuelve al primero`() {
        val state = compute(seen = setOf(104))
        assertEquals(episodes[0], state.target)
        assertEquals("Ver Episodio 1", state.playLabel)
        assertFalse(state.allSeen)
    }
}
