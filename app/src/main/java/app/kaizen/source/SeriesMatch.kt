package app.kaizen.source

/**
 * La única regla de «misma serie» para fichas que no comparten id: la usan el catálogo ([SeriesResolver]), los listados
 * ([SeriesDedupe]) y la unión de resultados de varias fuentes ([mergeSeries]), así una pareja se une en todos los sitios o en ninguno.
 *  - **Título:** el normalizado ([normalizeTitle]) es el mismo, o uno es el comienzo del otro si ambos pasan de 40 caracteres
 *    (la web recorta los muy largos), ver [sameTitle].
 *  - **Año:** iguales, o alguno desconocido. Dos fichas con el mismo título y años distintos son series distintas («Tokyo Ghoul»
 *    2010 y 2020).
 *  - **Fuente:** distinta. Dos fichas de una misma fuente con título parecido (una serie y su película) no se unen solas.
 * Un mismo slug también exige año compatible ([yearsFit]).
 */
internal object SeriesMatch {
    /**
     * El slug sin el sufijo «-latino»: la versión doblada de una serie («x-latino») es la misma serie que «x», en otra fuente o en la
     * misma. Los años siguen mandando ([yearsFit]) y el usuario puede separarlas.
     */
    fun slugBase(slug: String): String = slug.removeSuffix("-latino").ifEmpty { slug }

    /** Años iguales, o alguno desconocido: la misma función de años que usa AniList ([LinkEvidence.yearsClose]) pero sin margen, porque aquí se agrupa el catálogo entero. */
    fun yearsFit(a: Int?, b: Int?): Boolean = LinkEvidence.yearsClose(a, b, margin = 0)

    /** Las dos fichas (título ya normalizado) son la misma serie por título, año y fuente. */
    fun sameSeries(titleA: String, yearA: Int?, sourceA: String, titleB: String, yearB: Int?, sourceB: String): Boolean =
        sourceA != sourceB && yearsFit(yearA, yearB) && sameTitle(titleA, titleB)
}
