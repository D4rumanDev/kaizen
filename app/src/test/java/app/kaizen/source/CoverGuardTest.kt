package app.kaizen.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverGuardTest {
    @Test
    fun elSlugSeLeeDeLasDireccionesQueLoLlevan() {
        assertEquals("chainsaw-man", CoverGuard.slugIn("https://cdn.jkdesa.com/assets/images/animes/image/chainsaw-man.jpg"))
        assertEquals("steinsgate", CoverGuard.slugIn("https://cdn.animegratis.net/anime/steinsgate/images/cover.webp"))
        assertEquals("sasaki-to-p-chan-temporada-2", CoverGuard.slugIn("https://monoschinos.st/assets/img/serie/imagen/sasaki-to-p-chan-temporada-2-1791392025.jpg"))
    }

    @Test
    fun lasDireccionesSinSlugNoDicenDeQueSerieSon() {
        assertNull(CoverGuard.slugIn("https://cdn.animeav1.com/covers/4453.jpg"))
        assertNull(CoverGuard.slugIn("https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx208025-JKvqWNr8rz8l.jpg"))
    }

    @Test
    fun unaPortadaDeOtraSerieNoValeYUnaPropiaSi() {
        val owners = mapOf("tensei-kizoku-kantei-skill-de-nariagaru" to listOf("3810"), "chainsaw-man" to listOf("59"))
        val same = { a: String, b: String -> a == b }
        // La de Tensei Kizoku asignada a Chainsaw Man (id 59): no vale.
        assertFalse(CoverGuard.fits("https://cdn.jkdesa.com/assets/images/animes/image/tensei-kizoku-kantei-skill-de-nariagaru.jpg", "59", { owners[it].orEmpty() }, same))
        assertTrue(CoverGuard.fits("https://cdn.jkdesa.com/assets/images/animes/image/chainsaw-man.jpg", "59", { owners[it].orEmpty() }, same))
    }

    @Test
    fun siNadieConoceElSlugOLaDireccionNoLoLlevaNoHayNadaQueLaContradiga() {
        val same = { a: String, b: String -> a == b }
        assertTrue(CoverGuard.fits("https://cdn.jkdesa.com/assets/images/animes/image/desconocida.jpg", "59", { emptyList() }, same))
        assertTrue(CoverGuard.fits("https://cdn.animeav1.com/covers/4453.jpg", "59", { listOf("3810") }, same))
    }

    @Test
    fun unaUnionDelUsuarioHaceQueLaPortadaDelOtroGrupoValga() {
        // Las series 3810 y 59 están unidas por el usuario: son la misma serie para las reglas.
        val same = { a: String, b: String -> setOf(a, b) == setOf("59", "3810") || a == b }
        assertTrue(CoverGuard.fits("https://cdn.jkdesa.com/assets/images/animes/image/otra.jpg", "59", { listOf("3810") }, same))
    }
}
