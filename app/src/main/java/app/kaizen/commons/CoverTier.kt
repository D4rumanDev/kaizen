package app.kaizen.commons

import app.kaizen.database.CatalogDB
import coil3.Extras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cuánto se guarda una portada en el disco (ver [CoverPipeline]):
 *  - [HOT]: la caché superior; solo la desaloja el tope de espacio, empezando por la menos usada.
 *  - [WARM]: la normal, con su propio tope (más pequeño).
 *  - [NONE]: no se guarda en disco (solo vive en la memoria mientras se usa) y se baja cada vez.
 */
enum class CoverTier {
    HOT, WARM, NONE;

    companion object {
        /** Con este dato en la petición de Coil la imagen es una portada de serie y pasa por [CoverPipeline]; sin él (fotogramas, iconos), por el camino normal. */
        val KEY = Extras.Key<CoverTier?>(default = null)
    }
}

/** Las reglas que dan a cada portada su [CoverTier]. Puras, para probarlas sin Android. */
object CoverTiers {
    /** Cuántas series cuentan como «las mejores» (por nota de AniList). */
    const val TOP_N = 300

    /** Años desde el estreno a partir de los cuales una serie terminada se considera antigua. */
    const val OLD_YEARS = 10

    /** Nota mínima para estar entre las mejores; hasta que se calcula (101) ninguna lo está. */
    @Volatile var topCutoff: Int = 101
        internal set

    private val RUNNING = setOf("RELEASING", "HIATUS", "NOT_YET_RELEASED")

    /**
     * Favoritas, en emisión o entre las mejores: [CoverTier.HOT]. Terminadas hace [OLD_YEARS] años o más (y no de las anteriores): [CoverTier.NONE].
     * El resto, y todo lo que no se sabe (serie sin enlazar con AniList), [CoverTier.WARM]: la duda nunca deja a una portada sin caché.
     */
    fun of(favorite: Boolean, anilistStatus: String?, score: Int?, year: Int?, thisYear: Int, cutoff: Int = topCutoff): CoverTier = when {
        favorite -> CoverTier.HOT
        anilistStatus in RUNNING -> CoverTier.HOT
        score != null && score >= cutoff -> CoverTier.HOT
        anilistStatus == "FINISHED" && year != null && thisYear - year >= OLD_YEARS -> CoverTier.NONE
        else -> CoverTier.WARM
    }

    /** Calcula el corte de «las mejores» con las fichas ya descargadas (una consulta, sin red). Si hay menos de [TOP_N], todas las que tienen nota. */
    suspend fun refreshCutoff() = withContext(Dispatchers.IO) {
        runCatching {
            val dao = CatalogDB.INSTANCE.fichaExportDao()
            topCutoff = dao.noteAtRank(TOP_N - 1) ?: dao.noteAtRank(0)?.let { 0 } ?: 101
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; DebugLog.error("Portadas", "No se pudo calcular el corte de las mejores portadas", it) }
    }
}
