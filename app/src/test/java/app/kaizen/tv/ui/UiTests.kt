package app.kaizen.tv.ui

import app.kaizen.tv.ui.theme.*

import androidx.compose.ui.unit.dp
import app.kaizen.pojos.av1.FavoriteAV1
import app.kaizen.pojos.av1.Record
import app.kaizen.pojos.av1.SeriesSummary
import app.kaizen.source.normalizeTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ===== FavoriteKeysTest.kt =====
class FavoriteKeysTest {
    // El favorito lleva el id de su serie; con slug «sin-id» (aún sin id en el libro) es 0.
    private fun favorite(aid: Int, name: String = "Serie Uno", slug: String = "serie-uno") =
        FavoriteAV1(aid = aid, name = name, slug = slug, type = "TV", category = FavoriteAV1.CATEGORY_NONE, seriesId = if (slug == "sin-id") 0L else 42L)

    @Test
    fun `un favorito da su titulo normalizado, su aid y su id de serie`() {
        val keys = favoriteKeys(listOf(favorite(aid = 7)))
        assertEquals(setOf(normalizeTitle("Serie Uno"), "@7", "@7|serie-uno", "#42"), keys)
    }

    @Test
    fun `aid 0 e id de serie 0 no generan clave para no casar con cualquier serie sin id`() {
        val keys = favoriteKeys(listOf(favorite(aid = 0, slug = "sin-id")))
        assertEquals(setOf(normalizeTitle("Serie Uno")), keys)
    }

    @Test
    fun `la tarjeta de otra fuente casa por aid o por id de serie aunque el titulo sea distinto`() {
        val keys = favoriteKeys(listOf(favorite(aid = 7, name = "Yozakura-san Chi no Daisakusen 2nda Temporada Parte 2")))
        // Mismo criterio que FavoriteAV1Dao.isFav: aid OR id de serie.
        assertTrue("@7" in keys)
        assertTrue("#42" in keys)
        assertFalse(normalizeTitle("Yozakura-san Chi no Daisakusen 2nda Te") in keys)
    }

    @Test
    fun `con slug, el aid de otra serie no enciende el corazon (ids internos repetidos)`() {
        // Caso real: el favorito de una serie de jkanime y «Tokyo Revengers: Santen Sensou-hen» tenían el mismo aid interno.
        val keys = favoriteKeys(listOf(favorite(aid = 1000064, name = "Doumo, Suki na Hito", slug = "doumo-suki-na-hito")))
        assertFalse(matchesFavorite(keys, "Tokyo Revengers: Santen Sensou-hen", 1000064, 0L, "tokyo-revengers-santen-sensou-hen"))
        assertTrue(matchesFavorite(keys, "Otro titulo", 1000064, 0L, "doumo-suki-na-hito"))
        // Sin slug (reproductor, listas sin él) sigue valiendo el aid como antes.
        assertTrue(matchesFavorite(keys, "Otro titulo", 1000064, 0L))
    }

    @Test
    fun `sin favoritos no hay claves`() {
        assertTrue(favoriteKeys(emptyList()).isEmpty())
    }
}

// ===== GridFocusTest.kt =====
class GridFocusTest {
    private val inicio = listOf(listOf("a", "b", "c"), listOf("r1", "r2"))

    @Test
    fun siLaTarjetaSigueNoHayQueMoverElFoco() {
        assertNull(refocusTarget(inicio, inicio, 0 to "b"))
    }

    @Test
    fun pasaALaQueOcupaSuPuesto() {
        assertEquals(0 to "c", refocusTarget(inicio, listOf(listOf("a", "c"), listOf("r1", "r2")), 0 to "b"))
        assertEquals(0 to "b", refocusTarget(inicio, listOf(listOf("b", "c"), listOf("r1", "r2")), 0 to "a"))
    }

    @Test
    fun siEraLaUltimaPasaALaAnterior() {
        assertEquals(0 to "b", refocusTarget(inicio, listOf(listOf("a", "b"), listOf("r1", "r2")), 0 to "c"))
    }

    @Test
    fun siSeVaciaLaSeccionPasaALaPrimeraDeLaSiguiente() {
        assertEquals(1 to "r1", refocusTarget(listOf(listOf("a"), listOf("r1", "r2")), listOf(emptyList(), listOf("r1", "r2")), 0 to "a"))
    }

    @Test
    fun sinSiguienteVuelveALaUltimaDeLaAnterior() {
        assertEquals(0 to "b", refocusTarget(listOf(listOf("a", "b"), listOf("r1")), listOf(listOf("a", "b"), emptyList()), 1 to "r1"))
    }

    @Test
    fun sinNadaQueEnfocarNoHayDestino() {
        assertNull(refocusTarget(listOf(listOf("a")), listOf(emptyList()), 0 to "a"))
    }

    @Test
    fun siNoSabemosDondeEstabaNoHayDestino() {
        assertNull(refocusTarget(emptyList(), inicio, 0 to "zzz"))
    }
}

// ===== IconCardHeightTest.kt =====
class IconCardHeightTest {
    @Test
    fun conAnchoNormalEsProporcional() {
        // 180 dp de ancho: 117 dp de alto, por debajo del mínimo → sube al mínimo.
        assertEquals(IconCardMinHeight, iconCardHeight(180.dp, tall = false))
        assertEquals(IconCardMinHeight, iconCardHeight(120.dp, tall = false))
    }

    @Test
    fun conAnchoGrandeNoSeTocaLaProporcion() {
        assertEquals(260.dp * 130 / 200, iconCardHeight(260.dp, tall = false))
    }

    @Test
    fun laAltaSigueLaProporcionDeLaPortada() {
        assertEquals(posterHeight(200.dp), iconCardHeight(200.dp, tall = true))
    }
}

// ===== MainSectionsTest.kt =====
class MainSectionsTest {
    private val rows = listOf(1 to "A", 2 to "B", 3 to "C")

    @Test fun savedOrderFirstThenNewOnes() {
        assertEquals(listOf(3, 1, 2), MainSections.applyOrder(rows, "3,1").map { it.first })
    }

    @Test fun emptyOrUnknownIdsKeepFactoryOrder() {
        assertEquals(listOf(1, 2, 3), MainSections.applyOrder(rows, "").map { it.first })
        assertEquals(listOf(2, 1, 3), MainSections.applyOrder(rows, "9,2,x,2,1").map { it.first })
    }
}

// ===== SeenIndexTest.kt =====
class SeenIndexTest {
    // Con slug «serie» el registro lleva el id de serie 5; con «sin-id», ninguno (0).
    private fun record(eid: Int, number: Double, aid: Int, slug: String = "sin-id") =
        Record(eid = eid, name = "Serie", number = number, aid = aid, slug = slug, date = 0L, source = "animeav1", seriesId = if (slug == "serie") 5L else 0L)

    private val index by lazy { SeenIndex(listOf(record(eid = 1, number = 3.0, aid = 10), record(eid = 2, number = 4.0, aid = 11, slug = "serie"))) }

    @Test
    fun `lo visto compartido de las tarjetas va por serie y numero como la consulta de Room`() {
        val seen = SeenChapters.seenOf(listOf(record(eid = 1, number = 3.0, aid = 10), record(eid = 2, number = 4.0, aid = 11, slug = "serie")))
        // Solo el registro con serie: uno sin serie tampoco lo encuentra la consulta (seriesId IN la serie de la ficha).
        assertEquals(setOf(5L to 4.0), seen)
        assertFalse((5L to 3.0) in seen)
        assertFalse((0L to 3.0) in seen)
    }

    @Test
    fun `el mismo capitulo cuenta como visto por eid`() {
        assertTrue(index.isSeen(eid = 1, aid = 99, seriesId = 0L, number = 9.0))
    }

    @Test
    fun `el mismo numero de la misma serie en otra fuente cuenta como visto por id de serie`() {
        assertTrue(index.isSeen(eid = 50, aid = 77, seriesId = 5L, number = 4.0))
    }

    @Test
    fun `un aid repetido en otra serie no cuenta como visto`() {
        assertFalse(index.isSeen(eid = 50, aid = 10, seriesId = 0L, number = 3.0))
        assertFalse(index.isSeen(eid = 50, aid = 11, seriesId = 6L, number = 4.0))
    }

    @Test
    fun `otro numero, otra serie o eid 0 sin coincidencia no cuentan como visto`() {
        assertFalse(index.isSeen(eid = 50, aid = 10, seriesId = 0L, number = 4.0))
        assertFalse(index.isSeen(eid = 50, aid = 77, seriesId = 5L, number = 5.0))
        assertFalse(index.isSeen(eid = 0, aid = 77, seriesId = 0L, number = 3.0))
    }

    @Test
    fun `un capitulo empezado y sin acabar no cuenta como visto y uno acabado si`() {
        val records = listOf(record(eid = 2, number = 4.0, aid = 11, slug = "serie"), record(eid = 3, number = 5.0, aid = 11, slug = "serie"))
        // El 4 está a medias (triángulo azul); el 5 está acabado (check verde).
        val index = SeenIndex(records) { _, number, _ -> number == 4.0 }
        assertFalse(index.isSeen(eid = 2, aid = 11, seriesId = 5L, number = 4.0))
        assertTrue(index.isSeen(eid = 3, aid = 11, seriesId = 5L, number = 5.0))
    }

    @Test
    fun `un id de serie 0 no casa con los registros sin id de serie`() {
        val sinId = SeenIndex(listOf(record(eid = 1, number = 3.0, aid = 10)))
        assertFalse(sinId.isSeen(eid = 50, aid = 77, seriesId = 0L, number = 3.0))
    }

    @Test
    fun `los tres estados del filtro`() {
        assertEquals(listOf("Todos", "Vistos", "No vistos"), SeenFilter.entries.map { it.label })
    }
}

// ===== SortUiTest.kt =====
/** Orden de las listas de tarjetas. */
class SortUiTest {
    private fun s(name: String, score: Double? = null, year: Int? = null) = SeriesSummary(1, name, name.lowercase(), 1, "animeav1", score, 0, year)
    private val items = listOf(s("Bleach", 4.0, 2004), s("#Zombie", null, 2026), s("Akira", 3.0), s("Naruto", 4.5, 2002))

    private fun names(sort: ListSort, desc: Boolean) = sortedItems(items, sort, desc, emptyMap()).map { it.name }

    @Test
    fun `A-Z ignora simbolos y el inverso lo da la vuelta`() {
        assertEquals(listOf("Akira", "Bleach", "Naruto", "#Zombie"), names(ListSort.NAME, false))
        assertEquals(listOf("#Zombie", "Naruto", "Bleach", "Akira"), names(ListSort.NAME, true))
    }

    @Test
    fun `lo que no trae el dato va siempre al final, en cualquier sentido`() {
        assertEquals(listOf("Akira", "Bleach", "Naruto", "#Zombie"), names(ListSort.SCORE, false))
        assertEquals(listOf("Naruto", "Bleach", "Akira", "#Zombie"), names(ListSort.SCORE, true))
        assertEquals(listOf("Naruto", "Bleach", "#Zombie", "Akira"), names(ListSort.YEAR, false))
    }

    @Test
    fun `el orden original no toca la lista`() {
        assertEquals(items.map { it.name }, names(ListSort.ORIGINAL, true))
    }

    @Test
    fun `el filtro deja solo las series con ese estado`() {
        val statuses = mapOf(
            "akira" to app.kaizen.domain.StatusEntry("completed", "Akira", "animeav1", "akira", 1, 0L),
            "naruto" to app.kaizen.domain.StatusEntry("watching", "Naruto", "animeav1", "naruto", 1, 0L)
        )
        val completed = app.kaizen.domain.SeriesStatus.COMPLETED.ordinal
        assertEquals(listOf("Akira"), sortedItems(items, ListSort.ORIGINAL, false, statuses, filter = completed).map { it.name })
        assertEquals(listOf("Bleach", "#Zombie"), sortedItems(items, ListSort.ORIGINAL, false, statuses, filter = app.kaizen.domain.SeriesStatus.NONE.ordinal).map { it.name })
        assertEquals(items.size, sortedItems(items, ListSort.ORIGINAL, false, statuses, filter = -1).size)
    }

    @Test
    fun `solo favoritas deja las series favoritas por titulo, aid o id de serie y se combina con el filtro de estado`() {
        val akiraFav = app.kaizen.pojos.av1.FavoriteAV1(aid = 9, name = "Akira", slug = "akira", type = "TV", category = app.kaizen.pojos.av1.FavoriteAV1.CATEGORY_NONE)
        assertEquals(listOf("Akira"), sortedItems(items, ListSort.ORIGINAL, false, emptyMap(), favorites = favoriteKeys(listOf(akiraFav)), favoritesOnly = true).map { it.name })
        // Sin activar el filtro no se quita nada, tenga o no favoritos.
        assertEquals(items.size, sortedItems(items, ListSort.ORIGINAL, false, emptyMap(), favorites = favoriteKeys(listOf(akiraFav)), favoritesOnly = false).size)
        // Todas las tarjetas de prueba tienen aid 1: un favorito con ese aid y el slug de cada una las casa; con otro aid y otro título, a ninguna.
        assertEquals(items.size, sortedItems(items, ListSort.ORIGINAL, false, emptyMap(), favorites = items.map { "@1|${it.slug}" }.toSet(), favoritesOnly = true).size)
        assertEquals(0, sortedItems(items, ListSort.ORIGINAL, false, emptyMap(), favorites = setOf("@2"), favoritesOnly = true).size)
        // Con un estado además: favorita Y con ese estado.
        val statuses = mapOf("akira" to app.kaizen.domain.StatusEntry("completed", "Akira", "animeav1", "akira", 1, 0L))
        val completed = app.kaizen.domain.SeriesStatus.COMPLETED.ordinal
        assertEquals(listOf("Akira"), sortedItems(items, ListSort.ORIGINAL, false, statuses, filter = completed, favorites = items.map { "@1|${it.slug}" }.toSet(), favoritesOnly = true).map { it.name })
        assertEquals(0, sortedItems(items, ListSort.ORIGINAL, false, statuses, filter = completed, favorites = setOf("@2"), favoritesOnly = true).size)
    }
}

// ===== ArquitecturaTest =====
/** Las pantallas de `tv/` no tocan las bases de datos: pasan por `Library` (favoritos, historial, recientes) o por un repositorio (enlaces con AniList). */
class ArquitecturaTest {
    @Test
    fun `las pantallas no usan las bases de datos directamente`() {
        val root = java.io.File("src/main/java/app/kaizen/tv")
        assertTrue("no se encuentra ${root.absolutePath}", root.isDirectory)
        val direct = Regex("""(UserDB|CatalogDB|CacheDB)\.INSTANCE|\.\w+(DAO|Dao)\(\)""")
        val offenders = root.walkTopDown().filter { it.extension == "kt" }
            .flatMap { file -> file.readLines().mapIndexedNotNull { index, line -> if (direct.containsMatchIn(line)) "${file.name}:${index + 1}" else null } }
            .toList()
        assertTrue("Pantallas que tocan una base de datos (usar Library o un repositorio): $offenders", offenders.isEmpty())
    }
}
