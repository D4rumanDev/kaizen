package app.kaizen.search

import app.kaizen.pojos.av1.Genre
import app.kaizen.source.titleFromSlug

/** Kaizen: géneros de búsqueda (antes en el buscador de la interfaz móvil, eliminada). */
object SearchGenres {
    /** Nombre de un género por su slug; si no está en la lista, el slug legible. */
    fun nameOf(slug: String): String = names[slug] ?: titleFromSlug(slug)

    private val names: Map<String, String> by lazy { genres.associate { it.slug to it.name } }

    val genres: MutableList<Genre>
        get() = mutableListOf(
                Genre("Acción", "accion"),
                Genre("Antropomórfico", "antropomorfico"),
                Genre("Artes Marciales", "artes-marciales"),
                Genre("Aventura", "aventura"),
                Genre("Carreras", "carreras"),
                Genre("Ciencia Ficción", "ciencia-ficcion"),
                Genre("Comedia", "comedia"),
                Genre("Deportes", "deportes"),
                Genre("Detectives", "detectives"),
                Genre("Drama", "drama"),
                Genre("Ecchi", "ecchi"),
                Genre("Elenco Adulto", "elenco-adulto"),
                Genre("Escolares", "escolares"),
                Genre("Espacial", "espacial"),
                Genre("Fantasía", "fantasia"),
                Genre("Gore", "gore"),
                Genre("Gourmet", "gourmet"),
                Genre("Harem", "harem"),
                Genre("Histórico", "historico"),
                Genre("Idols (Hombre)", "idols-hombre"),
                Genre("Idols (Mujer)", "idols-mujer"),
                Genre("Infantil", "infantil"),
                Genre("Isekai", "isekai"),
                Genre("Josei", "josei"),
                Genre("Juegos Estrategia", "juegos-estrategia"),
                Genre("Mahou Shoujo", "mahou-shoujo"),
                Genre("Mecha", "mecha"),
                Genre("Militar", "militar"),
                Genre("Misterio", "misterio"),
                Genre("Mitología", "mitologia"),
                Genre("Música", "musica"),
                Genre("Parodia", "parodia"),
                Genre("Psicológico", "psicologico"),
                Genre("Recuentos de la Vida", "recuentos-de-la-vida"),
                Genre("Romance", "romance"),
                Genre("Samurai", "samurai"),
                Genre("Seinen", "seinen"),
                Genre("Shoujo", "shoujo"),
                Genre("Shoujo Ai", "shoujo-ai"),
                Genre("Shounen", "shounen"),
                Genre("Shounen Ai", "shounen-ai"),
                Genre("Sobrenatural", "sobrenatural"),
                Genre("Suspenso", "suspenso"),
                Genre("Terror", "terror"),
                Genre("Vampiros", "vampiros"),
            )
}
