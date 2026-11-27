package app.kaizen.source

import app.kaizen.pojos.av1.SeriesSummary

/**
 * Las reglas que limpian las uniones con AniList, en un solo sitio (puras, sin base de datos ni red). Las usan el agrupado del catálogo
 * ([SeriesResolver]) y la revisión de enlaces ([AnilistReview]); medidas con `python tools/audit/audit.py quality`.
 *  - Series partidas: dos fichas de una misma fuente con el mismo anime de AniList son la misma serie si dicen la misma temporada y formato
 *    ([sameShapePerSource]).
 *  - Fichas sin enlace: una ficha por revisar cuyo candidato es el anime de una hermana de su serie se enlaza con él ([completions]).
 *  - Uniones que llegan de fuera: una copia o sincronización no une lo que contradice una separación del usuario ni juntan animes distintos
 *    de AniList ([safeGroups]).
 */
internal object JoinRules {
    /** Desde este parecido, un candidato que ya está enlazado en otra ficha de la serie, o que el usuario ya confirmó, se da por bueno sin volver a preguntar. */
    // Como «probable» del matcher: con otra temporada el parecido no pasa de 0,75, así que esas siguen preguntándose.
    const val CONFIRMED_FROM = 0.85

    /** Dos slugs de una misma fuente dicen la misma temporada y el mismo formato («x» y «x-tv» sí; «x» y «x-ova», o «x» y «x-2», no). */
    fun sameShape(slugA: String, slugB: String): Boolean {
        fun parts(slug: String) = AnilistMatcher.split(AnilistMatcher.normalize(slug.replace('-', ' ')))
        val a = parts(slugA)
        val b = parts(slugB)
        return a.season == b.season && a.format == b.format
    }

    /** Las fichas de [members] que son de una fuente que [group] ya tiene dicen la misma temporada y el mismo formato que las de [group] de esa fuente. */
    fun sameShapePerSource(group: List<Pair<AnimeSource, SeriesSummary>>, members: List<Pair<AnimeSource, SeriesSummary>>): Boolean =
        members.all { (source, item) -> group.filter { it.first.id == source.id }.all { sameShape(it.second.slug, item.slug) } }

    /**
     * Las fichas por revisar de una serie que ya tiene fichas enlazadas ([inSeries], sus animes de AniList) cuyo candidato es uno de esos animes
     * con parecido suficiente ([CONFIRMED_FROM]): una hermana ya confirmó que ese anime es el de la serie. Cada ficha con su propio candidato;
     * las del usuario no se tocan. [acceptable] juzga la fila tal como se guardaría (ver [AnilistReview.adoptions]).
     */
    fun completions(work: SeriesWork, row: (String, String) -> AnimeFuente?, inSeries: Set<Long>, at: Long, acceptable: (AnimeFuente) -> Boolean): List<AnimeFuente> =
        work.fichas.mapNotNull { (f, s) ->
            val own = row(f, s)?.takeIf { it.estado == AnimeFuente.PENDIENTE && it.origen != AnimeFuente.USUARIO && it.anilistId in inSeries && it.puntuacion >= CONFIRMED_FROM } ?: return@mapNotNull null
            AnimeFuente(f, s, own.anilistId, AnimeFuente.APLICADO, AnimeFuente.AUTO, "mismo-id", own.puntuacion, at).takeIf(acceptable)
        }

    /**
     * Qué grupos de fichas de una copia (o de otro equipo) se pueden unir sin contradecir lo que este equipo ya sabe. Un grupo que no pasa se
     * descarta entero: unir mal es peor que no unir, y la unión se puede rehacer a mano.
     *
     * Se descarta un grupo si:
     *  - dos de sus fichas tienen hoy series que el usuario separó a propósito ([isSplit] recibe los dos ids de serie); o
     *  - dos de sus fichas están enlazadas a animes distintos de AniList ([anilistOf] da solo los enlaces aplicados; sin enlace no cuenta).
     * Una ficha que el libro aún no conoce ([idOf] null) no puede chocar con una separación.
     */
    fun safeGroups(
        groups: List<List<Pair<String, String>>>,
        idOf: (source: String, slug: String) -> Long?,
        isSplit: (a: String, b: String) -> Boolean,
        anilistOf: (source: String, slug: String) -> Long?
    ): List<List<Pair<String, String>>> = groups.filter { group ->
        val fichas = group.distinct()
        val ids = fichas.mapNotNull { (source, slug) -> idOf(source, slug) }.distinct()
        val splitInside = ids.any { a -> ids.any { b -> a < b && isSplit(a.toString(), b.toString()) } }
        // Solo se consulta AniList si la separación no ha bastado para descartarlo: es una lectura por ficha.
        !splitInside && fichas.mapNotNull { (source, slug) -> anilistOf(source, slug) }.distinct().size <= 1
    }
}
