package app.kaizen.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El juicio de las series dudosas con AniList es uno solo: lo comparten la pantalla de revisión y la revisión por ADB. */
class AnilistAutoReviewTest {
    private fun facts(episodes: Int?, airing: Boolean, year: Int?, mal: Long? = null, duration: Int? = null, names: List<String> = emptyList()) =
        Catalog.SourceFacts(episodes, airing, emptyList(), null, year, null, mal, names, duration)

    @Test
    fun `las fichas de varias webs se juntan en un solo dato`() {
        val lf = LocalFacts.of(null, true, listOf(facts(12, false, 2015, mal = 7L), facts(13, true, 2014, duration = 24, names = listOf("A")), facts(null, false, null, names = listOf("A", "B"))))
        assertEquals(13, lf.episodes)
        assertEquals(true, lf.airing)
        assertEquals(2014, lf.year)
        assertEquals(7L, lf.mal)
        assertEquals(24, lf.duration)
        assertEquals(listOf("A", "B"), lf.names)
        assertTrue(lf.done)
    }

    @Test
    fun `el ano del catalogo manda sobre el de las fichas`() {
        val pick = Catalog.Pick("k", "Serie", emptyList(), year = 2020)
        assertEquals(2020, LocalFacts.of(pick, true, listOf(facts(12, false, 2015))).year)
    }

    @Test
    fun `sin fichas no hay datos y el airing queda sin saber`() {
        val lf = LocalFacts.of(null, false, emptyList())
        assertNull(lf.airing)
        assertNull(lf.episodes)
        assertNull(lf.year)
        assertFalse(lf.done)
    }

    @Test
    fun `el juicio se convierte en decision sin mezclar rechazo, aceptacion y duda`() {
        assertEquals(AutoOutcome.DISCARD, AutoOutcome.of(true, null))
        assertEquals(AutoOutcome.DISCARD, AutoOutcome.of(false, AnilistCompare.Auto.REJECT))
        assertEquals(AutoOutcome.ACCEPT, AutoOutcome.of(false, AnilistCompare.Auto.ACCEPT))
        // Un candidato que ya no se parece al título se descarta aunque el resto dijera aceptar.
        assertEquals(AutoOutcome.DISCARD, AutoOutcome.of(true, AnilistCompare.Auto.ACCEPT))
        assertEquals(AutoOutcome.ASK, AutoOutcome.of(false, null))
    }

    @Test
    fun `la prueba no escribe nada y aplicar solo acepta o descarta lo claro`() = kotlinx.coroutines.runBlocking {
        val calls = mutableListOf<String>()
        suspend fun run(outcome: AutoOutcome, apply: Boolean) =
            AutoOutcome.resolve(outcome, apply, accept = { calls += "accept" }, discard = { calls += "discard" })
        AutoOutcome.entries.forEach { run(it, apply = false) }
        assertTrue("la prueba no debe escribir: $calls", calls.isEmpty())
        assertEquals(AutoOutcome.ACCEPT, run(AutoOutcome.ACCEPT, apply = true))
        run(AutoOutcome.DISCARD, apply = true)
        run(AutoOutcome.ASK, apply = true)
        run(AutoOutcome.NO_DATA, apply = true)
        assertEquals(listOf("accept", "discard"), calls)
    }

    @Test
    fun `los contadores suman cada resultado y un fallo cuenta como sin datos`() {
        var p = AnilistAutoRunner.Progress(total = 5)
        listOf(AutoOutcome.ACCEPT, AutoOutcome.DISCARD, AutoOutcome.ASK, AutoOutcome.NO_DATA, null).forEach { p = p.counted(it) }
        assertEquals(5, p.done)
        assertEquals(1, p.accepted)
        assertEquals(1, p.discarded)
        assertEquals(1, p.ask)
        assertEquals(2, p.noData)
        assertTrue(p.line.contains("hechas=5") && p.line.contains("aceptarian=1") && p.line.contains("sin_datos=2"))
    }

    @Test
    fun `sin candidato no se descarta ni se decide nada`() {
        val lf = LocalFacts.of(null, true, emptyList())
        val judgement = AnilistAutoReview.judge("animeav1" to "bai-ri-cheng-wang", null, lf, null)
        // Sin la serie del catalogo el nombre sale del slug.
        assertEquals("Bai Ri Cheng Wang", judgement.localName)
        assertNull(judgement.titleScore)
        assertFalse(judgement.discard)
        assertNull(judgement.verdict)
        assertNull(judgement.compared.auto)
    }
}
