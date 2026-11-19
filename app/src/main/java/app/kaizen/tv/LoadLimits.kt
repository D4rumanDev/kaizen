package app.kaizen.tv

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Cuánto se espera a una carga de datos antes de darla por atascada. Un solo plazo para todas las pantallas (listas paginadas, «Mejores en emisión», horario, recomendaciones):
 * pasado este tiempo la carga se corta (o, si no se puede cortar, se avisa) y se ofrece reintentar, en vez de dejar la pantalla esperando sin fin con una web colgada.
 */
internal object LoadLimits {
    val DATA: Duration = 25.seconds
}
