package app.kaizen.source

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Kaizen: en qué fuentes está cada episodio de un anime (clave: slug canónico + número + fuente).
 * Tabla vacía hasta que las fuentes se consulten; no reemplaza a los datos actuales.
 */
@Entity(tableName = "EpisodeAvailability", primaryKeys = ["slug", "number", "source"])
data class EpisodeAvailability(
    val slug: String,
    val number: Double,
    val source: String,
    val url: String
)

@Dao
interface EpisodeAvailabilityDao {
    @Query("SELECT * FROM EpisodeAvailability WHERE slug = :slug AND number = :number")
    fun sourcesFor(slug: String, number: Double): List<EpisodeAvailability>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(items: List<EpisodeAvailability>)
}
