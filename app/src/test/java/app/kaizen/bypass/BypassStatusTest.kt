package app.kaizen.bypass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BypassStatusTest {
    private var now = 1_000_000L
    private val warnings = mutableListOf<String>()
    private val minute = 60_000L

    @Before
    fun setUp() {
        BypassStatus.storage = null
        BypassStatus.reset()
        warnings.clear()
        now = 1_000_000L
        BypassStatus.clock = { now }
        BypassStatus.onPause = { warnings += it }
    }

    private fun fail() {
        BypassStatus.start()
        now += 500
        BypassStatus.finish(BypassStatus.State.FAILED)
    }

    private fun pausedFor() = BypassStatus.snapshot.value.pausedUntil - now

    @Test
    fun mideLoQueTarda() {
        BypassStatus.start()
        now += 1_234
        BypassStatus.finish(BypassStatus.State.OK)
        val s = BypassStatus.snapshot.value
        assertEquals(BypassStatus.State.OK, s.state)
        assertEquals(1_234L, s.millis)
    }

    @Test
    fun dosFallosPausan2MinYDespuesUnoSoloPausaMasLarga() {
        fail()
        assertFalse(BypassStatus.coolingDown())
        fail()
        assertTrue(BypassStatus.coolingDown())
        assertEquals(2 * minute, pausedFor())
        assertEquals(1, warnings.size)

        now += 2 * minute + 1
        assertFalse(BypassStatus.coolingDown())
        fail()
        assertEquals(5 * minute, pausedFor())

        now += 5 * minute + 1
        fail()
        assertEquals(10 * minute, pausedFor())

        now += 10 * minute + 1
        fail()
        assertEquals(60 * minute, pausedFor())
        now += 60 * minute + 1
        fail()
        assertEquals(60 * minute, pausedFor())
    }

    @Test
    fun unExitoReiniciaElProtocolo() {
        repeat(2) { fail() }
        now += 2 * minute + 1
        BypassStatus.start()
        BypassStatus.finish(BypassStatus.State.OK)
        assertFalse(BypassStatus.coolingDown())
        fail()
        assertFalse(BypassStatus.coolingDown())
        fail()
        assertEquals(2 * minute, pausedFor())
    }

    @Test
    fun noAlcanzableEnPausaNoEscala() {
        repeat(2) { BypassStatus.unreachable() }
        assertEquals(2 * minute, pausedFor())
        repeat(5) { BypassStatus.unreachable() }
        assertEquals(2 * minute, pausedFor())
        assertEquals(1, warnings.size)
    }

    @Test
    fun cerrarAManoNoEsFallo() {
        repeat(5) { BypassStatus.start(); BypassStatus.cancel() }
        assertFalse(BypassStatus.coolingDown())
    }

    @Test
    fun respuestaNormalReiniciaLosFallosPrevios() {
        BypassStatus.unreachable()
        BypassStatus.notNeeded()
        BypassStatus.unreachable()
        assertFalse(BypassStatus.coolingDown())
        BypassStatus.unreachable()
        assertTrue(BypassStatus.coolingDown())
    }

    @Test
    fun respuestaNormalLevantaLaPausaYReiniciaLasPausasSeguidas() {
        repeat(2) { fail() }
        now += 2 * minute + 1
        fail()
        assertEquals(5 * minute, pausedFor())
        BypassStatus.notNeeded()
        assertFalse(BypassStatus.coolingDown())
        repeat(2) { fail() }
        assertEquals(2 * minute, pausedFor())
    }

    @Test
    fun cancelarConservaLaPausaYLimpiaElEstado() {
        repeat(2) { fail() }
        val until = BypassStatus.snapshot.value.pausedUntil
        BypassStatus.start()
        BypassStatus.cancel()
        val s = BypassStatus.snapshot.value
        assertEquals(BypassStatus.State.UNKNOWN, s.state)
        assertEquals(until, s.pausedUntil)
        assertEquals(0L, s.at)
    }

    @Test
    fun lasComprobacionesPreviasNoPisanUnBypassEnMarcha() {
        BypassStatus.start()
        BypassStatus.unreachable()
        BypassStatus.notNeeded()
        assertEquals(BypassStatus.State.RUNNING, BypassStatus.snapshot.value.state)
        now += 700
        BypassStatus.finish(BypassStatus.State.OK)
        assertEquals(700L, BypassStatus.snapshot.value.millis)
    }

    @Test
    fun noAlcanzableCuentaComoFallo() {
        repeat(2) { BypassStatus.unreachable() }
        assertTrue(BypassStatus.coolingDown())
    }
}
