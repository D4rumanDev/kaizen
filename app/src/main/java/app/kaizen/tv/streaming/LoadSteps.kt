package app.kaizen.tv.streaming

import app.kaizen.bypass.BypassStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Las fases de abrir un capítulo, en orden. */
enum class LoadStep(val label: String) {
    CLOUDFLARE("Cloudflare"),
    SERVERS("Servidores"),
    SERVER("Servidor"),
    CHAPTER("Capítulo")
}

enum class StepState { PENDING, RUNNING, DONE, WARN, FAILED, SKIPPED }

data class StepRow(val step: LoadStep, val state: StepState = StepState.PENDING, val detail: String = "")

/** Estado de las fases como lista inmutable: cada cambio devuelve la lista nueva (sin Android, se prueba en JVM). */
object LoadSteps {
    fun initial(): List<StepRow> = LoadStep.entries.map { StepRow(it) }

    /**
     * Pone [step] en [state]. Las fases anteriores que seguían en espera pasan a omitidas (no aplicaban), y las posteriores
     * vuelven a esperar: tras un servidor que falla se prueba otro, y el capítulo no puede seguir marcado como hecho.
     */
    fun set(rows: List<StepRow>, step: LoadStep, state: StepState, detail: String = ""): List<StepRow> = rows.map { row ->
        when {
            row.step == step -> StepRow(step, state, detail)
            row.step.ordinal < step.ordinal && row.state == StepState.PENDING -> row.copy(state = StepState.SKIPPED)
            row.step.ordinal > step.ordinal -> StepRow(row.step)
            else -> row
        }
    }

    /** Estado de la fase «Cloudflare» según el bypass: no hace ninguna petición, solo lee lo que ya se sabe. */
    fun cloudflare(b: BypassStatus.Snapshot, now: Long): Pair<StepState, String> = when {
        b.pausedUntil > now -> StepState.WARN to "Bypass en pausa hasta ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(b.pausedUntil))}"
        b.state == BypassStatus.State.OK -> StepState.DONE to "Verificado"
        b.state == BypassStatus.State.NOT_NEEDED -> StepState.DONE to "Sin desafío"
        b.state == BypassStatus.State.RUNNING -> StepState.WARN to "Verificando en segundo plano"
        b.state == BypassStatus.State.FAILED || b.state == BypassStatus.State.UNREACHABLE -> StepState.WARN to "Cloudflare no responde"
        else -> StepState.DONE to "Sin comprobar"
    }
}
