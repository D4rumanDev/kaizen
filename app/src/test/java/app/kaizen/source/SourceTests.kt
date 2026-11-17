package app.kaizen.source

import android.content.SharedPreferences
import androidx.paging.PagingSource
import app.kaizen.commons.FallbackDns
import app.kaizen.commons.HostGate
import app.kaizen.commons.JsExtractor
import app.kaizen.commons.decodeEmbedIframeUrl
import app.kaizen.database.CatalogDB
import app.kaizen.pojos.av1.AiringSeries
import app.kaizen.pojos.av1.ChapterEntry
import app.kaizen.pojos.av1.Genre
import app.kaizen.pojos.av1.RELATION_PREQUEL
import app.kaizen.pojos.av1.RELATION_SEQUEL
import app.kaizen.pojos.av1.RecentEpisode
import app.kaizen.pojos.av1.Relation
import app.kaizen.pojos.av1.Series
import app.kaizen.pojos.av1.SeriesSummary
import app.kaizen.source.AnilistMatcher.Candidate
import app.kaizen.source.AnilistMatcher.Level
import app.kaizen.source.WebFichaSchema.Kind
import app.kaizen.source.spec.HtmlNode
import app.kaizen.source.spec.JsonNode
import app.kaizen.source.spec.SourceSpec
import app.kaizen.source.spec.SpecException
import com.google.gson.Gson
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.Calendar
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// ===== AnilistCardsTest.kt =====
class AnilistCardsTest {
    private fun row(id: Long?, estado: String = AnimeFuente.APLICADO) = AnimeFuente("jkanime", "x", id, estado, AnimeFuente.AUTO, "seguro", 1.0, 0L)
    private fun ficha(json: String, consulta: Int = AnilistClient.FICHA_VERSION, es: AnimeotecaText? = null) =
        AnimeFicha(5, null, consulta, 0L, AnimeFicha.gzip(json), es?.let { AnimeFicha.gzip(it.toJson(0L)) })

    @Test
    fun `una serie enlazada con ficha da su nota y su portada`() {
        val card = AnilistCards.card(row(5), ficha("""{"id":5,"averageScore":78,"coverImage":{"large":"https://x/l.jpg"}}"""))!!
        assertEquals(78, card.score)
        assertEquals("https://x/l.jpg", card.cover)
    }

    @Test
    fun `sin enlace aplicado, sin ficha o con una consulta antigua no hay tarjeta`() {
        val json = """{"id":5,"averageScore":78}"""
        assertNull(AnilistCards.card(null, ficha(json)))
        assertNull(AnilistCards.card(row(5, AnimeFuente.PENDIENTE), ficha(json)))
        assertNull(AnilistCards.card(row(null), ficha(json)))
        assertNull(AnilistCards.card(row(5), null))
        assertNull(AnilistCards.card(row(5), ficha(json, consulta = AnilistClient.FICHA_VERSION + 1)))
    }

    @Test
    fun `el resumen del panel sale de la ficha con ano, formato, episodios, estado, tres generos y sinopsis recortada en una linea`() {
        val long = "Un texto largo. ".repeat(30)
        val json = """{"id":5,"seasonYear":2020,"format":"MOVIE","episodes":1,"status":"FINISHED","genres":["Action","Drama","Fantasy","Comedy"],"description":"English text"}"""
        val web = FichaData(org.json.JSONObject("""{"description":{"es":"Primera.<br><br>Segunda $long","en":null}}"""), "jkanime", "x")
        val info = AnilistCards.card(row(5), ficha(json), web)!!.info!!
        assertEquals(2020, info.year)
        assertEquals("MOVIE", info.format)
        assertEquals(1, info.episodes)
        assertEquals("FINISHED", info.status)
        // Sin la capa en español, los géneros de AniList se traducen (la sinopsis queda como la da AniList).
        assertEquals(listOf("Acción", "Drama", "Fantasía"), info.genres)
        val synopsis = info.synopsis!!
        assertTrue(synopsis.startsWith("Primera. Segunda"))
        assertFalse(synopsis.contains('\n'))
        assertTrue(synopsis.length <= CardInfo.SYNOPSIS_MAX + 1)
        assertTrue(synopsis.endsWith("…"))
    }

    @Test
    fun `con la capa en espanol el panel usa su sinopsis y sus generos y no los de AniList`() {
        val json = """{"id":5,"genres":["Action"],"description":"English text"}"""
        val es = AnimeotecaText("Una sinopsis en español.", listOf("Comedia", "Romance"))
        val info = AnilistCards.card(row(5), ficha(json, es = es))!!.info!!
        assertEquals("Una sinopsis en español.", info.synopsis)
        assertEquals(listOf("Comedia", "Romance"), info.genres)
        // Una capa en español sin sinopsis (solo géneros) no deja la inglesa de AniList: sin sinopsis.
        val onlyGenres = AnilistCards.card(row(5), ficha(json, es = AnimeotecaText(null, listOf("Drama"))))!!.info!!
        assertNull(onlyGenres.synopsis)
        assertEquals(listOf("Drama"), onlyGenres.genres)
        // Si la propia web da la sinopsis y los géneros en español, valen aunque Animeoteca no tenga nada.
        val web = FichaData(org.json.JSONObject("""{"genres":["Terror"],"description":{"es":"Sinopsis de la web.","en":"English"}}"""), "jkanime", "x")
        val fromWeb = AnilistCards.card(row(5), ficha(json), web)!!.info!!
        assertEquals("Sinopsis de la web.", fromWeb.synopsis)
        assertEquals(listOf("Terror"), fromWeb.genres)
        // Una web que solo la da en inglés tampoco cuenta.
        assertNull(AnilistCards.card(row(5), ficha(json), FichaData(org.json.JSONObject("""{"description":{"es":null,"en":"English"}}"""), "jkanime", "x"))!!.info!!.synopsis)
    }

    @Test
    fun `una ficha sin sinopsis ni generos da un resumen vacio y no rompe`() {
        val info = AnilistCards.card(row(5), ficha("""{"id":5}"""))!!.info!!
        assertNull(info.synopsis)
        assertTrue(info.genres.isEmpty())
        assertNull(info.year)
    }

    @Test
    fun `enlazada pero sin nota todavia es una tarjeta sin nota`() {
        val card = AnilistCards.card(row(5), ficha("""{"id":5,"averageScore":null}"""))
        assertNotNull(card)
        assertNull(card!!.score)
    }
}

// ===== AnilistClientTest.kt =====
class AnilistClientTest {
    @Test
    fun `una busqueda con alias devuelve una lista por titulo y respeta los fallos de una sola`() = runTest {
        var sent = ""
        val client = AnilistClient { body ->
            sent = body
            """{"data":{"s0":{"media":[{"id":1,"format":"TV","title":{"romaji":"Nekopara","english":null,"native":"ネコぱら"},"synonyms":["Nekopara TV"]}]},"s1":null}}"""
        }

        val found = client.search(listOf("nekopara", "titulo roto"))

        assertEquals(2, found.size)
        assertEquals(1L, found[0].single().id)
        assertEquals(listOf("Nekopara", "ネコぱら", "Nekopara TV"), found[0].single().names)
        assertTrue(found[1].isEmpty())
        assertTrue(sent.contains("s0: Page") && sent.contains("s1: Page") && sent.contains("\"t1\":\"titulo roto\""))
    }

    @Test
    fun `una respuesta vacia o sin datos no rompe`() = runTest {
        assertEquals(listOf(emptyList<AnilistMatcher.Candidate>()), AnilistClient { "{}" }.search(listOf("x")))
        assertEquals(emptyList<AnilistFichaRaw>(), AnilistClient { """{"errors":[{"message":"boom"}]}""" }.fichas(listOf(1L)))
    }

    @Test
    fun `la ficha conserva el JSON de AniList tal cual y saca lo derivado`() = runTest {
        val media = """{"id":206949,"idMal":63382,"title":{"romaji":"Kyouran Reijou Nia Liston","english":"Nia Liston: The Merciless Maiden","native":"凶乱令嬢ニア・リストン"},"format":"TV","seasonYear":2026,"averageScore":71,"status":"RELEASING"}"""
        val client = AnilistClient { """{"data":{"Page":{"media":[$media]}}}""" }

        val ficha = client.fichas(listOf(206949L)).single()

        assertEquals(206949L, ficha.id)
        assertEquals(63382L, ficha.malId)
        assertEquals(2026, ficha.year)
        assertEquals(71, ficha.score)
        assertEquals("RELEASING", ficha.status)
        assertTrue(ficha.json.contains("\"idMal\":63382"))
    }

    @Test
    fun `si AniList rechaza todo el lote se pide de uno en uno para saber cual falla`() = runTest {
        val sent = mutableListOf<String>()
        val client = AnilistClient { body ->
            sent += body
            when {
                !body.contains("\"t1\"") && body.contains("buena") -> """{"data":{"s0":{"media":[{"id":5,"format":"TV","title":{"romaji":"Buena"},"synonyms":[]}]}}}"""
                else -> "{}"
            }
        }

        val found = client.search(listOf("buena", "rota"))

        assertEquals(3, sent.size)
        assertEquals(5L, found[0].single().id)
        assertTrue(found[1].isEmpty())
    }
}

// ===== AnilistEvidenciaTest.kt =====
/** Un enlace automático no se aplica solo por el nombre: el año, el formato, el idMal y los episodios de la ficha tienen que cuadrar con los del anime. */
class AnilistEvidenciaTest {
    private fun web(year: Int? = null, format: String? = null, mal: Long? = null, episodes: Int? = null, status: String? = null): FichaData {
        val media = WebFichaSchema.empty()
        media.getJSONObject("title").put("romaji", "X")
        year?.let { media.getJSONObject("startDate").put("year", it) }
        format?.let { media.put("format", it) }; mal?.let { media.put("idMal", it) }; episodes?.let { media.put("episodes", it) }; status?.let { media.put("status", it) }
        return FichaData(media, "jkanime", "x")
    }

    private val anime = Candidate(1, "TV", "Nekopara", null, null, emptyList(), 2014, episodes = 12, idMal = 77)

    @Test
    fun `un probable sin ninguna prueba mas que el nombre se pregunta y con una prueba se aplica`() {
        assertEquals(Level.DUDOSO, AnilistMatcher.levelWithEvidence(Level.PROBABLE, AnilistMatcher.evidenceOf(null, null, anime)))
        assertEquals(Level.PROBABLE, AnilistMatcher.levelWithEvidence(Level.PROBABLE, AnilistMatcher.evidenceOf(2014, null, anime)))
        // El año de la web vale si la serie no lo da, y con un año de margen.
        assertEquals(Level.PROBABLE, AnilistMatcher.levelWithEvidence(Level.PROBABLE, AnilistMatcher.evidenceOf(null, web(year = 2015), anime)))
    }

    @Test
    fun `un seguro sin pruebas en contra se mantiene y con una en contra se pregunta`() {
        assertEquals(Level.SEGURO, AnilistMatcher.levelWithEvidence(Level.SEGURO, AnilistMatcher.evidenceOf(null, null, anime)))
        // Año lejano, otra clase de formato, otro idMal o otros episodios (en una serie terminada): lo contradicen.
        assertEquals(Level.DUDOSO, AnilistMatcher.levelWithEvidence(Level.SEGURO, AnilistMatcher.evidenceOf(2001, null, anime)))
        assertEquals(Level.DUDOSO, AnilistMatcher.levelWithEvidence(Level.SEGURO, AnilistMatcher.evidenceOf(null, web(format = "MOVIE"), anime)))
        assertEquals(Level.DUDOSO, AnilistMatcher.levelWithEvidence(Level.SEGURO, AnilistMatcher.evidenceOf(null, web(mal = 5), anime)))
        assertEquals(Level.DUDOSO, AnilistMatcher.levelWithEvidence(Level.SEGURO, AnilistMatcher.evidenceOf(null, web(episodes = 1, status = "FINISHED"), anime)))
        // En emisión los episodios no juzgan (todavía no están todos).
        assertEquals(Level.SEGURO, AnilistMatcher.levelWithEvidence(Level.SEGURO, AnilistMatcher.evidenceOf(null, web(episodes = 3, status = "RELEASING"), anime)))
    }

    @Test
    fun `el formato solo resta y el idMal o los episodios iguales suman`() {
        val same = AnilistMatcher.evidenceOf(null, web(format = "TV"), anime)
        assertEquals(0, same.supports); assertEquals(0, same.contradicts)
        val strong = AnilistMatcher.evidenceOf(null, web(mal = 77, episodes = 12, status = "FINISHED"), anime)
        assertEquals(2, strong.supports); assertEquals(0, strong.contradicts)
        assertEquals(Level.DUDOSO, AnilistMatcher.levelWithEvidence(Level.DUDOSO, strong))
        assertEquals(Level.NINGUNO, AnilistMatcher.levelWithEvidence(Level.NINGUNO, strong))
    }

    @Test
    fun `decide usa los datos de la ficha de la web`() {
        val near = Candidate(1, "TV", "Nekopara", null, null, emptyList(), 2014)
        // Con otra ficha de la serie que apunta a otro candidato el nombre solo da «probable»: sin pruebas se pregunta, con el año de la web se aplica.
        val titles = listOf("nekopara", "nekopara ova")
        val results = listOf(listOf(near), listOf(Candidate(7, "OVA", "Nekopara OVA", null, null)))
        assertEquals(Level.DUDOSO, AnilistMatcher.decide(titles, results).level)
        assertEquals(Level.PROBABLE, AnilistMatcher.decide(titles, results, web = web(year = 2014)).level)
        assertEquals(Level.DUDOSO, AnilistMatcher.decide(titles, results, web = web(year = 2014, format = "MOVIE")).level)
    }

    @Test
    fun `un solo plegado de vocales y un solo normalizado base`() {
        assertEquals("o", foldVowels("oou")) // el orden de las sustituciones es uno solo
        assertEquals("mahoshojo", foldVowels("mahoushoujo"))
        assertEquals(foldTitle("Hyōken"), foldTitle("Hyouken"))
        // El normalizado del emparejado hereda los homoglifos del catálogo («PSYЯEN» = «Psyren»).
        assertEquals(AnilistMatcher.normalize("Psyren"), AnilistMatcher.normalize("PSYЯEN"))
        assertEquals("steins gate", AnilistMatcher.normalize("Steins;Gate"))
    }

    @Test
    fun `una sola funcion de anios con y sin margen`() {
        assertTrue(LinkEvidence.yearsClose(2017, 2018))
        assertFalse(LinkEvidence.yearsClose(2017, 2018, margin = 0))
        assertFalse(SeriesMatch.yearsFit(2017, 2018))
        assertTrue(SeriesMatch.yearsFit(2017, null))
    }

    @Test
    fun `el parecido de palabras es el mismo para el emparejado y la cohesion de las series`() {
        // La misma palabra partida de otra forma, con las palabras en su orden o no.
        assertTrue(AnilistMatcher.wordSimilarity(listOf("hackgu", "trilogy"), listOf("hack", "gu", "trilogy")) >= 0.93)
        assertTrue(AnilistMatcher.wordSimilarity(listOf("kenka", "shitemo"), listOf("kenka", "shite", "mo")) >= 0.93)
        assertEquals(0.0, AnilistMatcher.wordSimilarity(emptyList(), listOf("x")), 0.0)
        assertTrue(AnilistMatcher.wordSimilarity(listOf("black", "clover"), listOf("naruto")) < 0.5)
    }
}

// ===== AnilistFichaDataTest.kt =====
class AnilistFichaDataTest {
    private fun row(fuente: String, slug: String, id: Long?, estado: String = AnimeFuente.APLICADO) =
        AnimeFuente(fuente, slug, id, estado, AnimeFuente.AUTO, "seguro", 1.0, 0L)

    private fun resolve(vararg rows: AnimeFuente, fichas: List<Pair<String, String>>) =
        AnilistFicha.resolve(fichas) { f, s -> rows.firstOrNull { it.fuente == f && it.slug == s } }

    @Test
    fun `sin filas no hay anime`() {
        assertEquals(AnilistResolution.None, resolve(fichas = listOf("a" to "x")))
    }

    @Test
    fun `una ficha aplicada da su anime y las demas fichas de la serie coinciden`() {
        val r = resolve(row("a", "x", 10), row("b", "x", 10), fichas = listOf("a" to "x", "b" to "x", "c" to "x"))
        assertEquals(AnilistResolution.Anime(10), r)
    }

    @Test
    fun `fichas con animes distintos son un conflicto`() {
        val r = resolve(row("a", "x", 10), row("b", "x", 11), fichas = listOf("a" to "x", "b" to "x"))
        assertEquals(AnilistResolution.Conflict(setOf(10L, 11L)), r)
    }

    @Test
    fun `por revisar y sin resultado no se usan`() {
        val r = resolve(row("a", "x", 10, AnimeFuente.PENDIENTE), row("b", "x", null, AnimeFuente.SIN_RESULTADO), fichas = listOf("a" to "x", "b" to "x"))
        assertEquals(AnilistResolution.None, r)
    }

    @Test
    fun `lee la ficha completa`() {
        val json = """{"id":5,"idMal":9,"title":{"romaji":"R","english":"E","native":"N"},"averageScore":78,"bannerImage":"https://x/b.jpg","format":"TV",
            "status":"FINISHED","seasonYear":2020,"episodes":12,"duration":24,"genres":["Action","Drama"],"description":"Texto",
            "studios":{"edges":[{"isMain":false,"node":{"name":"Otro"}},{"isMain":true,"node":{"name":"Principal"}}]},
            "characters":{"edges":[{"role":"MAIN","node":{"name":{"full":"Nia","native":"ニア"}},"voiceActors":[{"name":{"full":"Seiyuu"}}]},
            {"role":"SUPPORTING","node":{"name":{"native":"X"}},"voiceActors":[]}]},"nextAiringEpisode":{"episode":4,"airingAt":1700000000}}"""
        val d = FichaData.ofAnilistJson(json)!!
        assertEquals(5L, d.anilistId); assertEquals(9L, d.idMal); assertEquals(78, d.puntuacion); assertEquals("https://x/b.jpg", d.banner)
        assertEquals(2020, d.anio); assertEquals("Principal", d.estudio); assertEquals(listOf("Action", "Drama"), d.generos)
        assertEquals(4, d.proximoEpisodio); assertEquals(1700000000L, d.proximaEmision)
        assertEquals(listOf("Nia", "X"), d.personajes.map { it.nombre }); assertEquals("Seiyuu", d.personajes[0].voz); assertNull(d.personajes[1].voz)
    }

    @Test
    fun `tolera campos ausentes, nulos y de otro tipo`() {
        val d = FichaData.ofAnilistJson("""{"id":7,"averageScore":null,"title":null,"genres":"no","studios":{"edges":null},"episodes":"x"}""")!!
        assertEquals(7L, d.anilistId); assertNull(d.puntuacion); assertNull(d.episodios); assertTrue(d.generos.isEmpty()); assertNull(d.estudio); assertTrue(d.personajes.isEmpty())
    }

    @Test
    fun `la portada es la mas grande y solo https`() {
        fun cover(json: String) = FichaData.ofAnilistJson(json)!!.portada
        assertEquals("https://x/xl.jpg", cover("""{"id":1,"coverImage":{"extraLarge":"https://x/xl.jpg","large":"https://x/l.jpg"}}"""))
        assertEquals("https://x/l.jpg", cover("""{"id":1,"coverImage":{"large":"https://x/l.jpg"}}"""))
        assertNull(cover("""{"id":1,"coverImage":{"large":"http://x/l.jpg"}}"""))
        assertNull(cover("""{"id":1}"""))
    }

    @Test
    fun `un banner que no es https se descarta`() {
        assertNull(FichaData.ofAnilistJson("""{"id":1,"bannerImage":"http://x/b.jpg"}""")!!.banner)
    }

    @Test
    fun `una nota de cero no cuenta`() {
        assertNull(FichaData.ofAnilistJson("""{"id":1,"averageScore":0}""")!!.puntuacion)
    }

    @Test
    fun `sin id o con JSON invalido no hay datos`() {
        assertNull(FichaData.ofAnilistJson("""{"title":{}}"""))
        assertNull(FichaData.ofAnilist(AnimeFicha(1, null, 1, 0L, AnimeFicha.gzip("no es json {"))))
    }

    @Test
    fun `las relaciones dejan solo animes, con precuelas y secuelas primero`() {
        val json = """{"id":1,"relations":{"edges":[
            {"relationType":"ADAPTATION","node":{"id":9,"title":{"romaji":"Manga"},"format":"MANGA"}},
            {"relationType":"SIDE_STORY","node":{"id":4,"title":{"romaji":"Especial"},"format":"SPECIAL"}},
            {"relationType":"SEQUEL","node":{"id":3,"title":{"romaji":"Segunda"},"format":"TV"}},
            {"relationType":"PREQUEL","node":{"id":2,"title":{"romaji":"Primera"},"format":"TV"}}]}}"""
        val relations = FichaData.ofAnilistJson(json)!!.relaciones
        assertEquals(listOf(2L, 3L, 4L), relations.map { it.anilistId })
        assertEquals("Precuela", AnilistLabels.relation(relations.first().tipo))
    }
}

// ===== AnilistFormatoTest.kt =====
/** Una película, un OVA o un especial no se enlaza con una serie de TV (son entradas distintas en AniList): ni al emparejar ni en los enlaces que ya hay. */
class AnilistFormatoTest {
    private fun cand(id: Long, format: String?, romaji: String, english: String? = null) =
        AnilistMatcher.Candidate(id, format, romaji, english, null, emptyList())

    private fun clash(slug: String, format: String?, vararg names: String) = AnilistMatcher.formatClash(AnilistLinkCheck.slugTitle(slug), format, names.map(AnilistMatcher::normalize))

    @Test
    fun `una pelicula, un OVA o un especial frente a una serie de TV chocan`() {
        assertTrue(clash("black-clover-ova", "TV", "Black Clover"))
        assertTrue(clash("meitantei-precure-movie", "TV", "Meitantei Precure"))
        assertTrue(clash("amagami-ss-especiales", "TV", "Amagami SS"))
        assertTrue(clash("x-pelicula", "TV_SHORT", "X"))
    }

    @Test
    fun `no choca si el formato cuadra, si el slug no dice nada ni si es un ONA`() {
        assertFalse(clash("x-movie", "MOVIE", "X"))
        assertFalse(clash("x-ova", "OVA", "X"))
        assertFalse(clash("black-clover", "TV", "Black Clover"))
        assertFalse(clash("x-ona", "TV", "X"))
        assertFalse(clash("x-movie", null, "X"))
        // El propio anime lleva la palabra («Special A»): no es un especial.
        assertFalse(clash("special-a", "TV", "Special A"))
    }

    @Test
    fun `el emparejado no propone una serie de TV para una pelicula pero si su pelicula`() {
        val tv = cand(1, "TV", "Meitantei Precure")
        val movie = cand(2, "MOVIE", "Meitantei Precure Movie")
        assertEquals(AnilistMatcher.Level.NINGUNO, AnilistMatcher.decide(listOf("meitantei precure movie"), listOf(listOf(tv))).level)
        assertEquals(2L, AnilistMatcher.decide(listOf("meitantei precure movie"), listOf(listOf(tv, movie))).candidate?.id)
        // Sin marca de película en el slug, la serie sigue siendo la propuesta.
        assertEquals(1L, AnilistMatcher.decide(listOf("meitantei precure"), listOf(listOf(tv, movie))).candidate?.id)
    }

    private fun row(fuente: String, slug: String, id: Long, estado: String, origen: String = AnimeFuente.AUTO, nivel: String = "seguro") =
        AnimeFuente(fuente, slug, id, estado, origen, nivel, 1.0, 1L)

    @Test
    fun `los enlaces automaticos de una pelicula, un OVA o un especial con una serie se quitan y los del usuario no`() {
        val titles = mapOf(
            97940L to AnimeFichaDao.Titles(97940, "black clover", "black clover", "TV"),
            55L to AnimeFichaDao.Titles(55, "black clover movie", null, "MOVIE")
        )
        val rows = listOf(
            row("animeflv", "black-clover", 97940, AnimeFuente.APLICADO),
            row("animeflv", "black-clover-ova", 97940, AnimeFuente.APLICADO),
            row("jkanime", "black-clover-especiales", 97940, AnimeFuente.PENDIENTE, nivel = "dudoso"),
            row("animeav1", "black-clover-ova", 97940, AnimeFuente.APLICADO, AnimeFuente.USUARIO, "manual"),
            row("verani", "black-clover-movie", 55, AnimeFuente.APLICADO)
        )
        val bad = AnilistLinkCheck.inconsistent(rows, titles).map { it.fuente to it.slug }
        assertEquals(listOf("animeflv" to "black-clover-ova", "jkanime" to "black-clover-especiales"), bad)
    }

    @Test
    fun `la revision compara formato e idMal de la web con los de AniList`() {
        assertEquals(Match.SAME, AnilistCompare.format("TV", "TV"))
        assertEquals(Match.CLOSE, AnilistCompare.format("TV", "TV_SHORT"))
        assertEquals(Match.CLOSE, AnilistCompare.format("OVA", "SPECIAL"))
        assertEquals(Match.DIFFERENT, AnilistCompare.format("MOVIE", "TV"))
        assertEquals(Match.UNKNOWN, AnilistCompare.format(null, "TV"))
        assertEquals(Match.SAME, AnilistCompare.mal(5L, 5L))
        assertEquals(Match.DIFFERENT, AnilistCompare.mal(5L, 6L))
        assertEquals(Match.UNKNOWN, AnilistCompare.mal(null, 6L))
        // Un formato o un idMal distintos impiden darla por buena sin preguntar, aunque lo demás coincida.
        val same = listOf(Match.SAME, Match.SAME, Match.SAME)
        assertEquals(AnilistCompare.Auto.ACCEPT, AnilistCompare.auto(1.0, Match.SAME, Match.SAME, same))
        assertEquals(AnilistCompare.Auto.ASK, AnilistCompare.auto(1.0, Match.SAME, Match.SAME, same + Match.DIFFERENT))
    }

    @Test
    fun `el formato solo frena y que coincida no basta para darla por buena sin preguntar`() {
        // Título, año y episodios iguales y el formato igual (casi todo es TV): sigue preguntando, el formato no cuenta como prueba.
        assertEquals(AnilistCompare.Auto.ASK, AnilistCompare.auto(1.0, Match.SAME, Match.SAME, emptyList(), format = Match.SAME))
        // Un formato de otra clase frena aunque lo demás coincida.
        assertEquals(AnilistCompare.Auto.ASK, AnilistCompare.auto(1.0, Match.SAME, Match.SAME, listOf(Match.SAME, Match.SAME, Match.SAME), format = Match.DIFFERENT))
        // El idMal igual sí es una prueba fuerte: con año y episodios son tres.
        assertEquals(AnilistCompare.Auto.ACCEPT, AnilistCompare.auto(1.0, Match.SAME, Match.SAME, listOf(AnilistCompare.mal(5L, 5L)), format = Match.SAME))
        // El ONA es de la clase serie, como en el emparejado: frente a TV es parecido, no distinto.
        assertEquals(Match.CLOSE, AnilistCompare.format("ONA", "TV"))
    }

    @Test
    fun `una pelicula, un OVA o un especial se busca sin la palabra de formato y solo entre sus formatos`() {
        assertEquals("kaguya sama love is war" to listOf("SPECIAL", "OVA"), AnilistMatcher.searchPlan("kaguya sama love is war especiales"))
        assertEquals("black clover" to listOf("OVA", "ONA", "SPECIAL"), AnilistMatcher.searchPlan("black clover ova"))
        assertEquals("meitantei precure" to listOf("MOVIE"), AnilistMatcher.searchPlan("meitantei precure movie"))
        // Una serie, un ONA o un título que solo dice la palabra no se filtran.
        assertEquals(null, AnilistMatcher.searchPlan("black clover"))
        assertEquals(null, AnilistMatcher.searchPlan("x ona"))
        assertEquals(null, AnilistMatcher.searchPlan("ova"))
    }

    @Test
    fun `el cliente pide a AniList solo esos formatos y el texto sin la palabra de formato`() = kotlinx.coroutines.test.runTest {
        val bodies = mutableListOf<String>()
        val client = AnilistClient { body -> bodies += body; """{"data":{"s0":{"media":[]},"s1":{"media":[]}}}""" }

        client.search(listOf("one punch man 2 especiales", "one punch man 2"))

        // La primera petición: solo el primer título lleva filtro y su texto va sin «especiales».
        val first = bodies.first()
        assertTrue("format_in: [SPECIAL, OVA]" in first)
        assertEquals(1, Regex("format_in").findAll(first).count())
        assertTrue("\"one punch man 2\"" in first && "especiales" !in first)
        // Como no sale nada parecido, el título entero se repite sin filtro (una sola petición más).
        assertEquals(2, bodies.size)
        assertTrue("format_in" !in bodies[1] && "especiales" in bodies[1])
    }

    @Test
    fun `el nombre de una pelicula, un OVA o un especial no basta ni siendo igual sin una prueba mas`() {
        val ova = AnilistMatcher.Candidate(5, "OVA", "Kamichu!", null, null, emptyList(), 2005)
        assertEquals(AnilistMatcher.Level.DUDOSO, AnilistMatcher.decide(listOf("kamichu specials"), listOf(listOf(ova))).level)
        assertEquals(AnilistMatcher.Level.SEGURO, AnilistMatcher.decide(listOf("kamichu specials"), listOf(listOf(ova)), localYear = 2005).level)
        // Una serie con el nombre igual sí es segura sola, como siempre.
        assertEquals(AnilistMatcher.Level.SEGURO, AnilistMatcher.decide(listOf("kamichu"), listOf(listOf(AnilistMatcher.Candidate(6, "TV", "Kamichu!", null, null)))).level)
    }

    @Test
    fun `si con el filtro no sale nada parecido se repite el titulo entero sin filtro`() = kotlinx.coroutines.test.runTest {
        // «Special A» es una serie de TV: con el filtro de formato no está; la repetición sin filtro la encuentra.
        val bodies = mutableListOf<String>()
        val serie = """{"id":7,"format":"TV","title":{"romaji":"Special A","english":null,"native":null},"synonyms":[],"seasonYear":2008}"""
        val client = AnilistClient { body ->
            bodies += body
            if ("format_in" in body) """{"data":{"s0":{"media":[]}}}""" else """{"data":{"s0":{"media":[$serie]}}}"""
        }

        val found = client.search(listOf("special a"))

        assertEquals(2, bodies.size)
        assertEquals(listOf(7L), found.single().map { it.id })
    }

    @Test
    fun `un ONA frente a una serie de TV no sube el nivel sin pruebas`() {
        val tv = AnilistMatcher.Candidate(9, "TV", "Algo", null, null)
        // El nombre igual con «ona» en el slug: como antes (restaba), no pasa de dudoso sin una prueba.
        assertEquals(AnilistMatcher.Level.DUDOSO, AnilistMatcher.decide(listOf("algo ona"), listOf(listOf(tv))).level)
        assertEquals(AnilistMatcher.Level.SEGURO, AnilistMatcher.decide(listOf("algo"), listOf(listOf(tv))).level)
    }

    @Test
    fun `lo que se rehace por migracion son los probables sin pruebas y los sin resultado de formato, nunca lo del usuario ni los descartes`() {
        fun row(slug: String, estado: String, nivel: String, origen: String = AnimeFuente.AUTO, id: Long? = 1L) = AnimeFuente("jkanime", slug, id, estado, origen, nivel, 1.0, 1L)
        val titles = mapOf(1L to AnimeFichaDao.Titles(1, "x", "x", "TV"))
        val all = listOf(
            row("x", AnimeFuente.APLICADO, "probable"),
            row("y", AnimeFuente.APLICADO, "seguro"),
            row("x-ova", AnimeFuente.SIN_RESULTADO, "ninguno", id = null),
            row("z-movie", AnimeFuente.SIN_RESULTADO, "descartado", id = null),
            row("w-ova", AnimeFuente.SIN_RESULTADO, "mezcla", id = null),
            row("u-ova", AnimeFuente.SIN_RESULTADO, "manual", AnimeFuente.USUARIO, id = null),
            row("v", AnimeFuente.SIN_RESULTADO, "ninguno", id = null)
        )
        val both = AnilistLinkCheck.redo(all, titles, { null }, withEvidence = true, withFormat = true)
        assertEquals(listOf("x", "x-ova"), both.rows.map { it.slug })
        assertEquals(1 to 1, both.weak to both.format)
        val none = AnilistLinkCheck.redo(all, titles, { null }, withEvidence = false, withFormat = false)
        assertTrue(none.rows.isEmpty())
        // Una ficha que ya sobra por otra razón (choque de formato) no se cuenta dos veces.
        val clash = listOf(row("x-ova", AnimeFuente.APLICADO, "probable"))
        val r = AnilistLinkCheck.redo(clash, titles, { null }, withEvidence = true, withFormat = true)
        assertEquals(1, r.rows.size)
        assertEquals(0, r.weak)
    }

    @Test
    fun `un OVA o un especial frente a un ONA no pasa de dudoso sin pruebas`() {
        val ona = AnilistMatcher.Candidate(11, "ONA", "Algo", null, null, emptyList(), 2020)
        // Clase «corto» frente a «serie» (el ONA cuenta como serie): el nombre igual no basta.
        assertTrue(AnilistMatcher.decide(listOf("algo ova"), listOf(listOf(ona))).level != AnilistMatcher.Level.SEGURO)
        assertTrue(AnilistMatcher.decide(listOf("algo especiales"), listOf(listOf(ona))).level != AnilistMatcher.Level.SEGURO)
        // El mismo OVA, como OVA y con el año, sí es seguro.
        val ova = AnilistMatcher.Candidate(12, "OVA", "Algo", null, null, emptyList(), 2020)
        assertEquals(AnilistMatcher.Level.SEGURO, AnilistMatcher.decide(listOf("algo ova"), listOf(listOf(ova)), localYear = 2020).level)
    }

    @Test
    fun `un lote mezclado con un texto que AniList rechaza con 400 se resuelve de uno en uno`() = kotlinx.coroutines.test.runTest {
        val serie = """{"id":7,"format":"TV","title":{"romaji":"Algo","english":null,"native":null},"synonyms":[],"seasonYear":2008}"""
        val bodies = mutableListOf<String>()
        val client = AnilistClient { body ->
            bodies += body
            when {
                // El lote entero (varios alias) falla; uno solo falla si es el texto «malo» (sin «data», como el 400 de AniList).
                "s1:" in body -> """{"errors":[{"message":"Bad Request"}]}"""
                "\"malo\"" in body -> """{"errors":[{"message":"Bad Request"}]}"""
                else -> """{"data":{"s0":{"media":[$serie]}}}"""
            }
        }

        val found = client.search(listOf("algo", "malo", "otro"))

        assertEquals(listOf(listOf(7L), emptyList<Long>(), listOf(7L)), found.map { list -> list.map { it.id } })
        // Una petición del lote y tres sueltas; el texto malo no estropea a los demás.
        assertEquals(4, bodies.size)
    }

    @Test
    fun `la migracion no reabre los seguro ni lo probable que ya es del usuario o esta por revisar`() {
        fun row(slug: String, estado: String, nivel: String, origen: String = AnimeFuente.AUTO) = AnimeFuente("jkanime", slug, 1L, estado, origen, nivel, 1.0, 1L)
        val titles = mapOf(1L to AnimeFichaDao.Titles(1, "x", "x", "TV"))
        val all = listOf(
            row("x", AnimeFuente.APLICADO, "seguro"),
            row("x-2", AnimeFuente.APLICADO, "probable", AnimeFuente.USUARIO),
            row("x-3", AnimeFuente.PENDIENTE, "probable"),
            row("x-4", AnimeFuente.APLICADO, AnimeFuente.NIVEL_MAL),
            row("x-5", AnimeFuente.APLICADO, "probable")
        )
        val r = AnilistLinkCheck.redo(all, titles, { null }, withEvidence = true, withFormat = true)
        // Solo el «probable» automático aplicado se vuelve a mirar: los «seguro» ya aplicados no se reabren (límite conocido).
        assertEquals(listOf("x-5"), r.rows.map { it.slug })
        assertEquals(1, r.weak)
    }
}

// ===== AnilistHttpTest.kt =====
/** Qué hace [AnilistHttp] con cada respuesta de AniList: solo un 400 es «vacío»; una caída o un bloqueo paran la cola. */
class AnilistHttpTest {
    private val codes = ArrayDeque<Int>()
    private var calls = 0
    private var userAgent: String? = null

    @Before
    fun setUp() {
        // HostGate es global: una prueba que deja a AniList en pausa no debe afectar a la siguiente.
        HostGate.storage = null
        HostGate.reset()
        HostGate.onDown = {}
        HostGate.isNetworkUp = { true }
        AnilistHttp.spacingMs = 0
        AnilistHttp.RETRY_WAIT_MS = 0
        AnilistHttp.clientOverride = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            calls++
            userAgent = chain.request().header("User-Agent")
            val code = codes.removeFirstOrNull() ?: 200
            if (code == -1) throw IOException("sin red")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .header("Retry-After", "0").body("""{"data":{"ok":$code}}""".toResponseBody()).build()
        }).build()
    }

    @After
    fun tearDown() { AnilistHttp.clientOverride = null }

    @Test
    fun `un 200 devuelve el cuerpo y manda el User-Agent de la app`() = runTest {
        assertEquals("""{"data":{"ok":200}}""", AnilistHttp.post("{}"))
        assertTrue(userAgent.orEmpty().startsWith("Mozilla/5.0"))
    }

    @Test
    fun `un 400 es respuesta vacia y no se reintenta`() = runTest {
        codes += 400
        assertEquals("{}", AnilistHttp.post("{}"))
        assertEquals(1, calls)
    }

    @Test
    fun `un 429 y un 503 se reintentan y luego dan el resultado`() = runTest {
        codes += listOf(429, 503)
        assertEquals("""{"data":{"ok":200}}""", AnilistHttp.post("{}"))
        assertEquals(3, calls)
    }

    @Test
    fun `una caida que no cede lanza no marca vacio`() = runTest {
        codes += listOf(503, 503, 503, 503, 503)
        val failed = runCatching { AnilistHttp.post("{}") }.exceptionOrNull()
        assertTrue(failed is AnilistUnavailable)
        assertEquals(4, calls)
    }

    @Test
    fun `un 403 de bloqueo lanza y la red caida tambien`() = runTest {
        codes += listOf(403, 403, 403, 403)
        assertTrue(runCatching { AnilistHttp.post("{}") }.exceptionOrNull() is AnilistUnavailable)
        HostGate.reset()
        codes.clear()
        codes += listOf(-1, -1, -1, -1)
        assertTrue(runCatching { AnilistHttp.post("{}") }.exceptionOrNull() is AnilistUnavailable)
    }

    @Test
    fun `dos 403 seguidos ponen a AniList en pausa y la cola deja de insistir`() = runTest {
        codes += listOf(403, 403, 403, 403)
        val failed = runCatching { AnilistHttp.post("{}") }.exceptionOrNull()
        assertTrue(failed is AnilistUnavailable)
        // 2 peticiones: con el segundo 403 HostGate pausa AniList y el tercer intento ni sale.
        assertEquals(2, calls)
        assertTrue(HostGate.isDown("graphql.anilist.co"))
        val before = calls
        assertTrue(runCatching { AnilistHttp.post("{}") }.exceptionOrNull() is AnilistUnavailable)
        assertEquals("en pausa no se le pide nada", before, calls)
    }
}

// ===== AnilistLinksBackupTest.kt =====
/** La copia de los enlaces con AniList: la red de seguridad antes de cambiar las tablas del libro de series. */
class AnilistLinksBackupTest {
    private class FakeFuentes : AnimeFuenteDao {
        val rows = mutableMapOf<Pair<String, String>, AnimeFuente>()
        override fun all() = rows.values.toList()
        override fun get(fuente: String, slug: String) = rows[fuente to slug]
        override fun appliedIds() = rows.values.filter { it.estado == AnimeFuente.APLICADO }.mapNotNull { it.anilistId }.distinct()
        override fun countByState() = rows.values.groupingBy { it.estado }.eachCount().map { AnimeFuenteDao.StateCount(it.key, it.value) }
        override fun expireAuto() {}
        override fun deleteAuto(fuente: String, slug: String) {}
        override fun insertIfAbsent(rows: List<AnimeFuente>) { rows.forEach { this.rows.putIfAbsent(it.fuente to it.slug, it) } }
        override fun put(rows: List<AnimeFuente>) { rows.forEach { this.rows[it.fuente to it.slug] = it } }
    }

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File.createTempFile("links", "").also { it.delete(); it.mkdirs() }
        AnilistLinksBackupFile.directory = { dir }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun row(fuente: String, slug: String, id: Long?, estado: String = AnimeFuente.APLICADO, origen: String = AnimeFuente.AUTO) =
        AnimeFuente(fuente, slug, id, estado, origen, "seguro", 1.0, 123L)

    @Test
    fun `lo guardado se lee igual, con lo del usuario y las filas sin anime`() {
        val dao = FakeFuentes()
        dao.put(listOf(row("jkanime", "frieren", 154587), row("animeflv", "x", null, AnimeFuente.SIN_RESULTADO), row("animegratis", "y", 7, origen = AnimeFuente.USUARIO)))
        assertEquals(3, AnilistLinksBackups.save(dao, now = 9L))
        val back = AnilistLinksBackupFile.read()
        assertNotNull(back)
        assertTrue(back!!.usable())
        assertEquals(dao.all().toSet(), back.rows().toSet())
        assertEquals(9L, back.createdAt)
    }

    @Test
    fun `una tabla vacia no sustituye a una copia buena y la anterior se conserva`() {
        val dao = FakeFuentes()
        dao.put(listOf(row("jkanime", "a", 1)))
        AnilistLinksBackups.save(dao)
        assertEquals(0, AnilistLinksBackups.save(FakeFuentes()))
        assertEquals(1, AnilistLinksBackupFile.read()!!.rows().size)
        dao.put(listOf(row("jkanime", "b", 2)))
        AnilistLinksBackups.save(dao)
        assertEquals(2, AnilistLinksBackupFile.read()!!.rows().size)
        assertTrue(File(dir, "anilist-links.prev.json.gz").exists())
    }

    @Test
    fun `restaurar solo anade lo que falta y no pisa lo que el usuario cambio despues`() {
        val dao = FakeFuentes()
        dao.put(listOf(row("a", "1", 10), row("a", "2", 20, origen = AnimeFuente.USUARIO)))
        AnilistLinksBackups.save(dao)
        val fresh = FakeFuentes()
        fresh.put(listOf(row("a", "2", 99, origen = AnimeFuente.USUARIO)))
        assertEquals(1, AnilistLinksBackups.restoreMissing(fresh))
        assertEquals(10L, fresh.get("a", "1")?.anilistId)
        assertEquals(99L, fresh.get("a", "2")?.anilistId)
    }

    @Test
    fun `si la principal falta o esta rota se lee la anterior y una principal rota no la machaca`() {
        val dao = FakeFuentes()
        dao.put(listOf(row("a", "1", 1)))
        AnilistLinksBackups.save(dao)
        dao.put(listOf(row("a", "2", 2)))
        AnilistLinksBackups.save(dao)
        // `.prev` guarda la copia de antes (1 fila); la principal tiene las 2.
        assertEquals(2, AnilistLinksBackupFile.read()!!.rows().size)
        File(dir, "anilist-links.json.gz").writeText("rota")
        assertEquals(1, AnilistLinksBackupFile.read()!!.rows().size)
        // Una copia nueva con la principal rota no pisa la `.prev` buena con la rota.
        dao.put(listOf(row("a", "3", 3)))
        AnilistLinksBackups.save(dao)
        assertEquals(3, AnilistLinksBackupFile.read()!!.rows().size)
        // La `.prev` seguía siendo la buena de antes (1 fila): sin la principal, se lee ella.
        File(dir, "anilist-links.json.gz").delete()
        assertEquals(1, AnilistLinksBackupFile.read()!!.rows().size)
    }

    @Test
    fun `si no puede dejar la copia nueva en su sitio lo dice y la buena sigue en prev`() {
        val dao = FakeFuentes()
        dao.put(listOf(row("a", "1", 1)))
        AnilistLinksBackups.save(dao)
        dao.put(listOf(row("a", "2", 2)))
        AnilistLinksBackups.save(dao)
        // La principal pasa a ser un directorio con contenido: no se puede borrar ni sustituir.
        val main = File(dir, "anilist-links.json.gz")
        main.delete()
        main.mkdirs()
        File(main, "x").writeText("x")
        assertTrue(runCatching { AnilistLinksBackups.save(dao) }.isFailure)
        assertEquals(1, AnilistLinksBackupFile.read()!!.rows().size)
    }

    @Test
    fun `sin copia o con una copia rota no se restaura nada`() {
        assertNull(AnilistLinksBackupFile.read())
        assertEquals(0, AnilistLinksBackups.restoreMissing(FakeFuentes()))
        File(dir, "anilist-links.json.gz").writeText("no es gzip")
        assertNull(AnilistLinksBackupFile.read())
        assertFalse(AnilistLinksBackup(AnilistLinksBackup.VERSION, 0L, emptyList()).usable())
    }
}

// ===== AnilistMatcherTest.kt =====
class AnilistMatcherTest {
    private fun cand(id: Long, romaji: String, format: String = "TV", english: String? = null, vararg synonyms: String) =
        Candidate(id, format, romaji, english, null, synonyms.toList())

    @Test
    fun `un titulo exacto es seguro`() {
        val m = AnilistMatcher.decide(listOf("monster strike"), listOf(listOf(cand(1, "Monster Strike"))))
        assertEquals(Level.SEGURO, m.level)
        assertEquals(1L, m.candidate?.id)
    }

    @Test
    fun `la temporada se reconoce escrita de cualquier forma`() {
        val season2 = cand(2, "[Oshi no Ko] 2nd Season")
        val season1 = cand(1, "[Oshi no Ko]")
        val m = AnilistMatcher.decide(listOf("oshi no ko 2nd season"), listOf(listOf(season1, season2)))
        assertEquals(2L, m.candidate?.id)
        assertEquals(Level.SEGURO, m.level)
        assertEquals(2L, AnilistMatcher.decide(listOf("boku no kokoro no yabai yatsu season 2"), listOf(listOf(cand(1, "Boku no Kokoro no Yabai Yatsu"), cand(2, "Boku no Kokoro no Yabai Yatsu 2nd Season")))).candidate?.id)
        assertEquals(2L, AnilistMatcher.decide(listOf("mushishi segunda temporada"), listOf(listOf(cand(1, "Mushishi"), cand(2, "Mushishi Zoku Shou", english = "Mushishi 2nd Season")))).candidate?.id)
    }

    @Test
    fun `la romanizacion distinta no impide casar`() {
        val m = AnilistMatcher.decide(listOf("maho shojo madoka magica"), listOf(listOf(cand(9, "Mahou Shoujo Madoka Magica"))))
        assertEquals(Level.SEGURO, m.level)
    }

    @Test
    fun `el formato del titulo desempata entre la serie y su ova`() {
        val serie = cand(1, "Miss Monochrome", "TV")
        val ova = cand(2, "Miss Monochrome", "OVA")
        assertEquals(2L, AnilistMatcher.decide(listOf("miss monochrome ova"), listOf(listOf(serie, ova))).candidate?.id)
    }

    @Test
    fun `otra temporada no se propone ni como dudosa`() {
        val m = AnilistMatcher.decide(listOf("da capo"), listOf(listOf(cand(5, "D.C.S.S.: Da Capo Second Season"))))
        assertEquals(Level.NINGUNO, m.level)
        // La primera temporada tampoco es la segunda que pide el slug.
        assertEquals(Level.NINGUNO, AnilistMatcher.decide(listOf("vanitas no karte 2nd season"), listOf(listOf(cand(1, "Vanitas no Carte")))).level)
        assertEquals(Level.NINGUNO, AnilistMatcher.decide(listOf("ao ashi season 2"), listOf(listOf(cand(1, "Aoashi")))).level)
    }

    @Test
    fun `las palabras partidas de otra forma casan por las letras pegadas`() {
        // «shitemo» frente a «shite mo» y «ojousama» frente a «ojou sama»: antes el parecido por palabras no llegaba a probable.
        val a = AnilistMatcher.decide(listOf("hanaori san wa tensei shitemo kenka ga shitai"), listOf(listOf(cand(1, "Hanaori-san wa Tensei Shite mo Kenka ga Shitai"))))
        // El parecido llega a probable (sin más prueba que el nombre, se pregunta: dudoso).
        assertEquals(true, a.score >= 0.85)
        val b = AnilistMatcher.decide(listOf("tai ari deshita ojousama wa kakutou game nante shinai"), listOf(listOf(cand(2, "Tai-Ari deshita.: Ojou-sama wa Kakutou Game nante Shinai"))))
        assertEquals(true, b.score >= 0.85)
        // Un título largo al que AniList añade una palabra suelta no se da por el mismo (las letras pegadas coinciden casi todas, pero no llega a 0,93).
        assertEquals(true, AnilistMatcher.decide(listOf("kono subarashii sekai ni shukufuku wo"), listOf(listOf(cand(4, "Kono Subarashii Sekai ni Shukufuku wo! Kurenai Densetsu")))).level != Level.SEGURO)
        // Una secuela cuyo «2nd Season» está en un sinónimo se empareja con el título de esa temporada.
        val secuela = cand(5, "Shingeki no Kyojin", "TV", null, "Attack on Titan 2nd Season")
        assertEquals(true, AnilistMatcher.decide(listOf("attack on titan 2nd season"), listOf(listOf(secuela))).level != Level.NINGUNO)
        // La primera temporada no se empareja con una secuela aunque otro de sus nombres comparta una palabra (el mejor nombre es de otra temporada).
        assertEquals(Level.NINGUNO, AnilistMatcher.decide(listOf("mushishi"), listOf(listOf(cand(2, "Mushishi Zoku Shou", "TV", "Mushishi 2nd Season")))).level)
        // Dos títulos largos con algunas letras en común no se confunden.
        assertEquals(true, AnilistMatcher.decide(listOf("kara no kyoukai"), listOf(listOf(cand(3, "Kara no Kyoukai: Shuushou")))).level == Level.DUDOSO)
    }

    @Test
    fun `si otra ficha de la serie apunta a otro candidato solo es probable`() {
        val m = AnilistMatcher.decide(listOf("nekopara", "nekopara ova"), listOf(listOf(cand(1, "Nekopara")), listOf(cand(7, "Nekopara OVA", "OVA"))))
        // Sin ninguna prueba más que el nombre, ni probable: se pregunta.
        assertEquals(Level.DUDOSO, m.level)
        val withYear = AnilistMatcher.decide(listOf("nekopara", "nekopara ova"), listOf(listOf(Candidate(1, "TV", "Nekopara", null, null, emptyList(), 2014)), listOf(cand(7, "Nekopara OVA", "OVA"))), localYear = 2014)
        assertEquals(Level.PROBABLE, withYear.level)
        val agree = AnilistMatcher.decide(listOf("nekopara", "nekopara tv"), listOf(listOf(cand(1, "Nekopara")), listOf(cand(1, "Nekopara"))))
        assertEquals(Level.SEGURO, agree.level)
    }

    @Test
    fun `sin candidatos no hay emparejado`() {
        val m = AnilistMatcher.decide(listOf("fatestay night"), listOf(emptyList()))
        assertEquals(Level.NINGUNO, m.level)
        assertNull(m.candidate)
    }

    @Test
    fun `normalizar quita acentos signos y mayusculas`() {
        assertEquals("fairy tail rave", AnilistMatcher.normalize("FAIRY TAIL × RAVE"))
        assertEquals("pokemon", AnilistMatcher.normalize("Pokémon"))
    }

    @Test
    fun `una cifra suelta al final es la temporada, como 5th Season`() {
        val m = AnilistMatcher.decide(listOf("boku no hero academia 5th season"), listOf(listOf(cand(1, "Boku no Hero Academia 5"), cand(2, "Boku no Hero Academia 4th Season"))))
        assertEquals(1L, m.candidate?.id)
        assertEquals(true, m.score >= 1.0)
    }

    @Test
    fun `las secuelas numeradas no se dan por seguras contra la primera temporada`() {
        for ((wanted, first) in listOf("gintama 3" to "Gintama", "fate zero 2" to "Fate Zero", "re zero 2" to "Re Zero", "sword art online 2" to "Sword Art Online", "mob psycho 100" to "Mob Psycho 100 II")) {
            val m = AnilistMatcher.decide(listOf(wanted), listOf(listOf(cand(1, first))))
            assertEquals(wanted, true, m.level != AnilistMatcher.Level.SEGURO)
        }
    }

    @Test
    fun `un candidato que no se parece al titulo no es dudoso sino ninguno`() {
        val m = AnilistMatcher.decide(listOf("hunter x hunter movie 2 the last mission"), listOf(listOf(cand(18245, "WHITE ALBUM 2"))))
        assertEquals(AnilistMatcher.Level.NINGUNO, m.level)
    }

    @Test
    fun `la temporada en español con ordinal`() {
        val first = cand(1, "Boku no Kokoro no Yabai Yatsu")
        val m = AnilistMatcher.decide(listOf("boku no kokoro no yabai yatsu 3ra temporada"), listOf(listOf(first)))
        assertEquals(true, m.level != AnilistMatcher.Level.SEGURO && m.score < 0.85)
        assertEquals(3, AnilistMatcher.split("boku no yabai yatsu 3ra temporada").season)
        assertEquals(2, AnilistMatcher.split("slime 2da temporada").season)
    }

    @Test
    fun `la temporada se entiende en cifras, ordinales, palabras y romanos`() {
        fun season(title: String) = AnilistMatcher.split(AnilistMatcher.normalize(title)).season
        // Cifras con «season» o «temporada», delante o detrás, y la forma corta.
        assertEquals(2, season("black clover season 2"))
        assertEquals(2, season("black clover temporada 2"))
        assertEquals(2, season("black clover 2 temporada"))
        assertEquals(3, season("black clover 3 season"))
        assertEquals(12, season("black clover season 12"))
        assertEquals(2, season("black clover s2"))
        // Ordinales con sufijo en inglés y en español.
        for ((text, n) in listOf("2nd" to 2, "3rd" to 3, "4th" to 4, "2nda" to 2, "2da" to 2, "3ra" to 3, "4ta" to 4, "1era" to 1, "7ma" to 7, "8va" to 8, "9na" to 9, "6to" to 6)) {
            assertEquals(text, n, season("black clover $text season"))
            assertEquals(text, n, season("black clover $text temporada"))
        }
        // Ordinales en palabra.
        for ((word, n) in listOf("segunda" to 2, "tercera" to 3, "cuarta" to 4, "quinta" to 5, "sexta" to 6, "septima" to 7, "octava" to 8, "novena" to 9, "decima" to 10)) {
            assertEquals(word, n, season("black clover $word temporada"))
        }
        for ((word, n) in listOf("second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6, "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10)) {
            assertEquals(word, n, season("black clover $word season"))
        }
        // Romanos.
        for ((roman, n) in listOf("ii" to 2, "iii" to 3, "iv" to 4, "vi" to 6, "vii" to 7, "viii" to 8, "ix" to 9)) {
            assertEquals(roman, n, season("sword art online $roman"))
            assertEquals(roman, n, season("sword art online season $roman"))
        }
        // Lo que no es una temporada: la «x» de «Hunter x Hunter» y una «v» suelta no son romanos; sin número, es la primera.
        assertEquals(1, season("hunter x hunter"))
        assertEquals(1, season("black clover"))
        assertEquals(1, season("black clover primera temporada"))
    }

    @Test
    fun `la v y la x finales son temporada, la final es la ultima y un año no es una palabra del titulo`() {
        fun parts(title: String) = AnilistMatcher.split(AnilistMatcher.normalize(title))
        // Series cuya temporada se escribe en romano (v, x): «Date A Live V» no es «Date A Live».
        assertEquals(5, parts("date a live v").season)
        assertEquals(5, parts("yu gi oh arc v").season)
        assertEquals(10, parts("beyblade x").season)
        assertEquals(listOf("date", "a", "live"), parts("date a live v").words)
        // La x de en medio no es un romano.
        assertEquals(1, parts("hunter x hunter").season)
        assertEquals(listOf("hunter", "x", "hunter"), parts("hunter x hunter").words)
        // «Final season» y «temporada final» son la última, igual en las dos lenguas.
        assertEquals(parts("boku no hero academia final season").season, parts("boku no hero academia temporada final").season)
        assertEquals(true, parts("boku no hero academia final season").season > 10)
        assertEquals(listOf("boku", "hero", "academia"), parts("boku no hero academia final season").words)
        // Un año suelto tras el título distingue remakes y no cuenta como palabra.
        assertEquals(2019, parts("fruits basket 2019").year)
        assertEquals(listOf("fruits", "basket"), parts("fruits basket 2019").words)
        assertEquals(null, parts("fruits basket").year)
        assertEquals(null, parts("2001 nights").year)
    }

    @Test
    fun `el año del slug separa un remake del original y sin año nada cambia`() {
        val original = Candidate(1, "TV", "Fruits Basket", null, null, emptyList(), year = 2001)
        val remake = Candidate(2, "TV", "Fruits Basket", null, null, emptyList(), year = 2019)
        val title = AnilistMatcher.normalize("fruits basket 2019")
        assertEquals(true, AnilistMatcher.score(title, remake) > AnilistMatcher.score(title, original))
        assertEquals(2L, AnilistMatcher.best(title, listOf(original, remake))?.first?.id)
        // Un año de diferencia (estreno a final de año) todavía cuadra; sin año en el slug o en AniList no se juzga.
        assertEquals(true, AnilistMatcher.score(title, Candidate(3, "TV", "Fruits Basket", null, null, emptyList(), year = 2018)) >= 0.85)
        assertEquals(AnilistMatcher.score("fruits basket", original), AnilistMatcher.score("fruits basket", remake), 1e-9)
        assertEquals(AnilistMatcher.score(title, Candidate(4, "TV", "Fruits Basket", null, null, emptyList())), AnilistMatcher.score(title, Candidate(5, "TV", "Fruits Basket", null, null, emptyList(), year = null)), 1e-9)
    }

    @Test
    fun `un titulo que casa pero con otro año de estreno pasa a dudoso`() {
        val first = Candidate(1, "TV", "Fruits Basket", null, null, emptyList(), year = 2001)
        fun level(localYear: Int?) = AnilistMatcher.decide(listOf("fruits basket"), listOf(listOf(first)), localYear).level
        assertEquals(Level.SEGURO, level(null))
        assertEquals(Level.SEGURO, level(2001))
        assertEquals(Level.SEGURO, level(2002))
        assertEquals(Level.DUDOSO, level(2019))
        // Sin año del anime tampoco se juzga.
        assertEquals(Level.SEGURO, AnilistMatcher.decide(listOf("fruits basket"), listOf(listOf(Candidate(2, "TV", "Fruits Basket", null, null))), 2019).level)
    }
}

// ===== AnilistReviewTest.kt =====
class AnilistReviewTest {
    private fun row(f: String, s: String, id: Long?, estado: String, score: Double = 0.5, origen: String = AnimeFuente.AUTO) =
        AnimeFuente(f, s, id, estado, origen, "dudoso", score, 0L)

    private val rows = listOf(
        row("a", "uno", 10, AnimeFuente.PENDIENTE, 0.6), row("b", "uno", 11, AnimeFuente.PENDIENTE, 0.8),   // serie 1: dos candidatos, manda el mejor
        row("a", "dos", 20, AnimeFuente.APLICADO), row("b", "dos", 21, AnimeFuente.PENDIENTE),             // serie 2: ya tiene enlace aplicado
        row("a", "tres", null, AnimeFuente.SIN_RESULTADO),                                                  // serie 3: sin resultado
        row("a", "cuatro", 40, AnimeFuente.PENDIENTE, 0.9)                                                  // serie 4: dudosa, con favoritos
    ).associateBy { it.fuente to it.slug }

    private val series = listOf(
        SeriesWork(1, listOf("a" to "uno", "b" to "uno"), 0), SeriesWork(2, listOf("a" to "dos", "b" to "dos"), 0),
        SeriesWork(3, listOf("a" to "tres"), 1), SeriesWork(4, listOf("a" to "cuatro"), 2), SeriesWork(5, listOf("a" to "nuevo"), 0)
    )

    @Test
    fun `las dudosas son las que no tienen enlace aplicado, con favoritos primero y el mejor candidato`() {
        val pending = AnilistReview.pending(series) { f, s -> rows[f to s] }
        assertEquals(listOf(40L, 11L), pending.map { it.anilistId })
        assertEquals(listOf(2, 0), pending.map { it.priority })
    }

    private class Fuentes : AnimeFuenteDao {
        val saved = mutableMapOf<Pair<String, String>, AnimeFuente>()
        override fun all() = saved.values.toList()
        override fun get(fuente: String, slug: String) = saved[fuente to slug]
        override fun appliedIds() = emptyList<Long>()
        override fun countByState() = emptyList<AnimeFuenteDao.StateCount>()
        override fun expireAuto() {}
        override fun deleteAuto(fuente: String, slug: String) {}
        override fun insertIfAbsent(rows: List<AnimeFuente>) {}
        override fun put(rows: List<AnimeFuente>) { rows.forEach { saved[it.fuente to it.slug] = it } }
    }

    private class Fichas : AnimeFichaDao {
        val saved = mutableMapOf<Long, AnimeFicha>()
        override fun put(fichas: List<AnimeFicha>) { fichas.forEach { saved[it.anilistId] = it } }
        override fun get(id: Long) = saved[id]
        override fun freshIds(version: Int, since: Long) = emptyList<Long>()
        override fun downloaded(version: Int) = emptyList<AnimeFichaDao.Downloaded>()
        override fun count() = saved.size
        override fun titles() = emptyList<AnimeFichaDao.Titles>()
        override fun idsWithoutEs() = emptyList<Long>()
        override fun setEs(id: Long, es: ByteArray) {}
        override fun countWithEs() = 0
        override fun esMarks() = emptyList<AnimeFichaDao.EsMark>()
    }

    private val pending = PendingSeries(listOf("a" to "uno", "b" to "uno"), 11, "dudoso", 0.8, 0)
    private val raw = AnilistFichaRaw(11, 5, """{"id":11}""", "Uno", null, null, 2020, "TV", 70, "FINISHED")

    @Test
    fun `decir que es el mismo enlaza todas las fichas como decision del usuario y guarda la ficha`() {
        val fuentes = Fuentes(); val fichas = Fichas()
        AnilistReview.decide(pending, true, fuentes, fichas, raw, 99L)
        assertTrue(fuentes.saved.values.all { it.estado == AnimeFuente.APLICADO && it.origen == AnimeFuente.USUARIO && it.anilistId == 11L })
        assertEquals(2, fuentes.saved.size)
        assertEquals("""{"id":11}""", fichas.saved.getValue(11).jsonText())
    }

    @Test
    fun `decir que no es deja sin resultado como decision del usuario y no guarda ficha`() {
        val fuentes = Fuentes(); val fichas = Fichas()
        AnilistReview.decide(pending, false, fuentes, fichas, raw, 99L)
        assertTrue(fuentes.saved.values.all { it.estado == AnimeFuente.SIN_RESULTADO && it.origen == AnimeFuente.USUARIO && it.anilistId == null })
        assertNull(fichas.saved[11])
    }

    @Test
    fun `deshacer devuelve cada ficha a lo que tenia y la serie vuelve a ser dudosa`() {
        val fuentes = Fuentes(); val fichas = Fichas()
        // Antes: una ficha con el candidato dudoso y otra sin fila.
        fuentes.saved["a" to "uno"] = row("a", "uno", 11, AnimeFuente.PENDIENTE, 0.8)
        val before = AnilistReview.decide(pending, true, fuentes, fichas, raw, 99L)
        assertEquals(listOf(fuentes.saved["a" to "uno"]?.estado), listOf(AnimeFuente.APLICADO))
        AnilistReview.undo(pending, before, fuentes, 100L)
        assertEquals(AnimeFuente.PENDIENTE, fuentes.saved.getValue("a" to "uno").estado)
        assertEquals(AnimeFuente.AUTO, fuentes.saved.getValue("a" to "uno").origen)
        // La que no tenía fila vuelve a ser candidato dudoso del mismo anime.
        assertEquals(11L, fuentes.saved.getValue("b" to "uno").anilistId)
        assertEquals(AnimeFuente.PENDIENTE, fuentes.saved.getValue("b" to "uno").estado)
    }

    @Test
    fun `la comparacion colorea titulo, ano y episodios`() {
        val data = FichaData.ofAnilistJson("""{"id":1,"title":{"romaji":"Yowaki Reijou","english":"Timid Noble"},"format":"TV","status":"RELEASING","seasonYear":2026,"episodes":12,"duration":23,"nextAiringEpisode":{"episode":5,"airingAt":1}}""")!!
        assertEquals(Match.SAME, AnilistCompare.title("Yowaki Reijou", data))
        assertEquals(Match.DIFFERENT, AnilistCompare.title("Otra cosa distinta del todo", data))
        assertEquals(Match.SAME, AnilistCompare.year(2026, 2026)); assertEquals(Match.CLOSE, AnilistCompare.year(2025, 2026))
        assertEquals(Match.DIFFERENT, AnilistCompare.year(2019, 2026)); assertEquals(Match.UNKNOWN, AnilistCompare.year(null, 2026))
        // En emisión se comparan los emitidos (el siguiente es el 5: van 4), no los 12 totales.
        assertEquals(4, AnilistCompare.airedEpisodes(data))
        assertEquals(Match.SAME, AnilistCompare.episodes(4, 4)); assertEquals(Match.CLOSE, AnilistCompare.episodes(3, 4))
        assertEquals(Match.DIFFERENT, AnilistCompare.episodes(12, 4)); assertEquals(Match.UNKNOWN, AnilistCompare.episodes(null, 4))
    }

    @Test
    fun `un anime ya enlazado en otra ficha enlaza tambien la serie dudosa que lo propone`() {
        // La serie 4 propone el anime 40 con parecido 0,9; la 1 propone 11 (nadie lo tiene) y la 2 ya tiene enlace.
        val adopted = AnilistReview.adoptions(series, { f, s -> rows[f to s] }, setOf(40L, 20L), 5L)
        assertEquals(listOf("a" to "cuatro"), adopted.map { it.fuente to it.slug })
        assertEquals(AnimeFuente.APLICADO, adopted.single().estado)
        assertEquals(AnimeFuente.AUTO, adopted.single().origen)
        assertEquals(40L, adopted.single().anilistId)
    }

    @Test
    fun `episodios en dos partes y estudio sin guion`() {
        assertEquals(Match.CLOSE, AnilistCompare.episodes(24, 12))
        assertEquals(Match.DIFFERENT, AnilistCompare.episodes(25, 12))
        assertEquals(Match.SAME, AnilistCompare.studio("8bit", "8-bit"))
    }

    @Test
    fun `no se adopta con otra temporada ni si el mejor candidato de la serie es otro`() {
        val r = listOf(
            row("a", "foo-2", 1, AnimeFuente.PENDIENTE, 0.75),                                                    // otra temporada: 0,75 < 0,85
            row("a", "bar", 2, AnimeFuente.PENDIENTE, 0.9), row("b", "bar", 3, AnimeFuente.PENDIENTE, 0.86)      // el mejor (2) no está enlazado
        ).associateBy { it.fuente to it.slug }
        val work = listOf(SeriesWork(1, listOf("a" to "foo-2"), 0), SeriesWork(2, listOf("a" to "bar", "b" to "bar"), 0))
        assertEquals(emptyList<AnimeFuente>(), AnilistReview.adoptions(work, { f, s -> r[f to s] }, setOf(1L, 3L), 5L))
    }

    @Test
    fun `un enlace cuyo anime no se parece a la ficha se detecta`() {
        val titles = mapOf(
            204060L to AnimeFichaDao.Titles(204060, "tetsunabe no jan", "iron wok jan"),
            203490L to AnimeFichaDao.Titles(203490, "uchi no otouto domo ga sumimasen", null)
        )
        val bad = row("animeav1", "uchi-no-otouto-domo-ga-sumimasen", 204060, AnimeFuente.APLICADO)
        val good = row("animeflv", "uchi-no-otouto-domo-ga-sumimasen", 203490, AnimeFuente.APLICADO)
        val userChoice = row("jkanime", "uchi-no-otouto-domo-ga-sumimasen", 204060, AnimeFuente.APLICADO, origen = AnimeFuente.USUARIO)
        val fichas = titles.mapValues { (id, t) -> AnimeFicha(id, null, 1, 0L, AnimeFicha.gzip("""{"id":$id}"""), tituloRomaji = t.tituloRomaji, tituloIngles = t.tituloIngles) }
        assertEquals(listOf(bad), AnilistLinkCheck.inconsistent(listOf(bad, good, userChoice), titles) { fichas[it] })
        assertEquals(null, AnilistLinkCheck.consistent("x", AnimeFichaDao.Titles(1, null, null)))
    }

    @Test
    fun `la comprobacion de enlaces entiende sinonimos, sufijos de version y no juzga sin titulos`() {
        val json = """{"id":7,"synonyms":["Ataque a los Titanes"]}"""
        val ficha = AnimeFicha(7, null, 1, 0L, AnimeFicha.gzip(json), tituloRomaji = "shingeki no kyojin", tituloIngles = "attack on titan")
        val titles = mapOf(7L to AnimeFichaDao.Titles(7, "shingeki no kyojin", "attack on titan"), 8L to AnimeFichaDao.Titles(8, "gintama", null))
        val bySynonym = row("animeav1", "ataque-a-los-titanes", 7, AnimeFuente.APLICADO)
        val latino = row("animeflv", "gintama-latino-castellano", 8, AnimeFuente.APLICADO)
        val wrong = row("jkanime", "naruto", 7, AnimeFuente.APLICADO)
        assertEquals(listOf(wrong), AnilistLinkCheck.inconsistent(listOf(bySynonym, latino, wrong), titles) { if (it == 7L) ficha else null })
    }

    @Test
    fun `un enlace de otra temporada no pasa la comprobacion aunque el titulo se parezca`() {
        val first = AnilistMatcher.Candidate(97940, "TV", "Black Clover", "Black Clover", null)
        val second = AnilistMatcher.Candidate(195604, "TV", "Black Clover 2nd Season", "Black Clover 2nd Season", null)
        // La ficha de la 2.ª temporada no es la 1.ª, y la de la 1.ª no es la 2.ª.
        assertEquals(false, AnilistLinkCheck.fits("black-clover-2nd-season", first))
        assertEquals(false, AnilistLinkCheck.fits("black-clover-2nda-temporada", first))
        assertEquals(false, AnilistLinkCheck.fits("black-clover", second))
        // Cada una con la suya, y las variantes de versión de la 1.ª siguen valiendo.
        assertEquals(true, AnilistLinkCheck.fits("black-clover-2nd-season", second))
        assertEquals(true, AnilistLinkCheck.fits("black-clover", first))
        assertEquals(true, AnilistLinkCheck.fits("black-clover-latino", first))
        assertEquals(true, AnilistLinkCheck.fits("black-clover-tv", first))
        // Temporadas dichas de otra forma («5» suelto, ordinal, romano) coinciden con el nombre de AniList.
        assertEquals(true, AnilistLinkCheck.fits("boku-no-hero-academia-5", AnilistMatcher.Candidate(1, "TV", "Boku no Hero Academia 5th Season", null, null)))
        assertEquals(true, AnilistLinkCheck.fits("sword-art-online-ii", AnilistMatcher.Candidate(2, "TV", "Sword Art Online II", null, null)))
    }

    @Test
    fun `el comprobador distingue remakes por el año y temporadas escritas en romano o como final`() {
        val original = AnilistMatcher.Candidate(1, "TV", "Fruits Basket", null, null, emptyList(), year = 2001)
        val remake = AnilistMatcher.Candidate(2, "TV", "Fruits Basket", null, null, emptyList(), year = 2019)
        assertEquals(false, AnilistLinkCheck.fits("fruits-basket-2019", original))
        assertEquals(true, AnilistLinkCheck.fits("fruits-basket-2019", remake))
        assertEquals(true, AnilistLinkCheck.fits("fruits-basket", original))
        val dal = AnilistMatcher.Candidate(3, "TV", "Date A Live", null, null)
        val dalV = AnilistMatcher.Candidate(4, "TV", "Date A Live V", null, null)
        assertEquals(false, AnilistLinkCheck.fits("date-a-live-v", dal))
        assertEquals(true, AnilistLinkCheck.fits("date-a-live-v", dalV))
        assertEquals(false, AnilistLinkCheck.fits("date-a-live", dalV))
        val bnha = AnilistMatcher.Candidate(5, "TV", "Boku no Hero Academia", null, null)
        val bnhaFinal = AnilistMatcher.Candidate(6, "TV", "Boku no Hero Academia: Final Season", null, null)
        assertEquals(false, AnilistLinkCheck.fits("boku-no-hero-academia-final-season", bnha))
        assertEquals(true, AnilistLinkCheck.fits("boku-no-hero-academia-final-season", bnhaFinal))
        // Con el título guardado sin año, un slug con año se juzga con la ficha completa (que sí lo trae).
        val ficha = AnimeFicha(1, null, 1, 0L, AnimeFicha.gzip("""{"id":1,"synonyms":[]}"""), tituloRomaji = "fruits basket", tituloIngles = "fruits basket", anio = 2001)
        val titles = mapOf(1L to AnimeFichaDao.Titles(1, "fruits basket", "fruits basket"))
        assertEquals(false, AnilistLinkCheck.consistent("fruits-basket-2019", titles[1L]) { ficha })
        assertEquals(true, AnilistLinkCheck.consistent("fruits-basket", titles[1L]) { ficha })
    }

    @Test
    fun `una ficha de la 2 temporada enlazada a la 1 se detecta y se quita`() {
        val titles = mapOf(97940L to AnimeFichaDao.Titles(97940, "black clover", "black clover"))
        val ficha = AnimeFicha(97940, null, 1, 0L, AnimeFicha.gzip("""{"id":97940,"synonyms":[]}"""), tituloRomaji = "black clover", tituloIngles = "black clover")
        val season1 = row("jkanime", "black-clover-tv", 97940, AnimeFuente.APLICADO)
        val season2 = row("jkanime", "black-clover-2nd-season", 97940, AnimeFuente.APLICADO)
        assertEquals(listOf(season2), AnilistLinkCheck.inconsistent(listOf(season1, season2), titles) { ficha })
    }

    @Test
    fun `una serie unida es sospechosa si una ficha no se parece a las demas`() {
        val mixed = listOf("animeav1" to "uchi-no-otouto-domo-ga-sumimasen", "animeflv" to "tetsunabe-no-jan", "jkanime" to "uchi-no-otouto-domo-ga-sumimasen")
        assertEquals(true, JoinedCheck.cohesion(mixed, emptyMap()) < JoinedCheck.MIN_COHESION)
        val same = listOf("animeav1" to "frieren", "animeflv" to "sousou-no-frieren", "jkanime" to "frieren-latino")
        assertEquals(true, JoinedCheck.cohesion(same, emptyMap()) >= JoinedCheck.MIN_COHESION)
        // Distinto título pero el mismo anime de AniList en todas: de acuerdo.
        val spanish = listOf("animeav1" to "ataque-a-los-titanes", "animeflv" to "shingeki-no-kyojin")
        assertEquals(1.0, JoinedCheck.cohesion(spanish, mapOf(spanish[0] to 9L, spanish[1] to 9L)), 1e-9)
    }

    @Test
    fun `se resuelve solo lo clarisimo y el resto se pregunta`() {
        val s = Match.SAME; val d = Match.DIFFERENT; val u = Match.UNKNOWN
        // Nisekoi: título 0,75, año, episodios, género, estado y estudio coinciden.
        assertEquals(AnilistCompare.Auto.ACCEPT, AnilistCompare.auto(0.75, s, s, listOf(s, s, s)))
        // Título bajo, o un dato que difiere, o sin datos suficientes: se pregunta.
        assertEquals(AnilistCompare.Auto.ASK, AnilistCompare.auto(0.5, s, s, listOf(s, s, s)))
        assertEquals(AnilistCompare.Auto.ASK, AnilistCompare.auto(0.9, s, s, listOf(d, s, s)))
        assertEquals(AnilistCompare.Auto.ASK, AnilistCompare.auto(0.9, s, u, listOf(s, s, u)))
        // Año y episodios coinciden pero otro año (otra temporada): se pregunta.
        assertEquals(AnilistCompare.Auto.ASK, AnilistCompare.auto(0.75, d, s, listOf(s, s, s)))
        // Varios datos distintos y parecido bajo: se descarta.
        assertEquals(AnilistCompare.Auto.REJECT, AnilistCompare.auto(0.2, d, d, listOf(u, d, u)))
    }

    @Test
    fun `el mismo titulo escrito distinto no es una mezcla`() {
        val hack = listOf("animeav1" to "hackgu-trilogy", "animeflv" to "hack-gu-trilogy", "jkanime" to "hackgutrilogy")
        assertEquals(true, JoinedCheck.cohesion(hack, emptyMap()) >= JoinedCheck.MIN_COHESION)
    }
}

// ===== AnilistSeedTest.kt =====
/** Los enlaces de partida de una instalación limpia: solo con firma válida, mismo criterio y la tabla vacía; nunca pisan nada. */
class AnilistSeedTest {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)

    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(text.toByteArray()) } }.toByteArray()
    private fun sign(data: ByteArray, key: java.security.PrivateKey = pair.private): ByteArray =
        Base64.getEncoder().encode(Signature.getInstance("SHA256withECDSA").run { initSign(key); update(data); sign() })

    private fun file(matcher: Int = 3, version: Int = 1, links: String = """{"fuente":"animeav1","slug":"nekopara","anilistId":10,"nivel":"seguro","puntuacion":1.03},{"fuente":"jkanime","slug":"x","anilistId":11,"nivel":"mal"}""") =
        gzip("""{"version":$version,"matcherVersion":$matcher,"links":[$links]}""")

    private class Dao : AnimeFuenteDao {
        val rows = linkedMapOf<Pair<String, String>, AnimeFuente>()
        override fun all() = rows.values.toList()
        override fun get(fuente: String, slug: String) = rows[fuente to slug]
        override fun appliedIds() = rows.values.mapNotNull { it.anilistId }.distinct()
        override fun countByState() = emptyList<AnimeFuenteDao.StateCount>()
        override fun expireAuto() {}
        override fun deleteAuto(fuente: String, slug: String) { rows.remove(fuente to slug) }
        override fun insertIfAbsent(rows: List<AnimeFuente>) { rows.forEach { this.rows.putIfAbsent(it.fuente to it.slug, it) } }
        override fun put(rows: List<AnimeFuente>) { rows.forEach { this.rows[it.fuente to it.slug] = it } }
    }

    private fun seed(dao: Dao, data: ByteArray, signature: ByteArray = sign(data), matcher: Int = 3, key: String = publicKey) =
        AnilistSeed.seedIfEmpty(dao, matcher, 99L, key) { url -> if (url.endsWith(AnilistSeed.FILE)) data else signature }

    @Test
    fun `con firma valida y el mismo criterio guarda los enlaces como automaticos y aplicados`() {
        val dao = Dao()
        assertEquals(2, seed(dao, file()))
        val row = dao.get("animeav1", "nekopara")!!
        assertEquals(10L, row.anilistId); assertEquals(AnimeFuente.APLICADO, row.estado); assertEquals(AnimeFuente.AUTO, row.origen)
        assertEquals("seguro", row.nivel); assertEquals(99L, row.fecha); assertEquals(1.03, row.puntuacion, 0.0)
        // Sin puntuación en el fichero, 1.0.
        assertEquals(1.0, dao.get("jkanime", "x")!!.puntuacion, 0.0)
    }

    @Test
    fun `una firma de otra clave o un fichero cambiado no se usan`() {
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val data = file()
        assertEquals(0, seed(Dao(), data, sign(data, other.private)))
        assertEquals(0, seed(Dao(), data, "no-es-una-firma".toByteArray()))
        val tampered = file(links = """{"fuente":"animeav1","slug":"nekopara","anilistId":99,"nivel":"seguro"}""")
        assertEquals(0, seed(Dao(), tampered, sign(data)))
    }

    @Test
    fun `con otro criterio o formato no se usa`() {
        assertEquals(0, seed(Dao(), file(matcher = 2)))
        assertEquals(0, seed(Dao(), file(version = 2)))
    }

    @Test
    fun `solo se siembra con la tabla vacia y nunca pisa un enlace`() {
        val dao = Dao()
        dao.put(listOf(AnimeFuente("animeav1", "nekopara", 99, AnimeFuente.APLICADO, AnimeFuente.USUARIO, "manual", 1.0, 1L)))
        assertEquals(0, seed(dao, file()))
        assertEquals(99L, dao.get("animeav1", "nekopara")!!.anilistId)
        assertEquals(1, dao.all().size)
    }

    @Test
    fun `una fila que no vale invalida el fichero entero`() {
        for (bad in listOf(
            """{"fuente":"animeav1","slug":"a","anilistId":0,"nivel":"seguro"}""",
            """{"fuente":"animeav1","slug":"a","anilistId":5,"nivel":"manual"}""",
            """{"fuente":"../x","slug":"a","anilistId":5,"nivel":"seguro"}""",
            """{"fuente":"animeav1","slug":"","anilistId":5,"nivel":"seguro"}""",
            """{"fuente":"animeav1","slug":"a","nivel":"seguro"}"""
        )) {
            val data = file(links = """{"fuente":"jkanime","slug":"bien","anilistId":1,"nivel":"seguro"},$bad""")
            assertEquals(bad, 0, seed(Dao(), data))
        }
    }

    @Test
    fun `sin descarga o con un fichero que no es gzip no hace nada`() {
        assertEquals(0, AnilistSeed.seedIfEmpty(Dao(), 3, 1L, publicKey) { error("sin red") })
        val junk = "no es gzip".toByteArray()
        assertEquals(0, seed(Dao(), junk))
        assertNull(AnilistSeed.rows(junk, sign(junk), publicKey, 3, 1L))
        assertNotNull(AnilistSeed.rows(file(), sign(file()), publicKey, 3, 1L))
        assertTrue(AnilistSeed.FILE.endsWith(".gz"))
    }
}

// ===== AnilistStatsTest.kt =====
/** Las cifras de AniList no mezclan lo hecho con lo que solo se podría hacer. */
class AnilistStatsTest {
    private fun link(fuente: String, slug: String, anime: Long?, estado: String = AnimeFuente.APLICADO) = AnimeFuente(fuente, slug, anime, estado, AnimeFuente.AUTO, "seguro", 1.0, 1L)

    @Test
    fun `tras rehacer las uniones el mismo anime en tres series es una union por hacer y no una hecha`() {
        // Cada ficha es su propia serie (lo que deja «Rehacer las uniones»), y las tres tienen el mismo anime.
        val series = mapOf(1L to listOf("a" to "x"), 2L to listOf("b" to "x"), 3L to listOf("c" to "x"), 4L to listOf("a" to "otra"))
        val links = listOf(link("a", "x", 10), link("b", "x", 10), link("c", "x", 10), link("a", "otra", 20))
        val stats = AnilistStats.of(links, series, catalogoVacio = false)
        assertEquals(0, stats.seriesUnidas)
        assertEquals(0, stats.fichasUnidas)
        assertEquals(1, stats.unionesPorHacer)
        assertEquals(0, stats.unidasConAnimesDistintos)
        assertEquals(4, stats.fichas)
        assertEquals(4, stats.revisadas)
        assertEquals("Uniones hechas: 0 series con varias fichas (0 fichas)", stats.conflictsText().split(" · ")[0])
    }

    @Test
    fun `una vez unidas pasan a ser uniones hechas y ya no quedan por hacer`() {
        val series = mapOf(1L to listOf("a" to "x", "b" to "x", "c" to "x"), 2L to listOf("a" to "otra"))
        val links = listOf(link("a", "x", 10), link("b", "x", 10), link("c", "x", 10), link("a", "otra", 20))
        val stats = AnilistStats.of(links, series, catalogoVacio = false)
        assertEquals(1, stats.seriesUnidas)
        assertEquals(3, stats.fichasUnidas)
        assertEquals(0, stats.unionesPorHacer)
        assertEquals(0, stats.unidasConAnimesDistintos)
    }

    @Test
    fun `una serie unida con fichas de animes distintos se cuenta para corregir`() {
        val series = mapOf(1L to listOf("a" to "black-clover", "b" to "black-clover-2nd-season"))
        val links = listOf(link("a", "black-clover", 97940), link("b", "black-clover-2nd-season", 195604))
        val stats = AnilistStats.of(links, series, catalogoVacio = false)
        assertEquals(1, stats.unidasConAnimesDistintos)
        assertEquals(0, stats.unionesPorHacer)
    }

    @Test
    fun `solo cuentan los enlaces aplicados y las fichas sin fila no estan revisadas`() {
        val series = mapOf(1L to listOf("a" to "x"), 2L to listOf("b" to "x"), 3L to listOf("c" to "nueva"))
        val links = listOf(link("a", "x", 10), link("b", "x", 10, estado = AnimeFuente.PENDIENTE))
        val stats = AnilistStats.of(links, series, catalogoVacio = false)
        assertEquals(0, stats.unionesPorHacer)   // el de «b» está por revisar: no une
        assertEquals(2, stats.revisadas)
        assertEquals(3, stats.fichas)
    }

    @Test
    fun `un enlace dudoso no cuenta como union por hacer`() {
        val series = mapOf(1L to listOf("a" to "x"), 2L to listOf("b" to "x"))
        val links = listOf(link("a", "x", 10), link("b", "x", 10))
        assertEquals(1, AnilistStats.of(links, series, catalogoVacio = false).unionesPorHacer)
        assertEquals(0, AnilistStats.of(links, series, catalogoVacio = false, dudosos = setOf("b" to "x")).unionesPorHacer)
    }

    @Test
    fun `con el catalogo vacio lo dice y con catalogo no`() {
        val series = mapOf(1L to listOf("a" to "x"))
        assertTrue(AnilistStats.of(emptyList(), series, catalogoVacio = true).conflictsText().contains("El catálogo está vacío"))
        assertFalse(AnilistStats.of(emptyList(), series, catalogoVacio = false).conflictsText().contains("vacío"))
        assertEquals(0, AnilistStats.of(emptyList(), emptyMap(), catalogoVacio = true).fichas)
    }
}

// ===== AnilistSyncTest.kt =====
class AnilistSyncTest {
    private class FakeFuentes : AnimeFuenteDao {
        val rows = linkedMapOf<Pair<String, String>, AnimeFuente>()
        override fun all() = rows.values.toList()
        override fun get(fuente: String, slug: String) = rows[fuente to slug]
        override fun appliedIds() = rows.values.filter { it.estado == AnimeFuente.APLICADO }.mapNotNull { it.anilistId }.distinct()
        override fun countByState() = rows.values.groupingBy { it.estado }.eachCount().map { AnimeFuenteDao.StateCount(it.key, it.value) }
        override fun expireAuto() { rows.replaceAll { _, r -> if (r.origen == AnimeFuente.AUTO) r.copy(fecha = 0) else r } }
        override fun deleteAuto(fuente: String, slug: String) { if (rows[fuente to slug]?.origen == AnimeFuente.AUTO) rows.remove(fuente to slug) }
        override fun insertIfAbsent(rows: List<AnimeFuente>) { rows.forEach { this.rows.putIfAbsent(it.fuente to it.slug, it) } }
        override fun put(rows: List<AnimeFuente>) { rows.forEach { this.rows[it.fuente to it.slug] = it } }
    }

    private class FakeFichas : AnimeFichaDao {
        val rows = mutableMapOf<Long, AnimeFicha>()
        override fun put(fichas: List<AnimeFicha>) { fichas.forEach { rows[it.anilistId] = it } }
        override fun get(id: Long) = rows[id]
        override fun freshIds(version: Int, since: Long) = rows.values.filter { it.consulta == version && it.descargada >= since }.map { it.anilistId }
        override fun downloaded(version: Int) = rows.values.map { AnimeFichaDao.Downloaded(it.anilistId, it.descargada) }
        override fun count() = rows.size
        override fun titles() = emptyList<AnimeFichaDao.Titles>()
        override fun idsWithoutEs() = rows.values.filter { it.es == null }.map { it.anilistId }
        override fun setEs(id: Long, es: ByteArray) { rows[id]?.let { rows[id] = it.copy(es = es) } }
        override fun countWithEs() = rows.values.count { it.es != null }
        override fun esMarks() = rows.values.filter { it.es != null }.map { AnimeFichaDao.EsMark(it.anilistId, it.es) }
    }

    private class FakeApi(val catalog: Map<String, List<Candidate>>) : AnilistApi {
        val searches = mutableListOf<List<String>>()
        val fichaRequests = mutableListOf<List<Long>>()
        override suspend fun search(titles: List<String>): List<List<Candidate>> { searches += titles; return titles.map { catalog[it].orEmpty() } }
        override suspend fun fichas(ids: List<Long>): List<AnilistFichaRaw> {
            fichaRequests += ids
            return ids.map { AnilistFichaRaw(it, null, """{"id":$it}""", "R$it", null, null, 2020, "TV", 70, "FINISHED") }
        }
    }

    private val fuentes = FakeFuentes()
    private val fichas = FakeFichas()
    private var clock = 1_000_000_000_000L

    private fun series(id: Long, vararg ficha: Pair<String, String>, priority: Int = 0) = SeriesWork(id, ficha.toList(), priority)
    private fun sync(api: AnilistApi, work: List<SeriesWork>, canRun: () -> Boolean = { true }) =
        AnilistSync(api, fuentes, fichas, { work }, canRun, { clock })

    private val nekopara = Candidate(10, "TV", "Nekopara", null, null)

    @Test
    fun `empareja, escribe una fila por ficha y descarga la ficha de AniList una sola vez`() = runTest {
        val api = FakeApi(mapOf("nekopara" to listOf(nekopara)))
        val report = sync(api, listOf(series(1, "animeav1" to "nekopara", "jkanime" to "nekopara"))).runOnce()

        assertEquals(1, report.applied)
        assertEquals(setOf("animeav1" to "nekopara", "jkanime" to "nekopara"), fuentes.rows.keys)
        assertTrue(fuentes.rows.values.all { it.anilistId == 10L && it.estado == AnimeFuente.APLICADO && it.origen == AnimeFuente.AUTO })
        assertEquals(listOf(listOf(10L)), api.fichaRequests)
        assertEquals("""{"id":10}""", fichas.rows.getValue(10).jsonText())

        sync(api, listOf(series(1, "animeav1" to "nekopara"))).runOnce()
        assertEquals(1, api.fichaRequests.size)
    }

    @Test
    fun `lo del usuario no lo toca el trabajo automatico`() = runTest {
        fuentes.put(listOf(AnimeFuente("animeav1", "nekopara", 99, AnimeFuente.APLICADO, AnimeFuente.USUARIO, "manual", 1.0, 0L)))
        val api = FakeApi(mapOf("nekopara" to listOf(nekopara)))

        sync(api, listOf(series(1, "animeav1" to "nekopara", "jkanime" to "nekopara"))).runOnce()

        assertEquals(99L, fuentes.get("animeav1", "nekopara")?.anilistId)
        assertEquals(AnimeFuente.USUARIO, fuentes.get("animeav1", "nekopara")?.origen)
    }

    @Test
    fun `un dudoso queda pendiente sin descargar y sin resultado se guarda para no repetir`() = runTest {
        val dudoso = Candidate(20, "TV", "Da Capo Fantasy Extra Story", null, null)
        val api = FakeApi(mapOf("da capo" to listOf(dudoso)))
        val work = listOf(series(1, "animeav1" to "da-capo"), series(2, "animeav1" to "fatestay-night"))

        val report = sync(api, work).runOnce()

        assertEquals(1, report.pending)
        assertEquals(1, report.none)
        assertEquals(AnimeFuente.PENDIENTE, fuentes.get("animeav1", "da-capo")?.estado)
        assertEquals(20L, fuentes.get("animeav1", "da-capo")?.anilistId)
        assertNull(fuentes.get("animeav1", "fatestay-night")?.anilistId)
        assertTrue(api.fichaRequests.isEmpty())
        val searchesBefore = api.searches.size

        sync(api, work).runOnce()

        assertEquals(searchesBefore, api.searches.size)
    }

    @Test
    fun `pasados 30 dias se vuelve a intentar lo que no tuvo resultado`() = runTest {
        val api = FakeApi(emptyMap())
        val work = listOf(series(1, "animeav1" to "fatestay-night"))
        sync(api, work).runOnce()
        val first = api.searches.size

        clock += AnilistSync.RETRY_AFTER_MS + 1
        sync(api, work).runOnce()

        assertTrue(api.searches.size > first)
    }

    @Test
    fun `las series con favoritos van primero y la pausa para entre lotes`() = runTest {
        val api = FakeApi(mapOf("nekopara" to listOf(nekopara)))
        // Con ceros: dentro de una misma prioridad el orden es alfabético por título, y así coincide con el numérico.
        val work = (1..12).map { series(it.toLong(), "animeav1" to "relleno-%02d".format(it)) } + series(99, "animeav1" to "nekopara", priority = 2)
        var batches = 0

        val report = sync(api, work, canRun = { batches++ < 2 }).runOnce()

        assertEquals("pausa", report.stopped)
        assertEquals(AnimeFuente.APLICADO, fuentes.get("animeav1", "nekopara")?.estado)
        assertNull(fuentes.get("animeav1", "relleno-12"))
    }

    @Test
    fun `si AniList no responde para sin perder lo ya escrito`() = runTest {
        val failing = object : AnilistApi {
            var calls = 0
            override suspend fun search(titles: List<String>): List<List<Candidate>> {
                if (calls++ >= 1) throw AnilistUnavailable("caido")
                return titles.map { listOf(nekopara) }
            }
            override suspend fun fichas(ids: List<Long>) = emptyList<AnilistFichaRaw>()
        }
        val work = (1..10).map { series(it.toLong(), "animeav1" to "nekopara-$it") }

        val report = sync(failing, work).runOnce()

        assertEquals("caido", report.stopped)
        assertTrue(fuentes.rows.isNotEmpty())
    }

    @Test
    fun `el reintento sin ruido y las series con dos titulos dejan cada resultado en su serie`() = runTest {
        val monster = Candidate(30, "TV", "Monster Strike", null, null)
        val liston = Candidate(31, "TV", "Kyouran Reijou Nia Liston", null, null)
        // La primera serie solo aparece al quitar «tv reproduction»; la segunda tiene dos fichas con dos slugs que dan lo mismo.
        val api = FakeApi(mapOf("monster strike" to listOf(monster), "kyouran reijou nia liston" to listOf(liston)))
        val work = listOf(
            series(1, "animeav1" to "monster-strike-tv-reproduction"),
            series(2, "animeav1" to "kyouran-reijou-nia-liston", "jkanime" to "kyouran-reijou-nia-liston-latino")
        )

        val report = sync(api, work).runOnce()

        assertEquals(30L, fuentes.get("animeav1", "monster-strike-tv-reproduction")?.anilistId)
        assertEquals(31L, fuentes.get("animeav1", "kyouran-reijou-nia-liston")?.anilistId)
        assertEquals(31L, fuentes.get("jkanime", "kyouran-reijou-nia-liston-latino")?.anilistId)
        assertEquals(2, report.series)
    }

    @Test
    fun `sin red o con el reproductor abierto no se hace ni el trabajo previo`() = runTest {
        var worked = false
        val api = FakeApi(emptyMap())

        val report = AnilistSync(api, fuentes, fichas, { worked = true; emptyList() }, { false }, { clock }).runOnce()

        assertEquals("pausa", report.stopped)
        assertTrue(!worked && api.searches.isEmpty())
    }

    @Test
    fun `una fila del usuario cuenta como decision reciente y no vuelve a la cola`() = runTest {
        fuentes.put(listOf(AnimeFuente("animeav1", "x", null, AnimeFuente.SIN_RESULTADO, AnimeFuente.USUARIO, "manual", 1.0, 0L)))
        val api = FakeApi(emptyMap())

        sync(api, listOf(series(1, "animeav1" to "x"))).runOnce()

        assertTrue(api.searches.isEmpty())
    }

    @Test
    fun `con un criterio nuevo los enlaces automaticos se vuelven a emparejar y los del usuario no`() = runTest {
        sync(FakeApi(mapOf("nekopara" to listOf(nekopara))), listOf(series(1, "animeav1" to "nekopara"))).runOnce()
        assertEquals(AnimeFuente.APLICADO, fuentes.get("animeav1", "nekopara")?.estado)

        // El criterio nuevo ya no da por bueno ese candidato: tras caducar las automáticas se rehace y queda sin resultado.
        fuentes.expireAuto()
        sync(FakeApi(emptyMap()), listOf(series(1, "animeav1" to "nekopara"))).runOnce()
        assertEquals(AnimeFuente.SIN_RESULTADO, fuentes.get("animeav1", "nekopara")?.estado)

        fuentes.put(listOf(AnimeFuente("jkanime", "x", 5, AnimeFuente.APLICADO, AnimeFuente.USUARIO, "manual", 1.0, 0L)))
        fuentes.expireAuto()
        sync(FakeApi(emptyMap()), listOf(series(2, "jkanime" to "x"))).runOnce()
        assertEquals(5L, fuentes.get("jkanime", "x")?.anilistId)
    }

    @Test
    fun `cada ficha se empareja por su cuenta y no hereda el anime de las demas de su serie`() = runTest {
        val first = Candidate(97940, "TV", "Black Clover", null, null)
        val second = Candidate(195604, "TV", "Black Clover 2nd Season", null, null)
        val api = FakeApi(mapOf("black clover" to listOf(first, second), "black clover 2nd season" to listOf(second, first)))
        // Una serie mezclada: las dos fichas de la fuente son series distintas en AniList.
        val mixed = series(1, "jkanime" to "black-clover", "jkanime" to "black-clover-2nd-season")
        sync(api, listOf(mixed).perFicha()).runOnce()
        assertEquals(97940L, fuentes.rows["jkanime" to "black-clover"]?.anilistId)
        assertEquals(195604L, fuentes.rows["jkanime" to "black-clover-2nd-season"]?.anilistId)
    }

    @Test
    fun `un titulo que varias fuentes escriben igual se pide a AniList una sola vez y la version latino se busca como la base`() = runTest {
        val api = FakeApi(mapOf("nekopara" to listOf(nekopara)))
        val work = listOf(
            series(1, "jkanime" to "nekopara"), series(2, "animeflv" to "nekopara"),
            series(3, "animegratis" to "nekopara-latino"), series(4, "monoschinos" to "nekopara")
        )
        sync(api, work).runOnce()
        assertEquals(1, api.searches.flatten().count { it == "nekopara" })
        assertEquals(listOf(10L), work.flatMap { it.fichas }.map { fuentes.rows[it]?.anilistId }.distinct().map { it ?: -1L })
        assertEquals(false, api.searches.flatten().any { it.contains("latino") })
    }

    @Test
    fun `perFicha conserva la prioridad de la serie y el año de cada ficha`() {
        val work = listOf(SeriesWork(7, listOf("a" to "x", "b" to "y"), 2, listOf(2010, null))).perFicha()
        assertEquals(2, work.size)
        assertEquals(listOf(2, 2), work.map { it.priority })
        assertEquals(listOf(listOf<Int?>(2010), listOf<Int?>(null)), work.map { it.years })
        assertEquals(listOf(1, 1), work.map { it.fichas.size })
    }
}

// ===== AnilistWebFichaTest.kt =====
/** El emparejado con AniList usando lo que dio la web de cada ficha (`WebFicha`): el id de MyAnimeList (`idMal`) y los otros nombres. */
class AnilistWebFichaTest {
    // ---- cliente: Media(idMal)

    @Test
    fun `byMal pide un Page por id y devuelve el anime de cada id que AniList conoce`() = runTest {
        var sent = ""
        val client = AnilistClient { body ->
            sent = body
            """{"data":{"m0":{"media":[{"id":101,"format":"TV","title":{"romaji":"Black Clover","english":null,"native":null},"synonyms":[],"seasonYear":2017}]},"m1":{"media":[]}}}"""
        }

        val found = client.byMal(listOf(34572L, 99999999L))

        assertEquals(setOf(34572L), found.keys)
        assertEquals(101L, found.getValue(34572L).id)
        assertEquals(2017, found.getValue(34572L).year)
        // Page y no Media: un id desconocido da una lista vacía, no un error que estropee el lote.
        assertTrue(sent.contains("m0: Page") && sent.contains("m1: Page") && sent.contains("media(idMal: \$m0, type: ANIME)"))
        assertTrue(sent.contains("\"m0\":34572") && sent.contains("\"m1\":99999999"))
        assertEquals(emptyMap<Long, Candidate>(), AnilistClient { "{}" }.byMal(emptyList()))
        assertEquals(emptyMap<Long, Candidate>(), AnilistClient { """{"errors":[{"message":"boom"}]}""" }.byMal(listOf(1L)))
    }

    @Test
    fun `si AniList rechaza el lote de ids se pide de uno en uno`() = runTest {
        val bodies = mutableListOf<String>()
        val client = AnilistClient { body ->
            bodies += body
            if ("m1" in body) "{}" else """{"data":{"m0":{"media":[{"id":7,"format":"TV","title":{"romaji":"X"},"synonyms":[]}]}}}"""
        }
        val found = client.byMal(listOf(5L, 6L))
        assertEquals(3, bodies.size)
        assertEquals(setOf(5L, 6L), found.keys) // el segundo sale de su petición suelta (m0 de nuevo), el primero también
    }

    // ---- criterio: otros nombres de la web

    private val marriage = Candidate(50, "TV", "Watashi no Shiawase na Kekkon", "My Happy Marriage", null, emptyList(), 2023)

    @Test
    fun `un nombre alternativo de la web casa cuando el titulo del slug no encuentra nada`() {
        // El slug está en español: la búsqueda por él no trae nada, pero la web daba «My Happy Marriage».
        val without = AnilistMatcher.decide(listOf("mi feliz matrimonio"), listOf(emptyList()))
        assertEquals(Level.NINGUNO, without.level)

        val with = AnilistMatcher.decide(listOf("mi feliz matrimonio"), listOf(emptyList()), 2023, listOf("My Happy Marriage"), listOf(listOf(marriage)))
        assertEquals(50L, with.candidate?.id)
        assertEquals(Level.PROBABLE, with.level) // con el descuento del nombre alternativo: aplicado, pero no «seguro»
        assertTrue("${with.score}", with.score in 0.97..0.99)
    }

    @Test
    fun `el nombre alternativo no cambia la temporada que dice el slug`() {
        val first = Candidate(1, "TV", "Black Clover", null, null, emptyList(), 2017)
        // «Black Clover» como sinónimo de la ficha de la 2.ª temporada no la enlaza con la 1.ª.
        val match = AnilistMatcher.decide(listOf("black clover 2nd season"), listOf(listOf(first)), null, listOf("Black Clover"), listOf(listOf(first)))
        assertEquals(Level.NINGUNO, match.level)
    }

    @Test
    fun `un nombre alternativo con otro ano de estreno queda por revisar`() {
        val old = marriage.copy(year = 1999)
        val match = AnilistMatcher.decide(listOf("mi feliz matrimonio"), listOf(emptyList()), 2023, listOf("My Happy Marriage"), listOf(listOf(old)))
        assertEquals(Level.DUDOSO, match.level)
    }

    @Test
    fun `sin alternativas el criterio es el de siempre`() {
        val nekopara = Candidate(10, "TV", "Nekopara", null, null)
        val a = AnilistMatcher.decide(listOf("nekopara"), listOf(listOf(nekopara)))
        val b = AnilistMatcher.decide(listOf("nekopara"), listOf(listOf(nekopara)), null, emptyList(), emptyList())
        assertEquals(Level.SEGURO, a.level)
        assertEquals(a.level, b.level)
        assertEquals(a.score, b.score, 0.0)
    }

    // ---- lo guardado de la web

    private class FakeWebDao(val rows: MutableList<WebFicha> = mutableListOf()) : WebFichaDao {
        override fun put(ficha: WebFicha) { rows += ficha }
        override fun get(fuente: String, slug: String) = rows.firstOrNull { it.fuente == fuente && it.slug == slug }
        override fun byName(norm: String, raw: String, limit: Int) = emptyList<WebFicha>()
        override fun page(limit: Int, offset: Int) = rows.drop(offset).take(limit)
        override fun count() = rows.size
        override fun countWithMal() = rows.count { it.malId != null }
        override fun countByFuente() = rows.groupingBy { it.fuente }.eachCount().map { FuenteCount(it.key, it.value) }
    }

    /** Una pista a mano: el id de MyAnimeList y los otros nombres (el primero, el inglés), guardada en el instante [at]. */
    private fun WebHint(malId: Long?, names: List<String>, at: Long) =
        FichaData(JSONObject().put("idMal", malId ?: JSONObject.NULL).put("title", JSONObject().put("english", names.firstOrNull() ?: JSONObject.NULL)).put("synonyms", org.json.JSONArray(names.drop(1))), "test", "test", at)

    private fun stored(fuente: String, slug: String, json: String, at: Long = 5_000L) = WebFicha.of(fuente, slug, JSONObject(json), at)

    @Test
    fun `la pista de una ficha guardada lleva el id de MyAnimeList y los nombres con el ingles primero`() {
        val hint = checkNotNull(WebHints.of(stored("jkanime", "x", """{"idMal":34572,"title":{"romaji":"Black Clover (TV)","english":"Black Clover"},"synonyms":["Burakku Kuroba"," "]}""")))
        assertEquals(34572L, hint.idMal)
        assertEquals(listOf("Black Clover", "Black Clover (TV)", "Burakku Kuroba"), hint.alternativas)
        assertEquals("jkanime" to "x", hint.fuente to hint.slug)
        assertEquals(5_000L, hint.at)
        // Sin id ni nombres no hay pista.
        assertNull(WebHints.of(stored("verani", "y", """{"description":"solo texto"}""")))
    }

    @Test
    fun `se cargan todas las pistas aunque pasen de un tramo`() {
        val dao = FakeWebDao()
        repeat(450) { dao.put(stored("jkanime", "s$it", """{"idMal":${it + 1},"title":{"romaji":"T$it"}}""")) }
        dao.put(stored("verani", "sin-nada", """{"format":"TV"}"""))
        val hints = WebHints.load(dao)
        assertEquals(450, hints.size)
        assertEquals(450L, hints.getValue("jkanime" to "s449").idMal)
    }

    // ---- emparejado

    private class FakeFuentes : AnimeFuenteDao {
        val rows = linkedMapOf<Pair<String, String>, AnimeFuente>()
        override fun all() = rows.values.toList()
        override fun get(fuente: String, slug: String) = rows[fuente to slug]
        override fun appliedIds() = rows.values.filter { it.estado == AnimeFuente.APLICADO }.mapNotNull { it.anilistId }.distinct()
        override fun countByState() = rows.values.groupingBy { it.estado }.eachCount().map { AnimeFuenteDao.StateCount(it.key, it.value) }
        override fun expireAuto() { rows.replaceAll { _, r -> if (r.origen == AnimeFuente.AUTO) r.copy(fecha = 0) else r } }
        override fun deleteAuto(fuente: String, slug: String) { if (rows[fuente to slug]?.origen == AnimeFuente.AUTO) rows.remove(fuente to slug) }
        override fun insertIfAbsent(rows: List<AnimeFuente>) { rows.forEach { this.rows.putIfAbsent(it.fuente to it.slug, it) } }
        override fun put(rows: List<AnimeFuente>) { rows.forEach { this.rows[it.fuente to it.slug] = it } }
    }

    private class FakeFichas : AnimeFichaDao {
        val rows = mutableMapOf<Long, AnimeFicha>()
        override fun put(fichas: List<AnimeFicha>) { fichas.forEach { rows[it.anilistId] = it } }
        override fun get(id: Long) = rows[id]
        override fun freshIds(version: Int, since: Long) = rows.values.filter { it.consulta == version && it.descargada >= since }.map { it.anilistId }
        override fun downloaded(version: Int) = rows.values.map { AnimeFichaDao.Downloaded(it.anilistId, it.descargada) }
        override fun count() = rows.size
        override fun titles() = emptyList<AnimeFichaDao.Titles>()
        override fun idsWithoutEs() = rows.values.filter { it.es == null }.map { it.anilistId }
        override fun setEs(id: Long, es: ByteArray) { rows[id]?.let { rows[id] = it.copy(es = es) } }
        override fun countWithEs() = rows.values.count { it.es != null }
        override fun esMarks() = rows.values.filter { it.es != null }.map { AnimeFichaDao.EsMark(it.anilistId, it.es) }
    }

    private class FakeApi(val catalog: Map<String, List<Candidate>> = emptyMap(), val mal: Map<Long, Candidate> = emptyMap()) : AnilistApi {
        val searches = mutableListOf<List<String>>()
        val malRequests = mutableListOf<List<Long>>()
        override suspend fun search(titles: List<String>): List<List<Candidate>> { searches += titles; return titles.map { catalog[it].orEmpty() } }
        override suspend fun fichas(ids: List<Long>) = ids.map { AnilistFichaRaw(it, null, """{"id":$it}""", "R$it", null, null, 2020, "TV", 70, "FINISHED") }
        override suspend fun byMal(ids: List<Long>): Map<Long, Candidate> { malRequests += ids; return mal.filterKeys { it in ids } }
    }

    private val fuentes = FakeFuentes()
    private val fichas = FakeFichas()
    private var clock = 1_000_000_000_000L

    private fun series(id: Long, fuente: String, slug: String, priority: Int = 0) = SeriesWork(id, listOf(fuente to slug), priority)
    private fun sync(api: AnilistApi, work: List<SeriesWork>, hints: Map<Pair<String, String>, FichaData>) =
        AnilistSync(api, fuentes, fichas, { work }, { true }, { clock }, hints = { hints })

    @Test
    fun `una ficha con idMal se enlaza por ese id sin buscar por el nombre`() = runTest {
        val api = FakeApi(mal = mapOf(34572L to Candidate(77, "TV", "Black Clover", null, null, emptyList(), 2017)))
        val hints = mapOf(("jkanime" to "un-slug-que-no-se-parece") to WebHint(34572L, listOf("Black Clover"), 5_000L))

        val report = sync(api, listOf(series(1, "jkanime", "un-slug-que-no-se-parece")), hints).runOnce()

        assertEquals(1, report.applied)
        val row = fuentes.rows.getValue("jkanime" to "un-slug-que-no-se-parece")
        assertEquals(77L, row.anilistId)
        assertEquals(AnimeFuente.APLICADO, row.estado)
        assertEquals(AnimeFuente.AUTO, row.origen)
        assertEquals(AnimeFuente.NIVEL_MAL, row.nivel)
        assertEquals(1.0, row.puntuacion, 0.0)
        assertEquals(listOf(listOf(34572L)), api.malRequests)
        assertTrue("no debe buscar por nombre: ${api.searches}", api.searches.isEmpty())
        assertNotNull(fichas.rows[77L]) // y se descarga su ficha de AniList como siempre
    }

    @Test
    fun `una ficha de pelicula no se enlaza por idMal con la serie de TV aunque la web repita su id`() = runTest {
        val api = FakeApi(mal = mapOf(34572L to Candidate(77, "TV", "Black Clover", null, null, emptyList(), 2017)))
        val hints = mapOf(("jkanime" to "black-clover-movie") to WebHint(34572L, listOf("Black Clover"), 5_000L))

        sync(api, listOf(series(1, "jkanime", "black-clover-movie")), hints).runOnce()

        // No hay enlace por idMal: sigue por el nombre (y allí el choque de formato tampoco lo propone).
        assertTrue(fuentes.rows["jkanime" to "black-clover-movie"]?.nivel != AnimeFuente.NIVEL_MAL)
        assertTrue(fuentes.rows["jkanime" to "black-clover-movie"]?.estado != AnimeFuente.APLICADO)
    }

    @Test
    fun `si AniList no conoce ese id se empareja por el nombre como siempre`() = runTest {
        val nekopara = Candidate(10, "TV", "Nekopara", null, null)
        val api = FakeApi(catalog = mapOf("nekopara" to listOf(nekopara)))
        val hints = mapOf(("jkanime" to "nekopara") to WebHint(424242L, emptyList(), 5_000L))

        sync(api, listOf(series(1, "jkanime", "nekopara")), hints).runOnce()

        val row = fuentes.rows.getValue("jkanime" to "nekopara")
        assertEquals(10L, row.anilistId)
        assertEquals("seguro", row.nivel)
    }

    @Test
    fun `los otros nombres de la web se buscan y enlazan una ficha con slug en espanol`() = runTest {
        val api = FakeApi(catalog = mapOf("my happy marriage" to listOf(Candidate(50, "TV", "Watashi no Shiawase na Kekkon", "My Happy Marriage", null, emptyList(), 2023))))
        val hints = mapOf(("monoschinos" to "mi-feliz-matrimonio") to WebHint(null, listOf("My Happy Marriage", "Mi feliz matrimonio"), 5_000L))

        // Con el año de la ficha (2023) cuadrando con el del anime hay una prueba además del nombre.
        val report = sync(api, listOf(SeriesWork(1, listOf("monoschinos" to "mi-feliz-matrimonio"), 0, listOf(2023))), hints).runOnce()

        assertEquals(1, report.applied)
        val row = fuentes.rows.getValue("monoschinos" to "mi-feliz-matrimonio")
        assertEquals(50L, row.anilistId)
        assertEquals(AnimeFuente.APLICADO, row.estado)
        assertEquals(AnimeFuente.NIVEL_WEB, row.nivel) // el slug no se parece al anime: se enlazó por el nombre de la web
        // Se buscó por el título de la ficha y por el nombre de la web (el que ya es el título no se repite).
        assertTrue(api.searches.flatten().containsAll(listOf("mi feliz matrimonio", "my happy marriage")))
    }

    @Test
    fun `una decision anterior a la pista se vuelve a mirar una sola vez`() = runTest {
        val old = AnimeFuente("jkanime", "raro", null, AnimeFuente.SIN_RESULTADO, AnimeFuente.AUTO, "ninguno", 0.0, 1_000L)
        fuentes.put(listOf(old))
        val api = FakeApi(mal = mapOf(5L to Candidate(9, "TV", "Raro", null, null)))
        val hints = mapOf(("jkanime" to "raro") to WebHint(5L, emptyList(), 2_000L)) // la pista es posterior a la decisión

        sync(api, listOf(series(1, "jkanime", "raro")), hints).runOnce()
        assertEquals(9L, fuentes.rows.getValue("jkanime" to "raro").anilistId)

        // La nueva decisión es posterior a la pista: no se repite.
        val before = api.malRequests.size
        sync(api, listOf(series(1, "jkanime", "raro")), hints).runOnce()
        assertEquals(before, api.malRequests.size)
    }

    @Test
    fun `una decision posterior a la pista no se toca y lo del usuario tampoco`() = runTest {
        fuentes.put(
            listOf(
                AnimeFuente("jkanime", "reciente", null, AnimeFuente.SIN_RESULTADO, AnimeFuente.AUTO, "ninguno", 0.0, clock),
                AnimeFuente("jkanime", "mio", 99, AnimeFuente.APLICADO, AnimeFuente.USUARIO, "manual", 1.0, 0L)
            )
        )
        val api = FakeApi(mal = mapOf(5L to Candidate(9, "TV", "Raro", null, null), 6L to Candidate(8, "TV", "Otro", null, null)))
        val hints = mapOf(("jkanime" to "reciente") to WebHint(5L, emptyList(), clock - 10), ("jkanime" to "mio") to WebHint(6L, emptyList(), clock + 10))

        sync(api, listOf(series(1, "jkanime", "reciente"), series(2, "jkanime", "mio")), hints).runOnce()

        assertNull(fuentes.rows.getValue("jkanime" to "reciente").anilistId)
        assertEquals(99L, fuentes.rows.getValue("jkanime" to "mio").anilistId)
        assertTrue(api.malRequests.isEmpty())
    }

    @Test
    fun `un enlace por idMal corrige uno automatico hecho por el nombre`() = runTest {
        val bad = AnimeFuente("animegratis", "black-clover", 1, AnimeFuente.APLICADO, AnimeFuente.AUTO, "seguro", 1.0, 1_000L)
        fuentes.put(listOf(bad))
        val api = FakeApi(mal = mapOf(34572L to Candidate(2, "TV", "Black Clover", null, null)))
        val hints = mapOf(("animegratis" to "black-clover") to WebHint(34572L, emptyList(), 9_000L))

        sync(api, listOf(series(1, "animegratis", "black-clover")), hints).runOnce()

        val row = fuentes.rows.getValue("animegratis" to "black-clover")
        assertEquals(2L, row.anilistId)
        assertEquals(AnimeFuente.NIVEL_MAL, row.nivel)
    }

    @Test
    fun `una busqueda que no encuentra no deshace un enlace ya aplicado`() = runTest {
        val applied = AnimeFuente("animegratis", "naruto", 5, AnimeFuente.APLICADO, AnimeFuente.AUTO, "seguro", 1.0, 1_000L)
        fuentes.put(listOf(applied))
        // La pista trae un id que AniList no conoce y los nombres no dan nada: la búsqueda vuelve vacía.
        val api = FakeApi()
        val hints = mapOf(("animegratis" to "naruto") to WebHint(424242L, listOf("Algo Raro"), 9_000L))

        val report = sync(api, listOf(series(1, "animegratis", "naruto")), hints).runOnce()

        assertEquals("lo conservado no cuenta como enlace nuevo", 0, report.applied + report.pending + report.none)
        val row = fuentes.rows.getValue("animegratis" to "naruto")
        assertEquals(5L, row.anilistId)
        assertEquals(AnimeFuente.APLICADO, row.estado)
        assertEquals("seguro", row.nivel)
        assertEquals(clock, row.fecha) // anotada para no volver a mirarla en cada pasada
        val searches = api.searches.size
        sync(api, listOf(series(1, "animegratis", "naruto")), hints).runOnce()
        assertEquals(searches, api.searches.size)
    }

    @Test
    fun `una ficha ya enlazada no se vuelve a mirar por tener solo nombres alternativos`() = runTest {
        fuentes.put(listOf(AnimeFuente("animegratis", "naruto", 5, AnimeFuente.APLICADO, AnimeFuente.AUTO, "seguro", 1.0, 1_000L)))
        val api = FakeApi()
        sync(api, listOf(series(1, "animegratis", "naruto")), mapOf(("animegratis" to "naruto") to WebHint(null, listOf("Naruto Shippuden"), 9_000L))).runOnce()
        assertTrue(api.searches.isEmpty() && api.malRequests.isEmpty())
        assertEquals(1_000L, fuentes.rows.getValue("animegratis" to "naruto").fecha)
    }

    @Test
    fun `un idMal que no cuadra con el ano o con el nombre no se da por bueno`() = runTest {
        val oldAnime = Candidate(30, "TV", "Fruits Basket", null, null, emptyList(), 2001)
        val api = FakeApi(mal = mapOf(1L to oldAnime))
        // Año de la ficha 2019 frente al 2001 del anime de ese id: la web repite un id equivocado.
        val work = SeriesWork(1, listOf("jkanime" to "fruits-basket-2019"), 0, listOf(2019))
        sync(api, listOf(work), mapOf(("jkanime" to "fruits-basket-2019") to WebHint(1L, listOf("Fruits Basket (2019)"), 5_000L))).runOnce()
        assertTrue(fuentes.rows.values.none { it.nivel == AnimeFuente.NIVEL_MAL })
    }

    @Test
    fun `dos fichas con el mismo idMal y distinta temporada no se enlazan al mismo anime`() = runTest {
        val first = Candidate(40, "TV", "Overlord", null, null, emptyList(), 2015)
        val api = FakeApi(mal = mapOf(29803L to first))
        val hints = mapOf(
            ("jkanime" to "overlord") to WebHint(29803L, listOf("Overlord"), 5_000L),
            // La web da a la secuela el mismo id que a la primera temporada.
            ("jkanime" to "overlord-2nd-season") to WebHint(29803L, listOf("Overlord II"), 5_000L)
        )
        sync(api, listOf(series(1, "jkanime", "overlord"), series(2, "jkanime", "overlord-2nd-season")), hints).runOnce()
        assertEquals(AnimeFuente.NIVEL_MAL, fuentes.rows.getValue("jkanime" to "overlord").nivel)
        val second = fuentes.rows["jkanime" to "overlord-2nd-season"]
        assertTrue("la secuela no debe heredar el anime de la primera: $second", second?.nivel != AnimeFuente.NIVEL_MAL && second?.anilistId != 40L)
    }

    @Test
    fun `en un lote con reintento y nombres alternativos cada serie recibe su anime`() = runTest {
        val nekopara = Candidate(10, "TV", "Nekopara", null, null)
        val marriage = Candidate(50, "TV", "Watashi no Shiawase na Kekkon", "My Happy Marriage", null, emptyList(), 2023)
        // «nekopara tv» no existe tal cual: el reintento sin ruido («nekopara») sí.
        val api = FakeApi(catalog = mapOf("nekopara" to listOf(nekopara), "my happy marriage" to listOf(marriage)))
        val work = listOf(series(1, "jkanime", "mi-feliz-matrimonio"), series(2, "jkanime", "nekopara-tv"), series(3, "jkanime", "sin-nada-que-ver"))
        val hints = mapOf(("jkanime" to "mi-feliz-matrimonio") to WebHint(null, listOf("My Happy Marriage"), 5_000L))

        sync(api, work, hints).runOnce()

        assertEquals(50L, fuentes.rows.getValue("jkanime" to "mi-feliz-matrimonio").anilistId)
        assertEquals(10L, fuentes.rows.getValue("jkanime" to "nekopara-tv").anilistId)
        assertNull(fuentes.rows.getValue("jkanime" to "sin-nada-que-ver").anilistId)
    }

    @Test
    fun `el idMal se rechaza por separado por el ano y por el nombre`() = runTest {
        val old = Candidate(30, "TV", "Fruits Basket", null, null, emptyList(), 2001)
        // Solo el año falla: el slug y el nombre de la web casan con el anime, pero la ficha es de 2019 y el anime de ese id es de 2001.
        val byYear = SeriesWork(1, listOf("jkanime" to "fruits-basket"), 0, listOf(2019))
        sync(FakeApi(mal = mapOf(1L to old)), listOf(byYear), mapOf(("jkanime" to "fruits-basket") to WebHint(1L, listOf("Fruits Basket"), 5_000L))).runOnce()
        val a = fuentes.rows.getValue("jkanime" to "fruits-basket")
        assertTrue("por el año no debe enlazar por idMal: $a", a.nivel != AnimeFuente.NIVEL_MAL && a.estado == AnimeFuente.SIN_RESULTADO)

        // Solo el nombre falla: el año cuadra, pero ni el slug ni los nombres de la web se parecen al anime de ese id.
        val recent = old.copy(id = 31, year = 2019)
        val byName = SeriesWork(2, listOf("jkanime" to "otra-cosa-distinta"), 0, listOf(2019))
        sync(FakeApi(mal = mapOf(2L to recent)), listOf(byName), mapOf(("jkanime" to "otra-cosa-distinta") to WebHint(2L, listOf("Nada que ver"), 5_000L))).runOnce()
        val b = fuentes.rows.getValue("jkanime" to "otra-cosa-distinta")
        assertTrue("por el nombre no debe enlazar por idMal: $b", b.nivel != AnimeFuente.NIVEL_MAL && b.anilistId == null)

        // Y con año y nombre que cuadran sí se enlaza (la comprobación no rechaza de más).
        val good = SeriesWork(3, listOf("jkanime" to "otra-cosa-distinta-2"), 0, listOf(2019))
        sync(FakeApi(mal = mapOf(2L to recent)), listOf(good), mapOf(("jkanime" to "otra-cosa-distinta-2") to WebHint(2L, listOf("Fruits Basket"), 5_000L))).runOnce()
        assertEquals(31L, fuentes.rows.getValue("jkanime" to "otra-cosa-distinta-2").anilistId)
    }

    @Test
    fun `los resultados de cada serie se leen con su posicion cuando una anterior tiene dos titulos y alternativas`() = runTest {
        fun cand(id: Long, name: String) = Candidate(id, "TV", name, null, null, emptyList(), 2020)
        val gamma = cand(60, "Gamma Uno")
        val alpha = cand(61, "Alpha Beta")
        val delta = cand(62, "Delta Epsilon")
        val api = FakeApi(catalog = mapOf("gamma uno" to listOf(gamma), "gamma dos" to listOf(gamma), "alpha beta" to listOf(alpha), "delta epsilon" to listOf(delta)))
        // La serie 0 tiene dos fichas (dos títulos): la serie 1 empieza en la posición 2, no en la 1. Las series 1 y 2 tienen dos alternativas cada una y solo la segunda encuentra.
        val work = listOf(
            SeriesWork(1, listOf("jkanime" to "gamma-uno", "monoschinos" to "gamma-dos"), 0),
            series(2, "jkanime", "slug-alfa"),
            series(3, "jkanime", "slug-delta")
        )
        val hints = mapOf(
            ("jkanime" to "slug-alfa") to WebHint(null, listOf("Sin Resultado Uno", "Alpha Beta"), 5_000L),
            ("jkanime" to "slug-delta") to WebHint(null, listOf("Tampoco Esta", "Delta Epsilon"), 5_000L)
        )

        sync(api, work, hints).runOnce()

        assertEquals(60L, fuentes.rows.getValue("jkanime" to "gamma-uno").anilistId)
        assertEquals(61L, fuentes.rows.getValue("jkanime" to "slug-alfa").anilistId)
        assertEquals(62L, fuentes.rows.getValue("jkanime" to "slug-delta").anilistId)
    }

    @Test
    fun `el comprobador de enlaces no deshace los hechos por idMal`() {
        val titles = mapOf(2L to AnimeFichaDao.Titles(2L, "totally different title", null))
        val byMal = AnimeFuente("jkanime", "slug-en-espanol", 2, AnimeFuente.APLICADO, AnimeFuente.AUTO, AnimeFuente.NIVEL_MAL, 1.0, 1L)
        val byName = byMal.copy(nivel = "seguro")
        val full = AnilistFichaRaw(2, null, """{"id":2,"synonyms":[]}""", "totally different title", null, null, 2020, "TV", 70, "FINISHED").toFicha(1L, null)
        assertTrue(AnilistLinkCheck.inconsistent(listOf(byMal), titles) { full }.isEmpty())
        assertTrue(AnilistLinkCheck.inconsistent(listOf(byMal.copy(nivel = AnimeFuente.NIVEL_WEB)), titles) { full }.isEmpty())
        // Con el mismo desajuste, el enlace por nombre sí se rechaza: es la comprobación de siempre.
        assertEquals(listOf(byName), AnilistLinkCheck.inconsistent(listOf(byName), titles) { full })
    }

    @Test
    fun `el comprobador de enlaces no quita un enlace por nombre si la web y AniList comparten idMal`() {
        val titles = mapOf(2L to AnimeFichaDao.Titles(2L, "totally different title", null))
        val auto = AnimeFuente("verani", "slug-corto", 2, AnimeFuente.APLICADO, AnimeFuente.AUTO, "seguro", 1.03, 1L)
        val full = AnilistFichaRaw(2, 77L, """{"id":2,"synonyms":[]}""", "totally different title", null, null, 2020, "TV", 70, "FINISHED").toFicha(1L, null)
        // Mismo idMal en la ficha web y en AniList: se queda aunque el título no se parezca.
        assertTrue(AnilistLinkCheck.inconsistent(listOf(auto), titles, { _, _ -> 77L }) { full }.isEmpty())
        // idMal distinto o sin dato en la web: la comprobación de título sigue valiendo.
        assertEquals(listOf(auto), AnilistLinkCheck.inconsistent(listOf(auto), titles, { _, _ -> 78L }) { full })
        assertEquals(listOf(auto), AnilistLinkCheck.inconsistent(listOf(auto), titles, { _, _ -> null }) { full })
        assertTrue(AnilistLinkCheck.sameMal(77L, full))
        assertFalse(AnilistLinkCheck.sameMal(null, full))
    }
}

// ===== AnimeIdsTest.kt =====
class AnimeIdsTest {

    private class FakeDao : AnimeIdentityDao {
        val rows = mutableMapOf<String, AnimeIdentity>()
        override fun findBySlug(slug: String) = rows[slug]
        override fun maxId() = rows.values.maxOfOrNull { it.internalId }
        override fun insert(identity: AnimeIdentity) {
            rows.putIfAbsent(identity.slug, identity)
        }
    }

    @Test
    fun sameSlugAlwaysGetsTheSameId() {
        val dao = FakeDao()
        val first = AnimeIds.internalIdFor(dao, "one-piece", "jkanime")
        assertEquals(first, AnimeIds.internalIdFor(dao, "one-piece", "animeflv"))
    }

    @Test
    fun newIdsStartAtTheBaseAndNeverReuseOldOnes() {
        val dao = FakeDao()
        dao.insert(AnimeIdentity(1234, "legacy", "animeav1"))
        assertEquals(AnimeIds.NEW_ID_BASE, AnimeIds.internalIdFor(dao, "nuevo", "jkanime"))
        assertEquals(AnimeIds.NEW_ID_BASE + 1, AnimeIds.internalIdFor(dao, "otro", "jkanime"))
        assertEquals(1234, AnimeIds.internalIdFor(dao, "legacy", "jkanime"))
    }
}

// ===== AnimeotecaHttpTest.kt =====
/** El camino completo de [AnimeotecaHttp]: la petición que sale, lo que se lee de una página real y qué hace con cada código. */
class AnimeotecaHttpTest {
    private val codes = ArrayDeque<Int>()
    private val urls = mutableListOf<String>()
    private var userAgent: String? = null
    private val page = javaClass.getResource("/animeoteca-101922.html")!!.readText()

    @Before
    fun setUp() {
        // HostGate es global: el estado de una prueba no debe pasar a la siguiente.
        HostGate.storage = null
        HostGate.reset()
        HostGate.onDown = {}
        HostGate.isNetworkUp = { true }
        AnimeotecaHttp.spacingMs = 0
        AnimeotecaHttp.retryWaitMs = 0
        AnilistHttp.clientOverride = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            urls += chain.request().url.toString()
            userAgent = chain.request().header("User-Agent")
            val code = codes.removeFirstOrNull() ?: 200
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x").body(page.toResponseBody()).build()
        }).build()
    }

    @After
    fun tearDown() { AnilistHttp.clientOverride = null }

    @Test
    fun `pide solo la pagina del anime con el User-Agent de la app y lee su texto`() = runTest {
        val text = AnimeotecaHttp.text(101922)!!
        assertEquals(listOf("https://animeoteca.com/anime/101922"), urls)
        assertTrue(userAgent.orEmpty().startsWith("Mozilla/5.0"))
        assertTrue(text.sinopsis!!.startsWith("Es el Periodo Taisho"))
        assertTrue(urls.none { "/api/" in it })
    }

    @Test
    fun `un 404 es sin datos y una caida que no cede lanza sin marcar nada`() = runTest {
        codes += 404
        assertNull(AnimeotecaHttp.text(1))
        codes += listOf(503, 503, 503)
        assertTrue(runCatching { AnimeotecaHttp.text(2) }.exceptionOrNull() is AnilistUnavailable)
    }
}

// ===== AnimeotecaTest.kt =====
class AnimeotecaTest {
    private fun page(name: String) = javaClass.getResource("/$name")!!.readText()

    @Test
    fun `lee la sinopsis entera y los generos de una pagina real`() {
        val text = AnimeotecaParser.parse(page("animeoteca-101922.html"))!!
        assertTrue(text.sinopsis!!.startsWith("Es el Periodo Taisho en Japón"))
        assertTrue(text.sinopsis.endsWith("(Fuente: Crunchyroll)"))
        assertFalse(text.sinopsis.endsWith("…"))
        assertEquals(listOf("Acción", "Aventura", "Drama", "Fantasía", "Sobrenatural"), text.generos)
    }

    @Test
    fun `una pagina sin ficha o con la sinopsis cortada no aporta sinopsis`() {
        assertNull(AnimeotecaParser.parse("<html><body><div id=root></div></body></html>"))
        val cut = AnimeotecaParser.parse("<h2>Sinopsis</h2><p>Empieza y se corta…</p><a href=\"/genero/accion\">Acción</a>")!!
        assertNull(cut.sinopsis)
        assertEquals(listOf("Acción"), cut.generos)
    }

    @Test
    fun `el texto guardado vuelve igual y uno vacio no cuenta`() {
        val back = AnimeotecaText.fromJson(AnimeotecaText("Hola", listOf("A", "B")).toJson(5L))!!
        assertEquals("Hola", back.sinopsis); assertEquals(listOf("A", "B"), back.generos)
        assertTrue(AnimeotecaText.fromJson(AnimeotecaText(null, emptyList()).toJson(5L))!!.isEmpty)
        assertNull(AnimeotecaText.fromJson("no es json"))
    }

    @Test
    fun `la ficha de AniList lleva el texto de Animeoteca y la sinopsis se aplica`() {
        val json = """{"id":5,"averageScore":80,"description":"English"}"""
        val es = AnimeotecaText("Sinopsis en español", listOf("Acción"))
        val ficha = AnimeFicha(5, null, 1, 0L, AnimeFicha.gzip(json), es = AnimeFicha.gzip(es.toJson(1L)))
        val data = FichaData.ofAnilist(ficha)!!
        assertEquals("Sinopsis en español", data.es!!.sinopsis)
        // Un marcador «sin datos» no se considera texto.
        assertNull(FichaData.ofAnilist(AnimeFicha(5, null, 1, 0L, AnimeFicha.gzip(json), es = AnimeFicha.gzip(AnimeotecaText(null, emptyList()).toJson(1L))))!!.es)
    }

    @Test
    fun `la cola mira Animeoteca de cada ficha sin texto, guarda lo que no tiene y no repite`() = runTest {
        val fichas = HashMap<Long, AnimeFicha>()
        fichas[1] = AnimeFicha(1, null, 1, 0L, AnimeFicha.gzip("{}"))
        fichas[2] = AnimeFicha(2, null, 1, 0L, AnimeFicha.gzip("{}"))
        val asked = mutableListOf<Long>()
        val api = object : AnimeotecaApi { override suspend fun text(anilistId: Long): AnimeotecaText? { asked += anilistId; return if (anilistId == 1L) AnimeotecaText("Uno", listOf("X")) else null } }
        val dao = object : AnimeFichaDao {
            override fun put(fichas: List<AnimeFicha>) {}
            override fun get(id: Long) = fichas[id]
            override fun freshIds(version: Int, since: Long) = emptyList<Long>()
            override fun downloaded(version: Int) = emptyList<AnimeFichaDao.Downloaded>()
            override fun count() = fichas.size
            override fun titles() = emptyList<AnimeFichaDao.Titles>()
            override fun idsWithoutEs() = fichas.values.filter { it.es == null }.map { it.anilistId }
            override fun setEs(id: Long, es: ByteArray) { fichas[id] = fichas.getValue(id).copy(es = es) }
            override fun countWithEs() = fichas.values.count { it.es != null }
            override fun esMarks() = fichas.values.filter { it.es != null }.map { AnimeFichaDao.EsMark(it.anilistId, it.es) }
        }
        val fuentes = object : AnimeFuenteDao {
            override fun all() = emptyList<AnimeFuente>()
            override fun get(fuente: String, slug: String): AnimeFuente? = null
            override fun appliedIds() = emptyList<Long>()
            override fun countByState() = emptyList<AnimeFuenteDao.StateCount>()
            override fun expireAuto() {}
        override fun deleteAuto(fuente: String, slug: String) {}
            override fun insertIfAbsent(rows: List<AnimeFuente>) {}
            override fun put(rows: List<AnimeFuente>) {}
        }
        val sync = AnilistSync(object : AnilistApi {
            override suspend fun search(titles: List<String>) = emptyList<List<AnilistMatcher.Candidate>>()
            override suspend fun fichas(ids: List<Long>) = emptyList<AnilistFichaRaw>()
        }, fuentes, dao, { emptyList() }, animeoteca = api)

        assertEquals(2, sync.runOnce().esDownloaded)
        assertEquals(listOf(1L, 2L), asked)
        assertEquals("Uno", FichaData.ofAnilist(fichas.getValue(1).copy(json = AnimeFicha.gzip("""{"id":1}""")))!!.es!!.sinopsis)
        assertNotNull(fichas.getValue(2).es)
        // Segunda pasada: nada que pedir.
        assertEquals(0, sync.runOnce().esDownloaded)
        assertEquals(listOf(1L, 2L), asked)
    }

    @Test
    fun `un sin datos de hace mas de un mes se vuelve a pedir y un texto con contenido nunca`() {
        fun mark(id: Long, text: AnimeotecaText, at: Long) = AnimeFichaDao.EsMark(id, AnimeFicha.gzip(text.toJson(at)))
        val marks = listOf(
            mark(1, AnimeotecaText(null, emptyList()), 1_000L),
            mark(2, AnimeotecaText(null, emptyList()), 9_000L),
            mark(3, AnimeotecaText("Hola", listOf("X")), 1_000L),
            AnimeFichaDao.EsMark(4, byteArrayOf(1, 2, 3)),
            AnimeFichaDao.EsMark(5, null)
        )
        assertEquals(listOf(1L), marks.staleEmpty(before = 5_000L))
    }

    private fun queueWith(ids: List<Long>, api: AnimeotecaApi, priority: Map<Long, Int> = emptyMap()): Pair<AnilistSync, HashMap<Long, AnimeFicha>> {
        val fichas = HashMap<Long, AnimeFicha>()
        ids.forEach { fichas[it] = AnimeFicha(it, null, 1, 0L, AnimeFicha.gzip("{}")) }
        val dao = object : AnimeFichaDao {
            override fun put(fichas: List<AnimeFicha>) {}
            override fun get(id: Long) = fichas[id]
            override fun freshIds(version: Int, since: Long) = emptyList<Long>()
            override fun downloaded(version: Int) = emptyList<AnimeFichaDao.Downloaded>()
            override fun count() = fichas.size
            override fun titles() = emptyList<AnimeFichaDao.Titles>()
            override fun idsWithoutEs() = fichas.values.filter { it.es == null }.map { it.anilistId }
            override fun setEs(id: Long, es: ByteArray) { fichas[id] = fichas.getValue(id).copy(es = es) }
            override fun countWithEs() = fichas.values.count { it.es != null }
            override fun esMarks() = fichas.values.filter { it.es != null }.map { AnimeFichaDao.EsMark(it.anilistId, it.es) }
        }
        val fuentes = object : AnimeFuenteDao {
            override fun all() = emptyList<AnimeFuente>()
            override fun get(fuente: String, slug: String): AnimeFuente? = null
            override fun appliedIds() = emptyList<Long>()
            override fun countByState() = emptyList<AnimeFuenteDao.StateCount>()
            override fun expireAuto() {}
        override fun deleteAuto(fuente: String, slug: String) {}
            override fun insertIfAbsent(rows: List<AnimeFuente>) {}
            override fun put(rows: List<AnimeFuente>) {}
        }
        val anilist = object : AnilistApi {
            override suspend fun search(titles: List<String>) = emptyList<List<AnilistMatcher.Candidate>>()
            override suspend fun fichas(ids: List<Long>) = emptyList<AnilistFichaRaw>()
        }
        return AnilistSync(anilist, fuentes, dao, { emptyList() }, animeoteca = api, priority = { priority }) to fichas
    }

    @Test
    fun `la cola pide primero los animes con favoritos y vistos`() = runTest {
        val asked = mutableListOf<Long>()
        val api = object : AnimeotecaApi { override suspend fun text(anilistId: Long): AnimeotecaText? { asked += anilistId; return null } }
        val (sync, _) = queueWith(listOf(1, 2, 3, 4, 5), api, priority = mapOf(4L to 2, 2L to 1))
        sync.runOnce()
        // Favoritos (4), luego vistos (2), luego el resto en su orden.
        assertEquals(listOf(4L, 2L, 1L, 3L, 5L), asked)
    }

    @Test
    fun `una ficha que siempre falla se salta y las demas siguen`() = runTest {
        val api = object : AnimeotecaApi {
            override suspend fun text(anilistId: Long): AnimeotecaText? = if (anilistId == 2L) throw AnilistUnavailable("500") else AnimeotecaText("ok", emptyList())
        }
        val (sync, fichas) = queueWith(listOf(1, 2, 3), api)
        val report = sync.runOnce()
        assertEquals(2, report.esDownloaded)
        assertNull(report.stopped)
        // La que falló no se marca «sin datos»: se vuelve a intentar en la siguiente pasada.
        assertNull(fichas.getValue(2).es)
    }

    @Test
    fun `cinco fallos seguidos son una caida y la cola para`() = runTest {
        val api = object : AnimeotecaApi { override suspend fun text(anilistId: Long): AnimeotecaText? = throw AnilistUnavailable("Animeoteca respondió 503") }
        val (sync, fichas) = queueWith((1L..8L).toList(), api)
        val report = sync.runOnce()
        assertEquals("Animeoteca respondió 503", report.stopped)
        assertEquals(0, report.esDownloaded)
        assertTrue(fichas.values.all { it.es == null })
    }

    @Test
    fun `las etiquetas pasan los codigos de AniList a espanol`() {
        assertEquals("Finalizada", AnilistLabels.status("FINISHED")); assertEquals("En emisión", AnilistLabels.status("RELEASING"))
        assertEquals("Película", AnilistLabels.format("MOVIE")); assertEquals("principal", AnilistLabels.role("MAIN"))
        assertEquals("a\nb", AnilistLabels.plain("<i>a</i><br>b"))
        // Saltos dobles y triples, entidades y marcas de spoiler: un solo salto, sin líneas vacías.
        assertEquals("Uno \"dos\"\ntres", AnilistLabels.plain("Uno &quot;dos&quot;<br><br><br>\n<br>~!tres!~"))
        assertEquals("sin revisar", AnilistLabels.link(null))
    }
}

// ===== CatalogBuildCoreTest.kt =====
/** Recorrido del catálogo: qué se pide a cada fuente, qué se escribe y qué se conserva cuando una cae. */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogBuildCoreTest {

    private val day = ApiCache.DAY

    /** Catálogo en memoria: lo justo de [CatalogDao] para el recorrido. */
    private class FakeDao : CatalogDao {
        val entries = mutableMapOf<String, CatalogEntry>()
        val links = mutableListOf<CatalogLink>()

        override fun entries(sources: List<String>, text: String, minScore: Double?, fromYear: Int?, toYear: Int?, byRating: Int): PagingSource<Int, CatalogEntry> =
            throw UnsupportedOperationException()
        override fun links(key: String) = links.filter { it.key == key }
        override fun count() = entries.size
        override fun allEntries() = entries.values.toList()
        override fun entriesOf(keys: List<String>) = entries.values.filter { it.key in keys }
        override fun searchBySlug(fragment: String) = links.filter { it.slug.contains(fragment) }.mapNotNull { entries[it.key] }
        override fun allLinks() = links.toList()
        override fun countsBySource() = links.groupingBy { it.sourceId }.eachCount().map { SourceCount(it.key, it.value) }
        override fun linksOfSource(sourceId: String) = links.filter { it.sourceId == sourceId }
        override fun deleteLinks(sourceIds: List<String>) { links.removeAll { it.sourceId in sourceIds } }
        override fun keyOf(sourceId: String, slug: String) = links.firstOrNull { it.sourceId == sourceId && it.slug == slug }?.key
        override fun keyOfSlug(slug: String) = links.firstOrNull { it.slug == slug }?.key
        override fun slugOf(key: String, sourceId: String) = links.firstOrNull { it.key == key && it.sourceId == sourceId }?.slug
        override fun putEntries(entries: List<CatalogEntry>) { entries.forEach { this.entries[it.key] = it } }
        override fun putLinks(links: List<CatalogLink>) {
            links.forEach { new -> this.links.removeAll { it.key == new.key && it.sourceId == new.sourceId && it.slug == new.slug } }
            this.links += links
        }
        override fun purgeEntries(before: Long) { entries.values.removeAll { it.updatedAt < before } }
        override fun purgeOrphanLinks() { links.removeAll { it.key !in entries } }
        override fun clearEntries() { entries.clear() }
        override fun clearLinks() { links.clear() }
    }

    /** Tablas de ids en memoria. */
    private class FakeIds : SeriesIdDao {
        private var last = 0L
        val map = mutableMapOf<Pair<String, String>, Long>()
        val registered = mutableSetOf<Long>()
        override fun newSeries(registry: SeriesRegistry) = (++last).also { registered += it }
        override fun putRegistry(rows: List<SeriesRegistry>) { rows.forEach { registered += it.seriesId; last = maxOf(last, it.seriesId) } }
        override fun hasRegistry() = registered.isNotEmpty()
        override fun allMap() = map.map { (key, id) -> SeriesMap(key.first, key.second, id) }
        override fun idOf(sourceId: String, slug: String) = map[sourceId to slug]
        override fun putMap(rows: List<SeriesMap>) { rows.forEach { map[it.sourceId to it.slug] = it.seriesId } }
        override fun moveSeries(moves: Map<Long, Long>) { map.replaceAll { _, id -> moves[id] ?: id } }
        override fun putMapIfAbsent(rows: List<SeriesMap>) { rows.forEach { map.putIfAbsent(it.sourceId to it.slug, it.seriesId) } }
    }

    /** Fuente con las series que se le den, en páginas de 2; [failing] hace que toda petición de página falle. */
    private class FakeSource(
        override val id: String,
        var series: List<String>,
        var total: Int? = series.size,
        var failing: Boolean = false,
        /** Nombre de cada serie si no es su slug. */
        var titles: Map<String, String> = emptyMap(),
        /** Nota y votos de cada serie si no son 7,0 con 10 votos. */
        var ratings: Map<String, Pair<Double?, Int>> = emptyMap(),
        /** Año de cada serie si no es 2020 (null = la fuente no lo da). */
        var years: Map<String, Int?> = emptyMap(),
        /** Series de cada año si la fuente recorta el listado completo y hay que pedirlo año a año (2000..2002). */
        var byYear: Map<Int, List<String>>? = null
    ) : AnimeSource {
        var pageCalls = 0
        var totalCalls = 0
        var onPage: () -> Unit = {}

        override suspend fun catalogTotal(): Int? { totalCalls++; return total }

        override val catalogYears: IntRange? get() = byYear?.let { 2000..2002 }

        override suspend fun search(query: SearchQuery, cursor: String?): CatalogPage {
            val year = query.years?.first
            if (byYear == null || year == null) return super.search(query, cursor)
            return CatalogPage((byYear!![year] ?: emptyList()).map { SeriesSummary(it.hashCode() and 0xffff or 1, it, it, 1, id, 7.0, 10, year) }, null)
        }

        override suspend fun catalogPage(status: String, cursor: String?): CatalogPage {
            pageCalls++
            onPage()
            check(!failing) { "$id caída" }
            val from = cursor?.toInt() ?: 0
            val items = series.drop(from).take(2).map { SeriesSummary(it.hashCode() and 0xffff or 1, titles[it] ?: it, it, 1, id, (ratings[it] ?: (7.0 to 10)).first, (ratings[it] ?: (7.0 to 10)).second, if (it in years) years[it] else 2020) }
            return CatalogPage(items, if (from + 2 < series.size) (from + 2).toString() else null)
        }

        override val name = id
        override val host = "$id.test"
        override val badge = id
        override suspend fun recents(): List<RecentEpisode> = emptyList()
        override suspend fun anime(url: String): Series? = null
        override suspend fun schedule(): Map<Int, List<AiringSeries>> = emptyMap()
        override fun animeUrl(slug: String) = "https://$host/$slug"
        override fun chapterUrl(slug: String, number: String) = "https://$host/$slug/$number"
        override fun coverUrl(aid: Int, slug: String) = ""
        override fun thumbnailUrl(aid: Int, number: String) = ""
    }

    /** [chain]: el reintento vuelve a entrar al núcleo, como en la app (si no, solo se cuenta). */
    private class FakeSnapshots : CatalogSnapshots {
        var stored: CatalogSnapshot? = null
        /** Lo que pasa mientras se lee la fotografía (en la app, otro hilo puede dar un id). */
        var onRead: () -> Unit = {}
        override fun read(): CatalogSnapshot? { onRead(); return stored }
        override fun createdAt() = stored?.createdAt
        override fun write(snapshot: CatalogSnapshot) { stored = snapshot }
    }

    private class Harness(scope: TestScope, val sources: List<AnimeSource>, chain: Boolean = false, val snapshots: FakeSnapshots = FakeSnapshots()) {
        val dao = FakeDao()
        val ids = FakeIds()
        private val prefs = FakePreferences()
        val states = CatalogStateStore { prefs }
        val errors = mutableListOf<Throwable>()
        var now = 100 * 86_400_000L
        var retried = 0
        /** Enlaces de la serie del catálogo con ese nombre (en las pruebas el nombre es el slug). */
        fun linksOf(name: String) = dao.links(dao.entries.values.single { it.name == name }.key)
        /** Simula un fallo al escribir el catálogo (disco lleno): los ids viven en otra base y su transacción no falla. */
        var catalogFails = false

        /** Si no es null, "cargar el libro de ids" falla (como al no poder leer la base donde estaba el libro antiguo). */
        var ensureIdsError: Throwable? = null

        /** Enlaces de las fichas con AniList (fuente, slug) → anime: dos fichas con el mismo anime se unen. */
        var anilist: Map<Pair<String, String>, Long> = emptyMap()
        /** Los enlaces con AniList que el catálogo descargado dejó guardados. */
        val savedSeedLinks = mutableListOf<AnimeFuente>()
        val core: CatalogBuildCore = CatalogBuildCore(
            dao = dao,
            ids = ids,
            states = states,
            inTransaction = { if (catalogFails) throw IllegalStateException("disco lleno") else it() },
            idsTransaction = { it() },
            ensureIds = { ensureIdsError?.let { throw it } },
            enabled = { sources },
            all = { sources },
            scope = scope,
            retry = { retried++; if (chain) core.ensureFresh() },
            anilistLinks = { anilist },
            clock = { now },
            onError = { errors += it },
            snapshots = snapshots,
            saveSeedLinks = { rows -> savedSeedLinks += rows; anilist = anilist + rows.associate { (it.fuente to it.slug) to checkNotNull(it.anilistId) } }
        )
    }

    private fun TestScope.harness(vararg sources: AnimeSource) = Harness(this, sources.toList())

    @Test
    fun `sin datos recorre todas las fuentes y guarda series, enlaces y estado`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto", "bleach", "one-piece"))
        val jk = FakeSource("jk", listOf("naruto", "frieren"))
        val h = harness(av1, jk)
        h.anilist = mapOf(("av1" to "naruto") to 1L, ("jk" to "naruto") to 1L)

        h.core.ensureFresh()

        assertEquals(setOf("naruto", "bleach", "one-piece", "frieren"), h.dao.entries.values.map { it.name }.toSet())
        // naruto está en las dos fuentes: una sola serie con dos enlaces.
        assertEquals(setOf("av1", "jk"), h.linksOf("naruto").map { it.sourceId }.toSet())
        assertEquals(SourceState(h.now, h.now, 3), h.states["av1"])
        assertEquals(SourceState(h.now, h.now, 2), h.states["jk"])
    }

    @Test
    fun `el catalogo descargado se escribe con la base vacia, sin pedir nada a las webs, y no pisa uno existente`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto"))
        val jk = FakeSource("jk", listOf("naruto"))
        val h = harness(av1, jk)
        val seed = listOf(
            CatalogSeed.Entry("Naruto", 2002, 8.0, 100, listOf("av1" to "naruto", "jk" to "naruto"), anilistId = 20),
            CatalogSeed.Entry("Otra", null, null, 0, listOf("jk" to "otra")),
            CatalogSeed.Entry("De una fuente que no tengo", null, null, 0, listOf("otraweb" to "x"))
        )

        assertEquals(3, h.core.seedIfEmpty(seed))
        assertEquals(setOf("Naruto", "Otra"), h.dao.entries.values.map { it.name }.toSet())
        // La unión sale del anilistId del fichero: una serie con las dos fichas; los enlaces quedan guardados como automáticos del mismo anime.
        assertEquals(setOf("av1", "jk"), h.linksOf("Naruto").map { it.sourceId }.toSet())
        assertEquals(setOf("av1" to "naruto", "jk" to "naruto"), h.savedSeedLinks.map { it.fuente to it.slug }.toSet())
        assertTrue(h.savedSeedLinks.all { it.anilistId == 20L && it.estado == AnimeFuente.APLICADO && it.origen == AnimeFuente.AUTO })
        // Las fuentes aportadas quedan comprobadas: el recorrido normal no las pide hasta pasado el plazo.
        h.core.ensureFresh()
        assertEquals(0, av1.pageCalls)
        // Con catálogo, no escribe nada más.
        assertEquals(0, h.core.seedIfEmpty(seed))
    }

    @Test
    fun `con catalogo propio el descargado solo anade las fichas que faltan de fuentes activas`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto"))
        val h = harness(av1)
        h.core.ensureFresh()
        val before = h.dao.entries.size
        val seed = listOf(
            CatalogSeed.Entry("naruto", null, null, 0, listOf("av1" to "naruto")),
            CatalogSeed.Entry("Bleach", 2004, 8.0, 10, listOf("av1" to "bleach")),
            CatalogSeed.Entry("De otra web", null, null, 0, listOf("jk" to "x"))
        )

        assertEquals(1, h.core.mergeSeed(seed))
        assertEquals(before + 1, h.dao.entries.size)
        assertTrue(h.dao.entries.values.any { it.name == "Bleach" })
        // Repetirlo no añade nada; sin catálogo propio no aplica.
        assertEquals(0, h.core.mergeSeed(seed))
        assertEquals(-1, harness(av1).core.mergeSeed(seed))
    }

    @Test
    fun `si la fuente recorta el listado se completa año a año sin duplicar`() = runTest {
        val av1 = FakeSource("av1", listOf("a", "b"), byYear = mapOf(2000 to listOf("c"), 2001 to listOf("d", "a")))
        val h = harness(av1)

        h.core.ensureFresh()

        assertEquals(setOf("a", "b", "c", "d"), h.dao.entries.values.map { it.name }.toSet())
    }

    @Test
    fun `una fuente comprobada hace menos de 14 dias no se vuelve a pedir`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto"))
        val h = harness(av1)
        h.core.ensureFresh()
        val calls = av1.pageCalls + av1.totalCalls

        h.now += 13 * day
        h.core.ensureFresh()

        assertEquals(calls, av1.pageCalls + av1.totalCalls)
    }

    @Test
    fun `con la base vacia se rehace desde la fotografia sin pedir nada a las fuentes y conserva los ids`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto", "bleach"))
        val jk = FakeSource("jk", listOf("one-piece"))
        val h = harness(av1, jk)
        h.core.ensureFresh()
        val idOfNaruto = h.ids.map["av1" to "naruto"]
        h.core.saveSnapshotIfDue()
        assertTrue(h.snapshots.stored != null)

        // Se pierde la base de datos (catálogo e ids); los estados de las preferencias sobreviven.
        h.dao.clearEntries(); h.dao.clearLinks(); h.ids.map.clear(); h.ids.registered.clear()
        val calls = av1.pageCalls + jk.pageCalls + av1.totalCalls + jk.totalCalls

        assertTrue(h.core.restoreSnapshotIfEmpty())

        assertEquals(calls, av1.pageCalls + jk.pageCalls + av1.totalCalls + jk.totalCalls)
        assertEquals(3, h.dao.entries.size)
        assertEquals(idOfNaruto, h.ids.map["av1" to "naruto"])
        assertEquals(SourceState.decode(h.snapshots.stored!!.states!!["av1"]), h.states["av1"])
        // Con la base ya rellena no vuelve a hacer nada.
        assertFalse(h.core.restoreSnapshotIfEmpty())
    }

    @Test
    fun `la fotografia de otras reglas de union o con ids existentes no se restaura`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto"))
        val h = harness(av1)
        h.core.ensureFresh()
        h.core.saveSnapshotIfDue()
        val old = h.snapshots.stored!!
        h.snapshots.stored = CatalogSnapshot(old.version, old.rules + 1, old.createdAt, old.entries, old.links, old.map, old.redirects, old.states)
        h.dao.clearEntries(); h.dao.clearLinks(); h.ids.map.clear(); h.ids.registered.clear()
        assertFalse(h.core.restoreSnapshotIfEmpty())

        h.snapshots.stored = old
        h.ids.map["av1" to "otra"] = 99L // ya hay ids: no se pisa nada
        assertFalse(h.core.restoreSnapshotIfEmpty())
    }

    @Test
    fun `un id dado mientras se lee la fotografia impide restaurarla`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto", "bleach"))
        val h = harness(av1)
        h.core.ensureFresh()
        h.core.saveSnapshotIfDue()
        h.dao.clearEntries(); h.dao.clearLinks(); h.ids.map.clear(); h.ids.registered.clear()
        // Otro hilo da un id (primer paso de idOf: el registro, antes de escribir el mapa) mientras se lee la fotografía.
        h.snapshots.onRead = { h.ids.registered += 1L }

        assertFalse(h.core.restoreSnapshotIfEmpty())

        assertEquals(0, h.dao.entries.size)
        assertTrue(h.ids.map.isEmpty())
    }

    @Test
    fun `una fotografia mucho menor y reciente no sustituye a la buena y a la semana se renueva`() = runTest {
        val av1 = FakeSource("av1", listOf("a", "b", "c", "d", "e"))
        val h = harness(av1)
        h.core.ensureFresh()
        h.core.saveSnapshotIfDue()
        val first = h.snapshots.stored!!

        h.now += 8 * day
        h.dao.entries.keys.drop(1).forEach { h.dao.entries.remove(it) } // el catálogo quedó recortado
        h.core.saveSnapshotIfDue()
        assertEquals(first, h.snapshots.stored)

        h.now += 60 * day
        h.core.saveSnapshotIfDue()
        assertTrue(h.snapshots.stored !== first)
    }

    @Test
    fun `una fuente con estado vigente pero sin series en la base de datos se recorre`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto", "bleach"))
        val jk = FakeSource("jk", listOf("one-piece", "gintama"))
        val h = harness(av1, jk)
        h.core.ensureFresh()
        // Las preferencias dicen «recorrida» pero la base perdió sus series (se vació mientras los estados sobrevivían).
        h.dao.deleteLinks(listOf("jk"))
        val pages = jk.pageCalls

        h.now += 1 * day
        h.core.ensureFresh()

        assertTrue(jk.pageCalls > pages)
        assertEquals(2, h.dao.linksOfSource("jk").size)
    }

    @Test
    fun `con el mismo total no se recorren las paginas pero se renueva la comprobacion`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto", "bleach", "one-piece"))
        val h = harness(av1)
        h.core.ensureFresh()
        val walkedAt = h.now
        val pages = av1.pageCalls

        h.now += 15 * day
        h.core.ensureFresh()

        assertEquals(pages, av1.pageCalls)
        assertEquals(SourceState(checkedAt = h.now, walkedAt = walkedAt, total = 3), h.states["av1"])
        assertEquals(3, h.dao.entries.size)
    }

    @Test
    fun `si el total cambia se recorre de nuevo y entran las series nuevas`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto", "bleach"))
        val h = harness(av1)
        h.core.ensureFresh()

        h.now += 15 * day
        av1.series = listOf("naruto", "bleach", "frieren")
        av1.total = 3
        h.core.ensureFresh()

        assertEquals(setOf("naruto", "bleach", "frieren"), h.dao.entries.values.map { it.name }.toSet())
        assertEquals(h.now, h.states["av1"].walkedAt)
    }

    @Test
    fun `pasados 30 dias se recorre entera aunque el total no cambie`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto", "bleach"))
        val h = harness(av1)
        h.core.ensureFresh()
        val pages = av1.pageCalls

        h.now += 31 * day
        h.core.ensureFresh()

        assertTrue(av1.pageCalls > pages)
    }

    @Test
    fun `un recorrido vacio es un fallo, no toca el catalogo ni marca la fuente`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto", "bleach"))
        val h = harness(av1)
        h.core.ensureFresh()
        val before = h.dao.entries.toMap()
        val state = h.states["av1"]

        h.now += 15 * day
        av1.series = emptyList()
        av1.total = 0
        h.core.ensureFresh()
        advanceUntilIdle()

        assertEquals(before, h.dao.entries)
        assertEquals(state, h.states["av1"])
        assertEquals(1, h.errors.size)
        assertTrue("debe quedar un reintento programado", h.retried > 0)
    }

    @Test
    fun `si una fuente cae el catalogo sigue con la otra y conserva lo que ya tenia de la caida`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto", "bleach"))
        val jk = FakeSource("jk", listOf("frieren"))
        val h = harness(av1, jk)
        h.core.ensureFresh()

        h.now += 15 * day
        av1.series = listOf("naruto", "bleach", "one-piece")
        av1.total = 3
        jk.failing = true
        jk.total = 2
        h.core.ensureFresh()
        advanceUntilIdle()

        assertEquals(setOf("naruto", "bleach", "one-piece", "frieren"), h.dao.entries.values.map { it.name }.toSet())
        assertEquals("jk", h.linksOf("frieren").single().sourceId)
        // La caída no marca su fecha: sigue tocándole.
        assertEquals(SourceState(100 * day, 100 * day, 1), h.states["jk"])
        assertTrue(h.retried > 0)
    }

    @Test
    fun `una serie conserva su id al vaciar y reconstruir el catalogo`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto", "bleach"))
        val h = harness(av1)
        h.core.ensureFresh()
        val before = h.dao.entries.values.associate { it.name to it.seriesId }

        h.core.clear()
        h.core.ensureFresh()

        assertEquals(before, h.dao.entries.values.associate { it.name to it.seriesId })
        assertTrue(before.values.all { it != 0L })
    }

    @Test
    fun `al unirse dos series por el mismo anime de AniList la segunda pasa a la primera y se mueven sus datos`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto-a"), titles = mapOf("naruto-a" to "Naruto A"))
        val jk = FakeSource("jk", listOf("naruto-b"), titles = mapOf("naruto-b" to "Naruto B"))
        val h = harness(av1, jk)
        h.core.ensureFresh()
        val first = h.ids.map.getValue("av1" to "naruto-a")
        val second = h.ids.map.getValue("jk" to "naruto-b")
        assertEquals(2, h.dao.entries.size)

        h.now += 31 * day
        h.anilist = mapOf(("av1" to "naruto-a") to 20L, ("jk" to "naruto-b") to 20L)
        h.core.ensureFresh()

        assertEquals(1, h.dao.entries.size)
        assertEquals(first, h.ids.map.getValue("jk" to "naruto-b"))
        assertEquals(first, h.dao.entries.values.single().seriesId)
    }

    private fun summary(source: String, slug: String, name: String = slug) = SeriesSummary(slug.hashCode() and 0xffff or 1, name, slug, 1, source)

    @Test
    fun `una serie de recientes que el catalogo no tiene entra sin pedir nada a las fuentes`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto", "bleach"))
        val h = harness(av1)
        h.core.ensureFresh()
        val calls = av1.pageCalls + av1.totalCalls
        val before = h.dao.entries.values.associate { it.name to it.seriesId }

        h.core.addNew(listOf(av1 to summary("av1", "naruto"), av1 to summary("av1", "frieren"), av1 to summary("av1", "frieren")))

        assertEquals(setOf("naruto", "bleach", "frieren"), h.dao.entries.values.map { it.name }.toSet())
        assertEquals("av1", h.linksOf("frieren").single().sourceId)
        assertEquals(null, h.dao.entries.values.single { it.name == "frieren" }.year)
        // Las que ya estaban conservan su id, y no se ha pedido nada a la fuente ni se ha tocado su estado.
        assertEquals(before, h.dao.entries.values.filter { it.name in before }.associate { it.name to it.seriesId })
        assertEquals(calls, av1.pageCalls + av1.totalCalls)
        assertEquals(SourceState(h.now, h.now, 2), h.states["av1"])
    }

    @Test
    fun `una ficha nueva de recientes se une a la serie que otra fuente ya tiene`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto"))
        val jk = FakeSource("jk", listOf("bleach"))
        val h = harness(av1, jk)
        h.anilist = mapOf(("av1" to "naruto") to 1L, ("jk" to "naruto") to 1L)
        h.core.ensureFresh()

        h.core.addNew(listOf(jk to summary("jk", "naruto")))

        assertEquals(setOf("av1", "jk"), h.linksOf("naruto").map { it.sourceId }.toSet())
        assertEquals(2, h.dao.entries.size)
    }

    @Test
    fun `sin catalogo las altas de recientes no hacen nada`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto"))
        val h = harness(av1)

        h.core.addNew(listOf(av1 to summary("av1", "frieren")))

        assertTrue(h.dao.entries.isEmpty())
    }

    @Test
    fun `lo dado de alta desde recientes lo sustituye el siguiente recorrido de su fuente`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto"))
        val h = harness(av1)
        h.core.ensureFresh()
        h.core.addNew(listOf(av1 to summary("av1", "frieren")))

        h.now += 15 * day
        av1.series = listOf("naruto", "frieren", "bleach")
        av1.total = 3
        h.core.ensureFresh()

        assertEquals(setOf("naruto", "frieren", "bleach"), h.dao.entries.values.map { it.name }.toSet())
        // Con año y nota de su listado, no los del alta sin datos.
        assertEquals(2020, h.dao.entries.values.single { it.name == "frieren" }.year)
        assertEquals(1, h.linksOf("frieren").size)
    }

    @Test
    fun `abrir la ficha completa nota votos y año de la ficha del catalogo y de su serie`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto"))
        val h = harness(av1)
        h.core.ensureFresh()
        h.core.addNew(listOf(av1 to summary("av1", "frieren")))

        h.core.noteFicha("av1", "frieren", 4.5, 120, 2023)

        val link = h.linksOf("frieren").single()
        assertEquals(4.5, link.score)
        assertEquals(120, link.votes)
        assertEquals(2023, link.year)
        val entry = h.dao.entries.values.single { it.name == "frieren" }
        assertEquals(4.5, entry.score)
        assertEquals(120, entry.votes)
        assertEquals(2023, entry.year)
    }

    @Test
    fun `abrir la ficha no cambia el año que ya traia el listado ni borra la nota si la ficha no tiene votos`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto"))
        val h = harness(av1)
        h.core.ensureFresh()

        h.core.noteFicha("av1", "naruto", 0.0, 0, 1999)

        val link = h.linksOf("naruto").single()
        assertEquals(2020, link.year)
        assertEquals(7.0, link.score)
        assertEquals(10, link.votes)

        // Votos sin nota (la ficha no la trae): tampoco pisa la nota que había.
        h.core.noteFicha("av1", "naruto", 0.0, 5, null)

        assertEquals(7.0, h.linksOf("naruto").single().score)
        assertEquals(10, h.linksOf("naruto").single().votes)
    }

    @Test
    fun `la nota de una ficha unida se recalcula con las demas fichas de la serie`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto"), ratings = mapOf("naruto" to (8.0 to 30)))
        val jk = FakeSource("jk", listOf("naruto"), ratings = mapOf("naruto" to (4.0 to 10)))
        val h = harness(av1, jk)
        h.anilist = mapOf(("av1" to "naruto") to 1L, ("jk" to "naruto") to 1L)
        h.core.ensureFresh()
        assertEquals(7.0, h.dao.entries.values.single().score)

        h.core.noteFicha("jk", "naruto", 6.0, 30, null)

        val entry = h.dao.entries.values.single()
        assertEquals(60, entry.votes)
        assertEquals(7.0, entry.score)
        assertEquals(2, h.dao.links.size)
    }

    @Test
    fun `abrir la ficha de una serie que el catalogo no tiene no hace nada`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto"))
        val h = harness(av1)
        h.core.ensureFresh()
        val before = h.dao.entries.toMap()

        h.core.noteFicha("av1", "desconocida", 4.5, 120, 2023)

        assertEquals(before, h.dao.entries)
    }

    @Test
    fun `reagrupar conserva el catalogo y los ids sin pedir nada a las fuentes`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto", "bleach"))
        val jk = FakeSource("jk", listOf("naruto"))
        val h = harness(av1, jk)
        h.anilist = mapOf(("av1" to "naruto") to 1L, ("jk" to "naruto") to 1L)
        h.core.ensureFresh()
        val before = h.dao.entries.values.associate { it.name to it.seriesId }
        val calls = av1.pageCalls + jk.pageCalls

        h.core.regroup()

        assertEquals(before, h.dao.entries.values.associate { it.name to it.seriesId })
        assertEquals(setOf("av1", "jk"), h.linksOf("naruto").map { it.sourceId }.toSet())
        assertEquals(calls, av1.pageCalls + jk.pageCalls)
    }

    @Test
    fun `reagrupar no deja enlaces de la serie anterior cuando cambia el id de un grupo`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto"))
        val jk = FakeSource("jk", listOf("naruto"))
        val h = harness(av1, jk)
        h.core.ensureFresh()
        assertEquals(2, h.dao.links.size)
        // Una separación o una unión cambia el id de las fichas: el grupo pasa a otra clave.
        h.ids.map["av1" to "naruto"] = 500L
        h.ids.map["jk" to "naruto"] = 500L

        h.core.regroup()

        assertEquals(2, h.dao.links.size)
        assertEquals(setOf("500"), h.dao.links.map { it.key }.toSet())
        assertEquals(setOf("500"), h.dao.entries.keys)
    }

    @Test
    fun `reagrupar tras unir y separar deja a cada ficha con su nombre real`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto-a"), titles = mapOf("naruto-a" to "Naruto A"))
        val jk = FakeSource("jk", listOf("naruto-b"), titles = mapOf("naruto-b" to "Naruto B"))
        val h = harness(av1, jk)
        h.core.ensureFresh()
        // Unidas por id: la serie se llama como la primera ficha.
        h.ids.map["av1" to "naruto-a"] = 500L
        h.ids.map["jk" to "naruto-b"] = 500L
        h.core.regroup()
        assertEquals(setOf("Naruto A"), h.dao.entries.values.map { it.name }.toSet())

        // Separadas otra vez: cada ficha recupera su nombre, sin esperar a otro recorrido.
        h.ids.map["jk" to "naruto-b"] = 600L
        h.core.regroup()

        assertEquals(setOf("Naruto A", "Naruto B"), h.dao.entries.values.map { it.name }.toSet())
    }

    @Test
    fun `reagrupar muchas veces no multiplica los votos ni cambia la nota de una serie unida`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto"))
        val jk = FakeSource("jk", listOf("naruto"))
        val h = harness(av1, jk)
        h.anilist = mapOf(("av1" to "naruto") to 1L, ("jk" to "naruto") to 1L)
        h.core.ensureFresh()
        val before = h.dao.entries.values.single()
        // Dos fichas con 10 votos y nota 7,0 cada una.
        assertEquals(20, before.votes)
        assertEquals(7.0, before.score)

        repeat(5) { h.core.regroup() }

        val after = h.dao.entries.values.single()
        assertEquals(20, after.votes)
        assertEquals(7.0, after.score)
    }

    @Test
    fun `reagrupar y separar conserva la nota y los votos de cada ficha`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto-a"), titles = mapOf("naruto-a" to "Naruto A"), ratings = mapOf("naruto-a" to (8.0 to 30)))
        val jk = FakeSource("jk", listOf("naruto-b"), titles = mapOf("naruto-b" to "Naruto B"), ratings = mapOf("naruto-b" to (4.0 to 10)))
        val h = harness(av1, jk)
        h.core.ensureFresh()
        h.ids.map["av1" to "naruto-a"] = 500L
        h.ids.map["jk" to "naruto-b"] = 500L
        repeat(3) { h.core.regroup() }
        val joined = h.dao.entries.values.single()
        assertEquals(40, joined.votes)
        assertEquals(7.0, joined.score)

        h.ids.map["jk" to "naruto-b"] = 600L
        h.core.regroup()

        assertEquals(setOf(8.0 to 30, 4.0 to 10), h.dao.entries.values.map { it.score to it.votes }.toSet())
        assertEquals(setOf(8.0 to 30, 4.0 to 10), h.dao.links.map { it.score to it.votes }.toSet())
    }

    @Test
    fun `reagrupar y separar no da a una ficha sin año el de la serie a la que estuvo unida`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto-a"), titles = mapOf("naruto-a" to "Naruto A"), years = mapOf("naruto-a" to 2005))
        val jk = FakeSource("jk", listOf("naruto-b"), titles = mapOf("naruto-b" to "Naruto B"), years = mapOf("naruto-b" to null))
        val h = harness(av1, jk)
        h.core.ensureFresh()
        h.ids.map["av1" to "naruto-a"] = 500L
        h.ids.map["jk" to "naruto-b"] = 500L
        h.core.regroup()
        assertEquals(2005, h.dao.entries.values.single().year)

        h.ids.map["jk" to "naruto-b"] = 600L
        h.core.regroup()

        assertEquals(null, h.dao.entries.values.single { it.name == "Naruto B" }.year)
        assertEquals(2005, h.dao.entries.values.single { it.name == "Naruto A" }.year)
    }

    @Test
    fun `reagrupar tras vaciar no devuelve el catalogo borrado`() = runTest {
        val h = harness(FakeSource("av1", listOf("naruto", "bleach")))
        h.core.ensureFresh()

        h.core.clear()
        h.core.regroup()

        assertTrue(h.dao.entries.isEmpty())
        assertTrue(h.dao.links.isEmpty())
    }

    @Test
    fun `reagrupar sin catalogo no hace nada`() = runTest {
        val h = harness(FakeSource("av1", listOf("naruto")))

        h.core.regroup()

        assertTrue(h.dao.entries.isEmpty())
    }

    @Test
    fun `los reintentos seguidos tienen tope`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto"), failing = true)
        val h = Harness(this, listOf(av1), chain = true)

        h.core.ensureFresh()
        advanceUntilIdle()

        assertEquals(CatalogBuildCore.MAX_RETRIES, h.retried)
        assertTrue(h.dao.entries.isEmpty())
    }

    @Test
    fun `vaciar borra catalogo y estados`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto", "bleach"))
        val h = harness(av1)
        h.core.ensureFresh()

        h.core.clear()

        assertTrue(h.dao.entries.isEmpty())
        assertTrue(h.dao.links.isEmpty())
        assertEquals(SourceState.NEVER, h.states["av1"])
    }

    @Test
    fun `un recorrido que estaba en marcha al vaciar no devuelve lo borrado`() = runTest {
        val av1 = FakeSource("av1", listOf("naruto", "bleach"))
        lateinit var h: Harness
        // La fuente pide vaciar el catálogo a mitad de su recorrido.
        av1.onPage = { h.core.clear() }
        h = harness(av1)

        h.core.ensureFresh()

        assertTrue(h.dao.entries.isEmpty())
        assertEquals(SourceState.NEVER, h.states["av1"])
    }

    @Test
    fun `si falla la escritura del catalogo los ids ya dados se conservan y el siguiente recorrido los reutiliza`() = runTest {
        val h = harness(FakeSource("av1", listOf("naruto", "bleach")))
        h.catalogFails = true

        runCatching { h.core.ensureFresh() }

        assertTrue(h.dao.entries.isEmpty())
        val given = h.ids.allMap().associate { it.slug to it.seriesId }
        assertEquals(setOf("naruto", "bleach"), given.keys)

        h.catalogFails = false
        h.core.ensureFresh()

        assertEquals(given, h.ids.allMap().associate { it.slug to it.seriesId })
        assertEquals(setOf("naruto", "bleach"), h.dao.entries.values.map { it.name }.toSet())
    }

    @Test
    fun `sin poder cargar el libro de ids no se reparten ids nuevos ni se toca el catalogo`() = runTest {
        val h = harness(FakeSource("av1", listOf("naruto", "bleach")))
        h.ensureIdsError = IllegalStateException("libro sin cargar")

        runCatching { h.core.ensureFresh() }

        // Con el libro vacío los ids nuevos empezarían en 1 y chocarían con los antiguos: no se reparte ninguno.
        assertTrue(h.ids.allMap().isEmpty())
        assertTrue(h.dao.entries.isEmpty())

        h.ensureIdsError = null
        h.core.ensureFresh()

        assertEquals(setOf("naruto", "bleach"), h.dao.entries.values.map { it.name }.toSet())
    }

    @Test
    fun `sin poder cargar el libro de ids no se restaura la fotografia ni se toca nada`() = runTest {
        val h = harness(FakeSource("av1", listOf("naruto", "bleach")), FakeSource("jk", listOf("one-piece")))
        h.core.ensureFresh()
        h.core.saveSnapshotIfDue()
        h.dao.clearEntries(); h.dao.clearLinks(); h.ids.map.clear(); h.ids.registered.clear()
        h.ensureIdsError = IllegalStateException("libro sin cargar")

        assertTrue(runCatching { h.core.restoreSnapshotIfEmpty() }.isFailure)

        assertTrue(h.ids.map.isEmpty())
        assertTrue(h.dao.entries.isEmpty())
    }
}

// ===== CatalogSkipWalkTest.kt =====
class CatalogSkipWalkTest {
    private val day = ApiCache.DAY
    private val now = 100 * day
    private val state = SourceState(checkedAt = now - 8 * day, walkedAt = now - 8 * day, total = 5000)

    @Test
    fun `cubre el mismo total de un recorrido reciente`() {
        assertTrue(state.covers(5000, now))
    }

    @Test
    fun `un total distinto no lo cubre`() {
        assertFalse(state.covers(5001, now))
    }

    @Test
    fun `una fuente que no anuncia total no lo cubre`() {
        assertFalse(state.covers(null, now))
    }

    @Test
    fun `pasados 30 dias del recorrido completo no lo cubre aunque el total no cambie`() {
        assertTrue(state.copy(walkedAt = now - 29 * day).covers(5000, now))
        assertFalse(state.copy(walkedAt = now - 31 * day).covers(5000, now))
    }

    @Test
    fun `una fuente nunca recorrida no se cubre y siempre toca`() {
        assertFalse(SourceState.NEVER.covers(5000, now))
        assertFalse(SourceState.NEVER.covers(null, now))
        assertTrue(SourceState.NEVER.due(now))
    }

    @Test
    fun `toca comprobar a los 14 dias`() {
        assertFalse(state.copy(checkedAt = now - 13 * day).due(now))
        assertTrue(state.copy(checkedAt = now - 14 * day).due(now))
    }

    @Test
    fun `se guarda y se lee igual`() {
        assertEquals(state, SourceState.decode(state.encode()))
    }

    @Test
    fun `un texto que no es un estado vale NEVER`() {
        assertEquals(SourceState.NEVER, SourceState.decode(null))
        assertEquals(SourceState.NEVER, SourceState.decode("5000"))
        assertEquals(SourceState.NEVER, SourceState.decode("a:b:c"))
        assertEquals(SourceState.NEVER, SourceState.decode("1:2:3:4"))
    }
}

// ===== CatalogSnapshotTest.kt =====
/** La fotografía del catálogo: ida y vuelta por JSON y rechazo de lo dañado. */
class CatalogSnapshotTest {
    private val rules = CatalogStateStore.RULES_VERSION

    private fun snapshot(
        entries: List<SnapEntry> = listOf(SnapEntry("5", 5, "Naruto", 8.1, 100, 2002, 10L)),
        links: List<SnapLink> = listOf(SnapLink("5", "av1", "naruto", 4459, 5, 2002, "Naruto", 8.1, 100)),
        map: List<SnapMap> = listOf(SnapMap("av1", "naruto", 5)),
        version: Int = CatalogSnapshot.VERSION,
        rulesVersion: Int = rules
    ) = CatalogSnapshot(version, rulesVersion, 1L, entries, links, map, listOf(SnapRedirect(7, 5)), mapOf("av1" to "1:2:3"))

    private fun roundTrip(snapshot: CatalogSnapshot) = Gson().fromJson(Gson().toJson(snapshot), CatalogSnapshot::class.java)

    @Test
    fun `ida y vuelta por JSON conserva todo y es utilizable`() {
        val back = roundTrip(snapshot())

        assertTrue(back.usable(rules))
        assertEquals("Naruto", back.entries!!.single().toEntity().name)
        assertEquals(5L, back.links!!.single().toEntity().seriesId)
        assertEquals(mapOf("av1" to "1:2:3"), back.states)
        assertEquals(7L, back.redirects!!.single().fromId)
    }

    @Test
    fun `otra version o reglas de union distintas no es utilizable`() {
        assertFalse(snapshot(version = 99).usable(rules))
        assertFalse(snapshot(rulesVersion = rules + 1).usable(rules))
    }

    @Test
    fun `un fichero dañado o incompleto no es utilizable`() {
        assertFalse(Gson().fromJson("""{"version":1,"rules":$rules,"createdAt":1}""", CatalogSnapshot::class.java).usable(rules))
        // Campos que Gson deja nulos o a 0 cuando faltan en el JSON.
        val noKey = Gson().fromJson(
            """{"version":1,"rules":$rules,"createdAt":1,"entries":[{"seriesId":5,"name":"x"}],"links":[{"key":"5","sourceId":"av1","slug":"x","seriesId":5}],"map":[]}""",
            CatalogSnapshot::class.java
        )
        assertFalse(noKey.usable(rules))
        assertFalse(snapshot(map = listOf(SnapMap("av1", "naruto", 0))).usable(rules))
        assertFalse(snapshot(links = listOf(SnapLink("5", "av1", "", 1, 5, null, null, null, 0))).usable(rules))
    }
}

// ===== CatalogStateStoreTest.kt =====
/** Estados de las fuentes en preferencias: lectura, escritura y qué pasa al cambiar las reglas de unión. */
class CatalogStateStoreTest {
    private val prefs = FakePreferences()
    private val store = CatalogStateStore { prefs }
    private val state = SourceState(checkedAt = 10L, walkedAt = 5L, total = 1234)

    @Test
    fun `una fuente sin estado vale NEVER`() {
        assertEquals(SourceState.NEVER, store["animeav1"])
    }

    @Test
    fun `lo escrito se lee igual y cada fuente tiene el suyo`() {
        store["animeav1"] = state
        store["jkanime"] = state.copy(total = 7)

        assertEquals(state, store["animeav1"])
        assertEquals(7, store["jkanime"].total)
    }

    @Test
    fun `con otras reglas de union todo vale NEVER aunque haya estados guardados`() {
        prefs.edit().putInt(CatalogStateStore.KEY_RULES, CatalogStateStore.RULES_VERSION - 1)
            .putString(CatalogStateStore.STATE_PREFIX + "animeav1", state.encode()).apply()

        assertEquals(SourceState.NEVER, store["animeav1"])
    }

    @Test
    fun `la primera escritura con reglas antiguas borra los estados de antes, tambien los de fuentes inactivas`() {
        prefs.edit().putInt(CatalogStateStore.KEY_RULES, CatalogStateStore.RULES_VERSION - 1)
            .putString(CatalogStateStore.STATE_PREFIX + "animeav1", state.encode())
            .putString(CatalogStateStore.STATE_PREFIX + "inactiva", state.encode()).apply()

        store["jkanime"] = state

        assertEquals(CatalogStateStore.RULES_VERSION, prefs.getInt(CatalogStateStore.KEY_RULES, 0))
        assertEquals(SourceState.NEVER, store["animeav1"])
        assertFalse(prefs.contains(CatalogStateStore.STATE_PREFIX + "inactiva"))
        assertEquals(state, store["jkanime"])
    }

    @Test
    fun `vaciar olvida todos los estados`() {
        store["animeav1"] = state
        store["jkanime"] = state

        store.clear()

        assertEquals(SourceState.NEVER, store["animeav1"])
        assertEquals(SourceState.NEVER, store["jkanime"])
    }
}

// ===== ConfigurableAnimeAv1Test.kt =====
/** animeav1 por fichero (`animeav1.json`, `format: jsData`) con respuestas reales de animeav1.com (2026-10-14), recortadas. */
class ConfigurableAnimeAv1Test {
    private val specText = File("src/main/assets/sources/animeav1.json").readText()
    private val spec = SourceSpec.parse(specText)
    private val source = ConfigurableSource(spec)

    private fun fixture(name: String) = File("src/test/fixtures/animeav1/$name").readText()

    private fun list(name: String) = JsonNode(JSONArray(fixture(name)))

    @Test
    fun identityAndUrlsComeFromTheFile() {
        assertEquals("animeav1", source.id)
        assertEquals("AV1", source.badge)
        assertEquals("https://animeav1.com/media/black-clover", source.animeUrl("black-clover"))
        assertEquals("https://animeav1.com/media/black-clover/3", source.chapterUrl("black-clover", "3"))
        assertEquals("https://cdn.animeav1.com/covers/897.jpg", source.coverUrl(897, "black-clover"))
        assertEquals("https://cdn.animeav1.com/screenshots/897/3.jpg", source.thumbnailUrl(897, "3"))
        assertEquals(false, source.lightRating)
        assertEquals(setOf(Filter.AIRING, Filter.SORT_RATING), source.pushes(SearchQuery()))
        assertEquals("black-clover", source.slugOf("https://animeav1.com/media/black-clover"))
        assertEquals("black-clover", source.slugOf("https://animeav1.com/media/black-clover/3"))
        // La identidad que usan otras partes de la app coincide con el fichero.
        assertEquals(AnimeAv1.ID, source.id)
        assertEquals(AnimeAv1.animeUrl("x"), source.animeUrl("x"))
        assertEquals(AnimeAv1.coverUrl(897), source.coverUrl(897, "x"))
    }

    @Test
    fun catalogUsesTheSiteIds() {
        val cards = source.parseCards(list("catalog.json"))
        assertEquals(3, cards.size)
        assertEquals(171, cards[0].aid)
        assertEquals("shingeki-no-kyojin", cards[0].slug)
        assertEquals(1, cards[0].category)
        assertEquals("Shingeki no Kyojin", cards[0].name)
    }

    @Test
    fun cardsWithoutTheSiteIdAreDropped() {
        // Otro id partiría favoritos e historial: sin id del sitio la ficha no entra.
        val page = JsonNode(JSONArray("""[{"title":"Sin id","slug":"sin-id","categoryId":1},{"id":5,"title":"Con id","slug":"con-id","categoryId":1}]"""))
        assertEquals(listOf("con-id"), source.parseCards(page).map { it.slug })
    }

    @Test
    fun recentsHaveEpisodeIdMediaIdAndDate() {
        val recents = source.parseRecents(list("recents.json"))
        assertEquals(3, recents.size)
        assertEquals(60433, recents[0].eid)
        assertEquals(4497, recents[0].aid)
        assertEquals("dark-machine-the-animation", recents[0].slug)
        assertEquals(1.0, recents[0].number, 0.0)
        assertEquals("https://animeav1.com/media/dark-machine-the-animation/1", recents[0].url)
        // publishedAt «2026-10-13 18:43:59.37+00» en UTC.
        assertEquals(1791917039000L, recents[0].released)
    }

    @Test
    fun scheduleDayFromTheLatestEpisodeOrMinusOne() {
        val rows = source.parseScheduleList(list("schedule.json"))
        val withEpisode = rows.first { it.slug == "yamato-yo-towa-ni-rebel-3199" }
        // 2025-11-11 es martes.
        assertEquals(2, withEpisode.day)
        assertEquals(2, withEpisode.category)
        val without = source.parseScheduleList(JsonNode(JSONArray("""[{"id":1,"title":"Sin episodio","slug":"sin-episodio","category":{"id":1}}]""")))
        assertEquals(-1, without.single().day)
    }

    @Test
    fun ficheKeepsEpisodesGenresAndRelations() {
        val node = JsonNode(JSONObject(fixture("anime.json")))
        val chapters = source.parseChapterItems("black-clover", node, checkNotNull(spec.anime.chapters.items))
        val series = checkNotNull(source.parseSeries("black-clover", 897, node, chapters))
        assertEquals(897, series.aid)
        assertEquals("tv-anime", series.type)
        // El estado sale del registro de la ficha (0 = acabada en esta web): 1 es «en emisión» y 2 lo demás, igual que en todas las fuentes.
        assertEquals(2, series.state)
        assertEquals(8.14f / 2, series.rateStars, 0.001f)
        assertEquals(1001378, series.rateCount)
        assertEquals(listOf("accion", "fantasia", "shounen"), series.genres.map { it.slug })
        assertEquals(listOf(900 to 10, 898 to 5, 4454 to 2), series.relations.map { it.aid to it.relation })
        assertEquals(listOf(14499 to 1.0, 14500 to 2.0, 14501 to 3.0), series.chapters.map { it.eid to it.number })
        // updatedAt «2026-08-25 01:09:15+00» es martes.
        assertEquals(2, series.day)
        assertEquals("animeav1", series.source)
        // startDate «2017-10-03»: el año de estreno sale de la ficha.
        assertEquals(2017, series.year)
    }

    @Test
    fun ficheWithoutStartDateHasNoYear() {
        val node = JsonNode(JSONObject("""{"title":"Sin fecha"}"""))
        assertEquals(null, checkNotNull(source.parseSeries("sin-fecha", 1, node, emptyList())).year)
    }

    @Test
    fun fieldsUseTheSameLanguageAsHtml() {
        // Un campo puede ser una ruta sola, o un selector con regex y alternativas, igual que en las páginas HTML.
        val edited = SourceSpec.parse(JSONObject(specText).also {
            val recents = it.getJSONObject("recents")
            recents.put("name", JSONObject().put("sel", "media.title").put("regex", "^([A-Za-z]+)"))
            recents.put("slug", JSONArray().put("no.existe").put("media.slug"))
        }.toString())
        val recent = ConfigurableSource(edited).parseRecents(list("recents.json")).first()
        assertEquals("Dark", recent.name)
        assertEquals("dark-machine-the-animation", recent.slug)
    }

    @Test
    fun fileMustBeSafe() {
        assertThrows(SpecException::class.java) { SourceSpec.parse(JSONObject(specText).also { it.getJSONObject("urls").put("anime", "https://evil.example/{slug}") }.toString()) }
        // Otra web con la misma terminación, o con el dominio de la fuente solo como usuario, no es la CDN de la fuente.
        listOf("https://evil.com/{aid}.jpg", "https://animeav1.com@evil.com/{aid}.jpg", "https://animeav1.com.evil.com/{aid}.jpg", "http://cdn.animeav1.com/{aid}.jpg").forEach { cover ->
            assertThrows(SpecException::class.java) {
                SourceSpec.parse(JSONObject(specText).also { it.getJSONObject("images").put("cover", cover) }.toString())
            }
        }
        assertThrows(SpecException::class.java) {
            SourceSpec.parse(JSONObject(specText).also { it.getJSONObject("images").put("thumbnail", "https://cdn.animeav1.com/{otro}.jpg") }.toString())
        }
        // Una fuente que declara un filtro sin parámetro con el que pedirlo no es válida.
        assertThrows(SpecException::class.java) { SourceSpec.parse(JSONObject(specText).also { it.getJSONObject("urls").remove("airingParam") }.toString()) }
        assertThrows(SpecException::class.java) { SourceSpec.parse(JSONObject(specText).also { it.getJSONObject("search").put("text", "alone") }.toString()) }
        assertThrows(SpecException::class.java) { SourceSpec.parse(JSONObject(specText).also { it.getJSONObject("urls").remove("chapter") }.toString()) }
    }

    @Test
    fun theFormatFlagPicksHowPagesAreRead() {
        assertEquals("jsData", spec.format)
        assertEquals("html", SourceSpec.parse(File("src/main/assets/sources/verani.json").readText()).format)
        assertThrows(SpecException::class.java) { SourceSpec.parse(JSONObject(specText).put("format", "otro").toString()) }
    }
}

// ===== ConfigurableAnimeFlvTest.kt =====
/**
 * `animeflv.json` con fragmentos reales de animeflv.one (2026-10-20). La web redirige `animeflv.one` a `vww.animeflv.one`:
 * los enlaces vienen relativos (`./anime/x`) o con ese subdominio, y el fichero no depende de él.
 */
class ConfigurableAnimeFlvTest {
    private val spec = SourceSpec.parse(File("src/main/assets/sources/animeflv.json").readText())
    private val source = ConfigurableSource(spec)

    private fun doc(html: String) = Jsoup.parse(html)

    @Test
    fun theFileDoesNotNeedTheSubdomain() {
        assertEquals("animeflv.one", spec.host)
        assertEquals("https://animeflv.one/anime/black-clover", source.animeUrl("black-clover"))
        assertEquals("https://animeflv.one/ver/black-clover-3", source.chapterUrl("black-clover", "3"))
    }

    @Test
    fun slugOfAnimeAndEpisodeLinks() {
        assertEquals("black-clover", source.slugOf("./anime/black-clover"))
        assertEquals("black-clover", source.slugOf("https://vww.animeflv.one/anime/black-clover?x=1"))
        assertEquals("sora-wa-akai-kawa-no-hotori", source.slugOf("https://vww.animeflv.one/ver/sora-wa-akai-kawa-no-hotori-16"))
        // Un título que acaba en número conserva el suyo: solo se quita el del episodio.
        assertEquals("tantei-wa-mo-shindeiru-2", source.slugOf("./ver/tantei-wa-mo-shindeiru-2-12"))
        assertEquals("x", source.slugOf("./ver/x-3.5"))
        assertNull(source.slugOf("https://vww.animeflv.one/"))
    }

    @Test
    fun listCardsIgnoreTheEpisodesOfTheSidebar() {
        val html = """<article class="li"><figure class="i"><a href="./ver/gensou-suikoden-3"><img data-src="x" alt="Gensou Suikoden episodio 3"></a></figure></article>
            <article class="li"><figure class="i"><a href="./anime/dark-machine-the-animation" title="Ver Anime"><img width="100" height="140" data-src="https://vww.animeflv.one/cdn/img/anime/dark-machine-the-animation.webp?t=0.9" src="./cdn/img/anime.png" alt="Dark Machine: The Animation"><u class="c-a">Anime</u></a></figure>
            <h3 class="h"><a href="./anime/dark-machine-the-animation">Dark Machine: The Animation</a></h3></article>"""
        val card = source.parseCards(doc(html)).single()
        assertEquals("dark-machine-the-animation", card.slug)
        assertEquals("Dark Machine: The Animation", card.name)
        assertEquals("https://vww.animeflv.one/cdn/img/anime/dark-machine-the-animation.webp", card.cover)
    }

    @Test
    fun lastPageIsTheHighestOne() {
        val html = """<a href="./animes?pag=1">1</a><a href="./animes?pag=10">10</a><a href="./animes?pag=303">303</a><a href="./animes?pag=2">2</a>"""
        assertEquals(303, source.parseLastPage(HtmlNode(doc(html))))
        assertEquals(1, source.parseLastPage(HtmlNode(doc("<p>nada</p>"))))
    }

    @Test
    fun homeEpisodes() {
        val html = """<article class="li"><figure class="i"><a href="https://vww.animeflv.one/ver/gensou-suikoden-3" title="Ver Gensou Suikoden episodio 3"><img data-src="https://vww.animeflv.one/cdn/img/portada/gensou-suikoden.webp?t=0.11" alt="Gensou Suikoden episodio 3"><u>Episodio 3</u><span>Gensou Suikoden</span></a></figure></article>
            <article class="li"><figure class="i"><a href="./ver/sora-wa-akai-kawa-no-hotori-16"><img data-src="https://vww.animeflv.one/cdn/img/portada/sora.webp" alt="Sora wa Akai Kawa no Hotori episodio 16"><span>Sora wa Akai Kawa no Hotori</span></a></figure></article>
            <article class="li"><figure class="i"><a href="https://evil.example/ver/otra-1"><span>Otra</span></a></figure></article>"""
        val episodes = source.parseRecents(doc(html))
        assertEquals(listOf("gensou-suikoden", "sora-wa-akai-kawa-no-hotori"), episodes.map { it.slug })
        assertEquals(listOf("Gensou Suikoden", "Sora wa Akai Kawa no Hotori"), episodes.map { it.name })
        assertEquals(listOf(3.0, 16.0), episodes.map { it.number })
        assertEquals("https://vww.animeflv.one/cdn/img/portada/gensou-suikoden.webp", episodes.first().cover)
    }

    @Test
    fun fichaGivesNameDescriptionStateGenresAndTheTotalFromTheScript() {
        val html = """<html><head><meta property="og:image" content="https://vww.animeflv.one/cdn/img/anime/sora-wa-akai-kawa-no-hotori.webp?t=0.1.1"/></head><body>
            <h1>Ver Sora wa Akai Kawa no Hotori Sub español latino Online</h1>
            <div class="info-l"><figure class="i"><img data-src="x" alt="Sora wa Akai Kawa no Hotori"></figure><div class="st c-e"><span>En emision</span></div></div>
            <div class="info-r" data-ep="15" data-id="7321"><ul class="gn"><li><a href="./animes?genero=historico">Historico</a></li><li><a href="./animes?genero=shoujo">Shoujo</a></li></ul>
            <div class="tx"><p>Yuri, una bonita chica japonesa, está emocionada.</p></div></div>
            <script>var eps = [["16","1",""],["15","1",""],["1","1",""]];</script></body></html>"""
        val node = HtmlNode(doc(html))
        val (series, ficha) = checkNotNull(source.readFicha("sora-wa-akai-kawa-no-hotori", 1, node, emptyList()))
        assertEquals("Yuri, una bonita chica japonesa, está emocionada.", series.description)
        assertEquals(1, series.state)
        assertEquals(listOf("historico", "shoujo"), source.parseGenreLinks(doc(html), checkNotNull(spec.anime.genreLinks)).map { it.slug })
        assertEquals("16", spec.anime.chapters.count?.first(node))
        assertEquals("Sora wa Akai Kawa no Hotori", series.name)
        assertEquals("https://vww.animeflv.one/cdn/img/anime/sora-wa-akai-kawa-no-hotori.webp", ficha.getJSONObject("coverImage").getString("extraLarge"))
        assertEquals("RELEASING", ficha.getString("status"))
    }

    @Test
    fun serversComeFromTheRequestInHex() {
        val request = checkNotNull(spec.servers.request)
        assertEquals("/flv", request.path)
        val page = HtmlNode(doc("""<ul class="opt" data-encrypt="373332312d31"></ul>"""))
        assertEquals("opt", request.form.getValue("acc").literal)
        assertEquals("373332312d31", request.form.getValue("i").pick?.first(page))
        val response = """<li  star="1"  encrypt="68747470733a2f2f6279736573756b696f722e636f6d2f652f6b326b7135796d6374356b64"><span>filemoon 1</span></li>
            <li encrypt="68747470733a2f2f6279736573756b696f722e636f6d2f652f6b326b7135796d6374356b64"><span>filemoon 2</span></li>
            <li encrypt="6a617661736372697074"><span>no http</span></li><li encrypt="zz"><span>no hex</span></li>"""
        assertEquals(listOf("https://bysesukior.com/e/k2kq5ymct5kd"), source.parseServers(doc(response)).map { it.url })
    }

    @Test
    fun filtersFollowTheFile() {
        assertEquals(setOf(Filter.TEXT), source.pushes(SearchQuery(text = "naruto")))
        assertEquals(setOf(Filter.GENRE, Filter.AIRING), source.pushes(SearchQuery(genre = "accion")))
        assertTrue(spec.urls.searchFirst!!.endsWith("buscar={query}"))
    }
}

// ===== ConfigurableAnimeGratisTest.kt =====
/** Motor de fuentes por fichero con el `animegratis.json` que lleva la app; fragmentos de animegratis.net (2026-10-14). */
class ConfigurableAnimeGratisTest {
    private val spec = SourceSpec.parse(File("src/main/assets/sources/animegratis.json").readText())
    private val source = ConfigurableSource(spec)

    private fun doc(html: String) = Jsoup.parse(html)

    @Test
    fun urlsAndSlugs() {
        assertEquals("https://animegratis.net/anime/black-clover-anime", source.animeUrl("black-clover"))
        assertEquals("https://animegratis.net/anime/black-clover/episodio-3", source.chapterUrl("black-clover", "3"))
        assertEquals("black-clover", source.slugOf("https://animegratis.net/anime/black-clover-anime"))
        assertEquals("black-clover", source.slugOf("/anime/black-clover-anime"))
        assertEquals("black-clover-2nda-temporada", source.slugOf("/anime/black-clover-2nda-temporada/episodio-12"))
        assertNull(source.slugOf("https://animegratis.net/"))
        assertEquals(true, source.listsScores)
    }

    @Test
    fun apiResolvesEveryFilterButYearRanges() {
        assertEquals(Filter.values().toSet(), source.pushes(SearchQuery(text = "x", genre = "accion", years = 2020..2020)))
        assertEquals(Filter.values().toSet() - Filter.YEAR, source.pushes(SearchQuery(years = 2019..2021)))
    }

    @Test
    fun genrePageCardsHaveRelativeLinksAndNoCoverPrefix() {
        val html = """<a href="/anime/tank-chair-sensha-isu-anime"><img src="https://cdn.animegratis.net/anime/tank-chair-sensha-isu/images/cover.webp" alt="Portada de TANK CHAIR: Sensha Isu"></a>"""
        val card = source.parseCards(doc(html)).single()
        assertEquals("tank-chair-sensha-isu", card.slug)
        assertEquals("TANK CHAIR: Sensha Isu", card.name)
        assertEquals("https://cdn.animegratis.net/anime/tank-chair-sensha-isu/images/cover.webp", card.cover)
    }

    @Test
    fun homeEpisodesWithDate() {
        val html = """<div id="emisionGrid"><a href="/anime/gensou-suikoden/episodio-2"><img src="https://cdn.animegratis.net/anime/gensou-suikoden/images/cover.webp" alt="Gensou Suikoden Episodio 2"><span>12 oct</span></a>
            <a href="/anime/otro/episodio-1"><img alt="sin numero"></a></div>"""
        val episode = source.parseRecents(doc(html)).single()
        assertEquals("gensou-suikoden", episode.slug)
        assertEquals("Gensou Suikoden", episode.name)
        assertEquals(2.0, episode.number, 0.0)
        assertEquals("https://animegratis.net/anime/gensou-suikoden/episodio-2", episode.url)
        assertEquals(true, episode.released > 0L)
    }

    @Test
    fun scheduleDaysAreThePositionOfTheSection() {
        fun card(slug: String, title: String) = """<li data-card><a href="/anime/$slug-anime"><img src="https://cdn.animegratis.net/anime/$slug/images/cover.webp"></a><button data-title="$title"></button></li>"""
        val html = """<section data-day-section="lunes">${card("a", "Serie A")}</section><section data-day-section="martes">${card("b", "Serie B")}${card("b", "Serie B")}</section>"""
        val schedule = source.parseSchedule(doc(html))
        assertEquals(setOf(1, 2), schedule.keys)
        assertEquals("Serie B", schedule.getValue(2).single().name)
    }

    @Test
    fun sagaSplitsAtTheCurrentEntry() {
        val html = """<div id="seasons-carousel"><a href="/anime/uno-anime" title="Uno"></a><div data-saga-current></div><a href="/anime/tres-anime" title="Tres"></a></div>"""
        assertEquals(
            listOf(Triple("uno", "Uno", RELATION_PREQUEL), Triple("tres", "Tres", RELATION_SEQUEL)),
            source.parseSaga(doc(html))
        )
    }

    @Test
    fun genreChipsDropTheLeadingSymbol() {
        val html = """<nav><a href="/directorio/genero/menu">Menú</a></nav><div class="flex-wrap"><a href="/directorio/genero/accion">⚔ Acción</a><a href="/directorio/genero/accion">Acción</a></div>"""
        val genres = source.parseGenreLinks(doc(html), checkNotNull(spec.anime.genreLinks))
        assertEquals(listOf("accion" to "Acción"), genres.map { it.slug to it.name })
    }

    @Test
    fun ficheNumbersComeFromThePageText() {
        val html = """<body><h1>X</h1><p>170 episodios disponibles</p><p>Puntuación 9.5 / 10</p><p data-ag-rating-count> 11 valoraciones </p><p id="anime-synopsis-text">Sinopsis.</p></body>"""
        val page = doc(html)
        assertEquals("170", spec.anime.chapters.count!!.first(page))
        val (series, ficha) = checkNotNull(source.readFicha("x", 1, HtmlNode(page), emptyList()))
        // La nota de la web (9,5 sobre 10) se guarda sobre 5, como en todas las fuentes.
        assertEquals(4.75, ficha.getJSONObject("web").getDouble("score"), 0.001)
        assertEquals(4.75f, series.rateStars, 0.001f)
        assertEquals(11, ficha.getJSONObject("web").getInt("votes"))
        assertEquals("Sinopsis.", ficha.getJSONObject("description").getString("es"))
        assertEquals("24", spec.anime.chapters.count.first(doc("<p>24 de 24 episodios</p>")))
    }

    @Test
    fun embeddedServersCompleteProtocolRelativeUrls() {
        val html = """<script>var x=[{"key":"ja-1","name":"JA","players":[{"name":"HLS","url":"//a.example/e?x=1&y=2"},{"name":"Voe","url":"https://voe.sx/e/b"},{"name":"vacío","url":""}]}];</script>"""
        assertEquals(
            listOf(ServerLink("https://a.example/e?x=1&y=2"), ServerLink("https://voe.sx/e/b")),
            source.parseEmbeddedServers(html, checkNotNull(spec.servers.embedded))
        )
        assertEquals(emptyList<ServerLink>(), source.parseEmbeddedServers("<p>nada</p>", checkNotNull(spec.servers.embedded)))
    }
}

// ===== ConfigurableAnimeflvArTest.kt =====
/**
 * La fuente animeflv.ar (WordPress, tema «animestream») con el `animeflvar.json` que lleva la app. Los ficheros de `fixtures/animeflvar` son
 * bloques tal cual de las páginas reales (2026-11-04): listado, ficha de «Sora wa Akai Kawa no Hotori», capítulo 18 y portada.
 */
class ConfigurableAnimeflvArTest {
    private val spec = SourceSpec.parse(File("src/main/assets/sources/animeflvar.json").readText())
    private val source = ConfigurableSource(spec)

    private fun page(name: String) = HtmlNode(Jsoup.parse(File("src/test/fixtures/animeflvar/$name").readText(), "https://animeflv.ar/"))

    @Test
    fun slugComesFromTheSeriesOrTheEpisodeUrl() {
        assertEquals("sora-wa-akai-kawa-no-hotori", source.slugOf("https://animeflv.ar/anime/sora-wa-akai-kawa-no-hotori/"))
        assertEquals("sora-wa-akai-kawa-no-hotori", source.slugOf("https://animeflv.ar/sora-wa-akai-kawa-no-hotori-episodio-18-sub-espanol/"))
        assertNull(source.slugOf("https://animeflv.ar/anime/page/2/"))
        assertNull(source.slugOf("https://animeflv.ar/"))
    }

    @Test
    fun urlsRoundTrip() {
        assertEquals("https://animeflv.ar/anime/black-clover/", source.animeUrl("black-clover"))
        assertEquals("https://animeflv.ar/black-clover-episodio-3-sub-espanol/", source.chapterUrl("black-clover", "3"))
        assertEquals("black-clover", source.slugOf(source.chapterUrl("black-clover", "3")))
        assertEquals("AnimeFLV.ar", source.name)
    }

    @Test
    fun listCardsWithTheirAiringMark() {
        val cards = source.parseCards(page("listado.html"))
        assertEquals(listOf("dorohedoro-season-2", "doumo-suki-na-hito-ni-horegusuri-wo-irai-sareta-majo-desu", "dr-stone-science-future"), cards.map { it.slug })
        assertEquals(listOf(true, false, false), cards.map { it.airing })
        assertEquals("Dorohedoro Season 2", cards.first().name)
        // La portada sale sin el «?resize=247,350» que la achica.
        assertTrue(cards.first().cover!!.startsWith("https://i") && "?" !in cards.first().cover!! && "/wp-content/uploads/" in cards.first().cover!!)
    }

    @Test
    fun lastPageComesFromTheNextLinkAndStopsWithoutIt() {
        // La web solo da «Previous» y «Next»: con «Next» (?page=3) hay al menos 3 páginas; sin él, es la última.
        assertEquals(3, source.parseLastPage(page("listado.html")))
        assertEquals(1, source.parseLastPage(page("listado-ultima.html")))
    }

    @Test
    fun ficheTitleStatusYearStudioGenresAndChapters() {
        val node = page("ficha.html")
        val (series, ficha) = checkNotNull(source.readFicha("sora-wa-akai-kawa-no-hotori", 1, node, source.parseChapters(node)))
        assertEquals("Sora wa Akai Kawa no Hotori", series.name)
        assertEquals(1, series.state) // «Ongoing»
        assertEquals(2026, series.year)
        assertEquals("Tatsunoko Production", series.studio)
        assertEquals(listOf("Action", "Adventure", "Drama", "Historical", "Romance", "Shoujo", "Supernatural", "Time Travel"), series.genres.map { it.name })
        assertEquals(listOf("action", "time-travel"), series.genres.map { it.slug }.let { listOf(it.first(), it.last()) })
        assertEquals(listOf(18.0, 17.0, 16.0), source.parseChapters(node).map { it.number })
        assertEquals("https://animeflv.ar/sora-wa-akai-kawa-no-hotori-episodio-18-sub-espanol/", source.parseChapters(node).first().url)
        assertTrue(series.description.startsWith("Yuri, a pretty Japanese girl"))
        // Sin el «(Source: VIZ Media)» ni los textos de relleno para buscadores.
        assertFalse("VIZ" in series.description || "Watch streaming" in series.description)
        assertEquals("https://animeflv.ar/wp-content/uploads/2026/07/1783455075-7373-158138.jpg", ficha.getJSONObject("coverImage").getString("extraLarge"))
    }

    @Test
    fun serversAreTheIframeSrcOfEachBase64Option() {
        val servers = source.parseServers(page("capitulo.html"))
        assertEquals(4, servers.size)
        assertTrue(servers[0].url.startsWith("https://www.blogger.com/video.g?token=TOKEN_DE_PRUEBA&"))
        assertEquals("https://player.zilla-networks.com/m3u8/ecc9c49422b8fc5582d969e4647e5f0a", servers[1].url)
        assertEquals("https://www.mp4upload.com/embed-3fik3hzg6kqy.html", servers[2].url)
        assertEquals("https://voe.sx/e/l9ouf1gujyjl", servers[3].url)
    }

    @Test
    fun recentsAreTheLatestEpisodesWithTheirSeriesName() {
        val episodes = source.parseRecents(page("inicio.html"))
        assertEquals(2, episodes.size)
        assertEquals("dark-machine-the-animation", episodes[0].slug)
        assertEquals("Dark Machine: The Animation", episodes[0].name)
        assertEquals(4.0, episodes[0].number, 0.0)
        assertEquals("https://animeflv.ar/dark-machine-the-animation-episodio-4-sub-espanol/", episodes[0].url)
        assertEquals("sora-wa-akai-kawa-no-hotori", episodes[1].slug)
        assertEquals(18.0, episodes[1].number, 0.0)
    }

    @Test
    fun iframeUrlDecoder() {
        fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())
        assertEquals("https://voe.sx/e/abc", decodeEmbedIframeUrl(b64("""<iframe src="https://voe.sx/e/abc" width="100%"></iframe>""")))
        assertEquals("https://x.com/e/1", decodeEmbedIframeUrl(b64("""<center><IFRAME height="380" SRC='https://x.com/e/1'></iframe></center>""")))
        assertEquals("https://x.com/e?a=1&b=2", decodeEmbedIframeUrl(b64("""<iframe src="https://x.com/e?a=1&amp;b=2"></iframe>""")))
        assertEquals("https://x.com/e/2", decodeEmbedIframeUrl(b64("""<iframe src="//x.com/e/2"></iframe>""")))
        assertEquals("https://x.com/e/3", decodeEmbedIframeUrl(b64("https://x.com/e/3")))
        assertNull(decodeEmbedIframeUrl(b64("""<iframe src="javascript:alert(1)"></iframe>""")))
        assertNull(decodeEmbedIframeUrl(b64("<p>sin iframe</p>")))
        assertNull(decodeEmbedIframeUrl("esto no es base64 !!"))
    }
}

// ===== ConfigurableChallengeFallbackTest.kt =====
/** animegratis responde 403 `{"challenge":true}` en su API: sin texto de búsqueda se recorre su directorio HTML; con texto, el fallo se propaga. */
class ConfigurableChallengeFallbackTest {
    private val spec = SourceSpec.parse(File("src/main/assets/sources/animegratis.json").readText())
    private val paths = mutableListOf<String>()

    @Before
    fun setUp() {
        SourceHttp.clientOverride = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            paths += request.url.encodedPath
            val api = request.url.encodedPath.startsWith("/api/")
            val body = if (api) """{"error":"Verificación de seguridad pendiente","challenge":true}""" else "<html></html>"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (api) 403 else 200).message("test")
                .body(body.toResponseBody((if (api) "application/json" else "text/html").toMediaType())).build()
        }).build()
    }

    @After
    fun tearDown() { SourceHttp.clientOverride = null }

    @Test
    fun `sin texto una API que pide verificacion pasa al directorio HTML`() = runTest {
        val page = ConfigurableSource(spec).search(SearchQuery(), null)
        assertEquals(listOf("/api/animes/directory", "/directorio"), paths)
        assertTrue(page.items.isEmpty())
    }

    @Test
    fun `con texto de busqueda el fallo de la API se propaga y no se mira el HTML`() = runTest {
        try {
            ConfigurableSource(spec).search(SearchQuery(text = "naruto"), null)
            fail("debía lanzar")
        } catch (e: HttpStatusException) {
            assertEquals(403, e.statusCode)
        }
        assertEquals(listOf("/api/animes/directory"), paths)
    }
}

// ===== ConfigurableJkanimeTest.kt =====
/** Motor de fuentes por fichero con el `jkanime.json` que lleva la app; fragmentos de jkanime.net (2026-10-14). */
class ConfigurableJkanimeTest {
    private val spec = SourceSpec.parse(File("src/main/assets/sources/jkanime.json").readText())
    private val source = ConfigurableSource(spec)

    private fun doc(html: String) = Jsoup.parse(html)

    @Test
    fun urlsSlugsAndIds() {
        assertEquals("https://jkanime.net/black-clover-tv/", source.animeUrl("black-clover-tv"))
        assertEquals("https://jkanime.net/black-clover-tv/3/", source.chapterUrl("black-clover-tv", "3"))
        assertEquals("black-clover-tv", source.slugOf("https://jkanime.net/black-clover-tv/"))
        assertEquals("black-clover-tv", source.slugOf("https://jkanime.net/black-clover-tv/12/"))
        assertNull(source.slugOf("https://jkanime.net/directorio"))
        assertNull(source.slugOf("https://jkanime.net/"))
        // El id de episodio conserva el decimal («slug#1.0») para no perder favoritos ni historial.
        assertEquals(stableId("black-clover-tv#1.0"), source.episodeEid("black-clover-tv", 1.0))
    }

    @Test
    fun directoryComesFromTheEmbeddedJson() {
        val html = """<script>var animes = {"total":3,"data":[{"slug":"25-jigen","title":"2.5-jigen","image":"https://cdn.jkdesa.com/assets/images/animes/image/25-jigen.jpg"},
            {"slug":"directorio","title":"no es serie"},{"slug":"otra","title":""}]}; var x = 1;</script>"""
        val cards = source.parseJsonList(html)
        assertEquals(listOf("25-jigen", "otra"), cards.map { it.slug })
        assertEquals("2.5-jigen", cards[0].name)
        assertEquals(emptyList<String>(), source.parseJsonList("<p>sin lista</p>").map { it.slug })
    }

    @Test
    fun filtersAreQueryParameters() {
        assertEquals(setOf(Filter.TEXT), source.pushes(SearchQuery(text = "naruto", years = 2020..2020)))
        assertEquals(setOf(Filter.GENRE, Filter.AIRING, Filter.SORT_RATING), source.pushes(SearchQuery(genre = "accion")))
        assertEquals(setOf(Filter.GENRE, Filter.AIRING, Filter.SORT_RATING, Filter.YEAR), source.pushes(SearchQuery(years = 2020..2020)))
    }

    @Test
    fun searchCards() {
        val html = """<div class="anime__item"><a href="https://jkanime.net/black-clover-tv/"><img></a><div class="anime__item__text"><h5>Black Clover (TV)</h5></div></div>"""
        val card = source.parseCards(doc(html)).single()
        assertEquals("black-clover-tv", card.slug)
        assertEquals("Black Clover (TV)", card.name)
    }

    @Test
    fun homeEpisodes() {
        val html = """<a href="https://jkanime.net/dark-machine/1/"><div class="d-thumb"></div><h5>Dark Machine</h5><span>hace 2 horas</span></a>
            <a href="https://jkanime.net/solo-ficha/"><div class="d-thumb"></div><h5>Sin número</h5></a>"""
        val episode = source.parseRecents(doc(html)).single()
        assertEquals("dark-machine", episode.slug)
        assertEquals("Dark Machine", episode.name)
        assertEquals(1.0, episode.number, 0.0)
        assertEquals(true, episode.released > 0L)
    }

    @Test
    fun scheduleJoinsLinksOfTheSameSeriesKeepingTheNamedOne() {
        val html = """<div class="semana"><div class="boxx"><a href="https://jkanime.net/uno/"><img src="https://cdn.jkdesa.com/assets/images/animes/image/uno.jpg" title="Uno"></a>
            <a href="https://jkanime.net/uno/"><span>texto</span></a><a href="https://jkanime.net/dos/"><span>sin imagen</span></a></div></div>
            <div class="semana"><div class="boxx"><a href="https://jkanime.net/tres/"><img title="Tres"></a></div></div>"""
        val schedule = source.parseSchedule(doc(html))
        assertEquals(setOf(1, 2), schedule.keys)
        assertEquals(listOf("uno" to "Uno", "dos" to "Dos"), schedule.getValue(1).map { it.slug to it.name })
    }

    @Test
    fun relationsTakeTheTypeOfTheirGroupTitle() {
        val html = """<div><h3 class="mb-3">Temporadas y relacionados</h3><h5>Precuela</h5><a href="https://jkanime.net/uno/">Uno (Serie)</a>
            <h5>Secuela</h5><a href="https://jkanime.net/dos/">Dos (OVA)</a><a href="https://jkanime.net/directorio">no</a></div>"""
        assertEquals(
            listOf(Triple("uno", "Uno", RELATION_PREQUEL), Triple("dos", "Dos", RELATION_SEQUEL)),
            source.parseSaga(doc(html))
        )
    }

    @Test
    fun serversOwnPlayersThenTheJsArrayDecoded() {
        val html = """<script>video[1] = '<iframe class="player_conte" src="https://jkanime.net/jkplayer/um?e=abc" frameborder="0"></iframe>';
            var servers = [{"remote":"aHR0cHM6Ly92b2Uuc3gvZS9iYmI=","server":"VOE"},{"remote":"bm8gZXMgdW5hIHVybA==","server":"roto"}];</script>"""
        assertEquals(
            listOf(ServerLink("https://jkanime.net/jkplayer/um?e=abc"), ServerLink("https://voe.sx/e/bbb")),
            source.parseTextServers(html, checkNotNull(spec.servers.text))
        )
    }

    @Test
    fun episodeApiIsDescribedByTheFile() {
        val ajax = checkNotNull(spec.anime.chapters.ajax)
        val request = checkNotNull(ajax.request)
        assertEquals("POST", request.method)
        assertEquals("{token}", request.form["_token"])
        assertEquals(true, request.cookies)
        assertEquals("https://jkanime.net/ajax/episodes/{id}/{page}", ajax.url!!.replace("{base}", spec.base))
        assertEquals(16.0, checkNotNull(ajax.paged).perPage, 0.0)
        assertEquals("timestamp", ajax.date)
    }
}

// ===== ConfigurableMonosChinosTest.kt =====
/** Motor de fuentes por fichero con el `monoschinos.json` que lleva la app; fragmentos reales de monoschinos.st (2026-10-14). */
class ConfigurableMonosChinosTest {
    private val spec = SourceSpec.parse(File("src/main/assets/sources/monoschinos.json").readText())
    private val source = ConfigurableSource(spec)

    private fun doc(html: String) = Jsoup.parse(html)

    @Test
    fun slugDropsSiteSuffixAndEpisode() {
        assertEquals("black-clover", source.slugOf("https://monoschinos.st/anime/black-clover-sub-espanol"))
        assertEquals("tantei-wa-mo-shindeiru-temporada-2", source.slugOf("https://monoschinos.st/ver/tantei-wa-mo-shindeiru-temporada-2-episodio-1"))
        assertEquals("black-clover", source.slugOf("https://monoschinos.st/anime/black-clover-sub-espanol?p=2"))
        assertNull(source.slugOf("https://monoschinos.st/"))
    }

    @Test
    fun urlsRoundTrip() {
        assertEquals("https://monoschinos.st/anime/black-clover-sub-espanol", source.animeUrl("black-clover"))
        assertEquals("https://monoschinos.st/ver/black-clover-episodio-3", source.chapterUrl("black-clover", "3"))
        assertEquals(true, source.needsBrowserUa)
        assertEquals("MC", source.badge)
    }

    @Test
    fun listCards() {
        val html = """<article><a href="https://monoschinos.st/anime/dark-machine-the-animation-sub-espanol" class="card-wrap block group">
            <img class="lazy" src="https://monoschinos.st/img/anime.png" data-src="https://monoschinos.st/assets/thumbs/imagen/dark-machine-the-animation-1791917944.jpg.webp?v=6" alt="DARK MACHINE">
            <h3 class="card-title">DARK MACHINE -The Animation-</h3><div><span>2026</span><span>Ciencia Ficción</span></div></a></article>"""
        val card = source.parseCards(doc(html)).single()
        assertEquals("dark-machine-the-animation", card.slug)
        assertEquals("DARK MACHINE -The Animation-", card.name)
        assertEquals(2026, card.year)
        assertEquals("https://monoschinos.st/assets/thumbs/imagen/dark-machine-the-animation-1791917944.jpg.webp", card.cover)
    }

    @Test
    fun homeEpisodes() {
        val html = """<article><a href="https://monoschinos.st/ver/ao-no-hako-episodio-27" class="card-wrap block group">
            <img data-src="https://monoschinos.st/assets/thumbs/portada/ao-no-hako-portada-1791134353.jpg.webp?v=6"><div>EP 27</div><h3 class="card-title">Ao no Hako</h3></a></article>
            <a class="card-wrap" href="https://monoschinos.st/anime/otra-sub-espanol"><h3 class="card-title">Otra</h3></a>"""
        val episode = source.parseRecents(doc(html)).single()
        assertEquals("ao-no-hako", episode.slug)
        assertEquals("Ao no Hako", episode.name)
        assertEquals(27.0, episode.number, 0.0)
        assertEquals("https://monoschinos.st/assets/thumbs/portada/ao-no-hako-portada-1791134353.jpg.webp", episode.cover)
    }

    @Test
    fun episodeApiGivesNumbersAndTheContextComesFromTheFicha() {
        val ajaxSpec = checkNotNull(spec.anime.chapters.ajax)
        val episodes = AjaxEpisodes("monoschinos", spec.base, ajaxSpec)
        val json = JSONObject("""{"paginate_url":"x","perpage":50,"eps":[{"num":2},{"num":1},{"num":2},{"num":10}]}""")
        assertEquals(listOf(2.0, 1.0, 2.0, 10.0), episodes.numbersOf(json))
        assertEquals(emptyList<Double>(), episodes.numbersOf(JSONObject("{}")))
        // La URL de la API sale de data-ajax de la ficha y debe ser del propio sitio.
        val ficha = doc("""<section class="caplist" data-ajax="https://monoschinos.st/ajax/ajax_pagination/162"></section>""")
        val ctx = episodes.context("black-clover", "", ficha, emptyMap(), "https://monoschinos.st/anime/black-clover-sub-espanol") { ajaxSpec.from!!.first(it) }
        assertEquals("https://monoschinos.st/ajax/ajax_pagination/162", ctx?.directUrl)
        val other = doc("""<section class="caplist" data-ajax="https://otro.example/x"></section>""")
        assertNull(episodes.context("black-clover", "", other, emptyMap(), "r") { ajaxSpec.from!!.first(it) })
        assertEquals("https://monoschinos.st/ver/black-clover-episodio-10", source.chapterUrl("black-clover", "10"))
    }

    @Test
    fun serversDecodedAndUpgradedToHttps() {
        val html = """<button class="srv play-video" data-player="aHR0cDovL29rLnJ1L3ZpZGVvZW1iZWQvNTE1OTg2MTc1MDQzMQ==">ok</button>
            <button class="srv play-video" data-player="aHR0cHM6Ly93d3cubXA0dXBsb2FkLmNvbS9lbWJlZC1ldGlhbWZsM2h0c2QuaHRtbA==">mp4upload</button>
            <button class="srv play-video" data-player="aHR0cHM6Ly93d3cubXA0dXBsb2FkLmNvbS9lbWJlZC1ldGlhbWZsM2h0c2QuaHRtbA==">mp4upload</button>
            <button class="srv play-video" data-player="bm8gZXMgdW5hIHVybA==">roto</button>"""
        assertEquals(
            listOf("https://ok.ru/videoembed/5159861750431", "https://www.mp4upload.com/embed-etiamfl3htsd.html"),
            source.parseServers(doc(html)).map { it.url }
        )
    }

    @Test
    fun scheduleByDay() {
        val html = """<section data-day="lunes"><a href="https://monoschinos.st/anime/doumo-sub-espanol" class="rel-card"><img alt="Doumo, Suki na Hito"><h3>Doumo...</h3></a></section>
            <section class="day-col" data-day="miercoles"><a href="https://monoschinos.st/anime/otra-sub-espanol" class="rel-card"><img alt="Otra"></a></section>
            <section class="day-col" data-day="lunes"><a href="https://monoschinos.st/anime/doumo-sub-espanol" class="rel-card"><img alt="Doumo, Suki na Hito"></a></section>"""
        val schedule = source.parseSchedule(doc(html))
        assertEquals(setOf(1, 3), schedule.keys)
        assertEquals("Doumo, Suki na Hito", schedule.getValue(1).single().name)
    }

    @Test
    fun genresOfTheHeader() {
        val html = """<nav><a href="https://monoschinos.st/genero/menu">Menú</a></nav>
            <div><h1>Black Clover</h1><div><a href="https://monoschinos.st/genero/accion">Acción</a><a href="https://monoschinos.st/genero/comedia">Comedia</a></div></div>"""
        val links = checkNotNull(sourceSpecGenreLinks())
        assertEquals(listOf("accion", "comedia"), source.parseGenreLinks(doc(html), links).map { it.slug })
    }

    private fun sourceSpecGenreLinks() =
        SourceSpec.parse(File("src/main/assets/sources/monoschinos.json").readText()).anime.genreLinks

    @Test
    fun lastPageIgnoresTheCurrentOneAndOtherLinks() {
        val html = """<a href="https://monoschinos.st/animes?p=1">1</a><a href="https://monoschinos.st/animes?p=2">2</a>
            <a href="https://monoschinos.st/animes?p=186">186</a><a href="https://monoschinos.st/otra">otra</a>"""
        assertEquals(186, source.parseLastPage(doc(html)))
        assertEquals(1, source.parseLastPage(doc("<p>nada</p>")))
    }

    @Test
    fun filtersFollowTheFile() {
        assertEquals(setOf(Filter.TEXT), source.pushes(SearchQuery(text = "naruto")))
        assertEquals(setOf(Filter.GENRE, Filter.AIRING), source.pushes(SearchQuery(genre = "accion")))
        assertEquals(setOf(Filter.GENRE, Filter.AIRING, Filter.YEAR), source.pushes(SearchQuery(years = 2020..2020)))
        assertEquals(setOf(Filter.GENRE, Filter.AIRING), source.pushes(SearchQuery(years = 2019..2021)))
    }
}

// ===== ConfigurableSourceTest.kt =====
/** Motor de fuentes por fichero con el `verani.json` que lleva la app; fragmentos reales de verani.me (2026-10-14). */
class ConfigurableSourceTest {
    private val source = ConfigurableSource(SourceSpec.parse(File("src/main/assets/sources/verani.json").readText()))

    private fun doc(html: String) = Jsoup.parse(html)

    @Test
    fun identityComesFromTheFile() {
        assertEquals("verani", source.id)
        assertEquals("VA", source.badge)
        assertEquals("verani.me", source.host)
        assertEquals("https://verani.me/black-clover/", source.animeUrl("black-clover"))
        assertEquals(true, source.needsBrowserUa)
    }

    @Test
    fun directoryCardsUseTheCardLink() {
        val html = """<div class="animes-archive-grid"><div class="content-card anime-card" data-aos="fade-up">
            <a href="https://verani.me/danjo-no-yuujou/" class="card-link"><div class="card-image"><img src="https://image.tmdb.org/t/p/w500/74Oi.jpg" alt="Danjo no Yuujou"></div>
            <div class="overlay-meta"><span class="meta-item">2025</span> <span class="meta-item status-en-emision">En Emisión</span></div></a></div>
            <div class="content-card anime-card"><a href="https://verani.me/dorohedoro/" class="card-link"><img src="https://image.tmdb.org/t/p/w500/x.jpg" alt="Dorohedoro">
            <span class="meta-item">2020</span><span class="meta-item status-finalizado">Finalizado</span></a></div></div>"""
        val cards = source.parseCards(doc(html))
        assertEquals(listOf("danjo-no-yuujou", "dorohedoro"), cards.map { it.slug })
        assertEquals(listOf(true, false), cards.map { it.airing })
        assertEquals(2025, cards[0].year)
        assertEquals("https://image.tmdb.org/t/p/w500/74Oi.jpg", cards[0].cover)
    }

    @Test
    fun searchCardsUseTheOnclick() {
        val html = """<article class="content-card anime-card post-53490 anime" style="cursor:pointer" onclick="window.location.href='https://verani.me/mugyutto-black-clover/'">
            <img src="https://image.tmdb.org/t/p/w500/wc42.jpg" alt="Mugyutto! Black Clover"><span class="meta-item">2019</span></article>"""
        val card = source.parseCards(doc(html)).single()
        assertEquals("mugyutto-black-clover", card.slug)
        assertEquals("Mugyutto! Black Clover", card.name)
        assertEquals(2019, card.year)
    }

    @Test
    fun lastPageFromPagination() {
        val html = """<a href="https://verani.me/animes/page/2/">2</a><a href="https://verani.me/animes/page/3/">3</a><a href="https://verani.me/animes/page/57/">57</a>"""
        assertEquals(57, source.parseLastPage(doc(html)))
        assertEquals(1, source.parseLastPage(doc("<p>sin paginas</p>")))
    }

    @Test
    fun chaptersByAscendingSeasonSkippingRepeatedNumbers() {
        fun card(url: String, number: Int) = """<div class="capitulo-card"><a href="$url" class="capitulo-card-link"><h3 class="capitulo-card-title"> Capitulo $number</h3></a></div>"""
        val html = """<div class="temporada-group"><h3 class="temporada-title"><span class="temporada-name" data-season-num="2">Temporada 2</span></h3>
            ${card("https://verani.me/black-clover/capitulo-2-episodio-2-575/", 2)}${card("https://verani.me/black-clover/capitulo-1-episodio-1-559/", 1)}</div>
            <div class="temporada-group"><h3 class="temporada-title"><span class="temporada-name" data-season-num="1">Temporada 1</span></h3>
            ${card("https://verani.me/black-clover/3-el-juramento/", 3)}${card("https://verani.me/black-clover/2-juramento/", 2)}${card("https://verani.me/black-clover/1-asta-y-yuno/", 1)}</div>"""
        val chapters = source.parseChapters(doc(html))
        // Temporada 1 primero: sus capítulos 1 y 2 ganan a los de la temporada 2.
        assertEquals(listOf(3.0, 2.0, 1.0), chapters.map { it.number })
        assertEquals("https://verani.me/black-clover/1-asta-y-yuno/", chapters.last().url)
    }

    @Test
    fun chapterLinksOutsideTheSiteAreIgnored() {
        val html = """<div class="capitulo-card"><a href="https://otro.example/x/1/"><h3 class="capitulo-card-title">Capitulo 1</h3></a></div>"""
        assertEquals(emptyList<Double>(), source.parseChapters(doc(html)).map { it.number })
    }

    @Test
    fun homeEpisodesWithNameAndNumber() {
        val html = """<div class="content-card capitulo-card" style="cursor:pointer" onclick="window.location.href='https://verani.me/sora-wa-akai-kawa-no-hotori/capitulo-15-episodio-15-125/'">
            <img src="https://image.tmdb.org/t/p/w500/5LP.jpg" alt="Sora wa Akai Kawa no Hotori Preview de Episodio 15">
            <div class="episode-number-badge"><span class="badge-season">T1</span> <span class="badge-ep">Ep. 15</span></div></div>"""
        val episode = source.parseRecents(doc(html)).single()
        assertEquals("sora-wa-akai-kawa-no-hotori", episode.slug)
        assertEquals("Sora wa Akai Kawa no Hotori", episode.name)
        assertEquals(15.0, episode.number, 0.0)
        assertEquals("https://verani.me/sora-wa-akai-kawa-no-hotori/capitulo-15-episodio-15-125/", episode.url)
    }

    @Test
    fun recentDatesAreUtc() {
        val json = JSONArray("""[{"link":"https://verani.me/a/b/","date_gmt":"2026-10-14T05:19:24"},{"link":"https://verani.me/c/d/","date_gmt":"roto"}]""")
        val dates = source.parseRecentDates(json)
        assertEquals(setOf("https://verani.me/a/b/"), dates.keys)
        assertEquals(1791955164000L, dates.getValue("https://verani.me/a/b/"))
    }

    @Test
    fun genresFromRestClasses() {
        val classes = JSONArray("""["post-8113","anime","genero-accion","genero-ciencia-ficcion","genero-accion","hentry"]""")
        assertEquals(listOf("accion", "ciencia-ficcion"), source.parseGenreClasses(classes).map { it.slug })
    }

    @Test
    fun serversSubtitledFirstAndAlwaysHttps() {
        val html = """<button class="servidor-tab active" data-url="https://bysekoze.com/e/aaa" data-idioma="Español Latino"></button>
            <button class="servidor-tab" data-url="http://voe.sx/e/bbb" data-idioma="Japonés" data-subtitulos="Español"></button>
            <button class="servidor-tab" data-url="https://www.mp4upload.com/embed-ccc.html" data-idioma="Japonés"></button>
            <button class="servidor-tab" data-url="https://voe.sx/e/bbb" data-idioma="Japonés"></button>
            <button class="servidor-tab" data-url="no-es-url" data-idioma="Japonés"></button>"""
        assertEquals(
            listOf(ServerLink("https://voe.sx/e/bbb", "SUB"), ServerLink("https://www.mp4upload.com/embed-ccc.html", "SUB"), ServerLink("https://bysekoze.com/e/aaa", "Latino")),
            source.parseServers(doc(html))
        )
    }

    @Test
    fun slugIsTheFirstSegment() {
        assertEquals("black-clover", source.slugOf("https://verani.me/black-clover/1-asta-y-yuno/"))
        assertEquals("black-clover", source.slugOf("https://verani.me/black-clover/"))
        assertEquals("black-clover", source.slugOf("https://verani.me/black-clover"))
        assertNull(source.slugOf("https://verani.me/animes/accion/"))
        assertNull(source.slugOf("https://verani.me/"))
        assertNull(source.slugOf("https://otro.example/black-clover/"))
    }

    @Test
    fun siteLogoIsNotACover() {
        val logo = "https://verani.me/wp-content/uploads/2026/01/Gemini_Generated_Image_y27f1qy27f1qy27f-1-1024x401.png"
        val spec = SourceSpec.parse(File("src/main/assets/sources/verani.json").readText())
        assertEquals(true, spec.coverReject!!.containsMatchIn(logo))
        assertEquals(false, spec.coverReject.containsMatchIn("https://image.tmdb.org/t/p/w500/x.jpg"))
        // Sin portada propia no hay URL de relleno: la tarjeta usa la de otra fuente.
        assertEquals("", source.coverUrl(1, "serie-sin-portada"))
    }

    @Test
    fun filtersFollowTheFile() {
        assertEquals(setOf(Filter.TEXT), source.pushes(SearchQuery(text = "naruto")))
        assertEquals(setOf(Filter.GENRE, Filter.AIRING), source.pushes(SearchQuery(text = "naruto", genre = "accion")))
        assertFalse(Filter.YEAR in source.pushes(SearchQuery(years = 2020..2020)))
    }
}

// ===== CoverCandidatesTest.kt =====
/** Las portadas que se calculan con el id (animeav1) solo valen con un id de esa web: los ids de las demás fuentes empiezan en NEW_ID_BASE. */
class CoverCandidatesTest {
    private fun source(file: String) = ConfigurableSource(SourceSpec.parse(File("src/main/assets/sources/$file.json").readText()))
    private val sources = listOf(source("animeav1"), source("animegratis"))

    @Test
    fun `con un id de animeav1 se prueba su portada por id`() {
        assertTrue("https://cdn.animeav1.com/covers/4453.jpg" in coverCandidates(4453, "magicalexplorer", "", sources))
    }

    @Test
    fun `con un id de otra fuente no se prueba la portada por id de animeav1`() {
        val aid = AnimeIds.NEW_ID_BASE + 5
        val candidates = coverCandidates(aid, "magicalexplorer", "", sources)
        assertFalse(candidates.any { it.contains("cdn.animeav1.com/covers/") })
        // Las fuentes que calculan la portada con el slug siguen entrando.
        assertTrue(candidates.any { it.contains("cdn.animegratis.net") })
    }

    @Test
    fun `la portada preferida va antes que las de las fuentes`() {
        assertEquals("https://x/y.jpg", coverCandidates(4453, "magicalexplorer", "https://x/y.jpg", sources).first { it.startsWith("https://x") })
    }
}

// ===== CoverStoreTest.kt =====
class CoverStoreTest {
    private val cdn = "https://cdn.example/covers/"

    private fun store(prefs: FakePreferences = FakePreferences()) =
        CoverStore(fallback = { "${cdn}default/$it.jpg" }, accepts = { slug, url, hasStored ->
            url.startsWith(cdn) && url != "${cdn}default/$slug.jpg" && (!url.contains("banner") || !hasStored)
        }, prefs = { prefs })

    @Test
    fun fallbackUntilRemembered() {
        val covers = store()
        assertEquals("${cdn}default/a.jpg", covers.url("a"))
        covers.remember("a", "${cdn}a-123.jpg")
        assertEquals("${cdn}a-123.jpg", covers.url("a"))
    }

    @Test
    fun rejectedUrlsAreIgnored() {
        val covers = store()
        covers.rememberAll(listOf("a" to "https://otro.host/a.jpg", "b" to null, "c" to "${cdn}default/c.jpg"))
        assertNull(covers.stored("a"))
        assertNull(covers.stored("b"))
        assertNull(covers.stored("c"))
    }

    @Test
    fun survivesNewInstanceThroughPrefs() {
        val prefs = FakePreferences()
        store(prefs).remember("a", "${cdn}a-1.jpg")
        assertEquals("${cdn}a-1.jpg", store(prefs).url("a"))
    }

    @Test
    fun hasStoredDecidesWeakerCovers() {
        val covers = store()
        covers.remember("a", "${cdn}banner-a.jpg")
        assertEquals("${cdn}banner-a.jpg", covers.url("a"))
        covers.remember("a", "${cdn}banner-other.jpg")
        assertEquals("${cdn}banner-a.jpg", covers.url("a"))
        covers.remember("a", "${cdn}a-final.jpg")
        assertEquals("${cdn}a-final.jpg", covers.url("a"))
    }

    @Test
    fun firstBannerOfABatchWins() {
        val covers = store()
        covers.rememberAll(listOf("a" to "${cdn}banner-1.jpg", "a" to "${cdn}banner-2.jpg"))
        assertEquals("${cdn}banner-1.jpg", covers.url("a"))
    }

    @Test
    fun batchWritesOnce() {
        val prefs = FakePreferences()
        store(prefs).rememberAll(listOf("a" to "${cdn}a-1.jpg", "b" to "${cdn}b-1.jpg"))
        assertEquals(setOf("a", "b"), prefs.all.keys)
    }
}

// ===== EntrySearchFoldTest.kt =====
/** Búsqueda de series al unirlas: sin acentos ni diferencias de vocal larga. */
class EntrySearchFoldTest {
    private fun entry(key: String, name: String) = CatalogEntry(key, 1, name, null, 0, null, 0L)

    @Test
    fun `la busqueda encuentra el nombre con macron escribiendolo de cualquier forma`() {
        // «Hy\u014dken» con el macron escrito como escape, para no depender de la codificación del fichero.
        val entries = listOf(entry("1", "Hy\u014dken no Majutsushi ga Sekai wo Suberu"), entry("2", "Naruto"))
        assertEquals(listOf("1"), EntrySearch.byNormalizedName(entries, "hyouken majutsushi").map { it.key })
        assertEquals(listOf("1"), EntrySearch.byNormalizedName(entries, "hyoken").map { it.key })
        assertEquals(listOf("1"), EntrySearch.byNormalizedName(entries, "Hy\u014dken").map { it.key })
        assertEquals(emptyList<String>(), EntrySearch.byNormalizedName(entries, "bleach").map { it.key })
    }

    @Test
    fun `las palabras sueltas se buscan dentro del nombre sin espacios y sin simbolos`() {
        assertEquals(true, nameMatches("Magical\u2605Explorer: Eroge no Yuujin Chara", "Magical Explorer"))
        assertEquals(true, nameMatches("Kashita Maryoku wa \"Revo Barai\"", "kashita mary"))
        assertEquals(false, nameMatches("Naruto", "naruto shippuden"))
    }

    @Test
    fun `la primera palabra se corta antes del primer simbolo y la mas larga es la mas selectiva`() {
        assertEquals("Magical", firstWord("Magical\u2605Explorer: Eroge"))
        assertEquals("Hy\u014dken", firstWord("  Hy\u014dken no Majutsushi"))
        assertEquals("Explorer", longestWord("Magical Explorer"))
        assertEquals("Kashita", longestWord("Kashita mary"))
    }

    @Test
    fun `la busqueda acepta las palabras en cualquier orden y pliega la vocal larga`() {
        assertEquals(true, nameMatches("Sekai wo Suberu: Hyouken no Majutsushi", "majutsushi hyouken"))
        // Efecto buscado: la búsqueda no distingue «Moon» de «Mon» (la identidad de las series sí, ver SeriesMatchingTest).
        assertEquals(true, nameMatches("Mon", "Moon"))
        assertEquals(false, nameMatches("Mon", "   "))
    }

    @Test
    fun `una primera palabra muy corta se completa con la siguiente`() {
        assertEquals("K-On! Movie", searchSeed("K-On! Movie"))
        assertEquals("Naruto", searchSeed("Naruto Shippuden"))
    }
}

// ===== EntrySearchTest.kt =====
/** Búsqueda de series para unirlas. */
class EntrySearchTest {
    private fun entry(id: Long, name: String) = CatalogEntry(id.toString(), id, name, null, 0, null, 0L)

    @Test
    fun `el texto se convierte en fragmento de slug`() {
        assertEquals("shin-tennis-no-ouji-sama", EntrySearch.slugFragment("Shin Tennis no Ouji-sama!"))
        assertEquals("", EntrySearch.slugFragment("!!!"))
    }

    @Test
    fun `las series que casan por nombre y por slug salen ordenadas y sin repetir`() {
        val found = EntrySearch.merge(
            byName = listOf(entry(2, "The Prince of Tennis II")),
            bySlug = listOf(entry(2, "The Prince of Tennis II"), entry(3, "Tennis no Oujisama"))
        )
        assertEquals(listOf("3", "2"), found.map { it.key })
    }

    @Test
    fun `el resultado tiene tope`() {
        val many = (1L..30L).map { entry(it, "Serie $it") }
        assertEquals(20, EntrySearch.merge(many, emptyList()).size)
    }
}

// ===== FakePreferences.kt =====
/** Preferencias en memoria para los tests de JVM (las de android.jar son un stub que lanza excepción). */
internal class FakePreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        return values[key] as? MutableSet<String> ?: defValues
    }
    override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { clear = true }

        override fun commit(): Boolean {
            if (clear) values.clear()
            pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            return true
        }

        override fun apply() { commit() }
    }
}

// ===== FichaDataTest.kt =====
/** `FichaData` es el único lector del registro: sus getters dan lo mismo para una ficha de web y para una de AniList, y un dato que falta es null o vacío. */
class FichaDataTest {
    private fun web(): JSONObject {
        val media = WebFichaSchema.empty()
        media.put("idMal", 34572).put("format", "TV").put("status", "RELEASING").put("episodes", 170).put("season", "FALL").put("seasonYear", 2017).put("isAdult", false)
        media.getJSONObject("title").put("romaji", "Black Clover (TV)").put("english", "Black Clover").put("native", "ブラッククローバー")
        media.put("synonyms", org.json.JSONArray(listOf("Burakku Kuroba")))
        media.getJSONObject("startDate").put("year", 2017).put("month", 10).put("day", 3)
        media.put("genres", org.json.JSONArray(listOf("Acción"))).put("studios", org.json.JSONArray(listOf("Pierrot")))
        media.getJSONObject("description").put("en", "Only english")
        media.getJSONObject("web").put("fuente", "jkanime").put("url", "https://example.test/x").put("score", 4.07).put("votes", 12)
        return media
    }

    @Test
    fun `los getters leen el registro de una web`() {
        val d = FichaData(web(), "jkanime", "black-clover", 7L)
        assertEquals(listOf("jkanime", "black-clover", 7L), listOf(d.fuente, d.slug, d.at))
        assertEquals(34572L, d.idMal)
        assertEquals(listOf("Black Clover (TV)", "Black Clover", "ブラッククローバー", "Burakku Kuroba"), d.nombres)
        assertEquals(listOf("Black Clover", "Black Clover (TV)", "Burakku Kuroba"), d.alternativas)
        assertEquals("|black clover tv|black clover|ブラッククローバー|burakku kuroba|", d.nombresBuscables)
        assertEquals(listOf("TV", "RELEASING", 170, "FALL", 2017, 20171003, false), listOf(d.formato, d.estado, d.episodios, d.temporada, d.anio, d.inicio, d.adulto))
        assertEquals(listOf("Acción"), d.generos)
        assertEquals(listOf("Pierrot"), d.estudios)
        assertEquals("Only english", d.descripcion)
        assertEquals(4.07, d.nota!!, 0.0001)
        assertEquals(12, d.votos)
        assertTrue(d.aportaPistas)
    }

    @Test
    fun `valor da un solo dato por el nombre del getter o por su ruta en el JSON`() {
        val d = FichaData(web(), "jkanime", "black-clover", 7L)
        // Por nombre de getter: un texto tal cual, una lista como JSON, un dato que no hay como null.
        assertEquals("jkanime", d.valor("fuente"))
        assertEquals("2017", d.valor("anio"))
        assertEquals("""["Acción"]""", d.valor("generos"))
        assertEquals("4.07", d.valor("nota"))
        assertEquals("false", d.valor("adulto"))
        assertNull(d.valor("portada"))
        // Por ruta del JSON: lo que no tiene getter.
        assertEquals("Black Clover (TV)", d.valor("title.romaji"))
        assertEquals("Acción", d.valor("genres[0]"))
        assertEquals("Acción", d.valor("genres.0"))
        assertEquals("4.07", d.valor("web.score"))
        assertNull(d.valor("genres[9]"))
        assertNull(d.valor("trailer.id"))
        assertNull(d.valor("algo.que.no.existe"))
        // Sin campo, el registro entero.
        assertEquals(d.media.toString(), d.valor(""))
    }

    @Test
    fun `valores da varios datos a la vez en el orden pedido`() {
        val d = FichaData(web(), "jkanime", "black-clover")
        val r = d.valores("fuente", "generos", "web.score", "portada")
        assertEquals(listOf("fuente", "generos", "web.score", "portada"), r.keys.toList())
        assertEquals(listOf("jkanime", """["Acción"]""", "4.07", null), r.values.toList())
    }

    @Test
    fun `todo getter pedible por nombre responde y los nombres no se repiten`() {
        val d = FichaData(web(), "jkanime", "x")
        // Ningún nombre da error y los que el registro de prueba rellena no son null (los demás, los que esta web no da, sí).
        val vacios = FichaData.CAMPOS.keys.filter { d.valor(it) == null }.toSet()
        assertEquals(setOf("portada", "duracion", "pais", "calidad", "anilistId", "puntuacion", "banner", "proximoEpisodio", "proximaEmision"), vacios)
        // Un nombre de getter solo puede coincidir con una clave de primer nivel del JSON si da el mismo dato (idMal): así valor() no tiene dos lecturas.
        val topLevel = WebFichaSchema.empty().keys().asSequence().toSet()
        assertEquals(setOf("idMal"), FichaData.CAMPOS.keys.intersect(topLevel))
        assertEquals(d.media.get("idMal").toString(), d.valor("idMal"))
        assertTrue("generos" in FichaData.CAMPOS && "alternativas" in FichaData.CAMPOS)
    }

    @Test
    fun `un registro vacio no da ningun dato`() {
        val d = FichaData(WebFichaSchema.empty(), "x", "y")
        assertNull(d.idMal); assertNull(d.romaji); assertNull(d.formato); assertNull(d.anio); assertNull(d.inicio); assertNull(d.adulto)
        assertNull(d.descripcion); assertNull(d.nota); assertNull(d.votos); assertNull(d.portada)
        assertTrue(d.nombres.isEmpty() && d.generos.isEmpty() && d.estudios.isEmpty())
        assertEquals("||", d.nombresBuscables)
        assertFalse(d.aportaPistas)
    }

    @Test
    fun `una ficha de AniList se lee con los mismos getters`() {
        val json = """{"id":99,"idMal":34572,"title":{"romaji":"Black Clover","english":null,"native":"ブラッククローバー"},"synonyms":["Burakku Kuroba"],
            "format":"TV","status":"FINISHED","episodes":170,"season":"FALL","seasonYear":2017,"startDate":{"year":2017,"month":10,"day":3},
            "description":"  Sinopsis  ","genres":["Action"],"coverImage":{"extraLarge":"https://example.test/c.jpg"}}"""
        val row = AnimeFicha(99L, 34572L, 1, 3L, AnimeFicha.gzip(json))
        val d = FichaData.ofAnilist(row)!!
        assertEquals(listOf(FichaData.ANILIST, "99", 3L), listOf(d.fuente, d.slug, d.at))
        assertEquals(34572L, d.idMal)
        assertEquals(listOf("Black Clover", "ブラッククローバー", "Burakku Kuroba"), d.nombres)
        assertEquals(listOf("TV", "FINISHED", 170, 2017, 20171003), listOf(d.formato, d.estado, d.episodios, d.anio, d.inicio))
        assertEquals("Sinopsis", d.descripcion)
        assertEquals("https://example.test/c.jpg", d.portada)
    }

    @Test
    fun `los estudios y los personajes se leen igual de una web que de AniList`() {
        // Web: lista de nombres y personajes con su primera voz.
        val w = web()
        w.put("characters", org.json.JSONArray().put(JSONObject().put("name", "Asta").put("role", "MAIN").put("voiceActors", org.json.JSONArray(listOf("Gakuto Kajiwara", "Otro")))).put(JSONObject().put("role", "MAIN")))
        val dw = FichaData(w, "jkanime", "x")
        assertEquals("Pierrot", dw.estudio)
        assertEquals(listOf("Asta"), dw.personajes.map { it.nombre })
        assertEquals(listOf("MAIN", "Gakuto Kajiwara"), listOf(dw.personajes[0].rol, dw.personajes[0].voz))
        // AniList: aristas, con el estudio principal primero y la voz japonesa.
        val da = FichaData.ofAnilistJson("""{"id":5,"studios":{"edges":[{"isMain":false,"node":{"name":"Otro"}},{"isMain":true,"node":{"name":"Principal"}}]},
            "characters":{"edges":[{"role":"MAIN","node":{"name":{"full":"Nia"}},"voiceActors":[{"name":{"full":"Seiyuu"}}]}]}}""")!!
        assertEquals(listOf("Principal", "Otro"), da.estudios)
        assertEquals("Principal", da.estudio)
        assertEquals(listOf("Nia" to "Seiyuu"), da.personajes.map { it.nombre to it.voz })
        assertEquals(5L, da.anilistId)
        assertNull(dw.anilistId)
    }

    @Test
    fun `el anio es el de la fecha de inicio aunque la temporada diga otro`() {
        // Un anime que empieza en diciembre y AniList cuenta como invierno del año siguiente: manda la fecha de inicio, como en las webs.
        val d = FichaData.ofAnilistJson("""{"id":1,"seasonYear":2021,"startDate":{"year":2020,"month":12,"day":30}}""")!!
        assertEquals(listOf(2020, 20201230), listOf(d.anio, d.inicio))
        assertEquals(2021, FichaData.ofAnilistJson("""{"id":1,"seasonYear":2021}""")!!.anio)
    }

    @Test
    fun `los datos de AniList se piden tambien por nombre`() {
        val d = FichaData.ofAnilistJson("""{"id":5,"averageScore":78,"bannerImage":"https://x/b.jpg","nextAiringEpisode":{"episode":4,"airingAt":1700000000},
            "characters":{"edges":[{"role":"MAIN","node":{"name":{"full":"Nia"}}}]}}""")!!
        assertEquals(listOf("5", "78", "https://x/b.jpg", "4", "1700000000", """["Nia"]"""), listOf("anilistId", "puntuacion", "banner", "proximoEpisodio", "proximaEmision", "personajes").map { d.valor(it) })
    }
}

// ===== FichaExportTest.kt =====
/** La copia de las fichas de AniList a un fichero (`FichaExport`): se puede leer entera, idéntica, y una copia cortada se rechaza. */
class FichaExportTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun ficha(id: Long, es: Boolean = id % 2 == 0L) = AnimeFicha(
        anilistId = id, malId = if (id % 3 == 0L) null else id + 1000, consulta = 1, descargada = 1_000L + id,
        json = AnimeFicha.gzip("""{"id":$id,"title":{"romaji":"Título $id ñ 日本"}}"""), es = if (es) AnimeFicha.gzip("texto $id") else null,
        tituloRomaji = "titulo $id", tituloIngles = if (id % 5 == 0L) null else "title $id", tituloNativo = "日本$id",
        anio = if (id % 7 == 0L) null else 2000 + (id % 25).toInt(), formato = "TV", nota = if (id % 11 == 0L) null else 70
    )

    private fun pages(rows: List<AnimeFicha>) = { after: Long, limit: Int -> rows.filter { it.anilistId > after }.sortedBy { it.anilistId }.take(limit) }

    private fun export(rows: List<AnimeFicha>, name: String = "fichas-anilist-20261104-120000.jsonl.gz") =
        FichaExport.export(folder.root, name, 5_000L, rows.size, pages(rows))

    @Test
    fun `lo exportado se lee igual, con los mismos bytes y los nulos en su sitio`() {
        val rows = (1L..450L).map { ficha(it) } // más de dos tramos de 200
        val result = export(rows)

        assertEquals(450, result.rows)
        assertEquals(450, FichaExport.verify(result.file))
        val back = FichaExport.read(result.file)
        assertEquals(rows.size, back.size)
        rows.zip(back).forEach { (a, b) ->
            assertEquals(a.anilistId, b.anilistId)
            assertEquals(a.malId, b.malId)
            assertEquals(a.consulta, b.consulta)
            assertEquals(a.descargada, b.descargada)
            assertArrayEquals(a.json, b.json)
            if (a.es == null) assertNull(b.es) else assertArrayEquals(a.es, b.es)
            assertEquals(a.tituloRomaji, b.tituloRomaji)
            assertEquals(a.tituloIngles, b.tituloIngles)
            assertEquals(a.tituloNativo, b.tituloNativo)
            assertEquals(a.anio, b.anio)
            assertEquals(a.formato, b.formato)
            assertEquals(a.nota, b.nota)
        }
        // Y el JSON de AniList de dentro sigue siendo legible, con sus acentos y caracteres japoneses.
        assertEquals("""{"id":7,"title":{"romaji":"Título 7 ñ 日本"}}""", back[6].jsonText())
    }

    @Test
    fun `un numero exacto de tramos y una tabla vacia tambien salen bien`() {
        assertEquals(200, FichaExport.verify(export((1L..200L).map { ficha(it) }, "a.jsonl.gz").file))
        assertEquals(0, FichaExport.verify(export(emptyList(), "b.jsonl.gz").file))
        assertEquals(emptyList<AnimeFicha>(), FichaExport.read(File(folder.root, "b.jsonl.gz")))
    }

    @Test
    fun `no deja ficheros a medias ni pisa una copia anterior`() {
        export((1L..5L).map { ficha(it) })
        assertEquals(listOf("fichas-anilist-20261104-120000.jsonl.gz"), folder.root.list().orEmpty().sorted())
        try {
            export((1L..3L).map { ficha(it) }) // mismo nombre
            fail("debía negarse a pisar la copia")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ya existe"))
        }
        assertEquals(5, FichaExport.verify(File(folder.root, "fichas-anilist-20261104-120000.jsonl.gz")))
        assertFalse(File(folder.root, "fichas-anilist-20261104-120000.jsonl.gz.part").exists())
    }

    @Test
    fun `si la lectura falla a mitad no queda ni fichero final ni temporal`() {
        val broken = { after: Long, limit: Int -> if (after == Long.MIN_VALUE) (1L..limit.toLong()).map { ficha(it) } else error("se cayó la base") }
        try {
            FichaExport.export(folder.root, "roto.jsonl.gz", 1L, 500, broken)
            fail("debía fallar")
        } catch (e: IllegalStateException) {
            assertEquals("se cayó la base", e.message)
        }
        assertEquals(emptyList<String>(), folder.root.list().orEmpty().toList())
    }

    @Test
    fun `una ficha guardada mientras se exporta no repite ni salta filas`() {
        val rows = ((1L..400L).map { ficha(it * 10) }).toMutableList()
        var calls = 0
        val page = { after: Long, limit: Int ->
            // Entre el primer tramo y el segundo se guarda una ficha con un id MENOR que los ya leídos (con posición habría repetido una fila del tramo siguiente).
            if (calls++ == 1) rows += ficha(5)
            rows.filter { it.anilistId > after }.sortedBy { it.anilistId }.take(limit)
        }
        val result = FichaExport.export(folder.root, "carrera.jsonl.gz", 1L, 400, page)
        val ids = FichaExport.read(result.file).map { it.anilistId }
        assertEquals("sin repetidos", ids.size, ids.toSet().size)
        assertEquals("no salta ninguna de las que ya estaban", (1L..400L).map { it * 10 }, ids.filter { it != 5L })
    }

    @Test
    fun `limpia los temporales viejos de una exportacion muerta y no toca las copias completas`() {
        val now = 10_000_000_000L
        val old = File(folder.root, "fichas-anilist-20261101-000000.jsonl.gz.part").apply { writeText("a medias"); setLastModified(now - 2 * 60 * 60 * 1000) }
        val fresh = File(folder.root, "fichas-anilist-20261104-110000.jsonl.gz.part").apply { writeText("en curso"); setLastModified(now - 60 * 1000) }
        val complete = export((1L..3L).map { ficha(it) }, "fichas-anilist-20261103-000000.jsonl.gz").file
        val foreign = File(folder.root, "otra-cosa.part").apply { writeText("ajeno"); setLastModified(now - 2 * 60 * 60 * 1000) }
        FichaExport.export(folder.root, "siguiente.jsonl.gz", now, 0) { _, _ -> emptyList() }
        assertFalse("el temporal de hace dos horas se limpia", old.exists())
        assertTrue("el de hace un minuto puede ser de una exportación en curso", fresh.exists())
        assertTrue("las copias completas no se tocan", complete.exists())
        assertTrue("lo que no es de este formato no se toca", foreign.exists())
    }

    @Test
    fun `una copia cortada o ajena se rechaza`() {
        val good = export((1L..30L).map { ficha(it) }).file
        val lines = GZIPInputStream(good.inputStream()).bufferedReader().readLines()

        fun write(name: String, text: List<String>): File = File(folder.root, name).also { f ->
            GZIPOutputStream(f.outputStream()).bufferedWriter().use { w -> text.forEach { w.write(it); w.newLine() } }
        }
        // Sin la línea final (se cortó la escritura).
        val noEnd = write("sin-final.jsonl.gz", lines.dropLast(1))
        // La línea final dice otro número que las fichas que hay.
        val wrongEnd = write("fin-distinto.jsonl.gz", lines.dropLast(1) + """{"fin":31}""")
        // Una ficha de menos.
        val missing = write("falta-una.jsonl.gz", lines.take(10) + lines.drop(11))
        // No es una copia de Kaizen.
        val foreign = write("ajeno.jsonl.gz", listOf("""{"formato":"otra-cosa","version":1}""", """{"fin":0}"""))
        // Basura después del final.
        val trailing = write("despues.jsonl.gz", lines + lines[1])

        listOf(noEnd, wrongEnd, missing, foreign, trailing).forEach { f ->
            try {
                FichaExport.verify(f)
                fail("debía rechazar ${f.name}")
            } catch (e: IllegalStateException) {
                // esperado: copia cortada, ajena o con líneas de más
            }
        }
        assertEquals(30, FichaExport.verify(good))
    }

    @Test
    fun `el nombre lleva el prefijo y la fecha, y la copia de usuario no lo toca`() {
        val name = FichaExport.nameFor(1_700_000_000_000L)
        assertTrue(name.startsWith("fichas-anilist-") && name.endsWith(".jsonl.gz"))
        // Las copias del usuario son «kaizen-backup-….json»: este nombre no coincide con ese filtro, así que la limpieza de caducadas no lo borra.
        assertFalse(name.startsWith("kaizen-backup-") || name.endsWith(".json"))
    }
}

// ===== FichaFillTest.kt =====
/** El relleno de `WebFicha` de las fichas por revisar: qué fichas elige, a turnos entre fuentes y con tope, y cuándo deja una fuente. */
class FichaFillTest {
    private fun link(fuente: String, slug: String, estado: String = AnimeFuente.PENDIENTE, id: Long? = 1L) =
        AnimeFuente(fuente, slug, id, estado, AnimeFuente.AUTO, "dudoso", 0.5, 1L)

    private val links = listOf(
        link("jkanime", "a"), link("jkanime", "b"), link("jkanime", "c"),
        link("verani", "x"), link("verani", "y"),
        link("animeflv", "m"),
        link("animeflv", "ya-enlazada", AnimeFuente.APLICADO), link("animeflv", "sin-resultado", AnimeFuente.SIN_RESULTADO, null), link("animeflv", "sin-id", AnimeFuente.PENDIENTE, null)
    )

    @Test
    fun `elige las pendientes con candidato y las sin resultado, no las enlazadas, y las reparte a turnos entre fuentes`() {
        val plan = FichaFill.plan(links, { _, _ -> false }, 10)
        assertEquals(listOf("animeflv" to "m", "jkanime" to "a", "verani" to "x", "animeflv" to "sin-resultado", "jkanime" to "b", "verani" to "y", "jkanime" to "c"), plan)
    }

    @Test
    fun `el tope reparte entre fuentes y no deja a una con todo`() {
        assertEquals(listOf("animeflv" to "m", "jkanime" to "a", "verani" to "x"), FichaFill.plan(links, { _, _ -> false }, 3))
        assertTrue(FichaFill.plan(links, { _, _ -> false }, 0).isEmpty())
        // Un tope enorme se recorta al máximo.
        assertEquals(7, FichaFill.plan(links, { _, _ -> false }, 10_000).size)
    }

    @Test
    fun `se salta las que ya tienen copia y se puede pedir una sola fuente`() {
        assertEquals(listOf("jkanime" to "b", "jkanime" to "c"), FichaFill.plan(links, { f, s -> f == "jkanime" && s == "a" || f != "jkanime" }, 10))
        assertEquals(listOf("verani" to "x", "verani" to "y"), FichaFill.plan(links, { _, _ -> false }, 10, "verani"))
    }

    @Test
    fun `una fuente que falla tres veces seguidas se deja y las demas siguen`() = runBlocking {
        val todo = listOf("a" to "1", "b" to "1", "a" to "2", "b" to "2", "a" to "3", "b" to "3", "a" to "4", "b" to "4")
        val opened = ArrayList<Pair<String, String>>()
        val pauses = ArrayList<Long>()
        val report = FichaFill.run(todo, { s, slug -> opened += s to slug; s == "b" }, pause = { pauses += it })
        // «a» falla siempre: se intenta 3 veces y se deja; «b» abre todas.
        assertEquals(3, opened.count { it.first == "a" })
        assertEquals(4, opened.count { it.first == "b" })
        assertEquals(mapOf("b" to 4), report.ok); assertEquals(mapOf("a" to 3), report.failed); assertEquals(setOf("a"), report.stopped)
        assertEquals(7, pauses.size); assertTrue(pauses.all { it == FichaFill.PAUSE_MS })
        assertEquals("a ok=0 fallo=3 (dejada), b ok=4 fallo=0", report.toString())
    }

    @Test
    fun `un acierto reinicia la cuenta de fallos y una excepcion cuenta como fallo`() = runBlocking {
        val seq = ArrayDeque(listOf(false, false, true, false, false, false))
        val report = FichaFill.run((1..6).map { "a" to "$it" }, { _, _ -> seq.removeFirst() }, pause = {})
        assertEquals(mapOf("a" to 1), report.ok); assertEquals(setOf("a"), report.stopped)
        val thrown = FichaFill.run(listOf("a" to "1"), { _, _ -> error("red") }, pause = {})
        assertEquals(mapOf("a" to 1), thrown.failed)
    }

    @Test
    fun `se para cuando canRun lo dice`() = runBlocking {
        var n = 0
        val report = FichaFill.run((1..5).map { "a" to "$it" }, { _, _ -> n++; true }, canRun = { n < 2 }, pause = {})
        assertEquals(2, report.total)
    }

    @Test
    fun `no se pasa del ritmo ni del tope diario en segundo plano`() {
        val day = 20_000L * 24 * 60 * 60 * 1000
        val min = FichaFill.MIN_INTERVAL_MS
        // Una tanda reciente: nada. Pasado el intervalo: una tanda entera.
        assertEquals(0, FichaFill.allowance(day + min - 1, day, FichaFill.dayOf(day), 0))
        assertEquals(FichaFill.BATCH, FichaFill.allowance(day + min, day, FichaFill.dayOf(day), 0))
        // Cerca del tope diario: solo lo que queda; con el tope hecho, nada.
        assertEquals(5, FichaFill.allowance(day + min, day, FichaFill.dayOf(day), FichaFill.DAILY_CAP - 5))
        assertEquals(0, FichaFill.allowance(day + min, day, FichaFill.dayOf(day), FichaFill.DAILY_CAP))
        // Otro día: la cuenta empieza de cero.
        assertEquals(FichaFill.BATCH, FichaFill.allowance(day + 24L * 60 * 60 * 1000, day, FichaFill.dayOf(day), FichaFill.DAILY_CAP))
    }

    @Test
    fun `una ficha que fallo no se reintenta hasta pasada una semana y los fallos viejos se olvidan`() {
        val now = 10L * FichaFill.RETRY_AFTER_MS
        val old = mapOf(("jkanime" to "a") to now - FichaFill.RETRY_AFTER_MS - 1, ("verani" to "x") to now - 1000L)
        val text = FichaFill.encodeFailures(old, listOf("jkanime" to "b"), now)
        val back = FichaFill.parseFailures(text)
        // La de hace más de una semana se olvida; la reciente y la nueva se guardan.
        assertEquals(setOf("verani" to "x", "jkanime" to "b"), back.keys)
        assertEquals(now, back.getValue("jkanime" to "b"))
        // Y el plan las salta.
        val plan = FichaFill.plan(links, { _, _ -> false }, 10, skip = { f, s -> (f to s) in back })
        assertTrue(("verani" to "x") !in plan && ("jkanime" to "b") !in plan && ("jkanime" to "a") in plan)
        assertTrue(FichaFill.parseFailures(null).isEmpty() && FichaFill.parseFailures("basura|sin|numero").isEmpty())
    }

    @Test
    fun `solo una tanda a la vez`() {
        assertTrue(FichaFill.tryLock())
        assertTrue(!FichaFill.tryLock())
        FichaFill.unlock()
        assertTrue(FichaFill.tryLock())
        FichaFill.unlock()
    }

    @Test
    fun `una ficha que falla queda anotada en el informe`() = runBlocking {
        val report = FichaFill.run(listOf("a" to "1", "b" to "1"), { s, _ -> s == "b" }, pause = {})
        assertEquals(listOf("a" to "1"), report.failedPairs)
    }

    @Test
    fun `un fallo del momento no se anota como fallo de la ficha y deja la fuente para el resto de la tanda`() = runBlocking {
        val todo = listOf("a" to "1", "b" to "1", "a" to "2", "b" to "2")
        val opened = ArrayList<Pair<String, String>>()
        val report = FichaFill.run(todo, { s, slug -> opened += s to slug; if (s == "a") throw FichaFill.Transient() else true }, pause = {})
        // «a» se aplaza en su primera ficha y no se vuelve a intentar; «b» abre las dos.
        assertEquals(1, opened.count { it.first == "a" })
        assertEquals(mapOf("b" to 2), report.ok); assertTrue(report.failed.isEmpty()); assertTrue(report.failedPairs.isEmpty())
        assertEquals(mapOf("a" to 1), report.transient); assertEquals(setOf("a"), report.stopped)
        assertEquals(3, report.tried)
        assertEquals("a ok=0 fallo=0 aplazadas=1 (dejada), b ok=2 fallo=0", report.toString())
        // Lo aplazado no entra en lo que se guarda como fallido (una semana de espera).
        assertEquals("", FichaFill.encodeFailures(emptyMap(), report.failedPairs, 1L))
    }

    @Test
    fun `lo intentado cuenta abiertas, fallidas y aplazadas`() = runBlocking {
        val seq = ArrayDeque(listOf<Any>(true, false, FichaFill.Transient()))
        val report = FichaFill.run((1..3).map { "a" to "$it" }, { _, _ -> val r = seq.removeFirst(); if (r is Throwable) throw r else r as Boolean }, pause = {})
        assertEquals(3, report.tried)
        assertEquals(1, report.total)
    }
}

// ===== FichaPolicyTest.kt =====
/** Cuándo una ficha ya no necesita volver a pedirse a la web de su fuente. */
class FichaPolicyTest {
    private fun series(state: Int, chapters: Int) = Series(
        aid = 1, name = "S", slug = "s", description = "", rateStars = 0f, rateCount = 0, type = "anime", state = state, day = null,
        genres = emptyList(), relations = emptyList(), chapters = (1..chapters).map { ChapterEntry(it, it.toDouble()) }, source = "jkanime"
    )

    private fun anilist(status: String?, episodes: Int?) = FichaData.ofAnilistJson(
        JSONObject().put("id", 1).put("status", status ?: JSONObject.NULL).put("episodes", episodes ?: JSONObject.NULL).toString()
    )!!

    @Test
    fun `sin AniList manda el estado de la fuente`() {
        assertTrue(FichaPolicy.finished(series(state = 2, chapters = 12), null))
        assertFalse(FichaPolicy.finished(series(state = 1, chapters = 12), null))
    }

    @Test
    fun `AniList manda sobre la fuente`() {
        assertTrue(FichaPolicy.finished(series(state = 1, chapters = 12), anilist("FINISHED", 12)))
        assertTrue(FichaPolicy.finished(series(state = 2, chapters = 12), anilist("CANCELLED", 12)))
        assertFalse(FichaPolicy.finished(series(state = 2, chapters = 12), anilist("RELEASING", null)))
        assertFalse(FichaPolicy.finished(series(state = 2, chapters = 12), anilist("HIATUS", null)))
    }

    @Test
    fun `finalizada y con todos los capitulos no se vuelve a pedir`() {
        val s = series(state = 2, chapters = 12)
        assertTrue(FichaPolicy.settled(s, anilist("FINISHED", 12), true))
        assertEquals(FichaPolicy.SETTLED_TTL, FichaPolicy.ttl(s, anilist("FINISHED", 12), true))
        assertEquals(Long.MAX_VALUE, FichaPolicy.refreshAfter(s, anilist("FINISHED", 12), true))
        // Sin dato de episodios de AniList basta con que tenga alguno.
        assertTrue(FichaPolicy.settled(s, anilist("FINISHED", null), true))
    }

    @Test
    fun `finalizada solo segun la fuente se mira antes que la confirmada por AniList`() {
        val s = series(state = 2, chapters = 12)
        assertTrue(FichaPolicy.settled(s, null, true))
        assertEquals(FichaPolicy.UNLINKED_TTL, FichaPolicy.ttl(s, null, true))
        assertTrue(FichaPolicy.UNLINKED_TTL < FichaPolicy.SETTLED_TTL)
    }

    @Test
    fun `finalizada pero con capitulos de menos se renueva como mucho una vez al dia`() {
        val s = series(state = 2, chapters = 11)
        assertFalse(FichaPolicy.settled(s, anilist("FINISHED", 12), true))
        assertEquals(FichaPolicy.INCOMPLETE_TTL, FichaPolicy.ttl(s, anilist("FINISHED", 12), true))
        // La renovacion en segundo plano no es la de las series en emision (10 min): es cada 24 h.
        assertEquals(FichaPolicy.INCOMPLETE_TTL, FichaPolicy.refreshAfter(s, anilist("FINISHED", 12), true))
    }

    @Test
    fun `en emision se renueva como siempre`() {
        val s = series(state = 1, chapters = 5)
        assertFalse(FichaPolicy.settled(s, anilist("RELEASING", 12), true))
        assertEquals(FichaPolicy.AIRING_TTL, FichaPolicy.ttl(s, anilist("RELEASING", 12), true))
        assertEquals(FichaPolicy.AIRING_TTL, FichaPolicy.ttl(s, null, true))
        assertEquals(FichaPolicy.AIRING_REFRESH, FichaPolicy.refreshAfter(s, null, true))
    }

    @Test
    fun `con el ajuste apagado todo vuelve a como era antes`() {
        val done = series(state = 2, chapters = 12)
        assertFalse(FichaPolicy.settled(done, anilist("FINISHED", 12), false))
        assertEquals(FichaPolicy.AIRING_TTL, FichaPolicy.ttl(done, anilist("FINISHED", 12), false))
        assertEquals(FichaPolicy.AIRING_REFRESH, FichaPolicy.refreshAfter(done, anilist("FINISHED", 12), false))
        assertEquals(FichaPolicy.AIRING_TTL, FichaPolicy.ttl(done, null, false))
    }

    @Test
    fun `una serie sin capitulos nunca esta cerrada`() {
        assertFalse(FichaPolicy.settled(series(state = 2, chapters = 0), anilist("FINISHED", 12), true))
    }
}

// ===== FichaPreviewTest.kt =====
/**
 * Vista previa de lo que obtiene el sistema de scrap de una página, sin instalar nada: la baja y la pasa por el código real de la app
 * (la especificación de la fuente, `readFicha` y `JsExtractor` con Rhino en las webs `jsData`). No toca bases de datos ni la TV.
 * Solo se ejecuta si existe la variable de entorno `KAIZEN_FICHA_URL` (la lanza `tools/ficha-preview.sh URL`); escribe el resultado en
 * `app/build/probe/ficha-preview.txt`.
 *
 * Vale para fuentes de las dos clases: `format: jsData` (animeav1) y HTML (animeflv.ar...). En las de HTML, si la URL es la de un capítulo
 * (no trae la ficha), enseña sus servidores de vídeo.
 */
class FichaPreviewTest {
    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"

    /** Los pasos privados de [JsExtractor], los mismos que usa la app al abrir una ficha (`processLink`). */
    private fun jsExtractor(name: String, vararg types: Class<*>) = JsExtractor::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }

    @Test
    fun preview() {
        val url = System.getenv("KAIZEN_FICHA_URL")
        assumeTrue("sin KAIZEN_FICHA_URL no hay nada que previsualizar", !url.isNullOrBlank())
        val host = URI(url!!).host
        val spec = File("src/main/assets/sources").listFiles { f -> f.name.endsWith(".json") && !f.name.startsWith("index") }!!
            .map { SourceSpec.parse(it.readText()) }.firstOrNull { host == it.host || host.endsWith(".${it.host}") }
            ?: error("ninguna fuente declara el host $host")
        val source = ConfigurableSource(spec)
        val slug = checkNotNull(source.slugOf(url)) { "la URL no es de una ficha ni de un capítulo de ${spec.id}" }

        val html = Jsoup.connect(url).userAgent(ua).ignoreContentType(true).execute().body()
        val out = if (spec.format == "jsData") jsDataPreview(spec, source, url, slug, html) else htmlPreview(spec, source, url, slug, html)
        File("build/probe").mkdirs()
        File("build/probe/ficha-preview.txt").writeText(out)
        println(out)
    }

    /** animeav1: los datos van en un JSON dentro del JavaScript de la página. */
    private fun jsDataPreview(spec: SourceSpec, source: ConfigurableSource, url: String, slug: String, html: String): String {
        val raw = jsExtractor("extractDataArray", String::class.java).invoke(JsExtractor, html) as String
        val json = jsExtractor("executeJS", String::class.java, String::class.java).invoke(JsExtractor, raw, null) as String
        val fixed = jsExtractor("fixMojibakeRecursive", Any::class.java).invoke(JsExtractor, JSONTokener(json).nextValue())
        val array = if (fixed is JSONObject) JSONArray().put(fixed) else fixed as JSONArray
        // Como ConfigurableSource.anime(): en jsData la respuesta es una lista con la serie como primer elemento.
        val node = checkNotNull(JsonNode(array).first(".")) { "la página no trae la serie" }
        val aid = checkNotNull(spec.anime.id?.int(node))
        val chapters = source.parseChapterItems(slug, node, checkNotNull(spec.anime.chapters.items))
        val (series, ficha) = checkNotNull(source.readFicha(slug, aid, node, chapters)) { "la página no trae título" }
        val saved = setOf("malId", "title", "aka", "status", "category", "episodesCount", "runtime", "startDate", "endDate", "synopsis", "mature", "trailer", "genres", "episodes", "relations", "id", "slug", "categoryId", "score", "votes")
        val unsaved = array.getJSONObject(0).keys().asSequence().filter { it !in saved }.joinToString().ifBlank { "ninguna" }
        return report(spec, url, slug, series, ficha, unsaved)
    }

    /** Webs HTML: los datos se leen con selectores CSS. */
    private fun htmlPreview(spec: SourceSpec, source: ConfigurableSource, url: String, slug: String, html: String): String {
        val node = HtmlNode(Jsoup.parse(html, url))
        val require = spec.anime.require
        if (require != null && node.first(require) == null) {
            val servers = source.parseServers(node)
            return buildString {
                appendLine("URL: $url")
                appendLine("Fuente: ${spec.id} (especificación v${spec.version})  slug: $slug")
                appendLine()
                appendLine("La página no es una ficha (no tiene «$require»): se leen sus servidores de vídeo.")
                appendLine("== Servidores (${servers.size}) ==")
                servers.forEach { appendLine("- ${it.label ?: "(sin etiqueta)"}  ${it.url}") }
            }
        }
        val chapters = source.parseChapters(node)
        // La app lo saca de la base de datos de identidades; aquí no se toca: uno provisional que no afecta a lo que se guarda.
        val aid = slug.hashCode() and 0x7fffffff
        // Algunas fuentes calculan las relaciones con esa base de datos (no disponible aquí): sin ella se sigue con el registro, que no depende de ella.
        val read = runCatching { source.readFicha(slug, aid, node, chapters) }.getOrNull()
        val ficha = read?.second ?: run {
            val genres = spec.anime.genreLinks?.let { source.parseGenreLinks(node, it) }?.map { it.name }.orEmpty()
            checkNotNull(WebFichaSchema.build(spec.ficha, node, genres, spec.id, slug, source.animeUrl(slug))) { "la ficha no trae título" }
        }
        return report(spec, url, slug, read?.first, ficha, "(web HTML: no hay claves que listar; lo que la ficha no lee con un selector no se guarda)")
    }

    private fun report(spec: SourceSpec, url: String, slug: String, series: Series?, ficha: JSONObject, unsaved: String): String = buildString {
        appendLine("URL: $url")
        appendLine("Fuente: ${spec.id} (especificación v${spec.version})  slug: $slug")
        appendLine()
        appendLine("== Lo que se guarda en WebFicha (el registro único: todas las claves, null si la web no lo da) ==")
        appendLine(ficha.toString(2))
        appendLine()
        appendLine("== Columnas indexadas ==")
        val c = FichaData(ficha, spec.id, slug)
        appendLine("malId=${c.idMal}  anio=${c.anio}  inicio=${c.inicio}  formato=${c.formato}  estado=${c.estado}  temporada=${c.temporada}  episodios=${c.episodios}  adulto=${c.adulto}")
        appendLine("nombres=${c.nombresBuscables}")
        appendLine()
        appendLine("== Lo que usa la app en pantalla (Series, derivada del registro) ==")
        if (series == null) {
            appendLine("(no se pudo calcular aquí: necesita la base de datos de identidades; el registro de arriba no depende de ella)")
        } else {
            appendLine("aid=${series.aid}  nombre=${series.name}  tipo=${series.type}  estado=${series.state}  día=${series.day}  año=${series.year}  estudio=${series.studio}")
            appendLine("nota=${series.rateStars} estrellas (${series.rateCount} votos)")
            appendLine("capítulos=${series.chapters.size}" + (series.chapters.takeIf { it.isNotEmpty() }?.let { "  (del ${it.minOf { c -> c.number }} al ${it.maxOf { c -> c.number }})" } ?: ""))
            appendLine("relaciones=" + series.relations.joinToString { "${it.name} [tipo ${it.relation}]" }.ifBlank { "ninguna" })
        }
        appendLine()
        appendLine("== Claves que la web envía y el sistema NO guarda ==")
        appendLine(unsaved)
    }
}

// ===== FichaResueltaTest.kt =====
/** `FichaResuelta` elige de qué ficha sale cada dato (AniList primero, la web si AniList no lo da) sin copiar ni tocar ninguna de las dos. */
class FichaResueltaTest {
    private fun web(): FichaData {
        val media = WebFichaSchema.empty()
        media.getJSONObject("title").put("romaji", "Tensei shitara Ken deshita 2")
        media.put("idMal", 53913).put("format", "TV").put("status", "RELEASING")
        media.put("genres", JSONArray(listOf("Accion", "Aventura")))
        media.getJSONObject("web").put("fuente", "jkanime").put("quality", "1080p").put("score", 4.2)
        return FichaData(media, "jkanime", "tensei-ii")
    }

    private fun anilist(genres: String = """["Action","Adventure"]""") =
        FichaData.ofAnilistJson("""{"id":99,"idMal":53913,"title":{"romaji":"Tensei shitara Ken deshita 2"},"status":"FINISHED","genres":$genres,"averageScore":80}""")!!

    @Test
    fun `con enlace el dato sale de AniList y lo que AniList no tiene sale de la web`() {
        val r = FichaResuelta(anilist(), web())
        assertEquals("FINISHED", r.valor("estado"))
        assertEquals("anilist", r.origen("estado"))
        assertEquals("""["Action","Adventure"]""", r.valor("generos"))
        // Solo la web lo da: la calidad y la nota de la web.
        assertEquals("1080p", r.valor("calidad")); assertEquals("jkanime", r.origen("calidad"))
        assertEquals("4.2", r.valor("nota"))
        // Y lo que solo da AniList.
        assertEquals("80", r.valor("puntuacion")); assertEquals("anilist", r.origen("puntuacion"))
    }

    @Test
    fun `un dato vacio de AniList no tapa al de la web`() {
        val r = FichaResuelta(anilist(genres = "[]"), web())
        assertEquals("""["Accion","Aventura"]""", r.valor("generos"))
        assertEquals("jkanime", r.origen("generos"))
        assertEquals("Accion", r.dato { it.generos.firstOrNull() })
    }

    @Test
    fun `sin enlace todo sale de la web y sin ningun dato es null`() {
        val r = FichaResuelta(null, web())
        assertEquals("RELEASING", r.valor("estado")); assertEquals("jkanime", r.origen("estado"))
        assertNull(r.valor("puntuacion")); assertNull(r.origen("puntuacion"))
        assertNull(r.valor("pais"))
    }

    @Test
    fun `no modifica ninguna de las dos fichas`() {
        val w = web(); val a = anilist()
        val before = listOf(w.media.toString(), a.media.toString())
        FichaResuelta(a, w).apply { valor("estado"); valor("generos"); origen("calidad"); dato { it.estudios } }
        assertEquals(before, listOf(w.media.toString(), a.media.toString()))
        assertEquals("RELEASING", w.valor("estado"))
    }

    @Test
    fun `la consulta resuelta usa el enlace de cada ficha y marca el origen`() {
        val linked = WebFicha.of("jkanime", "tensei-ii", web().media, 1L)
        val free = WebFicha.of("animeflv", "tensei-ii", web().media.also { it.getJSONObject("web").put("fuente", "animeflv") }, 1L)
        val dao = object : WebFichaDao by NoWebDao {
            override fun byName(norm: String, raw: String, limit: Int) = listOf(linked, free).take(limit)
        }
        val rows = WebFichaQuery.resolved(dao, "tensei", "estado", 10) { row -> anilist().takeIf { row.fuente == "jkanime" } }
        assertEquals(listOf("jkanime" to "FINISHED", "animeflv" to "RELEASING"), rows.map { it.fuente to it.value })
        assertEquals(listOf("anilist", "animeflv"), rows.map { it.origen })
        // Si el enlace falla (excepción), la ficha sigue saliendo con lo de la web.
        val broken = WebFichaQuery.resolved(dao, "tensei", "estado", 10) { error("sin base") }
        assertEquals(listOf("RELEASING", "RELEASING"), broken.map { it.value })
    }

    @Test
    fun `la identidad sale siempre de la web`() {
        val r = FichaResuelta(anilist(), web())
        assertEquals(listOf("jkanime", "tensei-ii"), listOf(r.valor("fuente"), r.valor("slug")))
        assertEquals("jkanime", r.origen("fuente"))
    }

    @Test
    fun `solo un enlace aplicado con su ficha descargada da la ficha de AniList`() {
        val ficha = AnimeFicha(99L, 53913L, 1, 0L, AnimeFicha.gzip("""{"id":99,"idMal":53913,"title":{"romaji":"X"}}"""))
        fun link(estado: String, id: Long?) = AnimeFuente("jkanime", "x", id, estado, AnimeFuente.AUTO, "seguro", 1.0, 1L)
        assertEquals(99L, FichaResuelta.anilistOf(link(AnimeFuente.APLICADO, 99L)) { ficha }?.anilistId)
        assertNull(FichaResuelta.anilistOf(link(AnimeFuente.PENDIENTE, 99L)) { ficha })
        assertNull(FichaResuelta.anilistOf(link(AnimeFuente.SIN_RESULTADO, null)) { ficha })
        assertNull(FichaResuelta.anilistOf(link(AnimeFuente.APLICADO, null)) { ficha })
        assertNull(FichaResuelta.anilistOf(link(AnimeFuente.APLICADO, 99L)) { null })
        assertNull(FichaResuelta.anilistOf(null) { ficha })
    }

    /** Un DAO que no hace nada: las pruebas solo sobrescriben lo que usan. */
    private object NoWebDao : WebFichaDao {
        override fun put(ficha: WebFicha) = Unit
        override fun get(fuente: String, slug: String): WebFicha? = null
        override fun byName(norm: String, raw: String, limit: Int): List<WebFicha> = emptyList()
        override fun page(limit: Int, offset: Int): List<WebFicha> = emptyList()
        override fun count() = 0
        override fun countWithMal() = 0
        override fun countByFuente(): List<FuenteCount> = emptyList()
    }
}

// ===== FichaStoreTest.kt =====
/** La tabla única `Ficha`: el libro de ids y los enlaces con AniList sobre las mismas filas, sin perder nada de uno al escribir el otro. */
class FichaStoreTest {
    /** `Ficha` en memoria, con la misma semántica que las consultas de [FichaDao]. */
    private class FakeFichaDao(var high: Long? = null) : FichaDao() {
        val rows = linkedMapOf<Pair<String, String>, FichaRow>()

        override fun allMap() = rows.values.map { SeriesMap(it.fuente, it.slug, it.seriesId) }
        override fun any() = rows.isNotEmpty()
        override fun idOf(fuente: String, slug: String) = rows[fuente to slug]?.seriesId
        override fun insertIfAbsent(fuente: String, slug: String, seriesId: Long) { rows.putIfAbsent(fuente to slug, FichaRow(fuente, slug, seriesId)) }
        override fun setSeries(fuente: String, slug: String, seriesId: Long) { rows[fuente to slug]?.let { rows[fuente to slug] = it.copy(seriesId = seriesId) } }
        override fun moveSeries(fromId: Long, toId: Long) { rows.replaceAll { _, r -> if (r.seriesId == fromId) r.copy(seriesId = toId) else r } }
        override fun highWater() = high ?: rows.values.map { it.seriesId }.maxOrNull()

        override fun links() = rows.values.filter { it.estado != null }.map { it.toLink() }
        override fun link(fuente: String, slug: String) = rows[fuente to slug]?.takeIf { it.estado != null }?.toLink()
        override fun appliedIds() = rows.values.filter { it.estado == AnimeFuente.APLICADO }.mapNotNull { it.anilistId }.distinct()
        override fun countByState() = rows.values.mapNotNull { it.estado }.groupingBy { it }.eachCount().map { AnimeFuenteDao.StateCount(it.key, it.value) }
        override fun expireAuto() { rows.replaceAll { _, r -> if (r.origen == AnimeFuente.AUTO) r.copy(fecha = 0) else r } }
        override fun clearAutoLink(fuente: String, slug: String) {
            rows[fuente to slug]?.takeIf { it.origen == AnimeFuente.AUTO }?.let { rows[fuente to slug] = it.copy(anilistId = null, estado = null, origen = null, nivel = null, puntuacion = null, fecha = null) }
        }

        override fun setLink(fuente: String, slug: String, anilistId: Long?, estado: String, origen: String, nivel: String, puntuacion: Double, fecha: Long) {
            rows[fuente to slug]?.let { rows[fuente to slug] = it.copy(anilistId = anilistId, estado = estado, origen = origen, nivel = nivel, puntuacion = puntuacion, fecha = fecha) }
        }

        override fun setLinkIfAbsent(fuente: String, slug: String, anilistId: Long?, estado: String, origen: String, nivel: String, puntuacion: Double, fecha: Long) {
            if (rows[fuente to slug]?.estado == null) setLink(fuente, slug, anilistId, estado, origen, nivel, puntuacion, fecha)
        }

        private fun FichaRow.toLink() = AnimeFuente(fuente, slug, anilistId, estado!!, origen ?: AnimeFuente.AUTO, nivel.orEmpty(), puntuacion ?: 0.0, fecha ?: 0L)
    }

    private fun link(fuente: String, slug: String, id: Long?, estado: String = AnimeFuente.APLICADO, origen: String = AnimeFuente.AUTO) =
        AnimeFuente(fuente, slug, id, estado, origen, "seguro", 1.0, 5L)

    private fun stores(dao: FakeFichaDao): Pair<SeriesIdStore, AnimeFuenteStore> {
        val ids = SeriesIdStore(dao, { it() })
        return ids to AnimeFuenteStore(dao, ids) { it() }
    }

    // ---- libro de ids ----
    @Test
    fun `los ids nuevos salen por encima del mayor que hay y no se repiten`() {
        val dao = FakeFichaDao()
        dao.insertIfAbsent("a", "x", 41)
        val (ids, _) = stores(dao)
        assertEquals(42L, ids.newSeries(SeriesRegistry()))
        assertEquals(43L, ids.newSeries(SeriesRegistry()))
    }

    @Test
    fun `un id que nombran las reglas del usuario no se reparte aunque ya no tenga ficha`() {
        val dao = FakeFichaDao()
        dao.insertIfAbsent("a", "x", 41)
        val ids = SeriesIdStore(dao, { it() }, { 70L })
        assertEquals(71L, ids.newSeries(SeriesRegistry()))
    }

    @Test
    fun `unir series cambia el id de todas las fichas del id absorbido`() {
        val dao = FakeFichaDao()
        val (ids, _) = stores(dao)
        ids.putMap(listOf(SeriesMap("a", "x", 5), SeriesMap("b", "x", 9), SeriesMap("c", "x", 9), SeriesMap("d", "x", 12)))
        ids.moveSeries(mapOf(9L to 5L))
        assertEquals(listOf(5L, 5L, 5L, 12L), ids.allMap().map { it.seriesId }.sorted())
    }

    @Test
    fun `sin nada en la base el primer id es el 1`() {
        val (ids, _) = stores(FakeFichaDao())
        assertEquals(1L, ids.newSeries(SeriesRegistry()))
    }

    @Test
    fun `dar de alta ids concretos sube el contador por encima de todos`() {
        val (ids, _) = stores(FakeFichaDao())
        ids.putRegistry(listOf(SeriesRegistry(10), SeriesRegistry(500), SeriesRegistry(20)))
        assertEquals(501L, ids.newSeries(SeriesRegistry()))
    }

    @Test
    fun `cambiar el id de una ficha no toca su enlace con AniList`() {
        val dao = FakeFichaDao()
        val (ids, fuentes) = stores(dao)
        ids.putMap(listOf(SeriesMap("jk", "frieren", 7)))
        fuentes.put(listOf(link("jk", "frieren", 154587, origen = AnimeFuente.USUARIO)))
        ids.putMap(listOf(SeriesMap("jk", "frieren", 3)))
        assertEquals(3L, ids.idOf("jk", "frieren"))
        assertEquals(154587L, fuentes.get("jk", "frieren")?.anilistId)
        assertEquals(AnimeFuente.USUARIO, fuentes.get("jk", "frieren")?.origen)
    }

    @Test
    fun `putMapIfAbsent no pisa el id que ya tiene la ficha`() {
        val dao = FakeFichaDao()
        val (ids, _) = stores(dao)
        ids.putMap(listOf(SeriesMap("jk", "x", 7)))
        ids.putMapIfAbsent(listOf(SeriesMap("jk", "x", 99), SeriesMap("jk", "y", 8)))
        assertEquals(7L, ids.idOf("jk", "x"))
        assertEquals(8L, ids.idOf("jk", "y"))
    }

    @Test
    fun `restaurar el libro solo si esta vacio y con los mismos ids`() {
        val dao = FakeFichaDao()
        val (ids, _) = stores(dao)
        val map = listOf(SeriesMap("a", "1", 5), SeriesMap("b", "1", 9))
        assertTrue(ids.restoreLedgerIfEmpty(listOf(SeriesRegistry(5), SeriesRegistry(9)), map))
        assertEquals(listOf(5L, 9L), ids.allMap().map { it.seriesId }.sorted())
        assertFalse(ids.restoreLedgerIfEmpty(emptyList(), listOf(SeriesMap("c", "1", 1))))
        assertNull(ids.idOf("c", "1"))
        assertEquals(10L, ids.newSeries(SeriesRegistry()))
    }

    @Test
    fun `deshacer todas las uniones da id propio a cada ficha menos a la primera y conserva los enlaces`() {
        val dao = FakeFichaDao()
        val (ids, fuentes) = stores(dao)
        ids.putMap(listOf(SeriesMap("a", "x", 5), SeriesMap("b", "x", 5), SeriesMap("c", "x", 5), SeriesMap("a", "solo", 6)))
        fuentes.put(listOf(link("b", "x", 42)))
        ids.splitAllSeries()
        val byFicha = ids.allMap().associate { (it.sourceId to it.slug) to it.seriesId }
        assertEquals(5L, byFicha["a" to "x"])
        assertEquals(4, byFicha.values.toSet().size)
        assertEquals(6L, byFicha["a" to "solo"])
        assertEquals(42L, fuentes.get("b", "x")?.anilistId)
    }

    // ---- enlaces con AniList ----
    @Test
    fun `un enlace de una ficha sin fila crea la ficha con un id nuevo`() {
        val dao = FakeFichaDao()
        dao.insertIfAbsent("a", "x", 40)
        val (ids, fuentes) = stores(dao)
        fuentes.put(listOf(link("z", "nueva", 7, origen = AnimeFuente.USUARIO)))
        assertEquals(41L, ids.idOf("z", "nueva"))
        assertEquals(7L, fuentes.get("z", "nueva")?.anilistId)
        // Una ficha que ya existía conserva su id.
        fuentes.put(listOf(link("a", "x", 9)))
        assertEquals(40L, ids.idOf("a", "x"))
    }

    @Test
    fun `insertIfAbsent respeta el enlace que ya existe y solo ve fichas con enlace`() {
        val dao = FakeFichaDao()
        val (ids, fuentes) = stores(dao)
        ids.putMap(listOf(SeriesMap("a", "1", 1), SeriesMap("a", "2", 2)))
        assertTrue(fuentes.all().isEmpty())
        fuentes.put(listOf(link("a", "1", 10, origen = AnimeFuente.USUARIO)))
        fuentes.insertIfAbsent(listOf(link("a", "1", 99), link("a", "2", 20)))
        assertEquals(10L, fuentes.get("a", "1")?.anilistId)
        assertEquals(20L, fuentes.get("a", "2")?.anilistId)
        assertEquals(2, fuentes.all().size)
    }

    @Test
    fun `quitar el enlace automatico deja la ficha y su serie, y el del usuario no se toca`() {
        val dao = FakeFichaDao()
        val (ids, fuentes) = stores(dao)
        ids.putMap(listOf(SeriesMap("a", "auto", 1), SeriesMap("a", "usuario", 2)))
        fuentes.put(listOf(link("a", "auto", 10), link("a", "usuario", 20, origen = AnimeFuente.USUARIO)))
        fuentes.deleteAuto("a", "auto")
        fuentes.deleteAuto("a", "usuario")
        assertNull(fuentes.get("a", "auto"))
        assertEquals(1L, ids.idOf("a", "auto"))
        assertEquals(20L, fuentes.get("a", "usuario")?.anilistId)
    }

    @Test
    fun `caducar solo afecta a lo automatico y los recuentos salen de las filas con enlace`() {
        val dao = FakeFichaDao()
        val (ids, fuentes) = stores(dao)
        ids.putMap(listOf(SeriesMap("a", "1", 1), SeriesMap("a", "2", 2), SeriesMap("a", "3", 3)))
        fuentes.put(listOf(link("a", "1", 10), link("a", "2", 20, estado = AnimeFuente.PENDIENTE, origen = AnimeFuente.USUARIO)))
        fuentes.expireAuto()
        assertEquals(0L, fuentes.get("a", "1")?.fecha)
        assertEquals(5L, fuentes.get("a", "2")?.fecha)
        assertEquals(listOf(10L), fuentes.appliedIds())
        assertEquals(mapOf(AnimeFuente.APLICADO to 1, AnimeFuente.PENDIENTE to 1), fuentes.countByState().associate { it.estado to it.total })
    }
}

// ===== FicheCacheTest.kt =====
/** La ficha se guarda como JSON (ApiCache) y vuelve igual; y cuándo se renueva la copia. */
class FicheCacheTest {

    private val series = Series(
        aid = 7, name = "Naruto", slug = "naruto", description = "Un ninja.", rateStars = 4.5f, rateCount = 120, type = "anime",
        state = 1, day = null, genres = listOf(Genre("Acción", "accion")),
        relations = listOf(Relation(8, "Naruto Shippuden", RELATION_SEQUEL, "naruto-shippuden", "jkanime")),
        chapters = listOf(ChapterEntry(1, 1.0), ChapterEntry(2, 2.5)), source = "jkanime", year = 2002
    )

    @Test
    fun `la ficha guardada como JSON vuelve igual`() {
        val gson = Gson()
        val back = gson.fromJson(gson.toJson(series), Series::class.java)

        assertEquals(series, back)
        assertEquals(listOf(1.0, 2.5), back.chapters.map { it.number })
        // El nombre de cada episodio no es un parámetro del constructor: tiene que sobrevivir a la copia.
        assertEquals(listOf("Episodio 1", "Episodio 2.5"), back.chapters.map { it.name })
    }
}

// ===== FicheYearTest.kt =====
/** El año de estreno de la ficha: cada fuente por fichero lo lee de su página (fragmentos reales de las fichas de Naruto, 2026-10-21). */
class FicheYearTest {

    private fun yearOf(source: String, html: String): Int? {
        val spec = SourceSpec.parse(File("src/main/assets/sources/$source.json").readText())
        // El título es obligatorio en todas las fuentes (sin él la página no es una ficha): cada fragmento lleva el suyo, como las páginas reales.
        val title = mapOf(
            "jkanime" to """<div class="anime_info"><h3>Naruto</h3></div>""", "verani" to """<h1 class="anime-hero-title">Naruto</h1>""",
            "animeflv" to "<h1>Naruto</h1>", "monoschinos" to "<h1>Naruto</h1>"
        )[source].orEmpty()
        return checkNotNull(ConfigurableSource(spec).parseSeries("naruto", 1, HtmlNode(Jsoup.parse(title + html)), emptyList())).year
    }

    @Test
    fun animegratisReadsTheYearFromTheMetaLine() {
        val html = """<h1>Naruto</h1><p class="ag-meta-line mt-2"> <span>2002</span> <span>TV</span> <span>220 episodios</span></p>"""
        assertEquals(2002, yearOf("animegratis", html))
    }

    @Test
    fun animegratisIgnoresMetaLinesWithoutYear() {
        val html = """<h1>Naruto</h1><p class="ag-meta-line"> <span>TV</span> <span>220 episodios</span></p>"""
        assertEquals(null, yearOf("animegratis", html))
    }

    @Test
    fun jkanimeReadsTheAiredDate() {
        val html = """<ul><li><span>Duracion:</span> 23 min. por episodio</li> <li><span> Emitido: </span> Jueves, 03 de Octubre de 2002</li> <li><span>Estado:</span> Concluido</li></ul>"""
        assertEquals(2002, yearOf("jkanime", html))
    }

    @Test
    fun monoschinosReadsTheAiredDateOrTheSeason() {
        val aired = """<dl><dt>Temporada</dt><dd>Otoño 2002</dd><dt>Emisión</dt><dd>03 de Octubre de 2002</dd></dl>"""
        assertEquals(2002, yearOf("monoschinos", aired))
        // Sin fecha de emisión (una web que no la tiene) vale la temporada.
        val seasonOnly = """<dl><dt>Emisión</dt><dd>Sin fecha</dd><dt>Temporada</dt><dd>Primavera 2019</dd></dl>"""
        assertEquals(2019, yearOf("monoschinos", seasonOnly))
    }

    @Test
    fun veraniReadsTheYearCard() {
        val html = """<div class="anime-info-card"><div class="anime-info-label"><svg></svg> <span>Estudio</span></div><div class="anime-info-value">TV Tokyo</div></div>""" +
            """<div class="anime-info-card"><div class="anime-info-label"><svg></svg> <span>Año</span></div><div class="anime-info-value">2002</div></div>"""
        assertEquals(2002, yearOf("verani", html))
        // Una tarjeta que no es la del año nunca sirve, aunque lleve un número.
        assertEquals(null, yearOf("verani", """<div class="anime-info-card"><div class="anime-info-label"><span>Episodios</span></div><div class="anime-info-value">2002</div></div>"""))
    }

    @Test
    fun aSourceWithoutYearGivesNull() {
        assertEquals(null, yearOf("animeflv", """<div class="info-l">Estreno 2002</div>"""))
    }
}

// ===== GenreNamesTest.kt =====
class GenreNamesTest {
    @Test
    fun `los generos en espanol equivalen a los de AniList`() {
        assertEquals("Sci-Fi", GenreNames.anilist("ciencia-ficcion"))
        assertEquals("Slice of Life", GenreNames.anilist("Recuentos de la vida"))
        assertEquals("Fantasy", GenreNames.anilist("Fantasía"))
        assertEquals("Sports", GenreNames.anilist("deportes"))
        assertNull(GenreNames.anilist("shounen"))
    }

    @Test
    fun `comparar generos mira los que AniList tiene y no cuenta los que no`() {
        assertEquals(Match.SAME, AnilistCompare.genres(listOf("accion", "aventura", "shounen"), listOf("Action", "Adventure", "Comedy")))
        assertEquals(Match.DIFFERENT, AnilistCompare.genres(listOf("accion"), listOf("Romance", "Drama")))
        assertEquals(Match.UNKNOWN, AnilistCompare.genres(listOf("shounen"), listOf("Action")))
        assertEquals(Match.UNKNOWN, AnilistCompare.genres(emptyList(), listOf("Action")))
    }

    @Test
    fun `estado y estudio`() {
        assertEquals(Match.SAME, AnilistCompare.status(false, "FINISHED"))
        assertEquals(Match.DIFFERENT, AnilistCompare.status(true, "FINISHED"))
        assertEquals(Match.UNKNOWN, AnilistCompare.status(null, "FINISHED"))
        assertEquals(Match.UNKNOWN, AnilistCompare.status(true, "HIATUS"))
        assertEquals(Match.SAME, AnilistCompare.studio("Madhouse", "MADHOUSE"))
        assertEquals(Match.DIFFERENT, AnilistCompare.studio("Toei Animation", "Madhouse"))
    }

    @Test
    fun `el parecido total pesa el titulo y cuenta solo lo que se sabe`() {
        assertEquals(1.0, AnilistCompare.overall(1.0, listOf(Match.SAME, Match.UNKNOWN)), 1e-9)
        assertEquals(0.25, AnilistCompare.overall(0.28, listOf(Match.DIFFERENT, Match.SAME, Match.DIFFERENT)), 0.1)
        assertEquals(0.5, AnilistCompare.overall(0.5, emptyList()), 1e-9)
    }
}

// ===== LedgerBackupTest.kt =====
/** La copia del libro de series: si la base se vacía, las fichas recuperan sus ids y las reglas del usuario siguen apuntando a lo mismo. */
class LedgerBackupTest {
    private open class FakeDao : SeriesIdDao {
        var last = 0L
        val registry = mutableSetOf<Long>()
        val map = mutableMapOf<Pair<String, String>, Long>()

        override fun newSeries(registry: SeriesRegistry): Long { last++; this.registry += last; return last }
        override fun putRegistry(rows: List<SeriesRegistry>) { rows.forEach { last = maxOf(last, it.seriesId); registry += it.seriesId } }
        override fun hasRegistry() = registry.isNotEmpty()
        override fun allMap() = map.map { (key, id) -> SeriesMap(key.first, key.second, id) }
        override fun idOf(sourceId: String, slug: String) = map[sourceId to slug]
        override fun putMap(rows: List<SeriesMap>) { rows.forEach { map[it.sourceId to it.slug] = it.seriesId } }
        override fun putMapIfAbsent(rows: List<SeriesMap>) { rows.forEach { map.putIfAbsent(it.sourceId to it.slug, it.seriesId) } }
        override fun moveSeries(moves: Map<Long, Long>) { map.replaceAll { _, id -> moves[id] ?: id } }
    }

    private class FakeBackups(var stored: LedgerBackup? = null) : LedgerBackups {
        var writes = 0
        override fun read() = stored
        override fun write(backup: LedgerBackup) { stored = backup; writes++ }
    }

    private lateinit var dao: FakeDao
    private lateinit var backups: FakeBackups

    private fun backup(vararg map: Triple<String, String, Long>, redirects: List<Pair<Long, Long>> = emptyList()) = LedgerBackup(
        LedgerBackup.VERSION, 1L, map.map { SnapMap(it.first, it.second, it.third) }, redirects.map { SnapRedirect(it.first, it.second) }
    )

    @Before
    fun setUp() {
        dao = FakeDao()
        backups = FakeBackups()
        SeriesLedger.dao = { dao }
        SeriesLedger.userRows = { NoUserRows }
        SeriesLedger.transaction = { it() }
        SeriesLedger.backups = backups
        SeriesLedger.snapshotUsable = { false }
    }

    @After
    fun tearDown() {
        SeriesLedger.dao = { app.kaizen.database.CacheDB.INSTANCE.seriesIdDao() }
        SeriesLedger.backups = LedgerBackupFile
        SeriesLedger.snapshotUsable = { CatalogSnapshotFile.read()?.usable(CatalogStateStore.RULES_VERSION) == true }
    }

    @Test
    fun `con el libro vacio los ids vuelven a ser los de la copia y el siguiente va por encima`() {
        backups.stored = backup(Triple("animeav1", "a", 5L), Triple("jkanime", "a", 5L), Triple("animeav1", "b", 9L), Triple("jkanime", "c", 7L), redirects = listOf(7L to 5L))

        SeriesLedger.load()

        assertEquals(5L, SeriesLedger.peek("animeav1", "a"))
        assertEquals(5L, SeriesLedger.peek("jkanime", "a"))
        assertEquals(9L, SeriesLedger.peek("animeav1", "b"))
        // Una copia hecha cuando aún había redirecciones: la ficha del id 7 pasa a su serie vigente, la 5.
        assertEquals(5L, SeriesLedger.peek("jkanime", "c"))
        // Un id nuevo no choca con los restaurados (el 9 es el mayor).
        assertTrue(SeriesLedger.idOf("animeav1", "nueva") > 9L)
    }

    @Test
    fun `una copia con ids unidos tambien los restaura en el registro`() {
        backups.stored = backup(Triple("animeav1", "a", 5L), redirects = listOf(12L to 5L))

        SeriesLedger.load()

        assertTrue(12L in dao.registry)
        assertTrue(SeriesLedger.idOf("animeav1", "otra") > 12L)
    }

    @Test
    fun `no toca nada si ya hay ids`() {
        dao.map["animeav1" to "x"] = 1L
        dao.registry += 1L
        dao.last = 1L
        backups.stored = backup(Triple("animeav1", "a", 5L))

        SeriesLedger.load()

        assertNull(SeriesLedger.peek("animeav1", "a"))
        assertEquals(1L, SeriesLedger.peek("animeav1", "x"))
    }

    @Test
    fun `con una fotografia del catalogo que se pueda usar la copia del libro no actua`() {
        SeriesLedger.snapshotUsable = { true }
        backups.stored = backup(Triple("animeav1", "a", 5L))

        SeriesLedger.load()

        assertNull(SeriesLedger.peek("animeav1", "a"))
        assertFalse(dao.hasRegistry())
    }

    @Test
    fun `si otro hilo da un id entre la comprobacion y la restauracion no se pisa nada`() {
        // La primera comprobación (fuera de la transacción) ve el libro vacío; dentro de ella ya hay un id.
        var calls = 0
        val racing = object : FakeDao() {
            override fun hasRegistry() = if (calls++ == 0) false else true
        }
        backups.stored = backup(Triple("animeav1", "a", 5L))

        assertFalse(SeriesLedger.restoreBackupIfEmpty(racing))

        assertTrue(racing.map.isEmpty())
    }

    @Test
    fun `una copia dañada o de otra version no se usa`() {
        backups.stored = LedgerBackup(LedgerBackup.VERSION, 1L, listOf(SnapMap("animeav1", "a", 0L)), null)
        SeriesLedger.load()
        assertFalse(dao.hasRegistry())

        backups.stored = LedgerBackup(LedgerBackup.VERSION + 1, 1L, listOf(SnapMap("animeav1", "a", 5L)), null)
        SeriesLedger.load()
        assertFalse(dao.hasRegistry())

        backups.stored = LedgerBackup(LedgerBackup.VERSION, 1L, emptyList(), null)
        SeriesLedger.load()
        assertFalse(dao.hasRegistry())
    }

    @Test
    fun `guardar la copia lleva las fichas con su serie y un libro vacio no pisa la copia buena`() {
        SeriesLedger.saveBackup()
        assertEquals(0, backups.writes)

        dao.map["animeav1" to "a"] = 3L
        dao.map["jkanime" to "a"] = 3L
        dao.registry += 3L
        SeriesLedger.saveBackup()

        val saved = backups.stored!!
        assertEquals(1, backups.writes)
        assertTrue(saved.usable())
        assertEquals(setOf(Triple("animeav1", "a", 3L), Triple("jkanime", "a", 3L)), saved.map.orEmpty().map { Triple(it.sourceId, it.slug, it.seriesId) }.toSet())
        assertTrue(saved.redirects.orEmpty().isEmpty())
    }
}

// ===== LinkEvidenceTest.kt =====
/** La regla de año que comparten el emparejado con AniList, la comprobación de enlaces y la unión entre fuentes. */
class LinkEvidenceTest {
    @Test
    fun `dos años caben juntos con un año de margen y un año que falta no juzga`() {
        assertEquals(true, LinkEvidence.yearsClose(2017, 2017))
        assertEquals(true, LinkEvidence.yearsClose(2017, 2018))
        assertEquals(false, LinkEvidence.yearsClose(2017, 2019))
        assertEquals(true, LinkEvidence.yearsClose(null, 2019))
        assertEquals(true, LinkEvidence.yearsClose(2019, null))
    }

    @Test
    fun `un grupo de años es compatible si todos los conocidos caben en el margen`() {
        assertEquals(true, LinkEvidence.compatible(emptyList()))
        assertEquals(true, LinkEvidence.compatible(listOf(null, null)))
        assertEquals(true, LinkEvidence.compatible(listOf(2017, null, 2018)))
        assertEquals(false, LinkEvidence.compatible(listOf(2017, 2018, 2019)))
        assertEquals(false, LinkEvidence.compatible(listOf(2010, null, 2020)))
    }

    @Test
    fun `el año de una serie es el mas antiguo de sus fichas`() {
        assertEquals(2017, LinkEvidence.seriesYear(listOf(2018, null, 2017)))
        assertEquals(null, LinkEvidence.seriesYear(listOf(null)))
    }
}

// ===== MemoryCacheTest.kt =====
/** Caché en memoria delante de ApiCache: pequeña, acotada y sin copias grandes. */
class MemoryCacheTest {
    private fun entry(key: String, payload: String = "x", at: Long = 1L) = ApiCacheEntry(key, payload, at)

    @Test
    fun `devuelve lo guardado y la copia nueva sustituye a la anterior`() {
        val cache = MemoryCache()
        cache.put(entry("a", "uno", at = 1))
        cache.put(entry("a", "dos", at = 2))

        assertEquals(ApiCacheEntry("a", "dos", 2), cache.get("a"))
        assertNull(cache.get("b"))
    }

    @Test
    fun `al llenarse salen primero las menos usadas`() {
        val cache = MemoryCache(maxEntries = 2)
        cache.put(entry("a"))
        cache.put(entry("b"))
        cache.get("a") // a se usa: b pasa a ser la menos usada
        cache.put(entry("c"))

        assertEquals("a", cache.get("a")?.key)
        assertNull(cache.get("b"))
        assertEquals("c", cache.get("c")?.key)
    }

    @Test
    fun `una respuesta grande no se guarda y quita la anterior`() {
        val cache = MemoryCache(maxPayload = 10)
        cache.put(entry("a", "corta"))
        cache.put(entry("a", "x".repeat(11)))

        assertNull(cache.get("a"))
    }

    @Test
    fun `quitar y vaciar`() {
        val cache = MemoryCache()
        cache.put(entry("a"))
        cache.put(entry("b"))

        cache.remove("a")
        assertNull(cache.get("a"))
        assertEquals("b", cache.get("b")?.key)

        cache.clear()
        assertNull(cache.get("b"))
    }
}

// ===== MergeProposalsTest.kt =====
/** Propuestas de unión por slug sin sufijos. */
class MergeProposalsTest {
    private fun entry(id: Long, name: String) = CatalogEntry(id.toString(), id, name, null, 0, null, 0L)
    private fun link(id: Long, source: String, slug: String) = CatalogLink(id.toString(), source, slug, 0, id)

    private val entries = listOf(entry(1, "One Piece"), entry(2, "One Piece TV"), entry(3, "Bleach"))

    @Test
    fun `dos series con el mismo slug sin sufijos se proponen`() {
        val links = listOf(link(1, "av1", "one-piece"), link(2, "ag", "one-piece-anime"), link(3, "av1", "bleach"))

        val proposals = MergeProposals.find(entries, links, TitleRules.EMPTY)

        assertEquals(1, proposals.size)
        assertEquals("1" to "2", proposals.single().first.key to proposals.single().second.key)
        assertEquals("one-piece", proposals.single().cleanSlug)
    }

    @Test
    fun `lo ya unido o separado por el usuario no se propone`() {
        val links = listOf(link(1, "av1", "one-piece"), link(2, "ag", "one-piece-tv"))

        assertTrue(MergeProposals.find(entries, links, TitleRules.EMPTY.withMerge("1", "2")).isEmpty())
        assertTrue(MergeProposals.find(entries, links, TitleRules.EMPTY.withSplit("1", "2")).isEmpty())
    }

    @Test
    fun `una serie con varias fichas que coinciden con otra se propone una sola vez`() {
        val links = listOf(
            link(1, "av1", "one-piece"), link(1, "jk", "one-piece-tv"),
            link(2, "ag", "one-piece-anime"), link(2, "jk", "one-piece")
        )

        assertEquals(1, MergeProposals.find(entries, links, TitleRules.EMPTY).size)
    }

    @Test
    fun `tres series con el mismo slug se emparejan con la de id mas bajo`() {
        val three = entries + entry(4, "One Piece Z")
        val links = listOf(link(1, "av1", "x"), link(2, "ag", "x-anime"), link(4, "jk", "x-tv"))

        val proposals = MergeProposals.find(three, links, TitleRules.EMPTY)

        assertEquals(listOf("1" to "2", "1" to "4"), proposals.map { it.first.key to it.second.key })
    }

    @Test
    fun `los sufijos de las series ya unidas se aplican a otras`() {
        val all = entries + entry(4, "One Piece Latino") + entry(5, "Naruto") + entry(6, "Naruto Latino")
        val links = listOf(
            link(5, "av1", "naruto"), link(5, "ag", "naruto-latino"),
            link(1, "av1", "bleach"), link(1, "ag", "bleach-latino"),
            link(2, "av1", "one-piece"), link(4, "jk", "one-piece-latino")
        )

        val proposals = MergeProposals.find(all, links, TitleRules.EMPTY)

        assertEquals(listOf("2" to "4"), proposals.map { it.first.key to it.second.key })
    }

    @Test
    fun `un sufijo visto en una sola serie no se aprende`() {
        val links = listOf(link(1, "av1", "naruto"), link(1, "ag", "naruto-latino"), link(2, "av1", "one-piece"), link(3, "jk", "one-piece-latino"))

        assertTrue(MergeProposals.learnedSuffixes(links).isEmpty())
        assertTrue(MergeProposals.find(entries, links, TitleRules.EMPTY).isEmpty())
    }

    @Test
    fun `los sufijos de temporada o pelicula no se aprenden`() {
        val links = listOf(
            link(1, "av1", "x"), link(1, "ag", "x-2nd"), link(2, "av1", "y"), link(2, "ag", "y-2nd"),
            link(1, "jk", "z"), link(1, "tv", "z-season-final"), link(2, "jk", "w"), link(2, "tv", "w-season-final")
        )

        assertTrue(MergeProposals.learnedSuffixes(links).isEmpty())
    }

    @Test
    fun `dos propuestas encadenadas proponen tambien el par que las cierra`() {
        // La serie 2 coincide con la 1 por «a» y con la 3 por «b»: 1-2 y 2-3 salen del slug; 1-3 se infiere.
        val links = listOf(link(1, "av1", "a"), link(2, "ag", "a-tv"), link(2, "jk", "b-tv"), link(3, "av1", "b"))

        val proposals = MergeProposals.find(entries, links, TitleRules.EMPTY)

        assertEquals(setOf("1" to "2", "2" to "3", "1" to "3"), proposals.map { it.first.key to it.second.key }.toSet())
        assertEquals("1" to "3", proposals.single { it.inferred }.let { it.first.key to it.second.key })
    }

    @Test
    fun `un sufijo entre fichas de la misma fuente no se aprende`() {
        // «x» y «x-latino» en una misma fuente son dos obras; solo una plataforma distinta cuenta como sufijo.
        val links = listOf(
            link(1, "av1", "naruto"), link(1, "av1", "naruto-latino"),
            link(2, "av1", "bleach"), link(2, "av1", "bleach-latino")
        )

        assertTrue(MergeProposals.learnedSuffixes(links).isEmpty())
    }

    @Test
    fun `fichas del mismo sufijo en una misma serie no se proponen`() {
        val links = listOf(link(1, "av1", "one-piece"), link(1, "ag", "one-piece-anime"))

        assertTrue(MergeProposals.find(entries, links, TitleRules.EMPTY).isEmpty())
    }
}

// ===== RegroupSignatureTest.kt =====
/** Cuándo hay que reagrupar el catálogo al arrancar: solo si cambió la instalación de la app o las reglas del usuario. */
class RegroupSignatureTest {
    @Test
    fun `las mismas reglas y la misma instalacion dan la misma firma`() {
        assertEquals(regroupSignature(239L, """{"merge":[["1","2"]]}"""), regroupSignature(239L, """{"merge":[["1","2"]]}"""))
    }

    @Test
    fun `otras reglas o otra instalacion cambian la firma`() {
        val base = regroupSignature(239L, """{"merge":[["1","2"]]}""")

        assertNotEquals(base, regroupSignature(239L, """{"merge":[["1","3"]]}"""))
        assertNotEquals(base, regroupSignature(240L, """{"merge":[["1","2"]]}"""))
    }

    @Test
    fun `sin reglas la firma es estable y distinta de la de unas reglas`() {
        assertEquals(regroupSignature(239L, null), regroupSignature(239L, ""))
        assertNotEquals(regroupSignature(239L, null), regroupSignature(239L, """{"merge":[]}"""))
    }
}

// ===== RelationsMergeTest.kt =====
/** Relaciones de una ficha reunidas de varias fuentes. */
class RelationsMergeTest {
    private fun merge(vararg relations: Relation) =
        mergeRelations("serie-2", "Serie 2", "jkanime", relations.toList(), "animeav1")

    @Test
    fun laMismaSerieDeOtraFuenteNoSeRepite() {
        val own = Relation(1, "Serie", RELATION_PREQUEL, "serie", "jkanime")
        val other = Relation(2, "Serie", RELATION_PREQUEL, "serie-tv", "animegratis")
        assertEquals(listOf(own), merge(own, other))
    }

    @Test
    fun otraFuenteAportaLasQueFaltan() {
        val own = Relation(1, "Serie", RELATION_PREQUEL, "serie", "jkanime")
        val sequel = Relation(3, "Serie 3", RELATION_SEQUEL, "serie-3", "animegratis")
        assertEquals(listOf(own, sequel), merge(own, sequel))
    }

    @Test
    fun laPropiaFichaEnOtraFuenteNoSeAnade() {
        val self = Relation(4, "Serie 2", RELATION_SEQUEL, "serie-2nd-season", "animegratis")
        assertEquals(emptyList<Relation>(), merge(self))
    }

    @Test
    fun sinFuenteCuentaComoAnimeav1() {
        val legacy = Relation(5, "Película", 6, "pelicula", null)
        val same = Relation(6, "Película", 6, "pelicula-2", "animeav1")
        assertEquals(listOf(legacy, same), merge(legacy, same))
    }
}

// ===== ScheduleDedupeTest.kt =====
/** El horario no repite una serie unida aunque cada fuente la ponga en un día distinto: se queda el más temprano. */
class ScheduleDedupeTest {

    private class Card(val slug: String, val name: String, val source: String)

    private fun airing(slug: String, name: String, source: String) = Card(slug, name, source)

    private fun run(days: Map<Int, List<Card>>, dedupe: SeriesDedupe) =
        dedupeSchedule(days, dedupe) { Triple(it.slug, it.name, it.source) }.mapValues { (_, items) -> items.map { it.slug } }

    @Test
    fun `una serie unida que sale dos dias se queda en el mas temprano`() {
        val ids = mapOf(("a" to "shin-tennis") to 7L, ("b" to "shin-tennis-u17") to 7L)
        val dedupe = SeriesDedupe(TitleRules.EMPTY, idOf = { source, slug -> ids[source to slug] })
        val days = mapOf(
            5 to listOf(airing("shin-tennis-u17", "Shin Tennis U-17", "b")),
            2 to listOf(airing("shin-tennis", "Shin Tennis", "a"), airing("otra", "Otra serie", "a"))
        )

        assertEquals(mapOf(2 to listOf("shin-tennis", "otra"), 5 to emptyList()), run(days, dedupe))
    }

    @Test
    fun `el mismo titulo en dos fuentes y dos dias sale una vez`() {
        val dedupe = SeriesDedupe(TitleRules.EMPTY, idOf = { _, _ -> null })
        val days = mapOf(
            4 to listOf(airing("x-anime", "Kanata Kara", "b")),
            1 to listOf(airing("x", "Kanata Kara", "a"))
        )

        assertEquals(mapOf(1 to listOf("x"), 4 to emptyList()), run(days, dedupe))
    }

    @Test
    fun `dos fichas de una misma fuente sin unir se conservan`() {
        val dedupe = SeriesDedupe(TitleRules.EMPTY, idOf = { _, _ -> null })
        val days = mapOf(3 to listOf(airing("serie", "Serie", "a"), airing("serie-movie", "Serie Movie", "a")))

        assertEquals(mapOf(3 to listOf("serie", "serie-movie")), run(days, dedupe))
    }
}

// ===== SearchQueryTest.kt =====
class SearchQueryTest {

    @Test
    fun emptyQueryHasNoActiveFilters() {
        assertTrue(SearchQuery().active.isEmpty())
        assertTrue(SearchQuery(text = "   ").active.isEmpty())
    }

    @Test
    fun everyFieldMapsToItsFilter() {
        val query = SearchQuery("naruto", "Acción", airing = true, minScore = 4.0, years = 2000..2010, sort = SortBy.RATING)
        assertEquals(Filter.values().toSet(), query.active)
    }

    @Test
    fun cacheKeyIgnoresCaseAndSurroundingSpaces() {
        assertEquals(SearchQuery(" Naruto ").cacheKey, SearchQuery("naruto").cacheKey)
    }

    @Test
    fun cacheKeyChangesWithEveryFilter() {
        val base = SearchQuery("naruto")
        listOf(
            base.copy(genre = "Acción"), base.copy(airing = true), base.copy(minScore = 3.5),
            base.copy(years = 2000..2001), base.copy(sort = SortBy.RATING)
        ).forEach { assertNotEquals(base.cacheKey, it.cacheKey) }
    }
}

// ===== SeriesIdentityTest.kt =====
/** La identidad de serie para quitar repetidos en las listas. */
class SeriesIdentityTest {
    @Test
    fun `sin id de serie la identidad es el titulo normalizado`() {
        assertEquals(seriesIdentity("Steins;Gate", "jkanime", "steins-gate-a"), seriesIdentity("steins gate", "animeav1", "steins-gate-b"))
        assertNotEquals(seriesIdentity("Naruto", "jkanime", "naruto"), seriesIdentity("Bleach", "jkanime", "bleach"))
    }

    @Test
    fun `la clave de una ficha sin id es su fuente y su slug`() {
        assertEquals("f:jkanime:naruto", seriesKeyOf("jkanime", "naruto"))
    }
}

// ===== SeriesIdsTest.kt =====
/** Reparto de ids de serie: se conservan al reconstruir, y las uniones y separaciones los mueven sin perder ninguno. */
class SeriesIdsTest {
    private fun counter(start: Long = 100) = object {
        var next = start
        fun take() = next++
    }

    private fun group(vararg slugs: String) = slugs.map { it.substringBefore(':') to it.substringAfter(':') }

    @Test
    fun `una serie nueva recibe un id y una conocida lo conserva`() {
        val ids = counter()
        val known = mapOf(("av1" to "naruto") to 7L)

        val result = SeriesIds.assign(listOf(group("av1:naruto"), group("av1:bleach")), known, ids::take)

        assertEquals(listOf(7L, 100L), result.ids)
        assertEquals(0, result.moves.size)
    }

    @Test
    fun `fichas de varias fuentes con ids distintos se unen al mas bajo y el otro pasa a ser el mismo`() {
        val known = mapOf(("av1" to "naruto") to 7L, ("jk" to "naruto-tv") to 9L)

        val result = SeriesIds.assign(listOf(group("av1:naruto", "jk:naruto-tv")), known, counter()::take)

        assertEquals(listOf(7L), result.ids)
        assertEquals(mapOf(9L to 7L), result.moves)
        assertEquals(setOf(7L), result.map.map { it.seriesId }.toSet())
    }

    @Test
    fun `al separar una ficha se queda el id el grupo con mas fichas y la otra recibe uno nuevo`() {
        val known = mapOf(("av1" to "a") to 7L, ("jk" to "a") to 7L, ("ag" to "a-especial") to 7L)

        val result = SeriesIds.assign(listOf(group("av1:a", "jk:a"), group("ag:a-especial")), known, counter(50)::take)

        assertEquals(listOf(7L, 50L), result.ids)
    }

    @Test
    fun `tres ids en un grupo se unen al mas bajo`() {
        val known = mapOf(("a" to "x") to 5L, ("b" to "x") to 7L, ("c" to "x") to 9L)

        val result = SeriesIds.assign(listOf(group("a:x", "b:x", "c:x")), known, counter()::take)

        assertEquals(listOf(5L), result.ids)
        assertEquals(mapOf(7L to 5L, 9L to 5L), result.moves)
    }

    @Test
    fun `un grupo vacio gasta un id y no escribe filas`() {
        val result = SeriesIds.assign(listOf(emptyList()), emptyMap(), counter(40)::take)

        assertEquals(listOf(40L), result.ids)
        assertEquals(0, result.map.size)
    }

    @Test
    fun `a igualdad de fichas y tamano se queda el id el primer grupo`() {
        val known = mapOf(("a" to "x") to 5L, ("b" to "y") to 5L)

        val result = SeriesIds.assign(listOf(group("a:x"), group("b:y")), known, counter(60)::take)

        assertEquals(listOf(5L, 60L), result.ids)
    }

    @Test
    fun `dos fichas de la misma fuente en un grupo comparten id`() {
        val result = SeriesIds.assign(listOf(group("av1:a", "av1:a-2")), emptyMap(), counter()::take)

        assertEquals(2, result.map.size)
        assertEquals(1, result.map.map { it.seriesId }.toSet().size)
        assertNotEquals(0L, result.ids.single())
    }
}

// ===== SeriesLedgerTest.kt =====
/** Los ids de serie en memoria: se dan una vez por ficha, resuelven uniones y se recargan de golpe. */
class SeriesLedgerTest {
    private class FakeDao : SeriesIdDao {
        var last = 0L
        val map = mutableMapOf<Pair<String, String>, Long>()

        override fun newSeries(registry: SeriesRegistry) = ++last
        override fun putRegistry(rows: List<SeriesRegistry>) { rows.forEach { last = maxOf(last, it.seriesId) } }
        override fun hasRegistry() = last > 0
        override fun allMap() = map.map { (key, id) -> SeriesMap(key.first, key.second, id) }
        override fun idOf(sourceId: String, slug: String) = map[sourceId to slug]
        override fun putMap(rows: List<SeriesMap>) { rows.forEach { map[it.sourceId to it.slug] = it.seriesId } }
        override fun putMapIfAbsent(rows: List<SeriesMap>) { rows.forEach { map.putIfAbsent(it.sourceId to it.slug, it.seriesId) } }
        override fun moveSeries(moves: Map<Long, Long>) { map.replaceAll { _, id -> moves[id] ?: id } }
    }

    private lateinit var dao: FakeDao

    @Before
    fun setUp() {
        dao = FakeDao()
        SeriesLedger.dao = { dao }
        SeriesLedger.userRows = { NoUserRows }
        SeriesLedger.transaction = { it() }
        SeriesLedger.load()
    }

    @After
    fun tearDown() {
        SeriesLedger.dao = { app.kaizen.database.CacheDB.INSTANCE.seriesIdDao() }
    }

    @Test
    fun `las fichas de una serie se resuelven al leer y sin id no hay ninguna`() {
        dao.map["av1" to "x"] = 5L
        dao.map["jk" to "x"] = 5L
        dao.map["av1" to "otra"] = 8L
        SeriesLedger.load()

        assertEquals(setOf("av1:x", "jk:x"), SeriesLedger.fichaKeysOf(5L).toSet())
        assertEquals(listOf("av1:otra"), SeriesLedger.fichaKeysOf(8L))
        assertEquals(emptyList<String>(), SeriesLedger.fichaKeysOf(0L))
        assertEquals(emptyList<String>(), SeriesLedger.fichaKeysOf(77L))
    }

    @Test
    fun `una ficha sin id no tiene id hasta que se le da y entonces es siempre el mismo`() {
        assertNull(SeriesLedger.peek("jk", "frieren"))

        val id = SeriesLedger.idOf("jk", "frieren")

        assertEquals(id, SeriesLedger.peek("jk", "frieren"))
        assertEquals(id, SeriesLedger.idOf("jk", "frieren"))
        assertEquals(id.toString(), SeriesLedger.peekKey("jk", "frieren"))
        assertEquals(id, SeriesLedger.peekBySlug("frieren"))
    }

    @Test
    fun `fichas distintas tienen ids distintos`() {
        assertNotEquals(SeriesLedger.idOf("jk", "a"), SeriesLedger.idOf("ag", "a"))
    }

    @Test
    fun `si otro hilo se adelanto se usa el id que gano`() {
        dao.map["jk" to "x"] = 77L

        assertEquals(77L, SeriesLedger.idOf("jk", "x"))
        assertEquals(77L, SeriesLedger.peek("jk", "x"))
    }

    @Test
    fun `separar una ficha le da un id nuevo`() {
        val joined = SeriesLedger.idOf("jk", "a")

        val detached = SeriesLedger.detach("jk", "a")

        assertNotEquals(joined, detached)
        assertEquals(detached, SeriesLedger.peek("jk", "a"))
    }

    @Test
    fun `recargar cambia el estado entero y olvida lo que ya no esta`() {
        SeriesLedger.idOf("jk", "viejo")
        dao.map.clear()
        dao.map["ag" to "nuevo"] = 5L

        SeriesLedger.load()

        assertNull(SeriesLedger.peek("jk", "viejo"))
        assertEquals(5L, SeriesLedger.peek("ag", "nuevo"))
        assertEquals(setOf("ag" to "nuevo"), SeriesLedger.fichas())
    }

    @Test
    fun `fichasOfKeys da las fichas de cada serie`() {
        dao.map["av1" to "x"] = 5L
        dao.map["jk" to "x"] = 5L
        dao.map["ag" to "otra"] = 8L
        SeriesLedger.load()

        assertEquals(setOf("av1" to "x", "jk" to "x"), SeriesLedger.fichasOfKeys(listOf("5")))
        assertEquals(setOf("ag" to "otra"), SeriesLedger.fichasOfKeys(listOf("8", "no-es-un-id")))
    }

    @Test
    fun `el indice por serie sigue a las altas y a las separaciones`() {
        val id = SeriesLedger.idOf("jk", "a")
        assertEquals(setOf("jk" to "a"), SeriesLedger.fichasOfKeys(listOf(id.toString())))

        val detached = SeriesLedger.detach("jk", "a")

        assertEquals(emptySet<Pair<String, String>>(), SeriesLedger.fichasOfKeys(listOf(id.toString())))
        assertEquals(setOf("jk" to "a"), SeriesLedger.fichasOfKeys(listOf(detached.toString())))
    }

    @Test
    fun `seriesIdOf da cero sin id`() {
        assertEquals(0L, seriesIdOf("jk", "desconocida"))
        val id = SeriesLedger.idOf("jk", "conocida")
        assertEquals(id, seriesIdOf("jk", "conocida"))
    }

    @Test
    fun `deshacer las uniones da a cada ficha su id`() {
        dao.map["av1" to "x"] = 5L
        dao.map["jk" to "x"] = 5L
        dao.map["ag" to "x-anime"] = 5L
        dao.map["av1" to "otra"] = 8L
        dao.last = 10L

        SeriesLedger.splitAll()

        assertEquals(4, dao.map.values.toSet().size)
        assertEquals(5L, dao.map.getValue("ag" to "x-anime"))
        assertEquals(dao.map.getValue("jk" to "x"), SeriesLedger.peek("jk", "x"))
    }

    @Test
    fun `unir un grupo de fichas las deja en la serie de menor id, aunque alguna fuera nueva`() {
        dao.map["av1" to "x"] = 5L
        dao.map["jk" to "x"] = 7L
        dao.map["ag" to "x"] = 9L
        dao.last = 9L
        SeriesLedger.load()

        SeriesLedger.join(listOf(listOf("av1" to "x", "jk" to "x", "ag" to "x", "nueva" to "x"), listOf("solo" to "y")))

        val ids = listOf("av1" to "x", "jk" to "x", "ag" to "x", "nueva" to "x").map { SeriesLedger.peek(it.first, it.second) }.toSet()
        assertEquals(setOf(5L), ids)
        assertNull(SeriesLedger.peek("solo", "y"))
    }

    @Test
    fun `detachGroup deja las fichas del grupo juntas en una serie nueva y no toca el resto`() {
        val a = SeriesLedger.idOf("av", "x")
        SeriesLedger.join(listOf(listOf("av" to "x", "jk" to "x", "mc" to "x")))
        val joined = SeriesLedger.peek("av", "x")
        val group = SeriesLedger.detachGroup(listOf("jk" to "x", "mc" to "x", "zz" to "desconocida"))
        assertTrue(group != null && group != joined)
        assertEquals(group, SeriesLedger.peek("jk", "x"))
        assertEquals(group, SeriesLedger.peek("mc", "x"))
        assertEquals(joined, SeriesLedger.peek("av", "x"))
        assertNull(SeriesLedger.peek("zz", "desconocida"))
        assertNull(SeriesLedger.detachGroup(listOf("zz" to "otra")))
        assertTrue(a > 0)
    }

    @Test
    fun `al separar fichas su favorito se va con ellas a la serie nueva`() {
        SeriesLedger.join(listOf(listOf("av" to "x", "jk" to "x")))
        val moved = mutableListOf<Pair<List<Pair<String, String>>, Long>>()
        SeriesLedger.userRows = {
            object : UserRows {
                override fun followLedger() {}
                override fun moveFavorites(fichas: List<Pair<String, String>>, seriesId: Long) { moved += fichas to seriesId }
            }
        }
        val id = SeriesLedger.detachGroup(listOf("jk" to "x"))
        val single = SeriesLedger.detach("av", "x")
        assertEquals(listOf(listOf("jk" to "x") to id, listOf("av" to "x") to single), moved)
    }
}

// ===== SeriesMatchingTest.kt =====
/** Reglas con las que se decide que dos fichas de fuentes distintas son la misma serie. Cada caso salió de un fallo real. */
class SeriesMatchingTest {

    private fun key(name: String) = normalizeTitle(name)

    @Test
    fun seasonWordsAndNumbersGiveTheSameKey() {
        val forms = listOf(
            "Koori no Jouheki 2nd Season", "Koori no Jouheki 2nda Temporada", "Koori no Jouheki Season 2",
            "Koori no Jouheki Season2", "Koori no Jouheki Temporada 2", "Koori no Jouheki II", "Koori no Jouheki 2"
        )
        assertEquals(1, forms.map(::key).toSet().size)
    }

    @Test
    fun differentSeasonsStayDifferent() {
        assertNotEquals(key("Tensei shitara Ken deshita"), key("Tensei shitara Ken deshita II"))
        assertNotEquals(key("Kizu darake Seijo Season 2"), key("Kizu darake Seijo Season 3"))
    }

    @Test
    fun singleLettersAreNotRomanNumerals() {
        assertTrue(key("Hunter x Hunter").contains("x"))
        assertNotEquals(key("Gintama"), key("Gintama V"))
    }

    @Test
    fun punctuationAccentsAndCaseAreIgnored() {
        assertEquals(key("Steins;Gate"), key("steins gate"))
        assertEquals(key("Shin Tennis no Ouji-sama"), key("Shin Tennis no Oujisama"))
        assertEquals(key("Épico: Ñandú"), key("epico nandu"))
    }

    @Test
    fun lookAlikeCyrillicLettersMatchLatinOnes() {
        assertEquals(key("PSYЯEN"), key("Psyren"))
        assertEquals(key("Bаi Ri"), key("Bai Ri")) // «а» cirílica
    }

    @Test
    fun truncatedLongTitlesMatch() {
        val long = "Tsuihou sareta Cheat Fuyo Majutsushi wa Kimama na Second Life wo Ouka Suru Ore wa Buki dake ja Naku"
        assertTrue(sameTitle(key(long), key(long.substring(0, long.length - 12))))
        assertFalse(sameTitle(key("Naruto"), key("Naruto Shippuden"))) // cortos: no se tratan como recortados
    }

    @Test
    fun cleanSlugOnlyRemovesPlatformSuffixes() {
        assertEquals("one-piece", cleanSlug("one-piece-anime"))
        assertEquals("one-piece", cleanSlug("one-piece-tv"))
        assertEquals("one-piece-movie", cleanSlug("one-piece-movie"))
    }

    @Test
    fun titleFromSlugUsesTitleCase() {
        assertEquals("Bai Ri Cheng Wang", titleFromSlug("bai-ri-cheng-wang"))
    }

    @Test
    fun mergeSeriesJoinsAcrossSourcesAndWeightsTheScore() {
        val a = SeriesSummary(1, "Koori no Jouheki 2nd Season", "koori-no-jouheki-2nd-season", 1, "jkanime", 4.0, 100)
        val b = SeriesSummary(2, "Koori no Jouheki 2nda Temporada", "koori-no-jouheki-2nda-temporada", 1, "animegratis", 3.0, 300)
        val other = SeriesSummary(3, "Otra serie", "otra-serie", 1, "animegratis")
        val merged = mergeSeries(listOf(a, b, other))
        assertEquals(2, merged.size)
        assertEquals(3.25, merged[0].score!!, 0.001) // (4*100 + 3*300) / 400
        assertEquals(400, merged[0].votes)
    }

    @Test
    fun mergeSeriesKeepsTwoEntriesOfTheSameSource() {
        val series = SeriesSummary(1, "Serie X", "serie-x", 1, "jkanime")
        val movie = SeriesSummary(2, "Serie X", "serie-x-movie", 1, "jkanime")
        assertEquals(2, mergeSeries(listOf(series, movie)).size)
    }

    @Test
    fun starsAreScaledToFive() {
        assertEquals(4.365, toStars(8.73), 0.001)
        assertEquals(4.0, toStars(4.0), 0.001)
        assertNull(weightedStars(listOf(4.0 to 0)))
    }

    // ---- fechas ----

    private val now: Long = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 9, 16, 30, 0) }.timeInMillis

    private fun dayOf(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.DAY_OF_MONTH)

    @Test
    fun relativeDatesOfJkanime() {
        assertEquals(8, dayOf(relativeDate("Ep 2 Ayer Koori", now)))
        assertEquals(9, dayOf(relativeDate("Hoy", now)))
        assertEquals(7, dayOf(relativeDate("Ep 1 Miércoles 07 Magic Knight", now)))
        assertEquals(now - 3 * 3_600_000L, relativeDate("hace 3 horas", now))
        assertEquals(now - 20 * 60_000L, relativeDate("hace 20 minutos", now))
        assertEquals(0L, relativeDate("sin fecha", now))
    }

    @Test
    fun weekdayWithDayOfPreviousMonthGoesBack() {
        val early = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 2, 12, 0, 0) }.timeInMillis
        val result = Calendar.getInstance().apply { timeInMillis = relativeDate("Miércoles 30", early) }
        assertEquals(Calendar.SEPTEMBER, result.get(Calendar.MONTH))
        assertEquals(30, result.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun siteDateOfAnimegratis() {
        assertEquals(8, dayOf(siteDate(8, "oct", now)))
        assertEquals(0L, siteDate(8, "xyz", now))
    }

    // ---- SeriesDedupe: la regla única de «misma serie» en los listados ----

    @Test
    fun dedupeSameSlugIsAlwaysDuplicate() {
        val d = SeriesDedupe()
        assertTrue(d.isNew("psyren", "PSYЯEN", "animegratis"))
        assertFalse(d.isNew("psyren", "Otro nombre", "jkanime"))
        assertFalse(d.isNew("psyren", "Otro nombre", "animegratis"))
        assertFalse(d.isNew("psyren-anime", "PSYЯEN", "jkanime")) // otro slug, pero el mismo título en otra fuente
    }

    @Test
    fun dedupeSameTitleOnlyAcrossSources() {
        val d = SeriesDedupe()
        assertTrue(d.isNew("koori-no-jouheki-2nd-season", "Koori no Jouheki 2nd Season", "jkanime"))
        assertFalse(d.isNew("koori-no-jouheki-2nda-temporada", "Koori no Jouheki 2nda Temporada", "animegratis"))
        assertTrue(d.isNew("serie-y", "Serie Y", "jkanime"))
        assertTrue(d.isNew("serie-y-movie", "Serie Y", "jkanime")) // misma fuente: serie y película
    }

    @Test
    fun dedupeScopeKeepsDifferentEpisodes() {
        val d = SeriesDedupe()
        assertTrue(d.isNew("sora", "Sora wa Akai Kawa no Hotori", "jkanime", scope = "14.0"))
        assertTrue(d.isNew("sora", "Sora wa Akai Kawa no Hotori", "jkanime", scope = "13.0"))
        assertFalse(d.isNew("sora-anime", "Sora wa Akai Kawa no Hotori", "animegratis", scope = "14.0"))
    }

    @Test
    fun dedupeRejectedEntryDoesNotBlockOthers() {
        val d = SeriesDedupe()
        assertTrue(d.isNew("a", "Titulo Uno", "jkanime"))
        assertFalse(d.isNew("a", "Titulo Dos", "animegratis")) // rechazada por slug: no registra «Titulo Dos»
        assertTrue(d.isNew("c", "Titulo Dos", "animegratis"))
    }

    @Test
    fun dedupeSameIdWithDifferentSlugIsDuplicate() {
        val ids = mapOf("a-slug" to 5L, "b-slug" to 5L)
        val d = SeriesDedupe(TitleRules.EMPTY) { _, slug -> ids[slug] }
        assertTrue(d.isNew("a-slug", "Uno", "jkanime"))
        assertFalse(d.isNew("b-slug", "Otro", "animegratis"))
    }

    @Test
    fun dedupeSameSlugSeparatedByTheUserIsNotDuplicate() {
        val ids = mapOf("jkanime" to "x" to 1L, "animegratis" to "x" to 2L)
        val d = SeriesDedupe(TitleRules.EMPTY.withSplit("1", "2")) { source, slug -> ids[source to slug] }
        assertTrue(d.isNew("x", "X", "jkanime"))
        assertTrue(d.isNew("x", "X", "animegratis"))
    }

    @Test
    fun dedupeSameTitleWithDifferentKnownYearsAreDifferentSeries() {
        val d = SeriesDedupe()
        assertTrue(d.isNew("tokyo-ghoul-a", "Tokyo Ghoul", "jkanime", year = 2010))
        assertTrue(d.isNew("tokyo-ghoul-b", "Tokyo Ghoul", "animegratis", year = 2020))
        assertFalse(d.isNew("tokyo-ghoul-c", "Tokyo Ghoul", "animeflv", year = 2010))
    }

    @Test
    fun dedupeSameSlugWithDifferentKnownYearsIsNotDuplicate() {
        val d = SeriesDedupe()
        assertTrue(d.isNew("x", "X", "jkanime", year = 2010))
        assertTrue(d.isNew("x", "X", "animegratis", year = 2020))
        assertFalse(d.isNew("x", "X", "animeflv")) // año desconocido: compatible
    }

    @Test
    fun mergeSeriesKeepsSameTitleWithDifferentYearsApart() {
        val a = SeriesSummary(1, "Tokyo Ghoul", "tokyo-ghoul-a", 1, "jkanime", null, 0, 2010)
        val b = SeriesSummary(2, "Tokyo Ghoul", "tokyo-ghoul-b", 1, "animegratis", null, 0, 2020)
        assertEquals(2, mergeSeries(listOf(a, b)).size)
    }

    @Test
    fun normalizeTitleKeepsLongVowelsSoDifferentSeriesStayApart() {
        assertNotEquals(normalizeTitle("Moon"), normalizeTitle("Mon"))
        assertNotEquals(normalizeTitle("Ouji"), normalizeTitle("Oji"))
        assertEquals(normalizeTitle("Steins;Gate"), normalizeTitle("steins gate"))
    }

    @Test
    fun seriesMatchNeedsOtherSourceCompatibleYearAndSameTitle() {
        assertTrue(SeriesMatch.sameSeries("naruto", 2002, "jk", "naruto", null, "ag"))
        assertFalse(SeriesMatch.sameSeries("naruto", 2002, "jk", "naruto", 2007, "ag"))
        assertFalse(SeriesMatch.sameSeries("naruto", 2002, "jk", "naruto", 2002, "jk"))
        assertFalse(SeriesMatch.sameSeries("naruto", null, "jk", "bleach", null, "ag"))
    }
}

// ===== SeriesResolverTest.kt =====
/** Reglas con las que el catálogo une las fichas de varias fuentes en una serie. */
class SeriesResolverTest {

    /** Fuente de mentira: SeriesResolver solo lee su id. */
    private fun source(id: String): AnimeSource =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AnimeSource::class.java)) { _, method, _ ->
            if (method.name == "getId") id else throw UnsupportedOperationException(method.name)
        } as AnimeSource

    private val jk = source("jkanime")
    private val ag = source("animegratis")

    private fun summary(name: String, slug: String, source: String, year: Int? = null, score: Double? = null, votes: Int = 0) =
        SeriesSummary(1, name, slug, 1, source, score, votes, year)

    private fun resolve(vararg items: Pair<AnimeSource, SeriesSummary>) = SeriesResolver.resolveSeries(items.toList())

    @Test
    fun `el mismo slug en dos fuentes solo es una serie si AniList lo confirma`() {
        val a = jk to summary("Frieren", "frieren", "jkanime")
        val b = ag to summary("Frieren", "frieren", "animegratis")
        assertEquals(2, resolve(a, b).size)
        val links = mapOf("jkanime" to "frieren" to 154587L, "animegratis" to "frieren" to 154587L)
        val groups = SeriesResolver.resolveSeries(listOf(a, b), TitleRules.EMPTY, { s, slug -> links[s to slug] }, { _, _ -> null })
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].size)
    }

    @Test
    fun `el mismo slug con enlaces de AniList distintos son series distintas`() {
        val links = mapOf("jkanime" to "x" to 1L, "animegratis" to "x" to 2L)
        val groups = SeriesResolver.resolveSeries(
            listOf(jk to summary("X", "x", "jkanime"), ag to summary("X", "x", "animegratis")), TitleRules.EMPTY, { s, slug -> links[s to slug] }, { _, _ -> null }
        )
        assertEquals(2, groups.size)
    }

    @Test
    fun `una serie y su version latino se unen dentro de la misma fuente y no con otra fuente sin AniList`() {
        val groups = resolve(
            jk to summary("Naruto", "naruto", "jkanime", year = 2002),
            jk to summary("Naruto Latino", "naruto-latino", "jkanime", year = 2002),
            ag to summary("Naruto Castellano", "naruto-latino", "animegratis")
        )
        assertEquals(2, groups.size)
        assertEquals(listOf(1, 2), groups.map { it.size }.sorted())
    }

    @Test
    fun `la version latino con otro año es otra serie`() {
        val groups = resolve(
            jk to summary("Tokyo Ghoul", "tokyo-ghoul", "jkanime", year = 2014),
            ag to summary("Tokyo Ghoul Latino", "tokyo-ghoul-latino", "animegratis", year = 2024)
        )
        assertEquals(2, groups.size)
    }

    @Test
    fun `slugs distintos del mismo anime de AniList se unen y con el mismo titulo sin AniList no`() {
        val a = jk to summary("Koori no Jouheki 2nd Season", "koori-no-jouheki-2nd-season", "jkanime", year = 2024)
        val b = ag to summary("Koori no Jouheki 2nda Temporada", "koori-no-jouheki-2nda-temporada", "animegratis", year = 2024)
        val links = mapOf("jkanime" to "koori-no-jouheki-2nd-season" to 77L, "animegratis" to "koori-no-jouheki-2nda-temporada" to 77L)
        assertEquals(1, SeriesResolver.resolveSeries(listOf(a, b), TitleRules.EMPTY, { s, slug -> links[s to slug] }, { _, _ -> null }).size)
        assertEquals(2, resolve(a, b).size)
    }

    @Test
    fun `el mismo titulo con años distintos son series distintas`() {
        val groups = resolve(
            jk to summary("Tokyo Ghoul", "tokyo-ghoul-2010", "jkanime", year = 2010),
            ag to summary("Tokyo Ghoul", "tokyo-ghoul-2020", "animegratis", year = 2020)
        )
        assertEquals(2, groups.size)
    }

    @Test
    fun `un enlace de AniList no une fichas que el usuario separo`() {
        val links = mapOf("jkanime" to "tg-a" to 5L, "animegratis" to "tg-b" to 5L)
        val ids = mapOf("jkanime" to "tg-a" to 1L, "animegratis" to "tg-b" to 2L)
        val groups = SeriesResolver.resolveSeries(
            listOf(jk to summary("Tokyo Ghoul", "tg-a", "jkanime"), ag to summary("Tokyo Ghoul", "tg-b", "animegratis")),
            TitleRules.EMPTY.withSplit("1", "2"), { s, slug -> links[s to slug] }, { s, slug -> ids[s to slug] }
        )
        assertEquals(2, groups.size)
    }

    @Test
    fun `dos fichas de la misma fuente nunca se unen`() {
        val groups = resolve(
            jk to summary("Serie X", "serie-x", "jkanime"),
            jk to summary("Serie X", "serie-x-movie", "jkanime")
        )
        assertEquals(2, groups.size)
    }

    @Test
    fun `el orden del resultado es el de la primera aparicion`() {
        val links = mapOf("jkanime" to "beta" to 9L, "animegratis" to "beta" to 9L)
        val groups = SeriesResolver.resolveSeries(
            listOf(
                jk to summary("Beta", "beta", "jkanime"),
                jk to summary("Alfa", "alfa", "jkanime"),
                ag to summary("Beta", "beta", "animegratis")
            ), TitleRules.EMPTY, { s, slug -> links[s to slug] }, { _, _ -> null }
        )
        assertEquals(listOf("beta", "alfa"), groups.map { it[0].second.slug })
    }

    @Test
    fun `fichas con el mismo id de serie siguen unidas aunque su titulo ya no coincida`() {
        val ids = mapOf("jkanime" to "frieren" to 5L, "animegratis" to "sousou-no-frieren" to 5L)
        val groups = SeriesResolver.resolveSeries(
            listOf(jk to summary("Frieren", "frieren", "jkanime"), ag to summary("Sousou no Frieren", "sousou-no-frieren", "animegratis")),
            TitleRules.EMPTY
        ) { source, slug -> ids[source to slug] }
        assertEquals(1, groups.size)
    }

    @Test
    fun `dos fichas de la misma fuente con el mismo id se unen`() {
        val ids = mapOf("jkanime" to "a" to 5L, "jkanime" to "b" to 5L)
        val groups = SeriesResolver.resolveSeries(
            listOf(jk to summary("A", "a", "jkanime"), jk to summary("B", "b", "jkanime")), TitleRules.EMPTY
        ) { source, slug -> ids[source to slug] }
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().size)
    }

    @Test
    fun `la entrada usa el nombre real y no el slug con espacios`() {
        val entry = SeriesResolver.entryOf(
            "frieren",
            listOf(summary("frieren", "frieren", "jkanime"), summary("Sousou no Frieren", "frieren", "animegratis")),
            updatedAt = 5L
        )
        assertEquals("Sousou no Frieren", entry.name)
        assertEquals(5L, entry.updatedAt)
    }

    @Test
    fun `la nota es la media ponderada por votos y el año el mas antiguo`() {
        val entry = SeriesResolver.entryOf(
            "x",
            listOf(
                summary("X", "x", "jkanime", year = 2021, score = 4.0, votes = 100),
                summary("X", "x", "animegratis", year = 2020, score = 3.0, votes = 300)
            ),
            updatedAt = 1L
        )
        assertEquals(3.25, entry.score!!, 0.001)
        assertEquals(400, entry.votes)
        assertEquals(2020, entry.year)
    }

    @Test
    fun `sin votos la nota es la primera disponible y los votos son cero`() {
        val entry = SeriesResolver.entryOf("x", listOf(summary("X", "x", "jkanime", score = 4.5, votes = 0)), updatedAt = 1L)
        assertNotNull(entry.score)
        assertEquals(0, entry.votes)
    }

    @Test
    fun `sin nota ni año la entrada los deja vacios`() {
        val entry = SeriesResolver.entryOf("x", listOf(summary("X", "x", "jkanime")), updatedAt = 1L)
        assertNull(entry.score)
        assertNull(entry.year)
    }

    @Test
    fun `dos fichas con el mismo slug que el usuario separo no se juntan`() {
        val ids = mapOf("jkanime" to "one-piece" to 1L, "animegratis" to "one-piece" to 2L)
        val rules = TitleRules.EMPTY.withSplit("1", "2")
        val groups = SeriesResolver.resolveSeries(
            listOf(jk to summary("One Piece", "one-piece", "jkanime"), ag to summary("One Piece", "one-piece", "animegratis")), rules
        ) { source, slug -> ids[source to slug] }
        assertEquals(2, groups.size)
    }

    @Test
    fun `los grupos que comparten ids se unen en cadena`() {
        // «a» tiene el id 1; «b» (mismo anime de AniList en dos fuentes) tiene el 1 y el 2; «c» tiene el 2: las tres son una serie.
        val links = mapOf("jkanime" to "b" to 3L, "animegratis" to "b" to 3L)
        val ids = mapOf(
            "jkanime" to "a" to 1L, "jkanime" to "b" to 1L,
            "animegratis" to "b" to 2L, "animegratis" to "c" to 2L
        )
        val groups = SeriesResolver.resolveSeries(
            listOf(
                jk to summary("Alfa", "a", "jkanime"), jk to summary("Beta", "b", "jkanime"),
                ag to summary("Beta", "b", "animegratis"), ag to summary("Gamma", "c", "animegratis")
            ),
            TitleRules.EMPTY, { source, slug -> links[source to slug] }
        ) { source, slug -> ids[source to slug] }
        assertEquals(1, groups.size)
        assertEquals(4, groups.single().size)
    }

    @Test
    fun `el mismo slug con años conocidos y distintos son series distintas`() {
        val groups = resolve(
            jk to summary("Tokyo Ghoul", "tokyo-ghoul", "jkanime", year = 2010),
            ag to summary("Tokyo Ghoul", "tokyo-ghoul", "animegratis", year = 2020)
        )
        assertEquals(2, groups.size)
    }

    private val mc = source("monoschinos")
    private val va = source("verani")

    @Test
    fun `al reagrupar una ficha separada de un grupo unido por id y por titulo se queda sola`() {
        // Cuatro fichas con el mismo nombre (así las reconstruye el catálogo guardado); tres comparten el id 7 y una se separó (id 9).
        val ids = mapOf(
            "jkanime" to "x-a" to 7L, "animegratis" to "x-b" to 7L, "monoschinos" to "x-c" to 7L, "verani" to "x-d" to 9L
        )
        val groups = SeriesResolver.resolveSeries(
            listOf(
                jk to summary("Shin Tennis", "x-a", "jkanime", year = 2024), ag to summary("Shin Tennis", "x-b", "animegratis", year = 2024),
                mc to summary("Shin Tennis", "x-c", "monoschinos", year = 2024), va to summary("Shin Tennis", "x-d", "verani", year = 2024)
            ),
            TitleRules.EMPTY.withSplit("7", "9")
        ) { source, slug -> ids[source to slug] }

        assertEquals(listOf(3, 1), groups.map { it.size }.sortedDescending())
        assertEquals("x-d", groups.single { it.size == 1 }.single().second.slug)
    }

    @Test
    fun `el mismo anime de AniList con años de estreno que no caben juntos no une las fichas`() {
        val links = mapOf("jkanime" to "fruits-basket" to 7L, "animegratis" to "fruits-basket-remake" to 7L)
        fun groups(a: Int?, b: Int?) = SeriesResolver.resolveSeries(
            listOf(jk to summary("Fruits Basket", "fruits-basket", "jkanime", year = a), ag to summary("Fruits Basket", "fruits-basket-remake", "animegratis", year = b)),
            TitleRules.EMPTY, { s, slug -> links[s to slug] }, { _, _ -> null }
        ).size
        assertEquals(1, groups(2001, 2001))
        assertEquals(1, groups(2001, 2002))
        assertEquals(1, groups(null, 2019))
        assertEquals(2, groups(2001, 2019))
    }
}

// ===== SeriesSlugBaseTest.kt =====
class SeriesSlugBaseTest {
    @Test
    fun `quita solo el sufijo latino con guion`() {
        assertEquals("naruto", SeriesMatch.slugBase("naruto-latino"))
        assertEquals("latino", SeriesMatch.slugBase("latino"))
        assertEquals("-latino", SeriesMatch.slugBase("-latino"))
        assertEquals("x-latino", SeriesMatch.slugBase("x-latino-latino"))
        assertEquals("naruto-shippuden", SeriesMatch.slugBase("naruto-shippuden"))
    }
}

// ===== SiblingFichasTest.kt =====
/** Las uniones del usuario se ven en las fichas aunque el catálogo no tenga todas las fichas. */
class SiblingFichasTest {
    private val ids = mapOf(
        ("jkanime" to "sasaki-pii-chan-2") to "4663",
        ("monoschinos" to "sasaki-p-chan-2") to "4663",
        ("animegratis" to "sasaki-pii-chan-2nda") to "4663",
        ("animeav1" to "sasaki-to-pii-chan-2") to "6534",
        ("jkanime" to "otra-serie") to "777"
    )
    private val keyOf = { source: String, slug: String -> ids[source to slug] }
    private val fichasOf = { keys: Collection<String> -> ids.filterValues { it in keys }.keys }
    private val rules = TitleRules.parse("""{"version":2,"merges":[["4663","6534"]]}""")

    @Test
    fun `una union entre dos ids junta todas las fichas de los dos`() {
        assertEquals(
            listOf("animegratis" to "sasaki-pii-chan-2nda", "jkanime" to "sasaki-pii-chan-2", "monoschinos" to "sasaki-p-chan-2"),
            siblingFichas(rules, fichasOf, keyOf, "animeav1", "sasaki-to-pii-chan-2")
        )
    }

    @Test
    fun `desde cualquier ficha del grupo salen las demas y no ella misma`() {
        val siblings = siblingFichas(rules, fichasOf, keyOf, "jkanime", "sasaki-pii-chan-2")
        assertEquals(3, siblings.size)
        assertFalse(("jkanime" to "sasaki-pii-chan-2") in siblings)
    }

    @Test
    fun `el grupo de una clave incluye las unidas y ella misma`() {
        assertEquals(setOf("4663", "6534"), rules.groupKeys("6534"))
        assertEquals(setOf("777"), rules.groupKeys("777"))
    }

    @Test
    fun `una serie sin uniones ni ids compartidos no tiene hermanas`() {
        assertEquals(emptyList<Pair<String, String>>(), siblingFichas(rules, fichasOf, keyOf, "jkanime", "otra-serie"))
    }

    @Test
    fun `una ficha sin id no tiene hermanas`() {
        assertEquals(emptyList<Pair<String, String>>(), siblingFichas(rules, fichasOf, keyOf, "jkanime", "desconocida"))
    }

    @Test
    fun `sin la union solo comparten las fichas con el mismo id`() {
        val none = TitleRules.parse("""{"version":2,"merges":[]}""")
        assertEquals(
            listOf("animegratis" to "sasaki-pii-chan-2nda", "monoschinos" to "sasaki-p-chan-2"),
            siblingFichas(none, fichasOf, keyOf, "jkanime", "sasaki-pii-chan-2")
        )
    }
}

// ===== SingleFlightTest.kt =====
/** Una sola ejecución por clave cuando varias pantallas piden lo mismo a la vez. */
@OptIn(ExperimentalCoroutinesApi::class)
class SingleFlightTest {

    @Test
    fun `dos peticiones a la vez de la misma clave ejecutan el bloque una vez`() = runTest {
        val flight = SingleFlight<String>()
        var runs = 0
        val a = async { flight.run("k") { delay(100); runs++; "x" } }
        val b = async { flight.run("k") { delay(100); runs++; "y" } }

        assertEquals("x", a.await())
        assertEquals("x", b.await())
        assertEquals(1, runs)
    }

    @Test
    fun `claves distintas no se esperan`() = runTest {
        val flight = SingleFlight<String>()
        var runs = 0
        val a = async { flight.run("a") { delay(100); runs++; "a" } }
        val b = async { flight.run("b") { delay(100); runs++; "b" } }

        assertEquals("a", a.await())
        assertEquals("b", b.await())
        assertEquals(2, runs)
    }

    @Test
    fun `al terminar, la siguiente petición vuelve a ejecutar`() = runTest {
        val flight = SingleFlight<Int>()
        var runs = 0

        assertEquals(1, flight.run("k") { ++runs })
        assertEquals(2, flight.run("k") { ++runs })
    }

    @Test
    fun `un fallo llega también a quien esperaba`() = runTest {
        val flight = SingleFlight<String>()
        val a = async { runCatching { flight.run("k") { delay(100); error("sin red") } } }
        val b = async { runCatching { flight.run("k") { "no se ejecuta" } } }

        assertEquals("sin red", a.await().exceptionOrNull()?.message)
        assertEquals("sin red", b.await().exceptionOrNull()?.message)
        // Tras el fallo la clave queda libre.
        assertEquals("ok", flight.run("k") { "ok" })
    }

    @Test
    fun `si se cancela quien ejecutaba, quien esperaba lo ejecuta`() = runTest {
        val flight = SingleFlight<String>()
        val first = launch { flight.run("k") { delay(1_000); "primero" } }
        runCurrent()
        val second = async { flight.run("k") { "segundo" } }
        runCurrent()

        advanceTimeBy(100)
        first.cancel()
        runCurrent()

        assertEquals("segundo", second.await())
        assertTrue(first.isCancelled)
    }

    @Test
    fun `si se cancela quien esperaba, quien ejecutaba sigue`() = runTest {
        val flight = SingleFlight<String>()
        val first = async { flight.run("k") { delay(1_000); "primero" } }
        runCurrent()
        val second = launch { flight.run("k") { "segundo" } }
        runCurrent()

        second.cancel()

        assertEquals("primero", first.await())
        assertTrue(second.isCancelled)
    }
}

// ===== SourceChallengeNamesTest.kt =====
class SourceChallengeNamesTest {
    @Test
    fun `el registro solo lleva los nombres de las cookies y nunca los valores`() {
        assertEquals("a, c", SourceChallenge.cookieNames("a=1; bad; c=secreto"))
        assertEquals("", SourceChallenge.cookieNames("sinigual"))
    }
}

// ===== SourceChallengeTest.kt =====
class SourceChallengeTest {
    @Test
    fun `un 403 con challenge true es la peticion de verificacion`() {
        assertTrue(SourceChallenge.isChallenge(403, """{"error":"Verificación de seguridad pendiente: recarga la página.","challenge":true}"""))
        assertTrue(SourceChallenge.isChallenge(403, """{ "challenge" : true }"""))
    }

    @Test
    fun `otros codigos o cuerpos no lo son`() {
        assertFalse(SourceChallenge.isChallenge(200, """{"challenge":true}"""))
        assertFalse(SourceChallenge.isChallenge(403, """{"challenge":false}"""))
        assertFalse(SourceChallenge.isChallenge(403, "Forbidden"))
        assertFalse(SourceChallenge.isChallenge(503, """{"challenge":true}"""))
    }

    @Test
    fun `solo vale como cabecera el ASCII imprimible`() {
        assertTrue(SourceChallenge.isHeaderValue("_ag_ses=abc; _ag_cid=123"))
        assertFalse(SourceChallenge.isHeaderValue(""))
        assertFalse(SourceChallenge.isHeaderValue("a=ñ"))
        assertFalse(SourceChallenge.isHeaderValue("a=b\r\nX: y"))
    }
}

// ===== SourceHttpTest.kt =====
/** El cliente HTTP de las fuentes contra un servidor local: lo que antes hacía jsoup y el relevo de DNS. */
class SourceHttpTest {
    private lateinit var server: HttpServer
    private val base get() = "http://127.0.0.1:${server.address.port}"
    private var lastRequestBody = ""
    private var lastHeaders = mapOf<String, String>()
    private var lastQuery = ""

    @Before
    fun setUp() {
        SourceHttp.clientOverride = OkHttpClient()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            lastRequestBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            lastHeaders = exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
            lastQuery = exchange.requestURI.rawQuery.orEmpty()
            var type = "text/plain; charset=utf-8"
            val bytes: ByteArray = when (exchange.requestURI.path) {
                "/404" -> { exchange.sendResponseHeaders(404, -1); exchange.close(); return@createContext }
                "/redirect" -> {
                    exchange.responseHeaders.add("Location", "$base/final")
                    exchange.sendResponseHeaders(302, -1)
                    exchange.close()
                    return@createContext
                }
                "/latin1" -> {
                    type = "text/html"
                    "<html><head><meta charset=\"iso-8859-1\"></head><body>año</body></html>".toByteArray(Charsets.ISO_8859_1)
                }
                "/latin1-header" -> {
                    type = "text/plain; charset=iso-8859-1"
                    "año".toByteArray(Charsets.ISO_8859_1)
                }
                "/gzip" -> {
                    val out = ByteArrayOutputStream()
                    GZIPOutputStream(out).use { it.write("comprimido".toByteArray()) }
                    exchange.responseHeaders.add("Content-Encoding", "gzip")
                    out.toByteArray()
                }
                "/cookie" -> {
                    exchange.responseHeaders.add("Set-Cookie", "sesion=abc; Path=/")
                    "ok".toByteArray()
                }
                "/big" -> ByteArray(100) { 'x'.code.toByte() }
                else -> "hola".toByteArray()
            }
            exchange.responseHeaders.add("Content-Type", type)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        SourceHttp.clientOverride = null
        FallbackDns.reset()
        FallbackDns.system = Dns.SYSTEM
        FallbackDns.resolver = { emptyList() }
    }

    @Test
    fun `un 404 lanza HttpStatusException y con ignoreHttpErrors devuelve el codigo`() {
        val failure = runCatching { SourceHttp.execute("$base/404") }.exceptionOrNull()
        assertTrue(failure is HttpStatusException)
        assertEquals(404, (failure as HttpStatusException).statusCode)
        assertEquals(404, SourceHttp.execute("$base/404") { ignoreHttpErrors() }.statusCode)
    }

    @Test
    fun `una redireccion deja la URL final y es la base del documento`() {
        val reply = SourceHttp.execute("$base/redirect")
        assertTrue(reply.url.endsWith("/final"))
        assertTrue(reply.parse().baseUri().endsWith("/final"))
    }

    @Test
    fun `una pagina latin1 sin charset en la cabecera se lee por su meta`() {
        assertTrue(SourceHttp.execute("$base/latin1").parse().text().contains("año"))
    }

    @Test
    fun `con charset en la cabecera el cuerpo sale bien`() {
        assertEquals("año", SourceHttp.execute("$base/latin1-header").body())
    }

    @Test
    fun `gzip se descomprime solo`() {
        assertEquals("comprimido", SourceHttp.execute("$base/gzip").body())
    }

    @Test
    fun `POST lleva el formulario, las cabeceras y las cookies`() {
        SourceHttp.execute("$base/", ajax = true) {
            method("POST")
            data("a", "1")
            data("t", "x y")
            referrer("https://origen.test/")
            cookies(mapOf("c" to "d"))
            header("X-Otra", "z")
        }
        assertEquals("a=1&t=x+y", lastRequestBody)
        assertEquals("application/x-www-form-urlencoded", lastHeaders["content-type"])
        assertEquals("XMLHttpRequest", lastHeaders["x-requested-with"])
        assertEquals("https://origen.test/", lastHeaders["referer"])
        assertEquals("c=d", lastHeaders["cookie"])
        assertEquals("z", lastHeaders["x-otra"])
    }

    @Test
    fun `GET con datos los manda en la consulta`() {
        SourceHttp.execute("$base/?x=1") { data("a", "1") }
        assertEquals("x=1&a=1", lastQuery)
    }

    @Test
    fun `maxBodySize corta el cuerpo sin error`() {
        assertEquals(10, SourceHttp.execute("$base/big") { maxBodySize(10) }.bodyAsBytes().size)
    }

    @Test
    fun `las cabeceras y las cookies de la respuesta se leen sin importar las mayusculas`() {
        val reply = SourceHttp.execute("$base/cookie")
        assertEquals(reply.header("content-type"), reply.header("Content-Type"))
        assertEquals(mapOf("sesion" to "abc"), reply.cookies())
    }

    @Test
    fun `una URL mal formada es un fallo de entrada y salida`() {
        assertTrue(runCatching { SourceHttp.execute("foo") }.exceptionOrNull() is MalformedURLException)
    }

    @Test
    fun `solo un fallo de llegar al servidor activa el relevo`() {
        assertTrue(SourceHttp.isConnectFailure(ConnectException("refused")))
        assertTrue(SourceHttp.isConnectFailure(UnknownHostException("x")))
        assertTrue(SourceHttp.isConnectFailure(SocketTimeoutException("failed to connect to /1.2.3.4 (port 443) after 6000ms")))
        assertTrue(!SourceHttp.isConnectFailure(SocketTimeoutException("Read timed out")))
        assertTrue(!SourceHttp.isConnectFailure(SSLHandshakeException("x")))
        assertTrue(!SourceHttp.isConnectFailure(HttpStatusException("x", 500, "u")))
        assertTrue(!SourceHttp.isConnectFailure(IOException("otro")))
    }

    @Test
    fun `si la IP de la red no conecta se reintenta con la de 1_1_1_1 y se aprende`() {
        // La red da 127.0.0.2 (nadie escucha en ese puerto: conexión rechazada) y 9.9.9.9 da 127.0.0.1, donde está el servidor.
        val host = "fuente.example.com"
        FallbackDns.system = Dns { listOf(InetAddress.getByAddress(host, byteArrayOf(127, 0, 0, 2))) }
        var queries = 0
        FallbackDns.resolver = { queries++; listOf(InetAddress.getByAddress(host, byteArrayOf(127, 0, 0, 1))) }
        val url = "http://$host:${server.address.port}/"

        assertEquals("hola", SourceHttp.execute(url).body())
        assertEquals("hola", SourceHttp.execute(url).body())

        assertEquals(1, queries)
        assertTrue(FallbackDns.isPreferred(host))
    }

    @Test
    fun `con noRelay no se consulta a Quad9 aunque la conexion falle`() {
        // Lo que el DNS de la red bloquea o no alcanza (p. ej. AdGuard) se respeta: la petición falla sin preguntar a otro DNS.
        val host = "cdn.example.com"
        FallbackDns.system = Dns { listOf(InetAddress.getByAddress(host, byteArrayOf(127, 0, 0, 2))) }
        var queries = 0
        FallbackDns.resolver = { queries++; listOf(InetAddress.getByAddress(host, byteArrayOf(127, 0, 0, 1))) }

        val failure = runCatching { SourceHttp.execute("http://$host:${server.address.port}/") { noRelay() } }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(0, queries)
        assertTrue(!FallbackDns.isPreferred(host))
    }

    @Test
    fun `si 1_1_1_1 tampoco responde se relanza el fallo original`() {
        val host = "fuente.example.com"
        FallbackDns.system = Dns { listOf(InetAddress.getByAddress(host, byteArrayOf(127, 0, 0, 2))) }
        FallbackDns.resolver = { emptyList() }

        val failure = runCatching { SourceHttp.execute("http://$host:${server.address.port}/") }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(!FallbackDns.isPreferred(host))
    }
}

// ===== SourceNamesTest.kt =====
class SourceNamesTest {
    @Test
    fun `las fuentes conocidas llevan su nombre y una nueva empieza en mayuscula`() {
        assertEquals("AnimeFLV", SourceNames.display("animeflv", "animeflv.one"))
        assertEquals("JKAnime", SourceNames.display("jkanime", "jkanime.net"))
        assertEquals("Nuevafuente.com", SourceNames.display("nueva", "nuevafuente.com"))
    }
}

// ===== SourceStateDecodeTest.kt =====
class SourceStateDecodeTest {

    @Test
    fun `un estado guardado se lee igual`() {
        val state = SourceState(checkedAt = 10L, walkedAt = 5L, total = 1234)
        assertEquals(state, SourceState.decode(state.encode()))
    }

    @Test
    fun `un total que no cabe en un entero es un estado corrupto`() {
        assertEquals(SourceState.NEVER, SourceState.decode("10:5:99999999999"))
    }

    @Test
    fun `un texto con otra forma vale como nunca recorrido`() {
        assertEquals(SourceState.NEVER, SourceState.decode(null))
        assertEquals(SourceState.NEVER, SourceState.decode("1:2"))
        assertEquals(SourceState.NEVER, SourceState.decode("a:b:c"))
    }
}

// ===== TestLedger.kt =====
/** Libro de ids de serie en memoria para pruebas de código que lo consulta (`seriesIdOf`, `SeriesLedger.fichaKeysOf`). */
internal object TestLedger {
    private class Dao(private val map: Map<Pair<String, String>, Long>) : SeriesIdDao {
        override fun newSeries(registry: SeriesRegistry) = map.values.maxOrNull() ?: 0L
        override fun putRegistry(rows: List<SeriesRegistry>) {}
        override fun hasRegistry() = map.isNotEmpty()
        override fun allMap() = map.map { (key, id) -> SeriesMap(key.first, key.second, id) }
        override fun idOf(sourceId: String, slug: String) = map[sourceId to slug]
        override fun putMap(rows: List<SeriesMap>) {}
        override fun putMapIfAbsent(rows: List<SeriesMap>) {}
        override fun moveSeries(moves: Map<Long, Long>) {}
    }

    /** Carga el libro con estas fichas (fuente, slug) y sus ids. */
    fun install(map: Map<Pair<String, String>, Long> = emptyMap()) {
        SeriesLedger.dao = { Dao(map) }
        SeriesLedger.userRows = { NoUserRows }
        SeriesLedger.transaction = { it() }
        SeriesLedger.load()
    }

    /** Lo deja vacío, como al empezar: que una prueba no vea el libro de otra. */
    fun reset() = install()
}

// ===== TitleRulesTest.kt =====
class TitleRulesTest {
    @Test
    fun mergedKeysShareTheRepresentativeAndTheirRules() {
        val rules = TitleRules.EMPTY.withMerge("frieren", "sousou-no-frieren").withCover("frieren", "https://x.example/f.jpg")
        assertEquals("frieren", rules.canonical("sousou-no-frieren"))
        assertEquals("frieren", rules.canonical("frieren"))
        assertEquals("https://x.example/f.jpg", rules.cover("sousou-no-frieren"))
        assertEquals("otra", rules.canonical("otra"))
    }

    @Test
    fun overlappingGroupsAreJoined() {
        val rules = TitleRules.EMPTY.withMerge("a", "b").withMerge("c", "b").withMerge("d", "e")
        assertEquals(rules.canonical("a"), rules.canonical("c"))
        assertTrue(rules.canonical("a") != rules.canonical("d"))
    }

    @Test
    fun splitsAreSymmetricAndFollowTheMergedGroup() {
        val rules = TitleRules.EMPTY.withSplit("tokyo-ghoul", "tokyo-ghoul-re")
        assertTrue(rules.isSplit("tokyo-ghoul", "tokyo-ghoul-re"))
        assertTrue(rules.isSplit("tokyo-ghoul-re", "tokyo-ghoul"))
        assertFalse(rules.isSplit("tokyo-ghoul", "otra"))
        // Si una de las dos series se une a otra, la separación sigue valiendo para el grupo.
        val joined = rules.withMerge("tokyo-ghoul-a", "tokyo-ghoul-re")
        assertTrue(joined.isSplit("tokyo-ghoul", "tokyo-ghoul-a"))
    }

    @Test
    fun mergingRemovesAnOldSplitBetweenThem() {
        val rules = TitleRules.EMPTY.withSplit("a", "b").withMerge("a", "b")
        assertFalse(rules.isSplit("a", "b"))
        assertEquals(rules.canonical("a"), rules.canonical("b"))
    }

    @Test
    fun splittingAMergedMemberTakesItOutOfTheGroup() {
        val rules = TitleRules.EMPTY.withMerge("a", "b").withMerge("a", "c").withSplit("a", "b")
        assertEquals("a", rules.canonical("c"))
        assertEquals("b", rules.canonical("b"))
        assertTrue(rules.isSplit("a", "b"))
    }

    @Test
    fun withoutRemovesEveryRuleOfAKey() {
        val rules = TitleRules.EMPTY.withCover("a", "https://x.example/a.jpg").withMerge("a", "b").withSplit("a", "z")
            .without("a")
        assertNull(rules.cover("a"))
        assertEquals("b", rules.canonical("b"))
        assertFalse(rules.isSplit("a", "z"))
    }

    @Test
    fun coversMustBeHttps() {
        val rules = TitleRules.EMPTY.withCover("a", "http://x.example/a.jpg").withCover("b", "file:///sdcard/a.jpg")
        assertNull(rules.cover("a"))
        assertNull(rules.cover("b"))
    }

    @Test
    fun jsonRoundTripKeepsEverything() {
        val rules = TitleRules.EMPTY.withCover("a", "https://x.example/a.jpg").withMerge("a", "b").withSplit("c", "d")
        val back = TitleRules.parse(rules.toJson().toString())
        assertEquals("https://x.example/a.jpg", back.cover("b"))
        assertEquals("https://x.example/a.jpg", back.cover("a"))
        assertEquals("a", back.canonical("b"))
        assertTrue(back.isSplit("d", "c"))
    }

    @Test
    fun parsingIgnoresWhatDoesNotFollowTheFormat() {
        val text = """{"names":{"BAD KEY":"x","ok":"Bien","long":"${"x".repeat(300)}"},
            "covers":{"ok":"http://no-https","bien":"https://x.example/c.jpg","../etc":"https://x.example/d.jpg"},
            "merges":[["a"],["a","b"],"nope"],"splits":[["x","y"],["solo"]]}"""
        val rules = TitleRules.parse(text)
        assertNull(rules.cover("ok"))
        assertEquals("https://x.example/c.jpg", rules.cover("bien"))
        assertEquals("a", rules.canonical("b"))
        assertTrue(rules.isSplit("y", "x"))
        assertTrue(TitleRules.parse("no es json").isEmpty)
        assertTrue(TitleRules.parse("{}").isEmpty)
    }

    @Test
    fun fillFromKeepsOwnCoversAndAddsTheRest() {
        val mine = TitleRules.EMPTY.withCover("a", "https://x.example/mio.jpg")
        val other = TitleRules.EMPTY.withCover("a", "https://x.example/ajeno.jpg").withCover("b", "https://x.example/b.jpg").withMerge("c", "d").withSplit("e", "f")
        val joined = mine.fillFrom(other)
        assertEquals("https://x.example/mio.jpg", joined.cover("a"))
        assertEquals("https://x.example/b.jpg", joined.cover("b"))
        assertEquals(joined.canonical("c"), joined.canonical("d"))
        assertTrue(joined.isSplit("f", "e"))
    }

    @Test
    fun fillFromDoesNotAddASplitBetweenSeriesTheseRulesAlreadyJoin() {
        val mine = TitleRules.EMPTY.withMerge("1", "2")
        val joined = mine.fillFrom(TitleRules.EMPTY.withSplit("1", "2").withSplit("1", "3"))
        assertEquals(false, joined.isSplit("1", "2"))
        assertTrue(joined.isSplit("1", "3"))
    }

    @Test
    fun followLedgerMovesAbsorbedIdsWithoutTouchingTheRest() {
        // Lo que hace UserTitles.followLedger sobre las reglas: remapKey de cada id absorbido a su serie vigente.
        val rules = TitleRules.EMPTY.withCover("5", "https://x.example/cinco.jpg").withSplit("5", "9").withSplit("7", "9")
        val live = mapOf(5L to 2L)
        val moved = rules.idKeys().mapNotNull { key -> key.toLong().let { id -> (live[id] ?: id).takeIf { it != id }?.let { key to it.toString() } } }
        val next = moved.fold(rules) { acc, (old, to) -> acc.remapKey(old, listOf(to)) }
        assertEquals("https://x.example/cinco.jpg", next.cover("2"))
        assertTrue(next.isSplit("2", "9"))
        assertTrue(next.isSplit("7", "9"))
        assertEquals(false, next.idKeys().contains("5"))
    }

    @Test
    fun remapKeyDoesNotLeaveASplitOfASeriesWithItself() {
        // 5 estaba separado de 2 y 5 se absorbió en 2: no queda la pareja (2, 2).
        val next = TitleRules.EMPTY.withSplit("5", "2").withSplit("5", "9").remapKey("5", listOf("2"))
        assertEquals(false, next.isSplit("2", "2"))
        assertTrue(next.isSplit("2", "9"))
        assertEquals(1, next.splitCount())
    }

    @Test
    fun remapKeyMovesCoverAndJoinsTheSeriesThatSharedTheKey() {
        val rules = TitleRules.EMPTY.withCover("naruto", "https://x.example/n.jpg").remapKey("naruto", listOf("7", "9"))
        assertEquals("https://x.example/n.jpg", rules.cover("7"))
        assertEquals(rules.canonical("7"), rules.canonical("9"))
    }

    @Test
    fun remapKeyKeepsSplitsAndIgnoresUnknownKeys() {
        val rules = TitleRules.EMPTY.withSplit("naruto", "bleach")
        assertEquals(true, rules.remapKey("naruto", listOf("7")).isSplit("7", "bleach"))
        assertEquals(rules.splitCount(), rules.remapKey("otra", listOf("1")).splitCount())
    }

    @Test
    fun translateIdsMapsAllKeysAtOnceAndDropsTheUnknown() {
        val rules = TitleRules.EMPTY.withCover("5", "https://x.example/cinco.jpg").withCover("9", "https://x.example/nueve.jpg").withSplit("5", "9").withCover("sin-id", "https://x.example/legacy.jpg")

        val result = rules.translateIds { mapOf("5" to listOf("9"), "9" to listOf("12")).getValue(it) }

        // 5 -> 9 y 9 -> 12 a la vez: el 9 que sale del 5 no se vuelve a traducir.
        assertEquals("https://x.example/cinco.jpg", result.cover("9"))
        assertEquals("https://x.example/nueve.jpg", result.cover("12"))
        assertTrue(result.isSplit("9", "12"))
        assertEquals("https://x.example/legacy.jpg", result.cover("sin-id"))
        assertEquals(setOf("9", "12"), result.idKeys())
    }

    @Test
    fun translateIdsKeepsTheCoverOfTheLowestIdWhenTwoIdsShareFichas() {
        // 9 se unió a 5 (el más bajo sobrevive): en la copia los dos llevan las mismas fichas.
        val rules = TitleRules.EMPTY.withCover("9", "https://x/viejo.jpg").withCover("5", "https://x/vigente.jpg")

        val result = rules.translateIds { listOf("100") }

        assertEquals("https://x/vigente.jpg", result.cover("100"))
    }

    @Test
    fun translateIdsDropsKeysTheCopyDoesNotExplain() {
        val result = TitleRules.EMPTY.withCover("5", "https://x.example/cinco.jpg").translateIds { null }
        assertTrue(result.isEmpty)
    }

    @Test
    fun digitKeysAreIds() {
        val rules = TitleRules.parse("""{"version":2,"covers":{"86":"https://x.example/u.jpg"}}""")

        assertEquals(setOf("86"), rules.idKeys())
    }

    @Test
    fun withoutMergesKeepsCoversAndSplits() {
        val rules = TitleRules.EMPTY.withCover("1", "https://x.example/uno.jpg").withMerge("1", "2").withSplit("1", "3").withoutMerges()

        assertEquals("https://x.example/uno.jpg", rules.cover("1"))
        assertEquals(false, rules.canonical("1") == rules.canonical("2"))
        assertTrue(rules.isSplit("1", "3"))
    }
}

// ===== UserTitlesExportTest.kt =====
/** La copia lleva las fichas de cada id que nombran las reglas; un id sin ninguna ficha (absorbido o muerto) no lleva nada. */
class UserTitlesExportTest {
    private class FakeDao : SeriesIdDao {
        val map = mutableMapOf<Pair<String, String>, Long>()

        override fun newSeries(registry: SeriesRegistry) = 0L
        override fun putRegistry(rows: List<SeriesRegistry>) {}
        override fun hasRegistry() = true
        override fun allMap() = map.map { (key, id) -> SeriesMap(key.first, key.second, id) }
        override fun idOf(sourceId: String, slug: String) = map[sourceId to slug]
        override fun putMap(rows: List<SeriesMap>) {}
        override fun putMapIfAbsent(rows: List<SeriesMap>) {}
        override fun moveSeries(moves: Map<Long, Long>) {}
    }

    @Before
    fun setUp() {
        val dao = FakeDao().apply {
            map["animeav1" to "x"] = 5L
            map["jkanime" to "x"] = 5L
            map["animeav1" to "otra"] = 8L
        }
        SeriesLedger.dao = { dao }
        SeriesLedger.userRows = { NoUserRows }
        SeriesLedger.transaction = { it() }
        SeriesLedger.load()
    }

    @After
    fun tearDown() {
        SeriesLedger.dao = { app.kaizen.database.CacheDB.INSTANCE.seriesIdDao() }
    }

    private fun members(fichas: org.json.JSONObject, key: String) =
        (0 until fichas.getJSONArray(key).length()).map { fichas.getJSONArray(key).getJSONArray(it).let { f -> f.getString(0) to f.getString(1) } }

    @Test
    fun `cada id lleva las fichas de su serie y el que no tiene ninguna se omite`() {
        val fichas = UserTitles.fichasByKey(listOf("5", "9", "8"))

        assertEquals(listOf("animeav1" to "x", "jkanime" to "x"), members(fichas, "5"))
        assertFalse(fichas.has("9"))
        assertEquals(listOf("animeav1" to "otra"), members(fichas, "8"))
    }

    @Test
    fun `un id sin ninguna ficha se omite`() {
        assertFalse(UserTitles.fichasByKey(listOf("77")).has("77"))
    }
}

// ===== UserTitlesMatchingTest.kt =====
/** Las reglas del usuario (titles.json) mandan sobre las reglas automáticas de «misma serie». */
class UserTitlesMatchingTest {
    private fun summary(aid: Int, name: String, slug: String, source: String) = SeriesSummary(aid, name, slug, 1, source)

    // Los ids de serie de cada ficha, por slug: las reglas del usuario hablan de ids.
    private val ids = mapOf("sousou-no-frieren" to 1L, "frieren" to 2L, "tokyo-ghoul" to 3L, "tokyo-ghoul-otro" to 4L, "serie-z" to 5L, "serie-z-alt" to 6L)

    private fun resolve(rules: TitleRules, vararg items: SeriesSummary) =
        SeriesResolver.resolveSeries(items.map { EmptySourceWithId(it.source) to it }, rules) { _, slug -> ids[slug] }

    private class EmptySourceWithId(override val id: String) : AnimeSource by EmptySource

    @Test
    fun mergeJoinsSeriesWithDifferentTitles() {
        val a = summary(1, "Sousou no Frieren", "sousou-no-frieren", "jkanime")
        val b = summary(2, "Frieren", "frieren", "animegratis")
        assertEquals(2, resolve(TitleRules.EMPTY, a, b).size)
        val joined = resolve(TitleRules.EMPTY.withMerge("2", "1"), a, b)
        assertEquals(1, joined.size)
        assertEquals(2, joined.single().size)
    }

    @Test
    fun splitKeepsApartSeriesWithTheSameTitle() {
        val a = summary(1, "Tokyo Ghoul", "tokyo-ghoul", "jkanime")
        val b = summary(2, "Tokyo Ghoul", "tokyo-ghoul-otro", "animegratis")
        // Las dos fichas son el mismo anime de AniList: se unen, salvo que el usuario las separara.
        fun withAnilist(rules: TitleRules) = SeriesResolver.resolveSeries(
            listOf(a, b).map { EmptySourceWithId(it.source) to it }, rules, { _, _ -> 9L }, { _, slug -> ids[slug] })
        assertEquals(1, withAnilist(TitleRules.EMPTY).size)
        assertEquals(2, withAnilist(TitleRules.EMPTY.withSplit("3", "4")).size)
    }

    @Test
    fun dedupeFollowsMergesAndSplits() {
        val merged = SeriesDedupe(TitleRules.EMPTY.withMerge("2", "1")) { _, slug -> ids[slug] }
        assertTrue(merged.isNew("frieren", "Frieren", "animegratis"))
        assertFalse(merged.isNew("sousou-no-frieren", "Sousou no Frieren", "jkanime"))

        val split = SeriesDedupe(TitleRules.EMPTY.withSplit("5", "6")) { _, slug -> ids[slug] }
        assertTrue(split.isNew("serie-z", "Serie Z", "jkanime"))
        assertTrue(split.isNew("serie-z-alt", "Serie Z", "animegratis")) // mismo título en otra fuente, pero separadas
    }
}

// ===== WebFichaEsquemaTest.kt =====
/**
 * El contrato del registro único (`WebFichaSchema`): todas las fuentes entregan **la misma estructura**, con todas las claves, los mismos tipos y solo
 * valores de AniList; una fuente nueva no puede declarar otra cosa; y la tabla de `catalog-db` coincide con el esquema que exporta Room.
 */
class WebFichaEsquemaTest {
    private val ids = listOf("animeav1", "animeflv", "animeflvar", "animegratis", "jkanime", "monoschinos", "verani")

    private fun specText(id: String) = File("src/main/assets/sources/$id.json").readText()

    private fun spec(id: String) = SourceSpec.parse(specText(id))

    /** Todas las fichas de ejemplo de todas las fuentes: (fuente, registro construido). */
    private fun everyRecord(): List<Pair<String, JSONObject>> {
        fun html(id: String, file: String): Pair<String, JSONObject> {
            val node = HtmlNode(Jsoup.parse(File("src/test/fixtures/$id/$file").readText(), "https://example.test/"))
            return id to checkNotNull(WebFichaSchema.build(spec(id).ficha, node, listOf("Acción"), id, "slug", "https://example.test/slug")) { "$id/$file sin título" }
        }
        val sora = JsonNode(JSONObject(File("src/test/fixtures/animeav1/anime-sora.json").readText()))
        return listOf(
            "animeav1" to checkNotNull(WebFichaSchema.build(spec("animeav1").ficha, sora, listOf("Acción"), "animeav1", "slug", "https://example.test/slug")),
            html("animeflv", "ficha-black-clover.html"), html("animeflvar", "ficha.html"), html("animegratis", "ficha-black-clover.html"),
            html("jkanime", "ficha-serie.html"), html("jkanime", "ficha-pelicula.html"), html("monoschinos", "ficha-black-clover.html"), html("verani", "ficha-black-clover.html")
        )
    }

    /** La forma de un registro: cada ruta con su clase (objetos y listas se recorren; un valor, nulo o no, es «valor»). */
    private fun shape(value: Any?, path: String = ""): List<String> = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().flatMap { shape(value.opt(it), if (path.isEmpty()) it else "$path.$it") }.toList()
        is JSONArray -> listOf("$path[]")
        else -> listOf(path)
    }

    private fun at(media: JSONObject, path: List<String>): Any? = path.fold(media as Any?) { cur, key -> (cur as JSONObject).opt(key) }

    @Test
    fun `el registro vacio lleva todas las claves y ningun dato`() {
        val empty = WebFichaSchema.empty()
        val expectedTop = WebFichaSchema.FIELDS.map { it.path.first() }.toSet()
        assertEquals(expectedTop, empty.keys().asSequence().toSet())
        WebFichaSchema.FIELDS.forEach { field ->
            val value = at(empty, field.path)
            when (field.kind) {
                Kind.LIST, Kind.ITEMS -> assertEquals("${field.key} debe ser una lista vacía", 0, (value as JSONArray).length())
                Kind.DATE -> (value as JSONObject).let { d ->
                    assertEquals(setOf("year", "month", "day"), d.keys().asSequence().toSet())
                    assertTrue(d.isNull("year") && d.isNull("month") && d.isNull("day"))
                }
                Kind.BY_LANGUAGE -> assertEquals(WebFichaSchema.LANGUAGES.toSet(), (value as JSONObject).keys().asSequence().toSet())
                else -> assertEquals("${field.key} debe ser null", JSONObject.NULL, value)
            }
        }
    }

    @Test
    fun `todas las fuentes entregan la misma estructura`() {
        val expected = shape(WebFichaSchema.empty())
        everyRecord().forEach { (id, media) -> assertEquals("$id: otra estructura", expected, shape(media)) }
    }

    @Test
    fun `cada campo de cada fuente tiene el tipo y los valores del esquema`() {
        everyRecord().forEach { (id, media) ->
            WebFichaSchema.FIELDS.forEach { field ->
                val value = at(media, field.path)
                val where = "$id.${field.key}"
                when (field.kind) {
                    Kind.LONG, Kind.INT -> assertTrue("$where: $value", value === JSONObject.NULL || (value is Number && value.toLong() > 0))
                    Kind.DECIMAL -> assertTrue("$where: $value", value === JSONObject.NULL || (value is Number && value.toDouble() in 0.0..WebFichaSchema.SCORE_SCALE))
                    Kind.BOOL -> assertTrue("$where: $value", value === JSONObject.NULL || value is Boolean)
                    Kind.TEXT -> assertTrue("$where: $value", value === JSONObject.NULL || (value is String && value.isNotBlank() && value == value.trim()))
                    Kind.URL -> assertTrue("$where: $value", value === JSONObject.NULL || (value is String && value.startsWith("https://")))
                    Kind.ENUM -> assertTrue("$where: $value", value === JSONObject.NULL || value in field.values)
                    Kind.LIST -> {
                        val list = (value as JSONArray).let { a -> List(a.length()) { a.get(it) } }
                        assertTrue("$where: elementos no válidos", list.all { it is String && it.isNotBlank() })
                        assertEquals("$where: repetidos", list.size, list.toSet().size)
                    }
                    Kind.DATE -> (value as JSONObject).let { d ->
                        listOf("year", "month", "day").forEach { k -> assertTrue("$where.$k", d.isNull(k) || d.get(k) is Int) }
                        // Un mes o un día sin año no tiene sentido.
                        if (d.isNull("year")) assertTrue("$where: mes sin año", d.isNull("month") && d.isNull("day"))
                    }
                    Kind.BY_LANGUAGE -> (value as JSONObject).keys().forEach { k -> assertTrue("$where.$k", (value).isNull(k) || (value).get(k) is String) }
                    Kind.ITEMS -> (value as JSONArray).let { a ->
                        repeat(a.length()) { i ->
                            val item = a.getJSONObject(i)
                            assertEquals("$where: claves de un elemento", field.items.map { it.key }.toSet(), item.keys().asSequence().toSet())
                            assertFalse("$where: elemento sin nombre", item.isNull("name"))
                        }
                    }
                }
            }
            assertEquals("$id: el título es obligatorio", false, media.getJSONObject("title").isNull("romaji"))
            assertEquals(id, media.getJSONObject("web").getString("fuente"))
            // Los otros nombres no repiten los tres del título.
            val own = listOf("romaji", "english", "native").mapNotNull { media.getJSONObject("title").text(it) }
            assertTrue("$id: sinónimos repetidos", media.strings("synonyms").none { it in own })
        }
    }

    @Test
    fun `sin titulo la pagina no es una ficha`() {
        assertNull(WebFichaSchema.build(spec("animeflv").ficha, HtmlNode(Jsoup.parse("<p>otra cosa</p>")), emptyList(), "animeflv", "x", "https://example.test/x"))
    }

    @Test
    fun `un estado escrito de otra forma sigue siendo el mismo`() {
        val html = """<div class="info-l"><figure class="i"><img alt="Algo"></figure><div class="st"><span>EN EMISION</span></div></div>"""
        val media = checkNotNull(WebFichaSchema.build(spec("animeflv").ficha, HtmlNode(Jsoup.parse(html)), emptyList(), "animeflv", "x", "https://example.test/x"))
        assertEquals("RELEASING", media.getString("status"))
        val unknown = html.replace("EN EMISION", "Pausado por la web")
        assertTrue(checkNotNull(WebFichaSchema.build(spec("animeflv").ficha, HtmlNode(Jsoup.parse(unknown)), emptyList(), "animeflv", "x", "https://example.test/x")).isNull("status"))
    }

    @Test
    fun `la nota de la web se guarda siempre sobre cinco`() {
        val ten = HtmlNode(Jsoup.parse("<h1>X</h1><p>Puntuación 8 / 10</p><p>20 valoraciones</p>"))
        assertEquals(4.0, checkNotNull(WebFichaSchema.build(spec("animegratis").ficha, ten, emptyList(), "animegratis", "x", "https://example.test/x")).getJSONObject("web").getDouble("score"), 0.001)
        assertEquals(4.0f to 20, WebFichaSchema.rating(spec("animegratis").ficha, ten))
        assertNull(WebFichaSchema.rating(spec("animegratis").ficha, HtmlNode(Jsoup.parse("<h1>X</h1>"))))
    }

    // ---- una fuente nueva queda obligada al mismo esquema

    private fun mutate(id: String, change: (JSONObject) -> Unit): String = JSONObject(specText(id)).also(change).toString()

    private fun assertRejected(text: String, fragment: String) {
        try {
            SourceSpec.parse(text)
            fail("debía rechazar la especificación (esperaba «$fragment»)")
        } catch (e: SpecException) {
            assertTrue("«${e.message}» no menciona «$fragment»", e.message.orEmpty().contains(fragment))
        }
    }

    @Test
    fun `una fuente sin ficha o con una ficha ajena al esquema se rechaza`() {
        assertRejected(mutate("animeflv") { it.remove("ficha") }, "falta el bloque ficha")
        assertRejected(mutate("animeflv") { it.getJSONObject("ficha").put("edadRecomendada", "x") }, "claves que no son del esquema")
        assertRejected(mutate("animeflv") { it.getJSONObject("ficha").remove("romaji") }, "falta romaji")
        assertRejected(mutate("animeflv") { it.getJSONObject("ficha").getJSONObject("status").getJSONObject("map").put("Algo", "TERMINADA") }, "no es un valor de AniList")
        assertRejected(mutate("animeflv") { it.getJSONObject("ficha").remove("descriptionLanguage") }, "descriptionLanguage")
        assertRejected(mutate("animeflv") { it.getJSONObject("ficha").put("descriptionLanguage", "fr") }, "descriptionLanguage")
        assertRejected(mutate("animegratis") { it.getJSONObject("ficha").remove("scoreMax") }, "scoreMax")
        assertRejected(mutate("animegratis") { it.getJSONObject("ficha").put("scoreMax", 7) }, "scoreMax")
        assertRejected(mutate("animeflvar") { it.getJSONObject("ficha").getJSONArray("synonyms").getJSONObject(0).put("split", "(") }, "split")
        assertRejected(mutate("animeflvar") { it.getJSONObject("ficha").getJSONObject("characters").put("edad", JSONObject().put("sel", "x")) }, "campos que no son del esquema")
        assertRejected(mutate("animeflvar") { it.getJSONObject("ficha").getJSONObject("characters").remove("name") }, "falta name")
    }

    @Test
    fun `los datos de la ficha no se vuelven a declarar en el bloque anime`() {
        SourceSpec.MOVED_TO_FICHA.forEach { key ->
            assertRejected(mutate("animeflv") { it.getJSONObject("anime").put(key, JSONObject().put("sel", "x")) }, "van en el bloque ficha")
        }
    }

    @Test
    fun `una alternativa que da texto pero no un valor valido no tapa a la siguiente`() {
        // monoschinos: «Emisión» trae un texto que no es una fecha y la temporada da el año.
        val spec = spec("monoschinos")
        val html = """<h1>X</h1><dl><dt>Emisión</dt><dd>Próximamente</dd><dt>Temporada</dt><dd>Otoño 2017</dd></dl>"""
        val media = checkNotNull(WebFichaSchema.build(spec.ficha, HtmlNode(Jsoup.parse(html)), emptyList(), "monoschinos", "x", "https://example.test/x"))
        assertEquals(2017, media.getJSONObject("startDate").getInt("year"))
        assertEquals("FALL", media.getString("season"))
    }

    @Test
    fun `una portada que no es https no se guarda y un valor no finito no es una nota`() {
        val flv = spec("animeflv")
        val http = """<h1>X</h1><meta property="og:image" content="http://example.test/a.jpg">"""
        assertTrue(checkNotNull(WebFichaSchema.build(flv.ficha, HtmlNode(Jsoup.parse(http)), emptyList(), "animeflv", "x", "https://example.test/x")).getJSONObject("coverImage").isNull("extraLarge"))
        val av1 = spec("animeav1")
        val page = JsonNode(JSONObject("""{"title":"X","score":"Infinity","votes":5}"""))
        val media = checkNotNull(WebFichaSchema.build(av1.ficha, page, emptyList(), "animeav1", "x", "https://example.test/x"))
        assertTrue(media.getJSONObject("web").isNull("score"))
        assertNull(WebFichaSchema.rating(av1.ficha, page))
    }

    // ---- columnas

    @Test
    fun `las columnas salen del registro`() {
        val sora = checkNotNull(WebFichaSchema.build(spec("animeav1").ficha, JsonNode(JSONObject(File("src/test/fixtures/animeav1/anime-sora.json").readText())), listOf("Acción"), "animeav1", "sora", "https://example.test/sora"))
        val row = WebFicha.of("animeav1", "sora", sora, 5L)
        assertEquals(63489L, row.malId)
        assertEquals(2026, row.anio)
        assertEquals(20260708, row.inicio)
        assertEquals("TV", row.formato)
        assertEquals("RELEASING", row.estado)
        assertEquals(18, row.episodios)
        assertEquals(false, row.adulto)
        assertNull(row.temporada)
        // Todos los nombres, normalizados, más el japonés tal cual: se busca con un «contiene».
        assertTrue(row.nombres.contains("|sora wa akai kawa no hotori|") && row.nombres.contains("|red river|") && row.nombres.contains("|天は赤い河のほとり|"))
        assertEquals(sora.toString(), row.jsonText())
    }

    @Test
    fun `sin fecha de inicio el ano sale de la temporada y la fecha queda sin dato`() {
        val media = WebFichaSchema.empty()
        media.getJSONObject("title").put("romaji", "Algo")
        media.put("seasonYear", 2021).put("season", "FALL")
        val c = FichaData(media, "test", "x")
        assertEquals(2021, c.anio)
        assertNull(c.inicio)
        assertEquals("FALL", c.temporada)
        assertNull(c.adulto)
    }

    @Test
    fun `un valor nulo no se lee como el texto null`() {
        val o = JSONObject("""{"a":null,"b":"  ","c":"x","d":0,"e":[null,"y"," "]}""")
        assertNull(o.text("a")); assertNull(o.text("b")); assertEquals("x", o.text("c")); assertNull(o.int("d")); assertEquals(listOf("y"), o.strings("e"))
    }

    // ---- la tabla de catalog-db

    @Test
    fun `la tabla de la migracion es la que exporta Room y AnimeFicha no cambia`() {
        fun entities(version: Int): Map<String, JSONObject> {
            val schema = JSONObject(File("schemas/app.kaizen.database.CatalogDB/$version.json").readText()).getJSONObject("database")
            assertEquals(version, schema.getInt("version"))
            val list = schema.getJSONArray("entities")
            return List(list.length()) { list.getJSONObject(it) }.associateBy { it.getString("tableName") }
        }
        val v3 = entities(3)
        assertEquals(setOf("AnimeFicha", "WebFicha"), v3.keys)
        val web = v3.getValue("WebFicha")
        val expected = listOf(web.getString("createSql")) + List(web.getJSONArray("indices").length()) { web.getJSONArray("indices").getJSONObject(it).getString("createSql") }
        // La migración 2 → 3 borra la tabla y la crea como la entidad actual.
        assertEquals(expected.map { it.replace("\${TABLE_NAME}", "WebFicha") }, WebFichaTables.REBUILD.drop(1))
        assertEquals("DROP TABLE IF EXISTS `WebFicha`", WebFichaTables.REBUILD.first())
        // Las fichas de AniList (horas de peticiones) no se tocan: su tabla es idéntica en la versión 2 y en la 3.
        assertEquals(entities(2).getValue("AnimeFicha").getString("createSql"), v3.getValue("AnimeFicha").getString("createSql"))
        // Desde la versión 1 y desde la 2 se llega a la 3 con la misma sentencia.
        assertEquals(listOf(1 to 3, 2 to 3), listOf(CatalogDB.MIGRATION_1_3, CatalogDB.MIGRATION_2_3).map { it.startVersion to it.endVersion })
    }
}

// ===== WebFichaFuentesTest.kt =====
/**
 * Lo que guarda `WebFicha` de cada fuente: valores reales de sus fichas (bloques de las páginas, 2026-11-04) con el esquema único. Que todas tengan la
 * misma estructura lo comprueba `WebFichaEsquemaTest`; aquí, qué lee cada una y qué deja a `null` por no darlo la web.
 */
class WebFichaFuentesTest {
    private fun record(id: String, fixture: String): JSONObject {
        val spec = SourceSpec.parse(File("src/main/assets/sources/$id.json").readText())
        val node = HtmlNode(Jsoup.parse(File("src/test/fixtures/$id/$fixture").readText(), "https://example.test/"))
        return checkNotNull(WebFichaSchema.build(spec.ficha, node, emptyList(), id, "slug", "https://example.test/slug")) { "$id: sin título" }
    }

    private fun JSONObject.title(key: String) = getJSONObject("title").text(key)
    private fun JSONObject.date(key: String) = getJSONObject(key).let { listOf(it.int("year"), it.int("month"), it.int("day")) }
    private fun JSONObject.web(key: String): Any? = getJSONObject("web").opt(key)

    @Test
    fun `animeav1 da el idMal, la nota y las tres lenguas del titulo`() {
        val spec = SourceSpec.parse(File("src/main/assets/sources/animeav1.json").readText())
        val page = JsonNode(JSONObject(File("src/test/fixtures/animeav1/anime-sora.json").readText()))
        val m = checkNotNull(WebFichaSchema.build(spec.ficha, page, listOf("Acción", "Aventura"), "animeav1", "sora", "https://animeav1.com/media/sora"))
        assertEquals(63489L, m.getLong("idMal"))
        assertEquals("Sora wa Akai Kawa no Hotori", m.title("romaji"))
        assertEquals("Red River", m.title("english"))
        assertEquals("天は赤い河のほとり", m.title("native"))
        assertEquals("TV", m.getString("format"))
        assertEquals("RELEASING", m.getString("status"))
        assertEquals(18, m.getInt("episodes"))
        assertEquals(listOf(2026, 7, 8), m.date("startDate"))
        assertEquals(listOf("Acción", "Aventura"), m.strings("genres"))
        assertEquals("qpFcQ1Bek08", m.getJSONObject("trailer").getString("id"))
        assertEquals("youtube", m.getJSONObject("trailer").getString("site"))
        assertEquals(false, m.getBoolean("isAdult"))
        assertTrue(m.getJSONObject("description").getString("es").startsWith("Yuri"))
        assertTrue(m.getJSONObject("description").isNull("en"))
        // La nota de la web (5,87 sobre 10) sale sobre 5, y sus votos.
        assertEquals(2.94, m.web("score") as Double, 0.001)
        assertEquals(2239, m.web("votes"))
        assertEquals("animeav1", m.web("fuente"))
        // Lo que esta web no da queda a null.
        assertTrue(m.isNull("season") && m.isNull("source") && m.isNull("countryOfOrigin") && m.isNull("bannerImage"))
        assertEquals(0, m.getJSONArray("studios").length())
    }

    @Test
    fun `una acabada, una pelicula y un nombre alternativo en animeav1`() {
        val spec = SourceSpec.parse(File("src/main/assets/sources/animeav1.json").readText())
        val raw = JSONObject(File("src/test/fixtures/animeav1/anime-sora.json").readText()).put("status", 0).put("runtime", 120).put("endDate", "2025-11-14").put("mature", true)
            .put("category", JSONObject("""{"id":2,"name":"Película","slug":"pelicula","malId":"Movie"}"""))
            .put("aka", JSONObject("""{"ja-jp":"天は赤い河のほとり","en-us":"Red River","en-ja":"Sora wa Akai Kawa no Hotori (TV)"}"""))
        val m = checkNotNull(WebFichaSchema.build(spec.ficha, JsonNode(raw), emptyList(), "animeav1", "s", "https://example.test/s"))
        assertEquals("FINISHED", m.getString("status"))
        assertEquals("MOVIE", m.getString("format"))
        assertEquals(120, m.getInt("duration"))
        assertEquals(listOf(2025, 11, 14), m.date("endDate"))
        assertEquals(true, m.getBoolean("isAdult"))
        assertEquals(listOf("Sora wa Akai Kawa no Hotori (TV)"), m.strings("synonyms"))
        // Un código que la web usa y no se vio en ninguna ficha no se adivina.
        val unknown = checkNotNull(WebFichaSchema.build(spec.ficha, JsonNode(raw.put("status", 1).put("category", JSONObject("""{"malId":"Algo nuevo"}"""))), emptyList(), "animeav1", "s", "https://example.test/s"))
        assertTrue(unknown.isNull("status") && unknown.isNull("format"))
    }

    @Test
    fun `animeflv one da el titulo, el estado y la sinopsis en espanol`() {
        val m = record("animeflv", "ficha-black-clover.html")
        assertEquals("Black Clover", m.title("romaji"))
        assertEquals("FINISHED", m.getString("status"))
        assertTrue(m.getJSONObject("description").getString("es").startsWith("Asta y Yuno fueron abandonados juntos"))
        assertTrue(m.isNull("idMal") && m.isNull("format") && m.isNull("season") && m.getJSONObject("startDate").isNull("year"))
    }

    @Test
    fun `animeflv ar da estudios, productoras, equipo, personajes y la sinopsis en ingles`() {
        val m = record("animeflvar", "ficha.html")
        assertEquals("Sora wa Akai Kawa no Hotori", m.title("romaji"))
        assertEquals("天は赤い河のほとり", m.title("native"))
        assertTrue(m.isNull("idMal") && m.title("english") == null) // la web no distingue el inglés de los demás nombres
        assertEquals(listOf("Red River", "Anatolia Story", "Heaven by the Red River", "Orontes Renka", "Orontes Love Song", "Where the Sky and the Red River Meet"), m.strings("synonyms"))
        assertEquals("TV", m.getString("format"))
        assertEquals("RELEASING", m.getString("status"))
        assertEquals("SUMMER", m.getString("season"))
        assertEquals(2026, m.getInt("seasonYear"))
        assertEquals(listOf(2026, null, null), m.date("startDate"))
        assertEquals(listOf("Tatsunoko Production"), m.strings("studios"))
        assertEquals(listOf("Team Kawadon", "VAP"), m.strings("producers"))
        val staff = m.getJSONArray("staff").getJSONObject(0)
        assertEquals("Kobayashi Kousuke", staff.getString("name"))
        assertEquals("Director", staff.getString("role"))
        val first = m.getJSONArray("characters").getJSONObject(0)
        assertEquals("Suzuki, Yuuri", first.getString("name"))
        assertEquals("MAIN", first.getString("role"))
        assertEquals(listOf("Tachibana, Mirai"), first.strings("voiceActors"))
        assertTrue(m.getJSONObject("description").getString("en").startsWith("Yuri, a pretty Japanese girl"))
        assertTrue(m.getJSONObject("description").isNull("es"))
        // Sin el «(Source: VIZ Media)» ni los textos de relleno para buscadores.
        assertFalse("VIZ" in m.getJSONObject("description").getString("en"))
    }

    @Test
    fun `animegratis da el idMal, fechas, origen, productoras, personajes, nota y audio`() {
        val m = record("animegratis", "ficha-black-clover.html")
        assertEquals(34572L, m.getLong("idMal"))
        assertEquals("Black Clover", m.title("romaji"))
        assertEquals("ブラッククローバー", m.title("native"))
        assertEquals(listOf("Black Clover (TV)"), m.strings("synonyms"))
        assertEquals("TV", m.getString("format"))
        // «Finalizado · 170 de 170 episodios · serie completa»: solo la primera parte es el estado.
        assertEquals("FINISHED", m.getString("status"))
        assertEquals(170, m.getInt("episodes"))
        assertEquals(23, m.getInt("duration"))
        assertEquals("FALL", m.getString("season"))
        assertEquals(2017, m.getInt("seasonYear"))
        assertEquals(listOf(2017, 10, 3), m.date("startDate"))
        assertEquals(listOf(2021, 3, 30), m.date("endDate"))
        assertEquals("JP", m.getString("countryOfOrigin"))
        assertEquals("MANGA", m.getString("source"))
        assertEquals(listOf("Studio Pierrot"), m.strings("studios"))
        assertEquals(listOf("TV Tokyo", "Avex Pictures", "Shueisha"), m.strings("producers"))
        val asta = m.getJSONArray("characters").getJSONObject(0)
        assertEquals(listOf("Asta", "アスタ", "MAIN"), listOf(asta.getString("name"), asta.getString("native"), asta.getString("role")))
        assertEquals(listOf("Gakuto Kajiwara"), asta.strings("voiceActors"))
        assertEquals("vUjAxk1qYzQ", m.getJSONObject("trailer").getString("id"))
        assertEquals("PG-13", m.web("ageRating"))
        assertEquals(listOf("sub español", "castellano", "latino"), m.getJSONObject("web").strings("audio"))
        assertTrue(m.getJSONObject("description").getString("es").startsWith("Asta y Yuno fueron abandonados en la misma iglesia"))
        assertFalse("la nota de la web viene en la página", m.getJSONObject("web").isNull("score"))
    }

    @Test
    fun `jkanime da el idMal, el titulo en ingles y en japones, calidad, nota y audio`() {
        val m = record("jkanime", "ficha-serie.html")
        assertEquals(34572L, m.getLong("idMal"))
        assertEquals("Black Clover (TV)", m.title("romaji"))
        assertEquals("Black Clover", m.title("english"))
        assertEquals("ブラッククローバー", m.title("native"))
        assertEquals("TV", m.getString("format"))
        assertEquals("FINISHED", m.getString("status"))
        assertEquals(170, m.getInt("episodes"))
        assertEquals(24, m.getInt("duration"))
        assertEquals("FALL", m.getString("season"))
        assertEquals(2017, m.getInt("seasonYear"))
        assertEquals(listOf(2017, 10, 3), m.date("startDate")) // «Martes, 03 de Octubre de 2017»
        assertEquals(listOf("Pierrot"), m.strings("studios"))
        assertEquals("720p", m.web("quality"))
        assertEquals(listOf("Japones"), m.getJSONObject("web").strings("audio"))
        // Puntuación 8,14 sobre 10 → 4,07 sobre 5; la web no da el número de votos de la nota.
        assertEquals(4.07, m.web("score") as Double, 0.001)
        assertTrue(m.getJSONObject("web").isNull("votes"))
        assertTrue(m.getJSONObject("description").getString("es").startsWith("Hay un mundo en donde la magia"))
    }

    @Test
    fun `jkanime pelicula, y una duracion en horas no se adivina`() {
        val m = record("jkanime", "ficha-pelicula.html")
        assertEquals(48585L, m.getLong("idMal"))
        assertEquals("MOVIE", m.getString("format"))
        assertEquals(1, m.getInt("episodes"))
        assertEquals(listOf(2023, 6, 16), m.date("startDate"))
        assertEquals("SPRING", m.getString("season"))
        assertEquals("Black Clover: Sword of the Wizard King", m.title("english"))
        // «1 hr 53 min» no se reduce a 53: sin duración.
        assertTrue(m.isNull("duration"))
    }

    @Test
    fun `monoschinos lee el nombre original como otro nombre, la temporada y la fecha`() {
        val m = record("monoschinos", "ficha-black-clover.html")
        assertEquals("Black Clover", m.title("romaji"))
        // «Nombre original» igual que el título no se repite entre los otros nombres.
        assertEquals(0, m.getJSONArray("synonyms").length())
        assertEquals("FALL", m.getString("season"))
        assertEquals(listOf(2017, 10, 3), m.date("startDate")) // «03 de Octubre de 2017»
        // Su «Estado» de esta ficha es «Estreno», que no se sabe si es en emisión o por estrenar: no se inventa.
        assertTrue(m.isNull("status"))
        assertTrue(m.isNull("format")) // el «Tipo» es siempre «Anime»
        assertTrue(m.getJSONObject("description").getString("es").startsWith("Asta y Yuno fueron abandonados juntos"))
    }

    @Test
    fun `verani da el ano, el estado, las productoras, el fondo, el audio y el trailer`() {
        val m = record("verani", "ficha-black-clover.html")
        assertEquals("Black Clover", m.title("romaji"))
        assertEquals(listOf(2017, null, null), m.date("startDate")) // solo el año
        assertEquals("RELEASING", m.getString("status")) // «En Emisión», como lo dice la web
        // La web llama «Estudio» a las cadenas que emiten la serie: son productoras, no el estudio de animación.
        assertEquals(listOf("TV Tokyo", "TV Aichi", "TVQ", "TV Osaka", "TVh", "TSC"), m.strings("producers"))
        assertEquals(0, m.getJSONArray("studios").length())
        assertEquals("https://image.tmdb.org/t/p/w1280/oUsm3pq6rUga7lVGQFS3g84etVE.jpg", m.getString("bannerImage"))
        // Los dos audios que anuncia la web: el original y el doblaje.
        assertEquals(listOf("Japonés", "Español Latino"), m.getJSONObject("web").strings("audio"))
        assertEquals("TiIZ2NZxjMI", m.getJSONObject("trailer").getString("id"))
        assertTrue(m.getJSONObject("description").has("es"))
    }

    /** La serie que usa la app, derivada del registro (sin red: verani pide sus géneros a una API y no se prueba aquí). */
    private fun series(id: String, fixture: String): app.kaizen.pojos.av1.Series {
        val node = HtmlNode(Jsoup.parse(File("src/test/fixtures/$id/$fixture").readText(), "https://example.test/"))
        return checkNotNull(ConfigurableSource(SourceSpec.parse(File("src/main/assets/sources/$id.json").readText())).parseSeries("slug", 1, node, emptyList()))
    }

    @Test
    fun `la serie de la app sale del registro en todas las fuentes`() {
        val jk = series("jkanime", "ficha-serie.html")
        assertEquals(listOf("Black Clover (TV)", "tv-anime", 2, 2017, "Pierrot"), listOf(jk.name, jk.type, jk.state, jk.year, jk.studio))
        assertEquals(4.07f, jk.rateStars, 0.001f)
        assertEquals(0, jk.rateCount)
        assertTrue(jk.description.startsWith("Hay un mundo en donde la magia"))
        assertEquals("pelicula", series("jkanime", "ficha-pelicula.html").type)
        // En emisión es state 1 y todo lo demás 2 (el catálogo lo lee así), también cuando la web no da estado.
        assertEquals(1, series("animeflvar", "ficha.html").state)
        val mc = series("monoschinos", "ficha-black-clover.html")
        assertEquals(listOf(2, 2017), listOf(mc.state, mc.year))
        val ag = series("animegratis", "ficha-black-clover.html")
        assertEquals(listOf("Studio Pierrot", 2017), listOf(ag.studio, ag.year))
        assertEquals(4.65f, ag.rateStars, 0.001f)
        // La sinopsis sale en el primer idioma que tiene: animeflv.ar solo la da en inglés.
        assertTrue(series("animeflvar", "ficha.html").description.startsWith("Yuri, a pretty Japanese girl"))
    }

    @Test
    fun `el tipo de la serie sale del formato`() {
        val spec = SourceSpec.parse(File("src/main/assets/sources/animeav1.json").readText())
        val source = ConfigurableSource(spec)
        fun kind(code: String) = checkNotNull(source.parseSeries("s", 1, JsonNode(JSONObject("""{"title":"X","category":{"malId":"$code"}}""")), emptyList())).type
        assertEquals("tv-anime", kind("TV"))
        assertEquals("pelicula", kind("Movie"))
        assertEquals("ova", kind("OVA"))
        assertEquals("especial", kind("Special"))
        assertEquals("tv-anime", kind("ONA"))
        // Sin formato (o uno que no se conoce) vale lo de siempre: serie de TV.
        assertEquals("tv-anime", kind("Algo nuevo"))
    }

    @Test
    fun `los personajes repetidos y sin nombre no se guardan`() {
        val spec = SourceSpec.parse(File("src/main/assets/sources/animeflvar.json").readText())
        val html = """<h1 class="entry-title">X</h1><div class="infox"><h1 class="entry-title">X</h1></div>
            <div class="cvitem"><div class="cvchar"><span class="charname">A</span><span class="charrole">Main</span></div></div>
            <div class="cvitem"><div class="cvchar"><span class="charname">A</span><span class="charrole">Main</span></div></div>
            <div class="cvitem"><div class="cvchar"><span class="charname"></span><span class="charrole">Main</span></div></div>"""
        val m = checkNotNull(WebFichaSchema.build(spec.ficha, HtmlNode(Jsoup.parse(html)), emptyList(), "animeflvar", "x", "https://example.test/x"))
        assertEquals(1, m.getJSONArray("characters").length())
    }
}

// ===== WebFichaQueryTest.kt =====
/** Consulta de las fichas guardadas por un trozo del nombre y extracción de cualquier campo de su JSON por ruta (`WebFichaQuery`). */
class WebFichaQueryTest {
    /** Un DAO en memoria que hace lo mismo que la consulta SQL de `byName`: «contiene» sobre la columna de nombres. */
    private class FakeDao(private val rows: List<WebFicha>) : WebFichaDao {
        override fun put(ficha: WebFicha) = error("no se usa")
        override fun get(fuente: String, slug: String) = rows.firstOrNull { it.fuente == fuente && it.slug == slug }
        override fun byName(norm: String, raw: String, limit: Int) = rows.filter { (norm != "" && it.nombres.contains(norm)) || (raw != "" && it.nombres.contains(raw)) }.take(limit)
            .sortedWith(compareBy({ it.fuente }, { it.slug }))
        override fun page(limit: Int, offset: Int) = rows.sortedWith(compareBy({ it.fuente }, { it.slug })).drop(offset).take(limit)
        override fun count() = rows.size
        override fun countWithMal() = rows.count { it.malId != null }
        override fun countByFuente() = rows.groupingBy { it.fuente }.eachCount().map { FuenteCount(it.key, it.value) }
    }

    private val sora = JSONObject(
        """{"idMal":63489,"title":{"romaji":"Sora wa Akai Kawa no Hotori","english":"Red River","native":"天は赤い河のほとり"},
        "synonyms":["Anatolia Story","Orontes Renka"],"format":"TV","status":"RELEASING","startDate":{"year":2026,"month":7,"day":8},
        "genres":["Action","Drama"],"episodes":24}"""
    )
    private val beast = JSONObject("""{"idMal":52046,"title":{"romaji":"Yuusha Party wo Tsuihou sareta Beast Tamer","english":"Beast Tamer"},"format":"TV","genres":["Acción"]}""")

    private val dao = FakeDao(
        listOf(
            WebFicha.of("animeav1", "sora-wa-akai-kawa-no-hotori", sora, 1L),
            WebFicha.of("animeflvar", "sora-wa-akai-kawa-no-hotori", sora, 1L),
            WebFicha.of("animeav1", "beast-tamer", beast, 1L)
        )
    )

    private fun valueAt(media: JSONObject, path: String) = FichaData(media, "test", "test").valor(path)

    @Test
    fun `una ruta saca claves anidadas, posiciones de listas y numeros`() {
        assertEquals("63489", valueAt(sora, "idMal"))
        assertEquals("天は赤い河のほとり", valueAt(sora, "title.native"))
        assertEquals("2026", valueAt(sora, "startDate.year"))
        assertEquals("Action", valueAt(sora, "genres[0]"))
        assertEquals("Drama", valueAt(sora, "genres.1"))
        assertEquals("Orontes Renka", valueAt(sora, "synonyms[1]"))
    }

    @Test
    fun `un objeto o una lista salen como JSON y una ruta vacia da todo`() {
        assertEquals("""["Action","Drama"]""", valueAt(sora, "genres"))
        assertEquals(2026, JSONObject(valueAt(sora, "startDate")!!).getInt("year"))
        assertEquals(sora.toString(), valueAt(sora, ""))
    }

    @Test
    fun `lo que no existe es null y no falla`() {
        assertNull(valueAt(sora, "trailer.id"))
        assertNull(valueAt(sora, "genres[9]"))
        assertNull(valueAt(sora, "title.native.algo"))
        assertNull(valueAt(sora, "idMal[0]"))
    }

    @Test
    fun `se busca por un trozo del nombre sin importar mayusculas ni signos`() {
        assertEquals(listOf("animeav1", "animeflvar"), WebFichaQuery.find(dao, "AKAI kawa").map { it.fuente })
        assertEquals(listOf("beast-tamer"), WebFichaQuery.find(dao, "beast").map { it.slug })
        // El inglés y el nativo también valen.
        assertEquals(2, WebFichaQuery.find(dao, "red river").size)
        assertEquals(2, WebFichaQuery.find(dao, "赤い河").size)
        assertEquals(emptyList<WebFicha>(), WebFichaQuery.find(dao, "naruto"))
        assertEquals(emptyList<WebFicha>(), WebFichaQuery.find(dao, "  "))
    }

    @Test
    fun `si el trozo solo esta entre los sinonimos tambien se encuentra`() {
        assertEquals(2, WebFichaQuery.find(dao, "Anatolia").size)
    }

    @Test
    fun `get da el campo de cada ficha que coincide y null si le falta`() {
        val years = WebFichaQuery.get(dao, "sora", "startDate.year")
        assertEquals(listOf("animeav1" to "2026", "animeflvar" to "2026"), years.map { it.fuente to it.value })
        assertEquals(listOf<String?>(null), WebFichaQuery.get(dao, "beast", "startDate.year").map { it.value })
        assertEquals(listOf("52046"), WebFichaQuery.get(dao, "Beast Tamer", "idMal").map { it.value })
    }

    @Test
    fun `el limite se aplica en la consulta`() {
        assertEquals(1, WebFichaQuery.get(dao, "sora", "startDate.year", 1).size)
        assertEquals(2, WebFichaQuery.find(dao, "sora", 5).size)
    }
}

class ReviewNavTest {
    private fun series(id: Long) = PendingSeries(listOf("animeav1" to "s$id"), id, "dudoso", 0.5, 0)
    private val before = listOf<AnimeFuente?>(null)

    @Test
    fun `decidir avanza, apunta la decision y suelta el candado`() {
        val nav = ReviewNav().claim()!!.done(series(1), before)
        assertEquals(1, nav.index)
        assertEquals(1, nav.frontier)
        assertEquals(listOf(0), nav.passed)
        assertEquals(1, nav.history.size)
        assertFalse(nav.busy)
    }

    @Test
    fun `saltar avanza pero no entra en el historial`() {
        val nav = ReviewNav().claim()!!.done(series(1), null)
        assertEquals(1, nav.index)
        assertFalse(nav.canUndo)
        assertEquals(listOf(0), nav.passed)
    }

    @Test
    fun `una segunda escritura a la vez no toma el candado`() {
        val taken = ReviewNav().claim()!!
        assertNull(taken.claim())
        assertNull(taken.undo())
    }

    @Test
    fun `tras volver atras, decidir lleva de nuevo hasta donde se estaba`() {
        var nav = ReviewNav()
        for (i in 1L..3L) nav = nav.claim()!!.done(series(i), null)
        assertEquals(3, nav.index)
        nav = nav.back()!!.back()!!
        assertEquals(1, nav.index)
        assertEquals(3, nav.frontier)
        nav = nav.claim()!!.done(series(2), null)
        assertEquals(3, nav.index)
        nav = nav.claim()!!.resolved()
        assertEquals(4, nav.index)
        assertEquals(4, nav.frontier)
    }

    @Test
    fun `anterior sin series pasadas no hace nada`() {
        assertNull(ReviewNav().back())
    }

    @Test
    fun `anterior no se acepta con una escritura en curso`() {
        val nav = ReviewNav().claim()!!.done(series(1), null)
        assertNotNull(nav.back())
        assertNull(nav.claim()!!.back())
    }

    @Test
    fun `lo resuelto solo avanza sin poder volver con anterior`() {
        val nav = ReviewNav().claim()!!.resolved()
        assertEquals(1, nav.index)
        assertEquals(emptyList<Int>(), nav.passed)
    }

    @Test
    fun `deshacer revierte la ultima decision, vuelve a su serie y recorta la frontera`() {
        var nav = ReviewNav()
        nav = nav.claim()!!.done(series(1), before)
        nav = nav.claim()!!.done(series(2), null)
        nav = nav.claim()!!.done(series(3), before)
        assertEquals(3, nav.index)
        val (taken, last) = nav.undo()!!
        assertTrue(taken.busy)
        assertEquals(2, last.index)
        assertEquals(1, taken.history.size)
        val back = taken.undone(last)
        assertFalse(back.busy)
        assertEquals(2, back.index)
        assertEquals(3, back.frontier)
        assertEquals(listOf(0, 1), back.passed)
    }

    @Test
    fun `sin historial no hay nada que deshacer`() {
        assertNull(ReviewNav().undo())
    }
}

class SharedNameTest {
    @Test
    fun `un nombre de la web igual a uno de AniList lo confirma, sin mirar mayusculas ni signos`() {
        val candidate = listOf("Strike the Blood FINAL", "Strike the Blood: Final", "ストライク・ザ・ブラッド FINAL")
        assertEquals("Strike the Blood: Final", AnilistCompare.sharedName(listOf("strike the blood final!!", "Otro nombre"), listOf("Strike the Blood Final Ova") + candidate.drop(1)))
        assertEquals("ストライク・ザ・ブラッド FINAL", AnilistCompare.sharedName(listOf("ストライク・ザ・ブラッド FINAL"), candidate))
        // Dos nombres en japonés distintos con el mismo resto latino no coinciden (el normalizador del emparejado dejaría los dos en «final»).
        assertNull(AnilistCompare.sharedName(listOf("別の作品 FINAL"), listOf("ストライク・ザ・ブラッド FINAL")))
        // Un nombre solo en japonés sí se compara.
        assertEquals("ロメリア戦記", AnilistCompare.sharedName(listOf("ロメリア戦記"), listOf("Romelia", "ロメリア戦記")))
    }

    @Test
    fun `sin nombres o sin coincidencia no hay nombre compartido`() {
        assertNull(AnilistCompare.sharedName(emptyList(), listOf("Algo")))
        assertNull(AnilistCompare.sharedName(listOf("  ", "!!!"), listOf("Algo")))
        assertNull(AnilistCompare.sharedName(listOf("Uno"), listOf("Dos", "Tres")))
        assertNull(AnilistCompare.sharedName(listOf("Uno"), emptyList()))
        // Un nombre corto o genérico lo comparten series distintas: no cuenta.
        assertNull(AnilistCompare.sharedName(listOf("OVA", "2", "Final"), listOf("OVA", "2", "Final", "Special")))
        assertEquals("Naruto", AnilistCompare.sharedName(listOf("Naruto"), listOf("Naruto")))
    }
}

class CompareTest {
    private val candidate = FichaData.ofAnilistJson("""{"id":5,"title":{"romaji":"Strike the Blood Final"},"synonyms":["Otro Nombre"],"seasonYear":2020,"episodes":12,"status":"FINISHED","format":"TV"}""")!!

    private fun compare(names: List<String>) =
        AnilistCompare.compare(candidate, 1.0, localYear = 2020, localEpisodes = 12, localGenres = emptyList(), localAiring = null, localStudio = null, localFormat = null, localMal = null, names = names)

    @Test
    fun `un nombre compartido cuenta como una coincidencia mas, tambien en la resolucion sin preguntar`() {
        val without = compare(listOf("Nada que ver"))
        assertEquals(Match.SAME, without.year)
        assertEquals(Match.SAME, without.episodes)
        assertNull(without.sharedName)
        // Solo año y episodios coinciden: faltan datos para darla por buena.
        assertEquals(AnilistCompare.Auto.ASK, without.auto)
        val with = compare(listOf("otro nombre!"))
        assertEquals("Otro Nombre", with.sharedName)
        assertEquals(AnilistCompare.Auto.ACCEPT, with.auto)
        assertTrue(with.overall!! >= without.overall!!)
    }

    @Test
    fun `un nombre compartido no basta si algun dato difiere, ni si es corto`() {
        // Con el formato de otra clase (película frente a serie) sigue preguntando aunque haya un nombre compartido.
        val different = AnilistCompare.compare(candidate, 1.0, 2020, 12, emptyList(), null, null, "MOVIE", null, listOf("Otro Nombre"))
        assertEquals("Otro Nombre", different.sharedName)
        assertEquals(AnilistCompare.Auto.ASK, different.auto)
        // Un nombre de una letra o dos no cuenta: sigue como sin él.
        val short = AnilistCompare.compare(candidate.let { FichaData.ofAnilistJson("""{"id":5,"title":{"romaji":"X"},"synonyms":["2"],"seasonYear":2020,"episodes":12,"status":"FINISHED"}""")!! }, 1.0, 2020, 12, emptyList(), null, null, null, null, listOf("2"))
        assertNull(short.sharedName)
        assertEquals(AnilistCompare.Auto.ASK, short.auto)
    }

    @Test
    fun `sin la ficha de AniList todo queda sin dato y no se decide nada`() {
        val c = AnilistCompare.compare(null, 0.8, 2020, 12, emptyList(), null, null, null, null, listOf("x"))
        assertEquals(listOf(Match.UNKNOWN), listOf(c.year, c.episodes, c.genres, c.status, c.studio, c.format, c.mal).distinct())
        assertNull(c.sharedName)
        assertNull(c.auto)
        assertEquals(0.8, c.overall!!, 1e-9)
    }

    @Test
    fun `la duracion solo se compara en fichas de un episodio y sin dato queda vacia`() {
        // Película de 90 min frente a un OVA de 25: los episodios (1 y 1) no los distinguen, la duración sí.
        assertEquals(Match.DIFFERENT, AnilistCompare.duration(90, 1, 25, 1))
        assertEquals(Match.SAME, AnilistCompare.duration(104, 1, 100, 1))
        assertEquals(Match.SAME, AnilistCompare.duration(23, 1, 25, 1))
        assertEquals(Match.CLOSE, AnilistCompare.duration(110, 1, 95, 1))
        // Una web que no da la duración, o una ficha que no es de un episodio, no suma ni resta.
        assertEquals(Match.UNKNOWN, AnilistCompare.duration(null, 1, 90, 1))
        assertEquals(Match.UNKNOWN, AnilistCompare.duration(90, 1, null, 1))
        assertEquals(Match.UNKNOWN, AnilistCompare.duration(24, 12, 24, 12))
        assertEquals(Match.UNKNOWN, AnilistCompare.duration(90, null, 90, 1))
        assertEquals(Match.UNKNOWN, AnilistCompare.duration(0, 1, 90, 1))
    }

    @Test
    fun `la duracion distinta resta pero no descarta sola, y la igual suma`() {
        val movie = FichaData.ofAnilistJson("""{"id":6,"title":{"romaji":"Una Pelicula"},"synonyms":[],"seasonYear":2020,"episodes":1,"duration":90,"status":"FINISHED","format":"MOVIE"}""")!!
        fun cmp(duration: Int?) = AnilistCompare.compare(movie, 1.0, 2020, 1, emptyList(), null, null, null, null, listOf("Una Pelicula Otra"), duration)
        val none = cmp(null)
        val same = cmp(92)
        val different = cmp(25)
        assertEquals(Match.UNKNOWN, none.duration)
        assertEquals(Match.SAME, same.duration)
        assertEquals(Match.DIFFERENT, different.duration)
        assertTrue(same.overall!! >= none.overall!!)
        assertTrue(different.overall!! < none.overall!!)
        // Con un solo dato en contra no se descarta: queda para preguntar.
        assertEquals(AnilistCompare.Auto.ASK, different.auto)
        // La duración igual no basta para darla por buena sola: año y episodios + duración sin otro dato ajeno sigue preguntando.
        assertEquals(AnilistCompare.Auto.ASK, same.auto)
        // Con un dato ajeno que coincide (nombre compartido), sí, como sin duración; y una duración distinta lo frena.
        val withName = FichaData.ofAnilistJson("""{"id":6,"title":{"romaji":"Una Pelicula"},"synonyms":["Otro Nombre"],"seasonYear":2020,"episodes":1,"duration":90,"status":"FINISHED","format":"MOVIE"}""")!!
        assertEquals(AnilistCompare.Auto.ACCEPT, AnilistCompare.compare(withName, 1.0, 2020, 1, emptyList(), null, null, null, null, listOf("otro nombre!"), 92).auto)
        assertEquals(AnilistCompare.Auto.ASK, AnilistCompare.compare(withName, 1.0, 2020, 1, emptyList(), null, null, null, null, listOf("otro nombre!"), 25).auto)
    }

    @Test
    fun `sin titulo no hay parecido total ni decision`() {
        val c = AnilistCompare.compare(candidate, null, 2020, 12, emptyList(), null, null, null, null, emptyList())
        assertNull(c.overall)
        assertNull(c.auto)
    }
}

class EpisodesFarApartTest {
    @Test
    fun `una OVA frente a una temporada, o el triple de episodios, es demasiada diferencia`() {
        assertTrue(AnilistCompare.episodesFarApart(2, 12, null))
        assertTrue(AnilistCompare.episodesFarApart(12, 2, null))
        assertTrue(AnilistCompare.episodesFarApart(5, 13, null))
        assertTrue(AnilistCompare.episodesFarApart(4, 12, null))
        assertTrue(AnilistCompare.episodesFarApart(50, 12, null))
    }

    @Test
    fun `episodios parecidos, el doble de un cour o sin dato no es demasiada diferencia`() {
        assertFalse(AnilistCompare.episodesFarApart(12, 12, null))
        assertFalse(AnilistCompare.episodesFarApart(24, 12, null))
        assertFalse(AnilistCompare.episodesFarApart(6, 12, null))
        assertFalse(AnilistCompare.episodesFarApart(8, 12, null))
        assertFalse(AnilistCompare.episodesFarApart(10, 25, null))
        assertFalse(AnilistCompare.episodesFarApart(1, 2, null))
        // Una película frente a una OVA corta, o dos números pequeños: no se decide por los episodios.
        assertFalse(AnilistCompare.episodesFarApart(1, 3, null))
        assertFalse(AnilistCompare.episodesFarApart(2, 6, null))
        assertFalse(AnilistCompare.episodesFarApart(2, 11, null))
        assertFalse(AnilistCompare.episodesFarApart(null, 12, null))
        assertFalse(AnilistCompare.episodesFarApart(12, null, null))
        assertFalse(AnilistCompare.episodesFarApart(0, 12, null))
    }

    @Test
    fun `una web en emision con menos episodios que AniList solo va con retraso`() {
        assertFalse(AnilistCompare.episodesFarApart(3, 12, true))
        // Pero si la web tiene muchos mas que AniList, o no esta en emision, si es demasiada diferencia.
        assertTrue(AnilistCompare.episodesFarApart(3, 12, false))
        assertTrue(AnilistCompare.episodesFarApart(36, 12, true))
    }

    @Test
    fun `con los episodios muy distintos se descarta aunque todo lo demas coincida`() {
        val candidate = FichaData.ofAnilistJson("""{"id":5,"title":{"romaji":"Una Serie Larga"},"synonyms":["Otro Nombre"],"seasonYear":2020,"episodes":12,"status":"FINISHED","format":"TV"}""")!!
        // Año y nombre coinciden y la web dice 2 episodios (una OVA) frente a una serie de 12: se descarta.
        val ova = AnilistCompare.compare(candidate, 1.0, 2020, 2, emptyList(), null, null, null, null, listOf("otro nombre!"))
        assertEquals(AnilistCompare.Auto.REJECT, ova.auto)
        // Con el mismo idMal en la web y en AniList no se descarta nunca: es la mejor prueba de que es el mismo anime.
        val byMal = AnilistCompare.compare(FichaData.ofAnilistJson("""{"id":5,"idMal":77,"title":{"romaji":"Una Serie Larga"},"seasonYear":2020,"episodes":12,"status":"FINISHED"}""")!!, 1.0, 2020, 2, emptyList(), null, null, null, 77L, emptyList())
        assertTrue(byMal.auto != AnilistCompare.Auto.REJECT)
        // Con 12 y 12 la misma serie ya no se descarta.
        val same = AnilistCompare.compare(candidate, 1.0, 2020, 12, emptyList(), null, null, null, null, listOf("otro nombre!"))
        assertEquals(AnilistCompare.Auto.ACCEPT, same.auto)
    }
}

class AnilistTrailerTest {
    @Test
    fun construyeLaDireccionSoloParaYoutubeYDailymotion() {
        assertEquals("https://www.youtube.com/watch?v=abc_DEF-123", AnilistTrailer.url("youtube", "abc_DEF-123"))
        assertEquals("https://www.youtube.com/watch?v=abc", AnilistTrailer.url("YouTube", "abc"))
        assertEquals("https://www.dailymotion.com/video/x7tgad0", AnilistTrailer.url("dailymotion", "x7tgad0"))
        assertEquals(null, AnilistTrailer.url("vimeo", "123"))
        assertEquals(null, AnilistTrailer.url(null, "abc"))
    }

    @Test
    fun rechazaIdsQueNoSonSoloLetrasNumerosGuionYGuionBajo() {
        assertEquals(null, AnilistTrailer.url("youtube", null))
        assertEquals(null, AnilistTrailer.url("youtube", ""))
        assertEquals(null, AnilistTrailer.url("youtube", "abc&list=x"))
        assertEquals(null, AnilistTrailer.url("youtube", "../etc"))
        assertEquals(null, AnilistTrailer.url("youtube", "a".repeat(33)))
    }
}

class AniSkipTest {
    private val fma = """{"found":true,"results":[{"interval":{"startTime":58.642,"endTime":148.642},"skipType":"op","skipId":"a","episodeLength":1469.6349},
        |{"interval":{"startTime":1349.903,"endTime":1474},"skipType":"ed","skipId":"b","episodeLength":1474.692}],"message":"ok","statusCode":200}""".trimMargin()

    @Test
    fun leeElOpeningYElCierre() {
        val times = AniSkip.parse(fma)!!
        assertEquals(58_642L, times.openingStartMs)
        assertEquals(148_642L, times.openingEndMs)
        assertEquals(1_349_903L, times.endingStartMs)
        assertEquals(1_474_000L, times.endingEndMs)
        assertEquals(1_469_634L, times.openingLengthMs)
        assertEquals(1_474_692L, times.endingLengthMs)
    }

    @Test
    fun sinOpeningNiCierreValidosNoHayTiempos() {
        assertEquals(null, AniSkip.parse("""{"found":false,"results":[]}"""))
        assertEquals(null, AniSkip.parse("""{"found":true,"results":[{"interval":{"startTime":10,"endTime":5},"skipType":"op","episodeLength":1400}]}"""))
        assertEquals(null, AniSkip.parse("""{"found":true,"results":[{"interval":{"startTime":0,"endTime":9000},"skipType":"op","episodeLength":1400}]}"""))
        assertEquals(null, AniSkip.parse("no es json"))
        assertEquals(null, AniSkip.parse("""{"found":true,"results":[{"interval":{"startTime":10,"endTime":50},"skipType":"recap","episodeLength":1400}]}"""))
    }

    @Test
    fun elAvisoSaleUnPocoAntesYSeQuitaAntesDelFinal() {
        val times = AniSkip.parse(fma)!!
        assertEquals(false, times.inOpening(56_000L))
        assertEquals(true, times.inOpening(57_000L))
        assertEquals(true, times.inOpening(147_000L))
        assertEquals(false, times.inOpening(148_000L))
        assertEquals(true, times.inEnding(1_400_000L))
        assertEquals(false, times.inEnding(1_473_500L))
    }

    @Test
    fun losTiemposSoloValenSiLaDuracionSeParece() {
        val times = AniSkip.parse(fma)!!
        assertNotNull(times.forDuration(1_474_000L))
        assertNotNull(times.forDuration(1_485_000L))
        assertNull(times.forDuration(1_300_000L))
        assertNull(times.forDuration(0L))
    }

    // La API devolvió, para Kimetsu no Yaiba: Hashira Geiko-hen cap. 1, un opening del corte de 49 min y un cierre de uno de 24: a los 22:16 del vídeo de 49 min avisaba de «saltar».
    private val mixedCuts = """{"found":true,"results":[{"interval":{"startTime":2633,"endTime":2723},"skipType":"op","episodeLength":2937},
        |{"interval":{"startTime":1328.694,"endTime":1418.694},"skipType":"ed","episodeLength":1429.9734}]}""".trimMargin()

    @Test
    fun cadaTramoSoloValeEnUnVideoDeLaDuracionDeSuCorte() {
        val times = AniSkip.parse(mixedCuts)!!
        // El «opening» de 43:53 de un vídeo de 48:57 está en la segunda mitad (era un cierre mal etiquetado) y el cierre de 22:08 es de otro corte: no queda nada.
        assertNull(times.forDuration(2_937_500L))
        val short = times.forDuration(1_430_000L)!!
        assertNull(short.openingStartMs)
        assertNotNull(short.endingStartMs)
        assertNull(times.forDuration(2_000_000L))
    }

    @Test
    fun unOpeningVaEnLaPrimeraMitadYUnCierreEnLaSegunda() {
        fun one(type: String, start: Int) = AniSkip.parse("""{"found":true,"results":[{"interval":{"startTime":$start,"endTime":${start + 90}},"skipType":"$type","episodeLength":1400}]}""")!!
        assertNotNull(one("op", 600).forDuration(1_400_000L)) // un opening tardío, tras una escena inicial larga, sigue valiendo
        assertNull(one("op", 800).forDuration(1_400_000L))
        assertNotNull(one("ed", 1300).forDuration(1_400_000L))
        assertNull(one("ed", 600).forDuration(1_400_000L))
    }

    @Test
    fun unTramoQueNoDiceSuDuracionNoSePuedeComprobarYNoVale() {
        val times = AniSkip.parse("""{"found":true,"results":[{"interval":{"startTime":10,"endTime":90},"skipType":"op","episodeLength":0}]}""")!!
        assertNull(times.forDuration(1_400_000L))
    }
}
