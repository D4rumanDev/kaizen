package app.kaizen.tv.ui

import app.kaizen.commons.warp.WarpMode
import app.kaizen.commons.warp.WarpTunnel.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lo que dicen las tarjetas de WARP y de LaLiga de «Fuentes»: color y texto según el estado. */
class SourcesSummaryTest {
    private fun snap(mode: WarpMode, consent: Boolean = true, account: Boolean = true, up: Boolean = false) = Snapshot(mode, consent, account, up)

    @Test fun `con el tunel subido esta activo, sea cual sea el modo`() {
        val (health, text) = warpSummary(snap(WarpMode.ALWAYS, up = true))
        assertEquals(SourcesHealth.OK, health)
        assertTrue(text.startsWith("Túnel activo"))
        assertEquals(SourcesHealth.OK, warpSummary(snap(WarpMode.ON_BLOCK, up = true)).first)
    }

    @Test fun `apagado no es un fallo`() {
        assertEquals(SourcesHealth.CHECKING, warpSummary(snap(WarpMode.OFF)).first)
        assertTrue(warpSummary(snap(WarpMode.OFF)).second.startsWith("Apagado"))
    }

    @Test fun `en modo solo con bloqueo, esperar es lo normal y falta de permiso es un aviso`() {
        assertEquals(SourcesHealth.OK, warpSummary(snap(WarpMode.ON_BLOCK)).first)
        assertEquals(SourcesHealth.PARTIAL, warpSummary(snap(WarpMode.ON_BLOCK, consent = false)).first)
    }

    @Test fun `en modo siempre, un tunel bajado es un fallo y dice que falta`() {
        assertEquals(SourcesHealth.DOWN, warpSummary(snap(WarpMode.ALWAYS)).first)
        assertTrue(warpSummary(snap(WarpMode.ALWAYS, consent = false)).second.contains("permiso de VPN"))
        assertEquals(SourcesHealth.PARTIAL, warpSummary(snap(WarpMode.ALWAYS, account = false)).first)
    }

    @Test fun `sin leer todavia no afirma nada`() {
        assertEquals(SourcesHealth.CHECKING, warpSummary(null).first)
    }

    @Test fun `LaLiga distingue apagado, no se sabe, sin bloqueo y bloqueo`() {
        assertEquals("Aviso apagado", laLigaSummary(false, emptySet(), 5).second)
        assertEquals(SourcesHealth.CHECKING, laLigaSummary(true, null, 5).first)
        assertEquals(SourcesHealth.OK to "Sin bloqueo", laLigaSummary(true, emptySet(), 5))
        assertEquals(SourcesHealth.DOWN to "2 de 5 fuentes bloqueadas", laLigaSummary(true, setOf("a.net", "b.net"), 5))
    }
}
