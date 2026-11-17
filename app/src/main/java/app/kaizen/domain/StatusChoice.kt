package app.kaizen.domain

import app.kaizen.pojos.av1.Series
import app.kaizen.tv.ui.KaizenToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Elegir el estado de una serie a mano, con las mismas reglas desde la ficha y desde el menú de una tarjeta: **«Completada» marca todos los capítulos como vistos**, y si la serie sigue
 * **en emisión** no puede quedar completada (saldrán más capítulos): queda «Viendo» con todo lo emitido visto y se avisa; pasará a «Completada» sola cuando termine y esté todo visto
 * (`SeriesStatuses.autoCompleted`). Cualquier otro estado se pone tal cual.
 */
object StatusChoice {
    /**
     * En emisión: manda el estado de AniList si la serie está enlazada y lo trae (`RELEASING`, `HIATUS` o `NOT_YET_RELEASED` cuentan; `FINISHED` y `CANCELLED` no). Sin AniList, lo que
     * dice la fuente (`state`) o, si no, el calendario (sin calendario cuenta como en emisión: no se da por terminada). El «día de emisión» de la ficha no se usa: no es fiable.
     */
    suspend fun isAiring(series: Series): Boolean {
        val anilist = app.kaizen.source.AnilistFicha.of(series.source, series.slug)
        if (anilist?.estado != null) return !app.kaizen.source.FichaPolicy.finished(series, anilist)
        return series.state == 1 || StatusMigration.isAiring(series.name, series.slug)
    }

    /** Lo que pasa al elegir [status] en una serie cuya ficha ya se tiene: el estado que queda y el aviso para el usuario (null si no hace falta). */
    internal data class Result(val status: SeriesStatus, val message: String?)

    internal fun decide(chosen: SeriesStatus, airing: Boolean): Result = when {
        chosen != SeriesStatus.COMPLETED -> Result(chosen, null)
        airing -> Result(SeriesStatus.WATCHING, "Está en emisión (o no se pudo confirmar que haya terminado): se marcan como vistos los capítulos emitidos y queda «Viendo»; pasará a «Completada» al terminar y ver todo")
        else -> Result(SeriesStatus.COMPLETED, null)
    }

    /** Pone [chosen] a [series] (marcando todos los capítulos si es «Completada»). Escribe en la base de datos. */
    suspend fun pick(series: Series, chosen: SeriesStatus) = withContext(Dispatchers.IO) {
        if (chosen == SeriesStatus.COMPLETED) series.setAllSeen(true)
        val result = decide(chosen, if (chosen == SeriesStatus.COMPLETED) isAiring(series) else false)
        SeriesStatuses.set(series.name, result.status, series.source, series.slug, series.aid)
        result.message?.let { KaizenToast.toastLong(it) }
    }

    /**
     * Lo mismo desde una tarjeta, que no trae los capítulos: para «Completada» se abre la ficha (si no se puede, se pone el estado y se avisa de que los capítulos no se marcaron).
     */
    suspend fun pick(animeUrl: String, name: String, source: String, slug: String, aid: Int, chosen: SeriesStatus) = withContext(Dispatchers.IO) {
        if (chosen != SeriesStatus.COMPLETED) return@withContext SeriesStatuses.set(name, chosen, source, slug, aid)
        KaizenToast.toast("Marcando capítulos…")
        val series = runCatching { Series.open(animeUrl) }.getOrNull()
        if (series != null) return@withContext pick(series, chosen)
        SeriesStatuses.set(name, chosen, source, slug, aid)
        KaizenToast.toastLong("No se pudo cargar la ficha: queda «Completada», pero los capítulos no se han marcado")
    }
}
