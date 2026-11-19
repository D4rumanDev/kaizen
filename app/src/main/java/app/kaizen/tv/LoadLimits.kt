package app.kaizen.tv

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Cuánto se espera a una carga de datos antes de darla por atascada. Un solo plazo para todas las pantallas (listas paginadas, «Mejores en emisión», horario, recomendaciones):
 * pasado este tiempo la carga se corta (o, si no se puede cortar, se avisa) y se ofrece reintentar, en vez de dejar la pantalla esperando sin fin con una web colgada.
 */
internal object LoadLimits {
    val DATA: Duration = 25.seconds

    /**
     * Abrir la ficha de una serie: `openAnime` prueba la fuente de la serie y, si falla, las demás **una tras otra**, así que el plazo cubre la suma y no puede ser el de una sola carga.
     * Más largo que [DATA] para no dejar sin ficha, en una tele lenta, series que antes salían (pasado el plazo la pantalla avisa y se cierra).
     */
    val OPEN_SERIES: Duration = 45.seconds
}
