package app.kaizen.source

import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.logError
import app.kaizen.commons.runCatchingCoop
import app.kaizen.database.CatalogDB
import app.kaizen.database.UserDB
import app.kaizen.pojos.av1.Series
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Qué anime de AniList es el de una serie, según las filas de [AnimeFuente] de sus fichas. */
internal sealed interface AnilistResolution {
    data object None : AnilistResolution

    /** Fichas de la misma serie con animes distintos: no se usa AniList hasta que el usuario lo resuelva (etapa E). */
    data class Conflict(val ids: Set<Long>) : AnilistResolution

    data class Anime(val id: Long) : AnilistResolution
}

internal object AnilistFicha {
    /** Los animes de las filas `aplicado` de estas fichas; las demás (por revisar, sin resultado) no cuentan. */
    fun appliedIds(fichas: List<Pair<String, String>>, row: (String, String) -> AnimeFuente?): Set<Long> =
        fichas.mapNotNull { (fuente, slug) -> row(fuente, slug)?.takeIf { it.estado == AnimeFuente.APLICADO }?.anilistId }.toSet()

    /** Un solo anime para todas las fichas: se usa; ninguno: nada; varios: conflicto. */
    fun resolve(fichas: List<Pair<String, String>>, row: (String, String) -> AnimeFuente?): AnilistResolution {
        val ids = appliedIds(fichas, row)
        return when (ids.size) {
            0 -> AnilistResolution.None
            1 -> AnilistResolution.Anime(ids.first())
            else -> AnilistResolution.Conflict(ids)
        }
    }

    /** Los datos de AniList de la serie de esta ficha de fuente; null si no hay anime enlazado, hay conflicto, no está descargada o falla algo. */
    suspend fun of(source: String, slug: String): FichaData? {
        if (!KaizenSettings.anilistFicha) return null
        return runCatchingCoop {
            withContext(Dispatchers.IO) {
                val fuentes = UserDB.INSTANCE.animeFuenteDao()
                when (val found = resolve(fichasOf(source, slug)) { f, s -> fuentes.get(f, s) }) {
                    is AnilistResolution.Anime -> CatalogDB.INSTANCE.animeFichaDao().get(found.id)
                        ?.takeIf { it.consulta == AnilistClient.FICHA_VERSION }?.let(FichaData::ofAnilist)
                    else -> null
                }
            }
        }.onFailure(::logError).getOrNull()
    }

    /** Las fichas de fuente que forman la serie de esta (ella misma si el libro aún no la tiene). */
    private fun fichasOf(source: String, slug: String): List<Pair<String, String>> =
        SeriesLedger.fichasOfKeys(listOf(seriesIdOf(source, slug).toString())).ifEmpty { setOf(source to slug) }.toList()

    /** Cada ficha de la serie con su fila de enlace con AniList (null si aún no se ha revisado), para la pestaña «Fuentes». */
    suspend fun links(source: String, slug: String): List<Pair<Pair<String, String>, AnimeFuente?>> = runCatchingCoop {
        withContext(Dispatchers.IO) {
            val fuentes = UserDB.INSTANCE.animeFuenteDao()
            fichasOf(source, slug).map { it to fuentes.get(it.first, it.second) }
        }
    }.onFailure(::logError).getOrDefault(emptyList())

    /**
     * La serie con la nota de AniList (de 0 a 100 pasada a estrellas de 0 a 5); la de las fuentes se descarta siempre. Sin anime enlazado no hay
     * nota (0). Con la sinopsis de Animeoteca si la tiene y el ajuste está encendido; apagado, la sinopsis queda como la dio la fuente.
     */
    fun applied(series: Series, data: FichaData?): Series {
        val scored = series.copy(rateStars = AnilistScores.stars(data?.puntuacion)?.toFloat() ?: 0f, rateCount = 0)
        if (!KaizenSettings.anilistFicha) return scored
        return data?.es?.sinopsis?.takeIf { it.isNotBlank() }?.let { scored.copy(description = it) } ?: scored
    }

}
