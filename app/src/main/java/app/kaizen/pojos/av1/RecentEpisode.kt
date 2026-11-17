package app.kaizen.pojos.av1

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import app.kaizen.commons.roundedString
import app.kaizen.source.AnimeAv1
import app.kaizen.source.AnimeSources
import app.kaizen.database.CacheDB
import kotlinx.coroutines.flow.Flow

@Entity
data class RecentEpisode(
    @PrimaryKey
    val key: Int,
    val aid: Int,
    val eid: Int,
    val name: String,
    val number: Double,
    val slug: String,
    @ColumnInfo(defaultValue = "'animeav1'")
    val source: String = AnimeAv1.ID,
    // Fecha de publicación del episodio (ms); 0 = desconocida. «Recientes» ordena por ella.
    @ColumnInfo(defaultValue = "0")
    val released: Long = 0L
) {
    val animeUrl: String get() = AnimeSources.of(source).animeUrl(slug)
    val animeImageUrl: String get() = AnimeSources.of(source).coverUrl(aid, slug)
    val episodeImageUrl: String get() = AnimeSources.of(source).thumbnailUrl(aid, number.roundedString())
        .ifBlank { animeImageUrl }
    val chapterUrl: String get() = AnimeSources.of(source).chapterUrl(slug, number.roundedString())
    val chapter: String get() = "Episodio ${number.roundedString()}"

    fun asRecord(): Record = Record(
        eid = eid,
        aid = aid,
        name = name,
        number = number,
        slug = slug,
        date = System.currentTimeMillis(),
        source = source
    )

    /** Si el capítulo está visto, y cada vez que cambia. */
    fun seenFlow(): Flow<Boolean> = CacheDB.INSTANCE.recordAV1DAO().chapterIsSeenFlow(source, slug, number)

}

@Dao
interface RecentEpisodeDao {
    @get:Query("SELECT * FROM RecentEpisode ORDER BY `key`")
    val allFlow: Flow<List<RecentEpisode>>

    @get:Query("SELECT * FROM RecentEpisode ORDER BY `key`")
    val all: List<RecentEpisode>

    @Query("DELETE FROM RecentEpisode")
    fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun setCache(objects: List<RecentEpisode>)
}
