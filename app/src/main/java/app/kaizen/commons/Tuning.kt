package app.kaizen.commons

import app.kaizen.domain.ConnectionStats
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration

/**
 * Reparte los ajustes de medida de [KaizenSettings] (tiempos, tamaños, días) a los módulos que no leen preferencias
 * (para que sigan probándose en JVM con sus valores de fábrica). Se aplica al arrancar y al cambiar un ajuste.
 */
object Tuning {
    /** Multiplicador de espera a fuentes y servidores (ver [KaizenSettings.patience]); lo leen [HostGate] y la carga del capítulo. */
    @Volatile var patience = 1f
        private set

    /** Tiempo base [base] ajustado por la paciencia. */
    fun patient(base: Duration): Duration = base * patience.toDouble()

    fun apply() {
        patience = KaizenSettings.patience.takeIf { it in KaizenSettings.PATIENCE_OPTIONS } ?: 1f
        FallbackDns.provider = FallbackDns.Provider.of(KaizenSettings.dnsProvider)
        KaizenProgress.minResumeMs = KaizenSettings.resumeAfterSeconds.coerceAtLeast(0) * 1_000L
        CacheFirstStrategy.maxAge = KaizenSettings.coverDays.coerceAtLeast(1).days
        DebugLog.maxBytes = KaizenSettings.logKb.coerceAtLeast(64) * 1024
        ConnectionStats.keepDays = KaizenSettings.statsDays.coerceAtLeast(7)
    }

    /** La opción que sigue a [current] en [options], dando la vuelta al final (un valor que ya no es opción empieza por la primera). */
    fun <T> next(options: List<T>, current: T): T = options[(options.indexOf(current) + 1) % options.size]

    /** Las gráficas no pueden abarcar más días de los que se guardan. */
    fun chartDays(): Int = KaizenSettings.chartDays.coerceIn(1, ConnectionStats.keepDays)

}
