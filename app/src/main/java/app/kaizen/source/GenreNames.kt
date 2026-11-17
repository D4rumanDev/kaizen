package app.kaizen.source

/**
 * Equivalencia entre los géneros que publican las fuentes (español; lo que dan es el slug de su enlace) y los de AniList (inglés).
 * Solo están los géneros que AniList tiene; los demás (demografías como shounen o seinen, «escolares», «militar»…) no se comparan.
 */
internal object GenreNames {
    private val TABLE: Map<String, String> = buildMap {
        fun add(english: String, vararg spanish: String) = spanish.forEach { put(it, english) }
        add("Action", "accion", "action")
        add("Adventure", "aventura", "aventuras", "adventure")
        add("Comedy", "comedia", "comedy")
        add("Drama", "drama")
        add("Ecchi", "ecchi")
        add("Fantasy", "fantasia", "fantasy")
        add("Hentai", "hentai")
        add("Horror", "terror", "horror")
        add("Mahou Shoujo", "mahou-shoujo", "magia", "chicas-magicas", "magical-girls")
        add("Mecha", "mecha", "robots")
        add("Music", "musica", "musical", "music")
        add("Mystery", "misterio", "mystery")
        add("Psychological", "psicologico", "psychological")
        add("Romance", "romance")
        add("Sci-Fi", "ciencia-ficcion", "ficcion-cientifica", "sci-fi", "scifi", "sci_fi")
        add("Slice of Life", "recuentos-de-la-vida", "recuento-de-la-vida", "vida-cotidiana", "slice-of-life")
        add("Sports", "deportes", "deporte", "sports")
        add("Supernatural", "sobrenatural", "supernatural")
        add("Thriller", "suspenso", "suspense", "thriller")
    }

    /** El slug o nombre de un género de una fuente, sin acentos, en minúsculas y con guiones. */
    private fun key(text: String) = AnilistMatcher.normalize(text).replace(' ', '-')

    /** El género de AniList que equivale al [name] de una fuente; null si AniList no tiene uno igual. */
    fun anilist(name: String): String? = TABLE[key(name)]

    /** Los géneros de AniList que equivalen a los de una fuente (sin repetir y sin los que AniList no tiene). */
    fun anilistSet(local: Collection<String>): Set<String> = local.mapNotNull(::anilist).toSet()
}
