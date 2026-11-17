package app.kaizen.source

import java.util.concurrent.ConcurrentHashMap

/**
 * La dirección del tráiler de un anime de AniList. Se pide al abrir la ficha y se recuerda en memoria (también «no tiene»); no se guarda en la base.
 * El id viene de una web externa: solo se acepta si son letras, números, guion y guion bajo, para no construir una dirección a medida.
 */
internal object AnilistTrailer {
    private val ID_OK = Regex("[A-Za-z0-9_-]{1,32}")
    private val cache = ConcurrentHashMap<Long, String>()

    /** La URL de YouTube o Dailymotion, o null si el sitio no es uno de esos o el id no es válido. */
    fun url(site: String?, id: String?): String? {
        if (id == null || !ID_OK.matches(id)) return null
        return when (site?.lowercase()) {
            "youtube" -> "https://www.youtube.com/watch?v=$id"
            "dailymotion" -> "https://www.dailymotion.com/video/$id"
            else -> null
        }
    }

    /** La URL del tráiler del anime [anilistId], o null si no tiene (o AniList no responde: entonces no se recuerda y se vuelve a intentar). */
    suspend fun of(anilistId: Long): String? {
        cache[anilistId]?.let { return it.ifEmpty { null } }
        val found = AnilistClient(AnilistHttp::post).trailer(anilistId) ?: return null
        val url = url(found.first, found.second)
        cache[anilistId] = url.orEmpty()
        return url
    }
}
