package app.kaizen.tv.streaming

import app.kaizen.bypass.BypassStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class LoadStepsTest {
    private fun List<StepRow>.states() = map { it.state }

    @Test
    fun empiezaTodoEnEspera() {
        assertEquals(List(4) { StepState.PENDING }, LoadSteps.initial().states())
    }

    @Test
    fun avanzaEnOrden() {
        var rows = LoadSteps.initial()
        rows = LoadSteps.set(rows, LoadStep.CLOUDFLARE, StepState.DONE, "OK")
        rows = LoadSteps.set(rows, LoadStep.SERVERS, StepState.RUNNING)
        assertEquals(listOf(StepState.DONE, StepState.RUNNING, StepState.PENDING, StepState.PENDING), rows.states())
        assertEquals("OK", rows[0].detail)
    }

    @Test
    fun laFaseAnteriorSinEmpezarQuedaOmitida() {
        val rows = LoadSteps.set(LoadSteps.initial(), LoadStep.SERVERS, StepState.RUNNING)
        assertEquals(StepState.SKIPPED, rows[0].state)
    }

    @Test
    fun volverAUnaFaseAnteriorReiniciaLasSiguientes() {
        var rows = LoadSteps.initial()
        listOf(LoadStep.CLOUDFLARE, LoadStep.SERVERS, LoadStep.SERVER, LoadStep.CHAPTER).forEach { rows = LoadSteps.set(rows, it, StepState.DONE) }
        rows = LoadSteps.set(rows, LoadStep.SERVER, StepState.RUNNING, "otro")
        assertEquals(listOf(StepState.DONE, StepState.DONE, StepState.RUNNING, StepState.PENDING), rows.states())
    }

    @Test
    fun unFalloSeConserva() {
        val rows = LoadSteps.set(LoadSteps.set(LoadSteps.initial(), LoadStep.SERVERS, StepState.RUNNING), LoadStep.SERVERS, StepState.FAILED, "sin respuesta")
        assertEquals(StepRow(LoadStep.SERVERS, StepState.FAILED, "sin respuesta"), rows[1])
    }

    @Test
    fun cloudflareSegunElEstadoDelBypass() {
        fun of(state: BypassStatus.State, until: Long = 0L) = LoadSteps.cloudflare(BypassStatus.Snapshot(state, pausedUntil = until), now = 1_000L).first
        assertEquals(StepState.DONE, of(BypassStatus.State.OK))
        assertEquals(StepState.DONE, of(BypassStatus.State.NOT_NEEDED))
        assertEquals(StepState.DONE, of(BypassStatus.State.UNKNOWN))
        assertEquals(StepState.WARN, of(BypassStatus.State.RUNNING))
        assertEquals(StepState.WARN, of(BypassStatus.State.UNREACHABLE))
        assertEquals(StepState.WARN, of(BypassStatus.State.OK, until = 5_000L))
    }
}
