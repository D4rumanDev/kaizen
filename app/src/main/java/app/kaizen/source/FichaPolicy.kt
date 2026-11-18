package app.kaizen.source

import app.kaizen.pojos.av1.Series
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * Cuándo vale la pena volver a pedir una ficha a la web de su fuente (ver `docs/wiki/architecture.md`). Una serie finalizada con todos
 * sus capítulos ya no cambia: se sirve de la copia sin red. Una en emisión sí cambia (capítulos) y se renueva como siempre.
 * Con `enabled` en false («Datos de AniList en la ficha» apagado) todo se comporta como antes de la etapa C.
 */
internal object FichaPolicy {
    /** Copia de una serie en emisión: cuánto se sirve sin volver a pedirla (ajustable: [app.kaizen.commons.Timings]). */
    val AIRING_TTL: Duration get() = app.kaizen.commons.Timings.fichaAiring()

    /** Pasado esto, al servir la copia de una serie en emisión se renueva en segundo plano (10 min, o menos si la copia caduca antes). */
    val AIRING_REFRESH: Duration get() = minOf(10.minutes, AIRING_TTL)

    /** Serie finalizada y con todos sus capítulos, con AniList que lo confirma: la copia casi no caduca (ajustable). */
    val SETTLED_TTL: Duration get() = app.kaizen.commons.Timings.fichaSettled()

    /** Finalizada solo según la fuente (sin AniList): su estado se detecta a veces mal, así que se mira más a menudo. */
    val UNLINKED_TTL = 7.days

    /** Finalizada, pero la fuente tiene menos capítulos de los que dice AniList: se vuelve a mirar como mucho una vez al día. */
    val INCOMPLETE_TTL = 1.days

    /** La ficha completada con las demás fuentes se guarda solo un tiempo: una fuente que falló ese día dejaría relaciones sin unir. */
    val COMPLETE_TTL = 7.days

    /** AniList manda sobre la fuente (que puede ir con retraso); sin dato de AniList, el estado de la fuente (`state` 2 = finalizada). */
    fun finished(series: Series, anilist: FichaData?): Boolean = when (anilist?.estado) {
        "FINISHED", "CANCELLED" -> true
        "RELEASING", "NOT_YET_RELEASED", "HIATUS" -> false
        else -> series.state == 2
    }

    /** La fuente tiene al menos los capítulos que AniList da para la serie (o, sin ese dato, alguno). */
    fun complete(series: Series, anilist: FichaData?): Boolean =
        series.chapters.isNotEmpty() && (anilist?.episodios?.let { series.chapters.size >= it } ?: true)

    /** Finalizada y completa: no hay nada nuevo que pedir a la web. */
    fun settled(series: Series, anilist: FichaData?, enabled: Boolean): Boolean = enabled && finished(series, anilist) && complete(series, anilist)

    /** Cuánto se sirve la copia sin volver a pedirla. */
    fun ttl(series: Series, anilist: FichaData?, enabled: Boolean): Duration = when {
        !enabled -> AIRING_TTL
        settled(series, anilist, true) -> if (anilist == null) UNLINKED_TTL else SETTLED_TTL
        finished(series, anilist) -> INCOMPLETE_TTL
        else -> AIRING_TTL
    }

    /** Edad de la copia a partir de la cual, al servirla, se renueva en segundo plano. */
    fun refreshAfter(series: Series, anilist: FichaData?, enabled: Boolean): Duration = when {
        !enabled -> AIRING_REFRESH
        settled(series, anilist, true) -> Duration.INFINITE
        finished(series, anilist) -> 1.days
        else -> AIRING_REFRESH
    }
}
