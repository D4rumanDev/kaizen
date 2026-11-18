package app.kaizen.commons

import app.kaizen.tv.settings.entryLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

/** Las imágenes que no existen no se vuelven a pedir en una semana, y el visor del registro enseña cada anotación entera. */
class MissLedgerTest {
    private var now = 1_000L
    private val ledger = MissLedger(ttl = 100.milliseconds, max = 3) { now }

    @Test
    fun `una imagen anotada no se vuelve a pedir hasta que caduca`() {
        assertFalse(ledger.isMissing("https://x/a.png"))
        assertTrue(ledger.mark("https://x/a.png"))
        assertTrue(ledger.isMissing("https://x/a.png"))
        now += 99
        assertTrue(ledger.isMissing("https://x/a.png"))
        now += 1
        assertFalse(ledger.isMissing("https://x/a.png"))
        assertEquals(0, ledger.size)
    }

    @Test
    fun `anotar otra vez la misma no cuenta como nueva y renueva la hora`() {
        assertTrue(ledger.mark("u"))
        now += 50
        assertFalse(ledger.mark("u"))
        now += 60
        assertTrue(ledger.isMissing("u"))
    }

    @Test
    fun `con mas del maximo se olvidan las mas antiguas`() {
        listOf("a", "b", "c", "d").forEach { ledger.mark(it); now += 1 }
        assertEquals(3, ledger.size)
        assertFalse(ledger.isMissing("a"))
        assertTrue(ledger.isMissing("d"))
    }

    @Test
    fun `lo guardado se recupera`() {
        ledger.mark("a")
        val other = MissLedger(100.milliseconds, 3) { now }
        other.load(ledger.snapshot())
        assertTrue(other.isMissing("a"))
    }

    @Test
    fun `los iconos que Fuentes prueba no son un fallo`() {
        assertTrue(ImageMisses.isProbe("https://animeflv.ar/apple-touch-icon.png"))
        assertTrue(ImageMisses.isProbe("https://jkanime.net/favicon.ico"))
        assertFalse(ImageMisses.isProbe("https://cdn.x/covers/1.jpg"))
    }

    @Test
    fun `el visor enseña cada anotacion entera en el panel del pie`() {
        val entry = app.kaizen.commons.DebugLog.Entry(
            "2026-11-12", "11:01:04", app.kaizen.commons.DebugLog.ERROR, "Carátulas", "E06 No encontrado (404)\nsegunda línea",
            listOf("Origen: la fuente", "Qué hacer: nada"), code = "E06", cause = "no-existe", origin = "SOURCE", context = "animeav1.com"
        )
        val rows = entryLines(listOf(entry))
        val full = rows.first { it.kind == app.kaizen.tv.settings.LogLine.Kind.ENTRY }.full
        assertTrue("segunda línea" in full)
        assertTrue("Qué hacer: nada" in full)
        assertTrue("E06" in full && "animeav1.com" in full)
        // La fila técnica de debajo lleva el mismo texto.
        assertEquals(full, rows.first { it.kind == app.kaizen.tv.settings.LogLine.Kind.DETAIL }.full)
        assertEquals("", rows.first { it.kind == app.kaizen.tv.settings.LogLine.Kind.DAY }.full)
    }
}
