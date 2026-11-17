package app.kaizen.commons

import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El recuento de peticiones a cada web por día (solo conocimiento interno): separa uso y segundo plano y cuenta cuántas veces se pidió la misma dirección. */
class CallLedgerTest {
    private val day = "2026-11-12"

    @Test
    fun `cuenta por servidor y separa el uso del segundo plano`() {
        val ledger = CallLedger()
        repeat(3) { ledger.note(day, "https://animeav1.com/media/x", background = false) }
        repeat(5) { ledger.note(day, "https://www.animeav1.com/catalogo?page=$it", background = true) }
        ledger.note(day, "https://jkanime.net/a", background = false)
        val rows = ledger.report(day).hosts
        assertEquals(listOf("animeav1.com", "jkanime.net"), rows.map { it.host })
        assertEquals(3L, rows[0].foreground)
        assertEquals(5L, rows[0].background)
        assertEquals(8L, rows[0].total)
    }

    @Test
    fun `la misma direccion sin parametros se cuenta junta`() {
        val ledger = CallLedger()
        ledger.note(day, "https://animeflv.one/anime/x?a=1", false)
        ledger.note(day, "https://animeflv.one/anime/x?a=2#top", false)
        ledger.note(day, "https://animeflv.one/anime/y", false)
        val urls = ledger.report(day).urls
        assertEquals("animeflv.one/anime/x", urls[0].url)
        assertEquals(2L, urls[0].count)
        assertEquals(1L, urls[1].count)
    }

    @Test
    fun `lo que no es una direccion web no se cuenta`() {
        val ledger = CallLedger()
        ledger.note(day, "about:blank", false)
        ledger.note(day, "javascript:void(0)", false)
        assertTrue(ledger.report(day).hosts.isEmpty())
        assertNull(ledger.hostOf("file:///x"))
    }

    @Test
    fun `cada dia va aparte y se olvidan los mas viejos`() {
        val ledger = CallLedger(keepDays = 2)
        ledger.note("2026-11-10", "https://a.com/x", false)
        ledger.note("2026-11-11", "https://a.com/x", false)
        ledger.note("2026-11-12", "https://a.com/x", false)
        assertTrue(ledger.report("2026-11-10").hosts.isEmpty())
        assertEquals(1L, ledger.report("2026-11-11").hosts.single().total)
        assertEquals(1L, ledger.report("2026-11-12").hosts.single().total)
    }

    @Test
    fun `con demasiados servidores o direcciones el resto se agrupa`() {
        val ledger = CallLedger(maxHosts = 2, maxUrls = 2)
        listOf("a.com", "b.com", "c.com", "d.com").forEach { ledger.note(day, "https://$it/p", false) }
        val report = ledger.report(day)
        assertEquals(setOf("a.com", "b.com", "(otros)"), report.hosts.map { it.host }.toSet())
        assertEquals(2L, report.hosts.first { it.host == "(otros)" }.total)
        assertEquals(2, report.urls.size)
        // Un servidor ya conocido sigue contando aunque se haya llegado al tope.
        ledger.note(day, "https://a.com/p", false)
        assertEquals(2L, ledger.report(day).hosts.first { it.host == "a.com" }.total)
    }

    @Test
    fun `el bucle local no cuenta y las credenciales no se guardan`() {
        val ledger = CallLedger()
        ledger.note(day, "http://127.0.0.1:8080/video", false)
        ledger.note(day, "https://user:clave@a.com/x?t=1", false)
        val report = ledger.report(day)
        assertEquals(listOf("a.com"), report.hosts.map { it.host })
        assertEquals(listOf("a.com/x"), report.urls.map { it.url })
    }

    @Test
    fun `el tope de servidores cuenta tambien los de solo segundo plano`() {
        val ledger = CallLedger(maxHosts = 2)
        ledger.note(day, "https://a.com/", true)
        ledger.note(day, "https://b.com/", true)
        ledger.note(day, "https://c.com/", true)
        assertEquals(setOf("a.com", "b.com", "(otros)"), ledger.report(day).hosts.map { it.host }.toSet())
    }

    @Test
    fun `tras recargar lo guardado se siguen respetando los topes`() {
        val first = CallLedger(maxHosts = 2, maxUrls = 2)
        first.note(day, "https://a.com/1", false)
        first.note(day, "https://b.com/1", false)
        val second = CallLedger(maxHosts = 2, maxUrls = 2)
        second.load(first.snapshot())
        second.note(day, "https://c.com/1", false)
        second.note(day, "https://a.com/1", false)
        val report = second.report(day)
        assertEquals(setOf("a.com", "b.com", "(otros)"), report.hosts.map { it.host }.toSet())
        assertEquals(2L, report.hosts.first { it.host == "a.com" }.total)
        assertEquals(2, report.urls.size)
    }

    @Test
    fun `las rutas solo se guardan de los servidores permitidos y los demas cuentan solo por servidor`() {
        val ledger = CallLedger(pathHosts = { it == "fuente.com" })
        ledger.note(day, "https://fuente.com/anime/x?a=1", false)
        ledger.note(day, "https://cdn.video.net/hls/TOKEN123/seg1.ts", false)
        val report = ledger.report(day)
        assertEquals(setOf("fuente.com", "cdn.video.net"), report.hosts.map { it.host }.toSet())
        assertEquals(listOf("fuente.com/anime/x"), report.urls.map { it.url })
    }

    @Test
    fun `la marca de segundo plano llega al codigo de dentro, tambien en otro hilo y se quita al salir`() = kotlinx.coroutines.runBlocking {
        assertFalse(RequestCounter.inBackground())
        kotlinx.coroutines.withContext(RequestCounter.Background) {
            assertTrue(RequestCounter.inBackground())
            assertTrue(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { RequestCounter.inBackground() })
            assertTrue(kotlinx.coroutines.coroutineScope { this.async(kotlinx.coroutines.Dispatchers.IO) { RequestCounter.inBackground() }.await() })
        }
        assertFalse(RequestCounter.inBackground())
        assertFalse(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { RequestCounter.inBackground() })
    }

    @Test
    fun `al cargar se descartan las rutas guardadas de servidores no permitidos`() {
        val before = CallLedger()
        before.note(day, "https://fuente.com/anime/x", false)
        before.note(day, "https://cdn.video.net/hls/TOKEN/seg1.ts", false)
        val after = CallLedger(pathHosts = { it == "fuente.com" })
        after.load(before.snapshot())
        val report = after.report(day)
        assertEquals(listOf("fuente.com/anime/x"), report.urls.map { it.url })
        assertEquals(setOf("fuente.com", "cdn.video.net"), report.hosts.map { it.host }.toSet())
    }

    @Test
    fun `lo guardado se recupera sumando`() {
        val ledger = CallLedger()
        ledger.note(day, "https://a.com/x", false)
        val other = CallLedger()
        other.load(ledger.snapshot())
        other.note(day, "https://a.com/x", true)
        val row = other.report(day).hosts.single()
        assertEquals(1L, row.foreground)
        assertEquals(1L, row.background)
    }
}
