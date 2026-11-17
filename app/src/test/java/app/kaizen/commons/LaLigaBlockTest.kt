package app.kaizen.commons

import app.kaizen.domain.ConnectionStats
import app.kaizen.tv.ui.SourceState
import app.kaizen.tv.ui.SourcesHealth
import app.kaizen.tv.ui.sourceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** El aviso de bloqueo de LaLiga: lee la lista de IPs de hayahora.futbol (una por línea) y la cruza con las IPs de cada fuente. */
class LaLigaBlockTest {
    @Test
    fun `lee una IP por linea e ignora lo que no lo es`() {
        val text = "192.124.249.25\n  104.16.93.114 \n\n<html>error</html>\n2606:4700::6810:5d72\nno es una ip"
        assertEquals(setOf("192.124.249.25", "104.16.93.114", "2606:4700::6810:5d72"), LaLigaBlock.parse(text))
        assertTrue(LaLigaBlock.parse("").isEmpty())
    }

    @Test
    fun `una web esta bloqueada si alguna de sus IPs lo esta`() {
        val blocked = setOf("104.16.93.114")
        assertEquals(setOf("104.16.93.114"), LaLigaBlock.blockedAmong(listOf("104.16.93.114", "1.1.1.1"), blocked))
        assertTrue(LaLigaBlock.blockedAmong(listOf("1.1.1.1"), blocked).isEmpty())
    }

    @Test
    fun `la tarjeta de la fuente dice bloqueo de LaLiga y no no responde`() {
        val state = sourceState(true, 0L, null, null, false, { "12:00" }, laLiga = true)
        assertEquals(SourcesHealth.DOWN, state.health)
        assertTrue("LaLiga" in state.text && "E23" in state.text)
        // Una fuente desactivada sigue diciendo que lo está.
        assertEquals(SourceState(SourcesHealth.OK, "✖ Desactivada"), sourceState(false, 0L, null, null, false, { "12:00" }, laLiga = true))
    }

    @Test
    fun `la causa E23 esta en el catalogo con su numero y origen`() {
        val cause = ConnectionStats.Cause.LALIGA
        assertEquals("E23", cause.tag)
        assertEquals(ConnectionStats.Origin.NETWORK, cause.origin)
        assertEquals(1, ConnectionStats.Cause.entries.count { it.number == 23 })
    }
}
