package app.kaizen.pojos.av1

import androidx.room.ColumnInfo

import app.kaizen.source.AnimeAv1
import app.kaizen.source.AnimeSources
import app.kaizen.source.SeriesLedger
import app.kaizen.source.userCover

import androidx.annotation.Keep
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.TypeConverters
import com.google.gson.annotations.SerializedName
import app.kaizen.database.BaseConverter
import kotlinx.coroutines.flow.Flow

open class FavoriteBase

/** Un favorito, por serie: la serie es favorita aunque se guardara desde otra fuente. La ficha (fuente y slug) es la que se abre. */
@Keep
@Entity
data class FavoriteAV1(
    @SerializedName("aid")
    val aid: Int,
    @SerializedName("name")
    val name: String,
    @SerializedName("slug")
    val slug: String,
    @SerializedName("type")
    val type: String,
    @SerializedName("category")
    var category: String,
    @ColumnInfo(defaultValue = "'animeav1'")
    val source: String = AnimeAv1.ID,
    /** Id de la serie en el libro de ids ([SeriesLedger]); 0 = aún sin dar (se da al guardar: [withSeries]). */
    @PrimaryKey
    val seriesId: Long = 0L
): FavoriteBase() {

    constructor(): this(0, "", "", "", "")

    /** Este favorito con el id de su serie (se lo da al libro si no lo tiene). Puede crear el id: hilo de fondo. */
    fun withSeries(): FavoriteAV1 = if (seriesId != 0L) this else copy(seriesId = SeriesLedger.idOf(source, slug))

    fun animeUrl() = AnimeSources.of(source).animeUrl(slug)
    fun imageUrl() = userCover(slug, source) ?: AnimeSources.of(source).coverUrl(aid, slug)

    fun isValid(): Boolean = aid != 0

    companion object {
        const val CATEGORY_NONE = "_NONE_"
    }
}

@Dao
@TypeConverters(BaseConverter::class)
abstract class FavoriteAV1Dao {
    @get:Query("SELECT * FROM Favoriteav1 ORDER BY name")
    abstract val all: Flow<List<FavoriteAV1>>

    @get:Query("SELECT * FROM Favoriteav1 ORDER BY name")
    abstract val allRaw: List<FavoriteAV1>

    @get:Query("SELECT category FROM FavoriteAV1 GROUP BY category ORDER BY category")
    abstract val categories: List<String>

    @get:Query("SELECT count(*) FROM FavoriteAV1")
    abstract val count: Int

    // Es favorita la serie de la ficha [source]/[slug]: sale del libro de ids (`Ficha`) en la propia consulta.
    @Query("SELECT EXISTS(SELECT 1 FROM FavoriteAV1 WHERE seriesId IN ($SERIES_OF_FICHA))")
    abstract fun isFav(source: String, slug: String): Boolean

    @Query("DELETE FROM FavoriteAV1 WHERE seriesId IN ($SERIES_OF_FICHA)")
    abstract fun removeFav(source: String, slug: String)

    // Las altas dan de alta la ficha en el libro de ids: ver [FavoriteAV1.withSeries]. Pueden crear ids: no llamar desde el hilo principal.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertFav(favorite: FavoriteAV1)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertAll(list: List<FavoriteAV1>)

    open fun addFav(favorite: FavoriteAV1) = insertFav(favorite.withSeries())

    open fun addAll(list: List<FavoriteAV1>) = insertAll(list.map(FavoriteAV1::withSeries))

    @Delete
    abstract fun delete(favorite: FavoriteAV1)

}