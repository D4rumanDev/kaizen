package app.kaizen.source


/**
 * Empareja el título de una ficha de una fuente con los candidatos que devuelve la búsqueda de AniList. Sin red ni base de datos: recibe
 * los candidatos ya pedidos. Es el mismo criterio que `tools/audit/match.py` (medido sobre 300 series: ≈68 % seguro, ≈8 % probable).
 *
 * El título de la ficha (normalmente el slug sin guiones) se compara con los títulos y sinónimos de cada candidato por sus palabras base
 * (sin temporada, formato, partículas ni variantes de romanización); la temporada y el formato coherentes suman, los incoherentes restan.
 */
internal object AnilistMatcher {
    /** Sube con cada cambio de criterio: las filas automáticas guardadas con una versión anterior se vuelven a emparejar. */
    const val VERSION = 3

    enum class Level { SEGURO, PROBABLE, DUDOSO, NINGUNO }

    /** Un resultado de la búsqueda de AniList. */
    data class Candidate(
        val id: Long,
        val format: String?,
        val romaji: String?,
        val english: String?,
        val native: String?,
        val synonyms: List<String> = emptyList(),
        /** Año de estreno si la búsqueda lo trae: separa un remake («Fruits Basket 2019») del original con el mismo título. */
        val year: Int? = null,
        /** Episodios y id de MyAnimeList del anime, si la búsqueda los trae: pruebas para comparar con lo que dice la web. */
        val episodes: Int? = null,
        val idMal: Long? = null
    ) {
        val names: List<String> get() = (listOf(romaji, english, native) + synonyms).filterNotNull().filter { it.isNotBlank() }
    }

    class Match(val candidate: Candidate?, val score: Double, val level: Level)

    /** Lo que dicen, además del nombre, los datos de la ficha frente a los del anime: cuántos coinciden ([supports]) y cuántos lo contradicen ([contradicts]). */
    class Evidence(val supports: Int, val contradicts: Int) {
        companion object { val NONE = Evidence(0, 0) }
    }

    /**
     * Las pruebas de que la ficha es ese anime con las mismas comparaciones que la revisión de propuestas ([AnilistCompare]): el año (el de la serie [localYear] o, si falta, el de la
     * web), el idMal y, en una serie ya terminada, los episodios suman si coinciden y restan si no; el formato solo puede restar (casi todo es «TV»: que coincida no prueba nada).
     * [web] son los datos de la ficha guardada de la web; sin ellos, solo cuenta el año.
     */
    fun evidenceOf(localYear: Int?, web: FichaData?, candidate: Candidate): Evidence {
        val strong = listOf(
            AnilistCompare.year(localYear ?: web?.anio, candidate.year),
            AnilistCompare.mal(web?.idMal, candidate.idMal),
            if (web?.estado == "FINISHED") AnilistCompare.episodes(web.episodios, candidate.episodes) else app.kaizen.source.Match.UNKNOWN
        )
        val format = AnilistCompare.format(web?.formato, candidate.format)
        val all = strong + format
        return Evidence(
            supports = strong.count { it == app.kaizen.source.Match.SAME || it == app.kaizen.source.Match.CLOSE },
            contradicts = all.count { it == app.kaizen.source.Match.DIFFERENT }
        )
    }

    /**
     * El nivel con las pruebas: un nombre parecido solo no basta para aplicar un enlace. Si alguna prueba lo contradice, no es seguro ni probable (se pregunta); un «probable» sin
     * ninguna prueba a favor tampoco se aplica solo. Un «seguro» (nombre igual) sin pruebas en contra se mantiene, salvo que el nombre sea ambiguo ([needsSupport]: película, OVA o
     * especial), que también necesita una a favor.
     */
    fun levelWithEvidence(level: Level, evidence: Evidence, needsSupport: Boolean = false): Level = when {
        level != Level.SEGURO && level != Level.PROBABLE -> level
        evidence.contradicts > 0 -> Level.DUDOSO
        (level == Level.PROBABLE || needsSupport) && evidence.supports == 0 -> Level.DUDOSO
        else -> level
    }

    private const val SEGURO_FROM = 1.0
    internal const val PROBABLE_FROM = 0.85

    /** Por debajo de esto el candidato no se parece ni a una versión del título: no es dudoso, es otra serie. */
    internal const val DUDOSO_FROM = 0.1
    /** Parecido mínimo por letras pegadas para que cuente en lugar del de palabras (por debajo, dos títulos largos parecen iguales sin serlo). */
    private const val COMPACT_FROM = 0.93
    private const val SEASON_BONUS = 0.03
    private const val SEASON_PENALTY = 0.25
    private const val FORMAT_BONUS = 0.03
    private const val FORMAT_PENALTY = 0.15
    private const val YEAR_BONUS = 0.03
    private const val YEAR_PENALTY = 0.4

    /** Lo que se resta a un parecido logrado con un nombre alternativo de la web en lugar del título de la ficha: a igualdad, manda el título. */
    private const val ALTERNATIVE_PENALTY = 0.05

    /** «Final season» / «temporada final»: la última, sin número; por encima de cualquier temporada con número. */
    private const val FINAL_SEASON = 99

    private val ORDINALS = mapOf(
        "segunda" to 2, "tercera" to 3, "cuarta" to 4, "quinta" to 5, "sexta" to 6, "septima" to 7, "octava" to 8, "novena" to 9, "decima" to 10,
        "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6, "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10,
        "ii" to 2, "iii" to 3, "iv" to 4, "vi" to 6, "vii" to 7, "viii" to 8, "ix" to 9
    )
    // Sin «v» ni «x»: una «v» suelta o la «x» de «Hunter x Hunter» no son una temporada.
    private val ROMAN = setOf("ii", "iii", "iv", "vi", "vii", "viii", "ix")
    private val FORMAT_WORDS = mapOf(
        "ova" to "OVA", "oav" to "OVA", "ona" to "ONA", "special" to "SPECIAL", "specials" to "SPECIAL", "especial" to "SPECIAL",
        "especiales" to "SPECIAL", "movie" to "MOVIE", "pelicula" to "MOVIE", "peliculas" to "MOVIE", "film" to "MOVIE"
    )
    private val NOISE = setOf("tv", "reproduction", "temporada", "season", "part", "parte", "cour", "the", "no", "wa", "ga", "to", "wo", "ni", "de", "ka", "mo")
    private val ORDINAL_SUFFIX = Regex("""(\d+)(st|nd|rd|th|ra|da|nda|era|er|ta|to|vo|mo|ma|va|na)""")
    private val SEASON_SHORT = Regex("""s(\d)""")

    /** Minúsculas, sin acentos y solo letras y cifras ASCII separadas por un espacio. */
    fun normalize(text: String?): String = normalizedBase(text.orEmpty()).trim()

    /** «ou/oo» → «o» y «uu» → «u»: «mahou shoujo» y «maho shojo» quedan iguales. */
    private fun fold(token: String) = foldVowels(token)

    class Parts(val words: List<String>, val season: Int, val format: String?, val year: Int? = null)

    /** Un título normalizado en palabras base plegadas, número de temporada (1 si no dice) y formato (null si no dice). */
    fun split(normalized: String): Parts {
        val words = normalized.split(' ').filter { it.isNotEmpty() }
        var season = 1
        var format: String? = null
        var year: Int? = null
        val base = ArrayList<String>()
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val next = words.getOrNull(i + 1)
            val shortSeason = ORDINAL_SUFFIX.matchEntire(w) ?: SEASON_SHORT.matchEntire(w)
            when {
                (w == "season" || w == "temporada") && next != null && next.all(Char::isDigit) -> { season = next.toInt(); i += 2; continue }
                (w == "final" && (next == "season" || next == "temporada")) || ((w == "season" || w == "temporada") && next == "final") -> { season = FINAL_SEASON; i += 2; continue }
                // Un año tras el título («fruits basket 2019»): distingue el remake del original; no es una palabra del título.
                i > 0 && w.length == 4 && w.all(Char::isDigit) && w.toInt() in 1950..2035 -> { year = w.toInt(); i++; continue }
                // «2 temporada», «3 season»: el número antes de la palabra.
                w.all(Char::isDigit) && (next == "temporada" || next == "season") -> { season = w.toInt(); i += 2; continue }
                shortSeason != null -> { season = shortSeason.groupValues[1].toIntOrNull() ?: 1; i++; continue }
                w in ORDINALS && (next == "temporada" || next == "season" || w in ROMAN) -> { season = ORDINALS.getValue(w); i++; continue }
                w in FORMAT_WORDS -> { format = FORMAT_WORDS.getValue(w); i++; continue }
                w !in NOISE -> base += fold(w)
            }
            i++
        }
        // «Boku no Hero Academia 5» es la quinta temporada, como «5th Season»: una cifra suelta de 2 a 9 al final, tras alguna palabra.
        if (season == 1 && base.size > 1 && base.last().length == 1 && base.last()[0] in '2'..'9') season = base.removeAt(base.lastIndex).toInt()
        // «Date A Live V», «Beyblade X»: un romano v o x al final es la temporada (5, 10); «Hunter x Hunter» lleva la x en medio.
        if (season == 1 && base.size > 1 && (base.last() == "v" || base.last() == "x")) season = if (base.removeAt(base.lastIndex) == "v") 5 else 10
        return Parts(base, season, format, year)
    }

    /** El título sin ruido (partículas, formato, temporada): para volver a buscar cuando el título entero no da nada. */
    fun cleanQuery(normalized: String): String =
        normalized.split(' ').filter { it.isNotEmpty() && it !in NOISE && it !in FORMAT_WORDS && it !in ORDINALS }.joinToString(" ")

    /** Los formatos de AniList que son una serie: una película, un OVA o un especial no se enlaza con ellos. */
    private val SERIES_FORMATS = setOf("TV", "TV_SHORT")

    /** Los formatos de AniList en los que buscar la entrada propia de una película, un OVA o un especial (un OVA puede estar como ONA o especial, y al revés). */
    private val SEARCH_FORMATS = mapOf("MOVIE" to listOf("MOVIE"), "OVA" to listOf("OVA", "ONA", "SPECIAL"), "SPECIAL" to listOf("SPECIAL", "OVA"))

    /**
     * Cómo buscar en AniList un título que dice que es una película, un OVA o un especial («x-movie», «x-ova», «x-especiales»): el texto sin la palabra de formato y los formatos en los
     * que buscarlo. Sin ese filtro la búsqueda por nombre devuelve la serie de TV («X») y la entrada propia («X OVA») ni aparece. null si el título no dice nada de eso.
     */
    fun searchPlan(title: String): Pair<String, List<String>>? {
        val normalized = normalize(title)
        val formats = SEARCH_FORMATS[split(normalized).format] ?: return null
        val text = cleanQuery(normalized).ifBlank { return null }
        return text to formats
    }

    /** La clase de un formato de AniList: serie (TV, TV_SHORT), película o corto (OVA, especial, música; el ONA cuenta como serie, como en [formatClash]); null si no se conoce. */
    fun formatClass(format: String?): String? = when (format) {
        "TV", "TV_SHORT", "ONA" -> "serie"
        "MOVIE" -> "pelicula"
        "OVA", "SPECIAL", "MUSIC" -> "corto"
        else -> null
    }
    private val NOT_SERIES = setOf("MOVIE", "OVA", "SPECIAL")

    /**
     * Si el título de la ficha dice que es una película, un OVA o un especial («x-movie», «x-ova», «x-especiales») y el anime es una serie de TV: son entradas distintas en
     * AniList y unirlas mezcla una película de un capítulo con una serie de veintidós. No cuenta si el propio anime lleva esa palabra en algún nombre («Special A»).
     * [normalizedTitle] ya normalizado; el formato del anime es [candidateFormat] y sus nombres [candidateNames].
     */
    fun formatClash(normalizedTitle: String, candidateFormat: String?, candidateNames: List<String>): Boolean {
        val want = split(normalizedTitle).format ?: return false
        if (want !in NOT_SERIES || candidateFormat !in SERIES_FORMATS) return false
        return candidateNames.none { split(normalize(it)).format == want }
    }

    /** El parecido y si la temporada del título es la del nombre que mejor casa. */
    private class Scored(val score: Double, val seasonFits: Boolean)

    /** Las palabras base pegadas y sin orden: «shitemo» y «shite mo», o «ojousama» y «ojou sama», dan las mismas letras. */
    private fun letters(words: List<String>) = words.sorted().joinToString("")

    /** Parecido de dos textos por sus pares de letras seguidas (0 a 1). */
    internal fun dice(a: String, b: String): Double {
        fun pairs(s: String) = s.windowed(2).groupingBy { it }.eachCount()
        val x = pairs(a); val y = pairs(b)
        if (x.isEmpty() || y.isEmpty()) return if (a == b) 1.0 else 0.0
        val shared = x.entries.sumOf { (k, v) -> minOf(v, y[k] ?: 0) }
        return 2.0 * shared / (x.values.sum() + y.values.sum())
    }

    /**
     * El parecido de dos títulos por sus palabras base ([a], [b]): lo que comparten sobre lo que suman o, si es más, el de las letras pegadas ([lettersA], [lettersB]) cuando casi
     * coinciden («shitemo» / «shite mo»; por debajo de [COMPACT_FROM] no cuenta). 0 si alguno no tiene palabras. Es el único parecido del código: el emparejado, la comprobación de
     * enlaces y la cohesión de las series unidas lo usan.
     */
    fun wordSimilarity(a: Collection<String>, b: Collection<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val sa = a.toSet(); val sb = b.toSet()
        val shared = (sa intersect sb).size.toDouble() / (sa union sb).size
        if (shared == 1.0) return 1.0
        // Letras pegadas sin orden (las palabras en otro orden) y en su orden (la misma palabra partida de otra forma: «hackgu» / «hack gu» ordenadas darían letras distintas).
        val compact = maxOf(dice(letters(sa.toList()), letters(sb.toList())), dice(a.joinToString(""), b.joinToString("")))
        return maxOf(shared, compact.takeIf { it >= COMPACT_FROM } ?: 0.0)
    }

    private fun scored(title: String, candidate: Candidate): Scored {
        val want = split(title)
        var best: Scored? = null
        for (name in candidate.names) {
            val parts = split(normalize(name))
            var sim = wordSimilarity(want.words, parts.words)
            val seasonFits = parts.season == want.season
            sim += if (seasonFits) SEASON_BONUS else -SEASON_PENALTY
            // El año del título de la ficha frente al del anime (con un año de margen: el estreno puede caer a final de año).
            val candidateYear = parts.year ?: candidate.year
            if (want.year != null && candidateYear != null) sim += if (kotlin.math.abs(candidateYear - want.year) <= 1) YEAR_BONUS else -YEAR_PENALTY
            if (want.format != null) {
                // La misma comparación de formato que la revisión ([AnilistCompare.format]): el mismo formato o de la misma clase (OVA y especial) suma; otra clase resta.
                sim += when (AnilistCompare.format(want.format, candidate.format)) {
                    app.kaizen.source.Match.SAME -> FORMAT_BONUS
                    // Misma clase pero otro formato: suma entre OVA y especial; un ONA frente a una serie de TV sigue restando (no es prueba de nada y sube el nivel sin ellas).
                    app.kaizen.source.Match.CLOSE -> if (want.format == "ONA") -FORMAT_PENALTY else FORMAT_BONUS
                    app.kaizen.source.Match.UNKNOWN -> 0.0
                    app.kaizen.source.Match.DIFFERENT -> -FORMAT_PENALTY
                }
            }
            if (best == null || sim > best.score) best = Scored(sim, seasonFits)
        }
        return best ?: Scored(0.0, true)
    }

    /** El parecido del [title] ya normalizado con un candidato: la mejor puntuación entre sus nombres. */
    fun score(title: String, candidate: Candidate): Double = scored(title, candidate).score

    /** El candidato que mejor casa con [title] y su puntuación; null si no hay candidatos. */
    fun best(title: String, candidates: List<Candidate>): Pair<Candidate, Double>? {
        var top: Pair<Candidate, Double>? = null
        for (candidate in candidates) {
            val s = score(title, candidate)
            if (top == null || s > top.second) top = candidate to s
        }
        return top
    }

    /**
     * Decide el emparejado de una serie. [titles] son los títulos de sus fichas (de más a menos fiable; el primero manda) y [results] los
     * candidatos que devolvió la búsqueda de cada uno. Es SEGURO solo si la puntuación llega a 1 y todas las demás búsquedas de la serie
     * traen ese mismo candidato entre sus tres primeros. [localYear] es el año de estreno de la serie según sus fichas (null si no lo dan).
     */
    fun decide(
        titles: List<String>,
        results: List<List<Candidate>>,
        localYear: Int? = null,
        alternatives: List<String> = emptyList(),
        alternativeResults: List<List<Candidate>> = emptyList(),
        web: FichaData? = null
    ): Match {
        val normalized = titles.map(::normalize)
        val first = normalized.firstOrNull() ?: return Match(null, 0.0, Level.NINGUNO)
        // Los otros nombres que da la propia web (inglés, sinónimos): cuentan como el título, con un pequeño descuento, y sus resultados suman candidatos.
        val alts = alternatives.map(::normalize).filter { it.isNotEmpty() && it != first }.distinct()
        val pool = (results.firstOrNull().orEmpty() + alternativeResults.flatten()).distinctBy { it.id }
        var top: Pair<Candidate, Double>? = null
        for (c in pool) {
            val s = maxOf(score(first, c), alts.maxOfOrNull { score(it, c) - ALTERNATIVE_PENALTY } ?: Double.NEGATIVE_INFINITY)
            if (top == null || s > top.second) top = c to s
        }
        val (candidate, score) = top ?: return Match(null, 0.0, Level.NINGUNO)
        // Otra temporada no es una duda: «x 2nd season» no es «X» y, si AniList no tiene la suya entre los resultados, no hay emparejado. Igual una película, un OVA o un especial frente a una serie de TV.
        val seasonFits = scored(normalized.first(), candidate).seasonFits && !formatClash(normalized.first(), candidate.format, candidate.names)
        val others = results.drop(1).all { found -> found.take(3).any { it.id == candidate.id } }
        val level = when {
            !seasonFits -> Level.NINGUNO
            score >= SEGURO_FROM && others -> Level.SEGURO
            score >= PROBABLE_FROM -> Level.PROBABLE
            score >= DUDOSO_FROM -> Level.DUDOSO
            else -> Level.NINGUNO
        }
        // Un título que casa no se da por bueno solo por el nombre: el año, el formato, el idMal y los episodios tienen que cuadrar con los del anime (ver [levelWithEvidence]).
        // Una película, un OVA o un especial se nombra de forma ambigua (varios con el mismo título base): aquí ni el nombre igual basta sin una prueba más.
        val ambiguous = split(normalized.first()).format in SEARCH_FORMATS.keys
        return Match(candidate, score, levelWithEvidence(level, evidenceOf(localYear, web, candidate), ambiguous))
    }
}
