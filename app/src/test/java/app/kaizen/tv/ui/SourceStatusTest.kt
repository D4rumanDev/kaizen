package app.kaizen.tv.ui

import app.kaizen.commons.HostGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** La tarjeta de una fuente: piloto y texto salen del mismo dato, y mientras se mide no dice «Activa». */
class SourceStatusTest {
    private val clock: (Long) -> String = { "12:00" }
    private fun ok(ms: Long, code: Int? = 200) = HostGate.CheckResult(true, code, ms, null)

    private fun state(
        active: Boolean = true, pausedUntil: Long = 0L, result: HostGate.CheckResult? = null,
        failure: Pair<Long, String>? = null, measuring: Boolean = false
    ) = sourceState(active, pausedUntil, result, failure, measuring, clock)

    @Test
    fun `mientras se mide no pone texto ni dice activa`() {
        val s = state(measuring = true)
        assertEquals(SourcesHealth.CHECKING, s.health)
        assertEquals("", s.text)
    }

    @Test
    fun `sin medir y sin resultado invita a comprobar`() {
        val s = state()
        assertEquals(SourcesHealth.CHECKING, s.health)
        assertTrue("Comprobar" in s.text)
    }

    @Test
    fun `una fuente que responde rapido va en verde con su tiempo`() {
        assertEquals(SourceState(SourcesHealth.OK, "Responde · 320 ms"), state(result = ok(320)))
    }

    @Test
    fun `una fuente lenta o con error HTTP lleva el aviso aunque responda`() {
        assertEquals(SourcesHealth.PARTIAL, state(result = ok(SLOW_SOURCE.inWholeMilliseconds + 1)).health)
        assertEquals("Lenta · 2000 ms", state(result = ok(2_000)).text)
        val http = state(result = ok(300, code = 503))
        assertEquals(SourcesHealth.PARTIAL, http.health)
        assertTrue("503" in http.text)
        // Justo en el límite todavía no es lenta.
        assertEquals(SourcesHealth.OK, state(result = ok(SLOW_SOURCE.inWholeMilliseconds)).health)
    }

    @Test
    fun `una fuente que no responde va en rojo con la causa`() {
        val s = state(result = HostGate.CheckResult(false, null, 10_000, "tiempo agotado"))
        assertEquals(SourcesHealth.DOWN, s.health)
        assertEquals("No responde (tiempo agotado)", s.text)
    }

    @Test
    fun `la pausa manda sobre la medicion y un fallo reciente avisa`() {
        assertEquals(SourceState(SourcesHealth.DOWN, "En pausa hasta 12:00"), state(pausedUntil = 5L, result = ok(100)))
        val failed = state(failure = 1L to "x".repeat(200))
        assertEquals(SourcesHealth.PARTIAL, failed.health)
        // El mensaje del fallo se recorta a 80 caracteres.
        assertEquals("⚠ Fallo a las 12:00: ".length + 80, failed.text.length)
    }

    @Test
    fun `sin Internet manda sobre pausa, medicion y fallo y no culpa a la fuente`() {
        val net = app.kaizen.commons.NetworkState.Mode.NO_INTERNET
        val s = sourceState(true, 5L, HostGate.CheckResult(false, null, 10_000, "x"), 1L to "fallo", false, clock, network = net)
        assertEquals(SourceState(SourcesHealth.OFFLINE, "Sin Internet · se usa lo guardado"), s)
        val manual = sourceState(true, 0L, null, null, true, clock, network = app.kaizen.commons.NetworkState.Mode.OFFLINE_MANUAL)
        assertEquals(SourceState(SourcesHealth.OFFLINE, "Modo sin conexión · se usa lo guardado"), manual)
        // Una fuente desactivada sigue diciendo que está desactivada.
        assertEquals("✖ Desactivada", sourceState(false, 0L, null, null, false, clock, network = net).text)
        assertEquals("Sin conexión: se usa lo guardado", sourcesSummary(listOf(s, s)))
    }

    @Test
    fun `la tarjeta de red es solo un indicador del estado`() {
        assertEquals(SourcesHealth.OK, networkSummary(app.kaizen.commons.NetworkState.Mode.ONLINE).first)
        assertEquals(SourcesHealth.OFFLINE, networkSummary(app.kaizen.commons.NetworkState.Mode.NO_INTERNET).first)
        assertTrue("detectado" in networkSummary(app.kaizen.commons.NetworkState.Mode.NO_INTERNET).second)
        assertTrue("Modo sin conexión" in networkSummary(app.kaizen.commons.NetworkState.Mode.OFFLINE_MANUAL).second)
        // Es un indicador: ningún texto invita a pulsarla.
        app.kaizen.commons.NetworkState.Mode.entries.forEach { assertTrue("pulsa" !in networkSummary(it).second) }
    }

    @Test
    fun `una fuente desactivada no se mide`() {
        assertEquals("✖ Desactivada", state(active = false, measuring = true, result = ok(100)).text)
    }

    @Test
    fun `el resumen cuenta las que responden bien de las medidas`() {
        val good = SourceState(SourcesHealth.OK, "")
        val slow = SourceState(SourcesHealth.PARTIAL, "")
        val pending = SourceState(SourcesHealth.CHECKING, "")
        assertEquals("", sourcesSummary(emptyList()))
        assertEquals("", sourcesSummary(listOf(pending, pending)))
        assertEquals("2 de 3 responden bien", sourcesSummary(listOf(good, good, slow)))
        assertEquals("1 de 3 responden bien", sourcesSummary(listOf(good, slow, pending)))
    }
}
