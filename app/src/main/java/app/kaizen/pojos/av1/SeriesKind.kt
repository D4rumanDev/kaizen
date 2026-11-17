package app.kaizen.pojos.av1

/**
 * Tipo de una serie. Las fuentes lo dan como texto (tv-anime) y el catálogo lo guarda como número: aquí se traduce
 * entre los dos y al texto que se muestra.
 */
enum class SeriesKind(val code: Int, val slug: String, val label: String) {
    ANIME(1, "tv-anime", "Anime"),
    MOVIE(2, "pelicula", "Película"),
    OVA(3, "ova", "OVA"),
    SPECIAL(4, "especial", "Especial");

    companion object {
        fun fromSlug(slug: String?): SeriesKind = entries.firstOrNull { it.slug == slug } ?: SPECIAL

        fun fromCode(code: Int): SeriesKind = entries.firstOrNull { it.code == code } ?: SPECIAL
    }
}
