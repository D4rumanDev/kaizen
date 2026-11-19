package app.kaizen.commons.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lo que la copia de estados da a la sincronización ([SyncKeys]), sin Android: claves portables, clave canónica, presencia, qué se quita y qué progreso entra. */
class SyncKeysTest {
    // Dos fichas de webs distintas que son la misma serie (id 5) y una que este dispositivo no conoce.
    private val ids = mapOf("animeflv:x" to 5L, "jkanime:x-tv" to 5L, "animeflv:y" to 6L)
    private val idOf = { source: String, slug: String -> ids["$source:$slug"] }

    @Test fun `la misma serie con otra ficha da la misma clave canonica`() {
        assertEquals("f/5", SyncKeys.canon(Removals.favoriteKey("animeflv:x"), idOf))
        assertEquals("f/5", SyncKeys.canon(Removals.favoriteKey("jkanime:x-tv"), idOf))
        assertEquals("e/5", SyncKeys.canon(Removals.statusKey("jkanime:x-tv"), idOf))
        assertEquals("c/5#3.0", SyncKeys.canon(Removals.progressKey("animeflv:x", 3.0), idOf))
        assertEquals("r/5#3.0", SyncKeys.canon(Removals.recordKey("jkanime:x-tv", 3.0), idOf))
        assertNull("ficha desconocida", SyncKeys.canon(Removals.favoriteKey("animeflv:z"), idOf))
        assertNull("clave mal formada", SyncKeys.canon("x/basura", idOf))
    }

    private val presence = SyncKeys.Presence(
        favoriteIds = setOf(5L), records = setOf(6L to 1.0), keys = emptySet(), progress = setOf(5L to 3.0),
        progressByFicha = setOf("animeflv:z" to 2.0), statusIds = setOf(6L), idOf = idOf
    )

    @Test fun `presencia por serie aunque la clave traiga otra ficha`() {
        assertEquals(true, presence.isPresent(Removals.favoriteKey("jkanime:x-tv")))
        assertEquals(false, presence.isPresent(Removals.favoriteKey("animeflv:y")))
        assertEquals(true, presence.isPresent(Removals.recordKey("animeflv:y", 1.0)))
        assertEquals(false, presence.isPresent(Removals.recordKey("animeflv:y", 2.0)))
        assertEquals(true, presence.isPresent(Removals.statusKey("animeflv:y")))
        assertEquals(false, presence.isPresent(Removals.statusKey("animeflv:x")))
        assertEquals(true, presence.isPresent(Removals.progressKey("jkanime:x-tv", 3.0)))
    }

    @Test fun `sin serie conocida no se sabe salvo el progreso guardado por ficha`() {
        assertNull(presence.isPresent(Removals.favoriteKey("animeflv:z")))
        assertNull(presence.isPresent(Removals.statusKey("animeflv:z")))
        assertEquals(true, presence.isPresent(Removals.progressKey("animeflv:z", 2.0)))
        assertNull(presence.isPresent(Removals.progressKey("animeflv:z", 9.0)))
        assertNull(presence.isPresent("basura"))
    }

    @Test fun `las claves salen con la ficha de su serie y lo que no tiene ficha no sale`() {
        val keys = SyncKeys.keys(
            favoriteIds = listOf(5L, 99L), records = listOf(6L to 1.0, 99L to 1.0), progress = listOf(5L to 3.0),
            progressByFicha = listOf("animeflv:z" to 2.0), statusFichas = listOf("animeflv:y")
        ) { id -> ids.filterValues { it == id }.keys.minOrNull() }
        assertEquals(setOf("f/animeflv:x", "r/animeflv:y#1.0", "c/animeflv:x#3.0", "c/animeflv:z#2.0", "e/animeflv:y"), keys)
    }

    @Test fun `solo estados y progreso llevan hora para decidir si una lapida llega tarde`() {
        val statusAt = { _: String, _: String -> 500L }
        val progressAt = { _: String, _: String, number: Double -> if (number == 3.0) 500L else 0L }
        assertTrue(SyncKeys.changedAfter(Removals.statusKey("animeflv:x"), 400L, statusAt, progressAt))
        assertFalse(SyncKeys.changedAfter(Removals.statusKey("animeflv:x"), 500L, statusAt, progressAt)) // la misma hora no es «después»
        assertTrue(SyncKeys.changedAfter(Removals.progressKey("animeflv:x", 3.0), 400L, statusAt, progressAt))
        assertFalse(SyncKeys.changedAfter(Removals.progressKey("animeflv:x", 4.0), 400L, statusAt, progressAt))
        assertFalse(SyncKeys.changedAfter(Removals.favoriteKey("animeflv:x"), 0L, statusAt, progressAt))
        assertFalse(SyncKeys.changedAfter(Removals.recordKey("animeflv:x", 1.0), 0L, statusAt, progressAt))
    }

    @Test fun `quitar lleva cada clave a su sitio y cuenta solo lo que habia`() {
        val calls = mutableListOf<String>()
        val target = object : SyncKeys.Target {
            override fun removeFavorite(source: String, slug: String) = calls.add("f $source:$slug").let { slug == "x" }
            override fun removeRecord(source: String, slug: String, number: Double) = calls.add("r $source:$slug#$number").let { true }
            override fun removeProgress(source: String, slug: String, number: Double) = calls.add("c $source:$slug#$number").let { false }
            override fun removeStatus(source: String, slug: String) = calls.add("e $source:$slug").let { true }
        }
        val keys = listOf(Removals.favoriteKey("animeflv:x"), Removals.favoriteKey("animeflv:y"), Removals.recordKey("animeflv:x", 1.0),
            Removals.progressKey("animeflv:x", 3.0), Removals.statusKey("animeflv:x"), "basura")
        assertEquals(3, SyncKeys.remove(keys, target))
        assertEquals(listOf("f animeflv:x", "f animeflv:y", "r animeflv:x#1.0", "c animeflv:x#3.0", "e animeflv:x"), calls)
    }

    @Test fun `del progreso ajeno no entran los ids locales ni lo que una lapida quito`() {
        val progress = mapOf(
            "s:5#1" to "1/2", "p:animeflv:x#1" to "1/2", "t:p:animeflv:x#1" to "9", "p:animeflv:x#2" to "1/2", "12345" to "1/2"
        )
        val skip = { key: String -> key == Removals.progressKey("animeflv:x", 2.0) }
        assertEquals(setOf("p:animeflv:x#1", "t:p:animeflv:x#1", "12345"), SyncKeys.progressToImport(progress, skip).keys)
    }

    @Test fun `los grupos solo unen fichas de fuentes conocidas y con tope`() {
        val groups = listOf(listOf("animeflv:x", "jkanime:x-tv", "inventada:x", "sin-dos-puntos", "animeflv:"), listOf("animeflv:a"), listOf("animeflv:b"))
        val out = SyncKeys.groups(groups, setOf("animeflv", "jkanime"), maxGroups = 2, maxSize = 4)
        assertEquals(listOf(listOf("animeflv" to "x", "jkanime" to "x-tv"), listOf("animeflv" to "a")), out)
    }
}
