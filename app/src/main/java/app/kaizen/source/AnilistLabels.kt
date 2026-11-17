package app.kaizen.source

import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Textos en español de los códigos de AniList para la pestaña «Datos» de la ficha. Un código desconocido se muestra tal cual. */
internal object AnilistLabels {
    fun status(code: String?): String? = when (code) {
        null -> null
        "FINISHED" -> "Finalizada"
        "RELEASING" -> "En emisión"
        "NOT_YET_RELEASED" -> "Sin estrenar"
        "CANCELLED" -> "Cancelada"
        "HIATUS" -> "En pausa"
        else -> code
    }

    /** La relación entre dos animes de AniList, en español. */
    fun relation(code: String?): String? = when (code) {
        "PREQUEL" -> "Precuela"
        "SEQUEL" -> "Secuela"
        "PARENT" -> "Historia principal"
        "SIDE_STORY" -> "Historia paralela"
        "SPIN_OFF" -> "Spin-off"
        "ALTERNATIVE" -> "Versión alternativa"
        "SUMMARY" -> "Resumen"
        "CHARACTER" -> "Mismos personajes"
        "COMPILATION" -> "Recopilatorio"
        "CONTAINS" -> "Contiene"
        "OTHER" -> "Relacionado"
        else -> null
    }

    /** Un género de AniList (inglés) en español; uno que no esté en la lista se muestra tal cual. */
    fun genre(code: String): String = when (code) {
        "Action" -> "Acción"
        "Adventure" -> "Aventura"
        "Comedy" -> "Comedia"
        "Fantasy" -> "Fantasía"
        "Horror" -> "Terror"
        "Music" -> "Música"
        "Mystery" -> "Misterio"
        "Psychological" -> "Psicológico"
        "Sci-Fi" -> "Ciencia ficción"
        "Slice of Life" -> "Recuentos de la vida"
        "Sports" -> "Deportes"
        "Supernatural" -> "Sobrenatural"
        "Thriller" -> "Suspenso"
        else -> code
    }

    fun format(code: String?): String? = when (code) {
        null -> null
        "TV" -> "Serie de TV"
        "TV_SHORT" -> "TV corto"
        "MOVIE" -> "Película"
        "OVA" -> "OVA"
        "ONA" -> "ONA"
        "SPECIAL" -> "Especial"
        "MUSIC" -> "Música"
        else -> code
    }

    fun role(code: String?): String? = when (code) {
        null -> null
        "MAIN" -> "principal"
        "SUPPORTING" -> "secundario"
        "BACKGROUND" -> "de fondo"
        else -> code.lowercase()
    }

    /**
     * La sinopsis de AniList trae saltos `<br>` (a menudo dobles o triples), marcas HTML, entidades (&quot;) y marcas de spoiler (`~!` `!~`):
     * texto plano con un solo salto entre párrafos y sin líneas vacías.
     */
    fun plain(html: String): String =
        Jsoup.parse(html.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")).wholeText()
            .replace("~!", "").replace("!~", "")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    /** Fecha del próximo episodio; AniList da segundos desde 1970. */
    fun airing(epochSeconds: Long, locale: Locale = Locale.getDefault()): String = SimpleDateFormat("dd/MM/yyyy HH:mm", locale).format(Date(epochSeconds * 1000))

    /** Estado de un enlace de una ficha de fuente con AniList. */
    fun link(row: AnimeFuente?): String = when {
        row == null -> "sin revisar"
        row.origen == AnimeFuente.USUARIO && row.estado == AnimeFuente.APLICADO -> "enlazada por ti con AniList #${row.anilistId}"
        row.estado == AnimeFuente.APLICADO -> "enlazada con AniList #${row.anilistId} (${row.nivel})"
        row.estado == AnimeFuente.PENDIENTE -> "por revisar (candidato AniList #${row.anilistId}, ${row.nivel})"
        else -> "sin resultado en AniList"
    }
}
