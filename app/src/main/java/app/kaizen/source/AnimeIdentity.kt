package app.kaizen.source

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Kaizen: identidad interna de cada anime. Un slug canónico = un id interno (`aid`), con
 * independencia de la web de origen. Los animes de animeav1 conservan su aid de siempre
 * (migración 23→24); los nuevos reciben ids a partir de [NEW_ID_BASE].
 */
@Entity(tableName = "AnimeIdentity", indices = [Index(value = ["slug"], unique = true)])
data class AnimeIdentity(
    @PrimaryKey val internalId: Int,
    @ColumnInfo(name = "slug") val slug: String,
    @ColumnInfo(name = "source") val source: String
)

@Dao
interface AnimeIdentityDao {
    @Query("SELECT * FROM AnimeIdentity WHERE slug = :slug LIMIT 1")
    fun findBySlug(slug: String): AnimeIdentity?

    @Query("SELECT MAX(internalId) FROM AnimeIdentity")
    fun maxId(): Int?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(identity: AnimeIdentity)
}

/** Obtiene (o crea) el id interno de un slug. Llamar desde un hilo de fondo. */
object AnimeIds {
    const val NEW_ID_BASE = 1_000_000

    /** Id interno de un slug de [source], creándolo si no existe. Llamar desde un hilo de fondo. */
    fun forSlug(slug: String, source: String): Int =
        internalIdFor(app.kaizen.database.CacheDB.INSTANCE.animeIdentityDao(), slug, source)

    @Synchronized
    fun internalIdFor(dao: AnimeIdentityDao, slug: String, source: String): Int {
        dao.findBySlug(slug)?.let { return it.internalId }
        val next = maxOf(dao.maxId() ?: 0, NEW_ID_BASE - 1) + 1
        dao.insert(AnimeIdentity(next, slug, source))
        return dao.findBySlug(slug)?.internalId ?: next
    }
}
