package app.kaizen.domain

import app.kaizen.database.CacheDB
import app.kaizen.pojos.av1.FavoriteAV1
import app.kaizen.pojos.av1.RecentEpisode
import app.kaizen.pojos.av1.Record
import kotlinx.coroutines.flow.Flow

/**
 * Lo del usuario que enseñan las pantallas: favoritos, historial de capítulos vistos y recientes de las fuentes. Es la única puerta de las pantallas de `tv/` a esas tablas (lo comprueba
 * `ArquitecturaTest`): así la base de datos y sus consultas se pueden cambiar sin tocar la interfaz, y las pantallas se prueban con datos propios. Las lecturas «ahora» son síncronas
 * (hilo de fondo); los flujos avisan de cada cambio.
 */
object Library {
    private val db get() = CacheDB.INSTANCE

    /** Favoritos guardados, con cada cambio. */
    val favorites: Flow<List<FavoriteAV1>> get() = db.favoriteAV1DAO().all

    fun favoritesNow(): List<FavoriteAV1> = db.favoriteAV1DAO().allRaw

    /** Si la ficha ([source], [slug]) está guardada como favorita. */
    fun isFavorite(source: String, slug: String): Boolean = db.favoriteAV1DAO().isFav(source, slug)

    /** Capítulos vistos, con cada cambio. */
    val history: Flow<List<Record>> get() = db.recordAV1DAO().allFlow

    fun historyNow(): List<Record> = db.recordAV1DAO().all.toList()

    /** Si el capítulo [number] de la serie de la ficha ([source], [slug]) está visto. */
    fun chapterSeen(source: String, slug: String, number: Double): Boolean = db.recordAV1DAO().chapterIsSeen(source, slug, number)

    /** Marca un capítulo como visto (o actualiza su fecha). */
    fun addChapter(record: Record) = db.recordAV1DAO().addChapter(record)

    /** Capítulos recientes guardados de las fuentes, con cada cambio. */
    val recents: Flow<List<RecentEpisode>> get() = db.recentEpisodeDAO().allFlow

    fun recentsNow(): List<RecentEpisode> = db.recentEpisodeDAO().all
}
