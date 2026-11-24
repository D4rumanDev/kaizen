package app.kaizen.commons

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Qué portadas van a la caché superior, a la normal o a ninguna; el recorte por espacio; y que lo repetido en el registro se cuenta en vez de escribirse. */
class CoverTiersTest {
    @get:Rule val folder = TemporaryFolder()

    private val originalRoot = CoverPipeline.rootProvider

    @Before fun setUp() { CoverPipeline.rootProvider = { folder.root } }
    @After fun tearDown() { CoverPipeline.rootProvider = originalRoot }

    private fun tier(favorite: Boolean = false, status: String? = null, score: Int? = null, year: Int? = null, cutoff: Int = 85) =
        CoverTiers.of(favorite, status, score, year, thisYear = 2026, cutoff = cutoff)

    @Test fun `favorita, en emision o de las mejores va a la cache superior`() {
        assertEquals(CoverTier.HOT, tier(favorite = true, status = "FINISHED", year = 1998))
        assertEquals(CoverTier.HOT, tier(status = "RELEASING", year = 2005))
        assertEquals(CoverTier.HOT, tier(status = "HIATUS"))
        assertEquals(CoverTier.HOT, tier(status = "NOT_YET_RELEASED"))
        assertEquals(CoverTier.HOT, tier(status = "FINISHED", score = 85, year = 2001))
    }

    @Test fun `terminada hace diez anos o mas y sin merito no tiene cache`() {
        assertEquals(CoverTier.NONE, tier(status = "FINISHED", score = 70, year = 2016))
        assertEquals(CoverTier.WARM, tier(status = "FINISHED", score = 70, year = 2017))
    }

    @Test fun `la duda nunca deja una portada sin cache`() {
        assertEquals(CoverTier.WARM, tier())
        assertEquals(CoverTier.WARM, tier(status = "FINISHED"))
        assertEquals(CoverTier.WARM, tier(status = "CANCELLED", year = 1990))
        assertEquals(CoverTier.WARM, tier(score = 84))
    }

    @Test fun `hasta calcular el corte nadie esta entre las mejores`() {
        assertEquals(CoverTier.WARM, CoverTiers.of(false, null, 100, null, 2026, cutoff = 101))
    }

    @Test fun `una portada guardada se encuentra en cualquiera de las dos carpetas y se olvida`() {
        val url = "https://cdn.example.com/a.jpg"
        assertNull(CoverPipeline.cached(url))
        val warm = File(folder.root, "warm").apply { mkdirs() }.let { File(it, CoverPipeline.nameOf(url)) }.apply { writeBytes(ByteArray(10)) }
        assertEquals(warm, CoverPipeline.cached(url))
        CoverPipeline.forget(url)
        assertNull(CoverPipeline.cached(url))
    }

    @Test fun `al pasar del tope se quita lo menos usado primero`() {
        val dir = File(folder.root, "warm").apply { mkdirs() }
        val files = (0 until 10).map { i -> File(dir, "$i.webp").apply { writeBytes(ByteArray(100)); setLastModified(1_000L + i * 1_000) } }
        CoverPipeline.trim(CoverTier.WARM, max = 500)
        val left = files.filter { it.exists() }
        assertTrue(left.size <= 4)
        assertTrue("se conserva lo más reciente", files.last().exists())
        assertFalse("se quita lo más antiguo", files.first().exists())
    }

    @Test fun `sin cache de disco la portada no se guarda y se avisa de que es por diseno`() = kotlinx.coroutines.runBlocking {
        val out = CoverPipeline.obtain("https://cdn.example.com/old.jpg", CoverTier.NONE)
        assertTrue(out is CoverPipeline.Outcome.Failed && out.error === CoverPipeline.NotStored)
        assertEquals(0L, CoverPipeline.sizeBytes())
    }

    @Test fun `lo repetido se calla dentro de la ventana y la siguiente lleva la cuenta`() {
        val r = DebugLog.Repeats(window = 60_000)
        assertEquals(0, r.admit("AniList", "Avance: 10 de 50 fichas", 0))
        assertNull(r.admit("AniList", "Avance: 11 de 50 fichas", 1_000))
        assertNull(r.admit("AniList", "Avance: 12 de 50 fichas", 2_000))
        assertEquals("otra etiqueta no cuenta como repetida", 0, r.admit("Series", "Avance: 12 de 50 fichas", 2_000))
        assertEquals("pasada la ventana se escribe con lo que se calló", 2, r.admit("AniList", "Avance: 20 de 50 fichas", 61_000))
        assertEquals(0, r.admit("AniList", "Otra cosa", 61_500))
    }
}
