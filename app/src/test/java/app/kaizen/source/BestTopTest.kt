package app.kaizen.source

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Mejores de siempre» desde AniList: elección de la serie, unicidad, orden y lectura del ranking, sin red ni base de datos. */
class BestTopTest {
    private fun top(vararg ids: Long) = ids.map { AnilistTop(it, "Anime $it", 80, 2000) }
    private val enabled = listOf("animeflv", "jkanime")

    // Fichas «fuente:slug» → serie: a1, a2 → 1; b1 → 2; c1 → 3; j1 → 9
    private val series = mapOf(("animeflv" to "a1") to 1L, ("animeflv" to "a2") to 1L, ("animeflv" to "b1") to 2L, ("animeflv" to "c1") to 3L, ("jkanime" to "j1") to 9L)
    private fun resolve(top: List<AnilistTop>, links: Map<Long, List<Pair<String, String>>>, enabled: List<String> = this.enabled) = BestTop.resolve(top, links, { series[it] ?: 0L }, enabled)

    @Test
    fun `cada anime sale una vez y en el orden del ranking`() {
        val picks = resolve(top(5, 3, 8), mapOf(3L to listOf("animeflv" to "c1"), 5L to listOf("animeflv" to "a1"), 8L to listOf("animeflv" to "b1")))
        assertEquals(listOf(5L, 3L, 8L), picks.map { it.anime.id })
    }

    @Test
    fun `con varias series gana la de mas fichas enlazadas y en empate la de id mas bajo`() {
        // Serie 1 tiene dos fichas enlazadas, la 2 una: gana la 1 aunque la 2 aparezca antes en la lista.
        val moreFichas = resolve(top(7), mapOf(7L to listOf("animeflv" to "b1", "animeflv" to "a1", "animeflv" to "a2")))
        assertEquals(listOf(1L), moreFichas.map { it.seriesId })
        // Empate (una ficha cada una): la de id más bajo, siempre la misma.
        val tie = resolve(top(7), mapOf(7L to listOf("animeflv" to "c1", "animeflv" to "b1")))
        assertEquals(listOf(2L), tie.map { it.seriesId })
        assertEquals(tie.map { it.seriesId }, resolve(top(7), mapOf(7L to listOf("animeflv" to "b1", "animeflv" to "c1"))).map { it.seriesId })
    }

    @Test
    fun `dos animes que resuelven a la misma serie salen una vez en el puesto del mejor`() {
        val picks = resolve(top(1, 2), mapOf(1L to listOf("animeflv" to "a1"), 2L to listOf("animeflv" to "a2")))
        assertEquals(listOf(1L), picks.map { it.anime.id })
    }

    @Test
    fun `un anime cuya serie ya la usa uno anterior pasa a otra serie suya si la tiene`() {
        val picks = resolve(top(1, 2), mapOf(1L to listOf("animeflv" to "a1"), 2L to listOf("animeflv" to "a2", "animeflv" to "b1")))
        assertEquals(listOf(1L to 1L, 2L to 2L), picks.map { it.anime.id to it.seriesId })
    }

    @Test
    fun `sin ficha utilizable no sale y no esconde a los demas`() {
        // 4 no tiene enlaces; 5 solo tiene una ficha sin serie (0); 6 solo de una fuente desactivada.
        val picks = resolve(top(4, 5, 6, 7), mapOf(5L to listOf("animeflv" to "sin-serie"), 6L to listOf("otra" to "x"), 7L to listOf("animeflv" to "c1")))
        assertEquals(listOf(7L), picks.map { it.anime.id })
    }

    @Test
    fun `se abre la ficha de la primera fuente activa, la base antes que latino`() {
        val links = mapOf(1L to listOf("jkanime" to "j1"))
        assertEquals("j1", resolve(top(1), links).single().slug)
        val both = mapOf(9L to listOf("jkanime" to "x", "animeflv" to "x-latino", "animeflv" to "x"))
        val ledger = BestTop.resolve(top(9), both, { 5L }, enabled).single()
        assertEquals("animeflv" to "x", ledger.source to ledger.slug)
    }

    @Test
    fun `el ranking se lee por paginas de 50 y se corta en lo pedido`() {
        val pages = ArrayList<Int>()
        val client = AnilistClient { body ->
            val page = Regex(""""page":(\d+)""").find(body)!!.groupValues[1].toInt()
            pages += page
            val first = (page - 1) * 50
            val media = (first until first + 50).joinToString(",") { """{"id":${it + 1},"title":{"romaji":"T${it + 1}"},"averageScore":90,"seasonYear":2010}""" }
            """{"data":{"Page":{"media":[$media]}}}"""
        }
        val result = runBlocking { client.top(120, 20_000) }.also { assertTrue(it.complete) }.items
        assertEquals(120, result.size)
        assertEquals(listOf(1, 2, 3), pages)
        assertEquals(1L, result.first().id)
        assertEquals("T120", result.last().title)
    }

    @Test
    fun `una respuesta sin datos deja la lista corta sin reintentar en bucle`() {
        var calls = 0
        val client = AnilistClient { calls++; "{}" }
        val result = runBlocking { client.top(300, 20_000) }
        assertTrue(result.items.isEmpty())
        assertFalse(result.complete)
        assertEquals(1, calls)
    }

    @Test
    fun `la lista se construye con la nota de AniList entre 0 y 10 y solo con enlaces aplicados`() {
        val api = object : AnilistApi {
            override suspend fun search(titles: List<String>) = emptyList<List<AnilistMatcher.Candidate>>()
            override suspend fun fichas(ids: List<Long>) = emptyList<AnilistFichaRaw>()
            override suspend fun top(count: Int, minPopularity: Int, airing: Boolean) = AnilistTops(listOf(AnilistTop(1, "Uno", 87, 1999), AnilistTop(2, "Dos", 80, null)), complete = false)
        }
        val rows = listOf(
            AnimeFuente("animeflv", "a1", 1, AnimeFuente.APLICADO, AnimeFuente.AUTO, AnimeFuente.NIVEL_MAL, 1.0, 1L),
            AnimeFuente("animeflv", "b1", 2, AnimeFuente.PENDIENTE, AnimeFuente.AUTO, "probable", 0.5, 1L)
        )
        val dao = object : AnimeFuenteDao {
            override fun all() = rows
            override fun get(fuente: String, slug: String) = null
            override fun appliedIds() = emptyList<Long>()
            override fun countByState() = emptyList<AnimeFuenteDao.StateCount>()
            override fun expireAuto() = Unit
            override fun deleteAuto(fuente: String, slug: String) = Unit
            override fun insertIfAbsent(rows: List<AnimeFuente>) = Unit
            override fun put(rows: List<AnimeFuente>) = Unit
        }
        val cards = runBlocking { BestTop.build(BestTop.Kind.ALWAYS, api, dao, listOf("animeflv"), { series[it] ?: 0L }) { _, _ -> 77 } }
        // Solo el anime 1 (enlace «mal» aplicado, fiable); el 2 solo tiene un enlace pendiente y no sale.
        assertEquals(listOf("Uno"), cards.cards.map { it.name })
        assertEquals(8.7, cards.cards.single().score!!, 1e-9)
        assertEquals(77, cards.cards.single().aid)
        assertEquals(1999, cards.cards.single().year)
        // Dos animes de 300 pedidos: el ranking vino cortado y no vale para guardarse una semana.
        assertFalse(cards.complete)
    }
}
