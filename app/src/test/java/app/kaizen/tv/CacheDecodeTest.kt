package app.kaizen.tv

import app.kaizen.home.DailyRecommendations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Lo guardado en las cachés nuevas (Mejores en emisión y Recomendados del día) puede estar corrupto, incompleto o ser de otro día: nunca debe tumbar la sección. */
class CacheDecodeTest {
    private val item = """{"aid":1,"name":"Naruto","slug":"naruto","category":1,"source":"animeav1","score":4.5,"votes":3}"""

    @Test fun `mejores en emision lee lo guardado y valido`() {
        val json = """{"at":1,"sources":["animeav1"],"items":[$item]}"""
        assertNotNull(BestCache.decode(json, listOf("animeav1")))
    }

    @Test fun `mejores en emision descarta un json sin campos o vacio sin lanzar`() {
        assertNull(BestCache.decode("{}", listOf("animeav1")))
        assertNull(BestCache.decode("""{"at":1,"sources":["animeav1"]}""", listOf("animeav1")))
        assertNull(BestCache.decode("""{"at":1,"sources":["animeav1"],"items":[]}""", listOf("animeav1")))
        assertNull(BestCache.decode(null, listOf("animeav1")))
    }

    @Test fun `mejores en emision no vale si cambiaron las fuentes activas`() {
        val json = """{"at":1,"sources":["animeav1"],"items":[$item]}"""
        assertNull(BestCache.decode(json, listOf("animeav1", "jkanime")))
        // El orden de las fuentes no importa.
        val two = """{"at":1,"sources":["animeav1","jkanime"],"items":[$item]}"""
        assertNotNull(BestCache.decode(two, listOf("jkanime", "animeav1")))
    }

    @Test fun `un json que no se puede leer lanza el error de gson para que load lo recoja`() {
        assertThrows(RuntimeException::class.java) { BestCache.decode("esto no es json", listOf("animeav1")) }
    }

    @Test fun `recomendados descarta lo guardado sin tanda o sin recientes`() {
        assertNull(DailyRecommendations.decode("{}"))
        assertNull(DailyRecommendations.decode("""{"day":5,"items":[$item]}"""))
        assertNull(DailyRecommendations.decode("""{"day":5,"recent":[]}"""))
        assertNull(DailyRecommendations.decode(null))
    }

    @Test fun `recomendados solo sirve la tanda del mismo dia`() {
        val stored = DailyRecommendations.decode("""{"day":100,"items":[$item],"recent":["bleach"]}""")
        assertEquals(1, DailyRecommendations.itemsFor(stored, 100)?.size)
        // Otro día: se rehace. Un día guardado en el futuro (reloj atrasado) tampoco vale.
        assertNull(DailyRecommendations.itemsFor(stored, 101))
        assertNull(DailyRecommendations.itemsFor(stored, 99))
        assertNull(DailyRecommendations.itemsFor(null, 100))
    }

    @Test fun `recomendados no sirve una tanda vacia`() {
        val stored = DailyRecommendations.decode("""{"day":100,"items":[],"recent":[]}""")
        assertNull(DailyRecommendations.itemsFor(stored, 100))
    }
}
