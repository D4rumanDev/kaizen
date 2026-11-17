package app.kaizen.source

import app.kaizen.commons.sync.RemoteStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las uniones con AniList que viajan entre equipos con el catálogo: qué enlaces se toman, el formato de los ficheros y los nombres de los tramos de fichas, sin red. */
class AnilistShareTest {
    private fun row(slug: String, id: Long?, estado: String = AnimeFuente.APLICADO, origen: String = AnimeFuente.AUTO) =
        AnimeFuente("animeflv", slug, id, estado, origen, "seguro", 1.0, 1L)

    @Test
    fun `toma los enlaces aplicados que faltan y no pisa los que ya hay`() {
        val local = listOf(
            row("a", 1), row("b", 2, origen = AnimeFuente.USUARIO), row("c", null, AnimeFuente.SIN_RESULTADO), row("d", 4, AnimeFuente.PENDIENTE),
            row("e", 5, AnimeFuente.PENDIENTE, AnimeFuente.USUARIO)
        ).associateBy { it.fuente to it.slug }
        val remote = listOf(row("a", 10), row("b", 20), row("c", 30), row("d", 40), row("e", 50), row("f", 60), row("g", null), row("h", 80, AnimeFuente.PENDIENTE))
        val take = AnilistShare.linksToTake(remote, local).associate { it.slug to it.anilistId }
        // a y b ya aplicados: no se tocan; c y d eran automáticos sin aplicar: se toman; e es del usuario: no; f es nueva; g y h no valen.
        assertEquals(mapOf<String, Long?>("c" to 30L, "d" to 40L, "f" to 60L), take)
    }

    @Test
    fun `los enlaces van y vuelven en el fichero`() {
        val rows = listOf(row("a", 1), row("b", 2, origen = AnimeFuente.USUARIO))
        assertEquals(rows, AnilistShare.decodeLinks(AnilistShare.encodeLinks(rows, 5L)))
        assertNull(AnilistShare.decodeLinks("no es un gzip".toByteArray()))
    }

    @Test
    fun `lee el tramo y las fichas que lleva el nombre de un fichero`() {
        assertEquals(Triple(3L, 120, "kaizen-anilist-fichas-3-120.jsonl.gz"), AnilistShare.parse("kaizen-anilist-fichas-3-120.jsonl.gz"))
        assertEquals(Triple(3L, 120, "kaizen-anilist-fichas-3-120.jsonl.gz"), AnilistShare.parse("/video/kaizen/kaizen-anilist-fichas-3-120.jsonl.gz"))
        assertNull(AnilistShare.parse("kaizen-anilist-fichas-3.jsonl.gz"))
        assertNull(AnilistShare.parse("kaizen-backup-20261101-100000.json"))
        assertEquals("kaizen-anilist-fichas-3-120.jsonl.gz", AnilistShare.bucketName(3, 120))
    }

    @Test
    fun `las fichas van y vuelven y un fichero cortado se rechaza`() {
        val ficha = AnimeFicha(7, null, AnilistClient.FICHA_VERSION, 9L, byteArrayOf(1, 2, 3), null, "Romaji", null, null, 2020, "TV", 80)
        val back = FichaExport.decode(FichaExport.encode(listOf(ficha)))
        assertNotNull(back)
        assertEquals(1, back!!.size)
        assertEquals(7L, back[0].anilistId)
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(back[0].json))
        assertNull(FichaExport.decode("basura".toByteArray()))
        val bytes = FichaExport.encode(listOf(ficha))
        assertNull(FichaExport.decode(bytes.copyOf(bytes.size / 2)))
    }

    @Test
    fun `un almacen vacio no rompe nada`() = runBlocking {
        val store = object : RemoteStore {
            override suspend fun list() = emptyList<String>()
            override suspend fun read(name: String): ByteArray? = null
            override suspend fun write(name: String, bytes: ByteArray) {}
        }
        assertTrue(store.list().isEmpty())
    }
}
