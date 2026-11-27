package app.kaizen.tv

import app.kaizen.commons.KaizenProgress
import app.kaizen.pojos.av1.Record
import app.kaizen.tv.ui.fitColumns
import app.kaizen.tv.ui.uniqueKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// ===== HomeChannelsSyncTest.kt =====
/** La fila de recientes del inicio solo se rehace cuando cambia algo. */
class HomeChannelsSyncTest {
    private val rows = listOf("animeav1|1|Serie A|Episodio 3|https://x/a.jpg|https://x/a", "animeav1|2|Serie B|Episodio 7|https://x/b.jpg|https://x/b")

    @Test
    fun `la firma cambia con un capitulo nuevo, otro orden, otra miniatura, otro canal o una miniatura menos por bajar`() {
        val base = HomeChannels.recentsSignature(5, rows, emptyList())
        assertEquals(base, HomeChannels.recentsSignature(5, rows.toList(), emptyList()))
        assertTrue(base != HomeChannels.recentsSignature(5, rows.reversed(), emptyList()))
        assertTrue(base != HomeChannels.recentsSignature(5, rows + "animeav1|3|Serie C|Episodio 1|https://x/c.jpg|https://x/c", emptyList()))
        assertTrue(base != HomeChannels.recentsSignature(5, listOf(rows[0].replace("a.jpg", "a2.jpg"), rows[1]), emptyList()))
        assertTrue(base != HomeChannels.recentsSignature(6, rows, emptyList()))
        // Con una miniatura por bajar los programas llevaban su dirección web: al llegar, la firma es otra y se rehacen.
        assertTrue(base != HomeChannels.recentsSignature(5, rows, listOf("https://x/a.jpg")))
        // Y si llega una y falta otra, el contador sería el mismo pero no la firma.
        assertTrue(HomeChannels.recentsSignature(5, rows, listOf("https://x/a.jpg")) != HomeChannels.recentsSignature(5, rows, listOf("https://x/b.jpg")))
        assertEquals(HomeChannels.recentsSignature(5, rows, listOf("https://x/a.jpg", "https://x/b.jpg")), HomeChannels.recentsSignature(5, rows, listOf("https://x/b.jpg", "https://x/a.jpg")))
    }

    @Test
    fun `solo se omite si la firma coincide y el canal conserva sus programas`() {
        // Control bueno: todo igual y los 2 programas siguen en el canal.
        assertTrue(HomeChannels.canSkip("firma", "firma", existingPrograms = 2, wanted = 2))
        // Controles malos: cada condición por separado obliga a rehacer.
        assertTrue(!HomeChannels.canSkip(null, "firma", 2, 2))
        assertTrue(!HomeChannels.canSkip("otra", "firma", 2, 2))
        assertTrue(!HomeChannels.canSkip("firma", "firma", 1, 2))
        assertTrue(!HomeChannels.canSkip("firma", "firma", -1, 2))
    }
}

// ===== FitColumnsTest.kt =====
/** Las tarjetas se estiran (o encogen) poco para llenar la fila. */
class FitColumnsTest {
    @Test
    fun `llena la fila sin huecos`() {
        val (columns, width) = fitColumns(availableDp = 700f, cardDp = 180f, gapDp = 12f)
        assertEquals(700f, columns * width + 12f * (columns - 1), 0.01f)
    }

    @Test
    fun `con una fila de justo 3 tarjetas no cambia nada`() {
        val (columns, width) = fitColumns(availableDp = 3 * 180f + 2 * 12f, cardDp = 180f, gapDp = 12f)
        assertEquals(3, columns)
        assertEquals(180f, width, 0.01f)
    }

    @Test
    fun `elige entre estirar o encoger la que deja la tarjeta mas cerca de su tamano`() {
        // 3 columnas darían ~215 (+20 %) y 4 darían ~158 (-12 %): gana 4.
        val (columns, width) = fitColumns(availableDp = 3 * 215f + 24f, cardDp = 180f, gapDp = 12f)
        assertEquals(4, columns)
        assertTrue(width in 150f..180f)
    }

    @Test
    fun `una pantalla mas estrecha que una tarjeta deja una columna`() {
        assertEquals(1 to 180f, fitColumns(availableDp = 100f, cardDp = 180f, gapDp = 12f))
    }
}

// ===== HomeChannelsTest.kt =====
class HomeChannelsTest {
    private fun record(eid: Int, slug: String, number: Double) = Record(eid, slug, number, eid, slug, 0L, seriesId = slug.hashCode().toLong().and(0xffff) + 1)
    private val started = KaizenProgress.Progress(60_000, 1_400_000)

    @Test fun onlyStartedChaptersAndOncePerSeriesEpisode() {
        val history = listOf(record(1, "a", 1.0), record(2, "a", 1.0), record(3, "b", 2.0), record(4, "c", 1.0))
        val picked = HomeChannels.pickContinue(history, history.filter { it.eid in setOf(1, 2, 3) }.associate { it.progressKey() to started })
        assertEquals(listOf(1, 3), picked.map { it.first.eid })
    }

    @Test fun limitedToTwentyRows() {
        val history = (1..30).map { record(it, "s$it", 1.0) }
        assertEquals(20, HomeChannels.pickContinue(history, history.associate { it.progressKey() to started }).size)
    }
}

// ===== UniqueKeysTest.kt =====
class UniqueKeysTest {

    @Test
    fun `las claves distintas no cambian`() {
        assertEquals(listOf("a", "b", "c"), uniqueKeys(listOf("a", "b", "c")))
    }

    @Test
    fun `una clave repetida lleva un sufijo y el orden se conserva`() {
        assertEquals(listOf("a", "b", "a#2", "a#3"), uniqueKeys(listOf("a", "b", "a", "a")))
    }

    @Test
    fun `el resultado nunca repite una clave`() {
        val keys = uniqueKeys(listOf("x", "x", "x#2", "y", "x"))
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.first() == "x")
    }

    @Test
    fun `una lista vacia da una lista vacia`() {
        assertEquals(emptyList<String>(), uniqueKeys(emptyList()))
    }
}
