package app.kaizen.pojos.av1

import androidx.room.ColumnInfo
import app.kaizen.domain.SeriesStatuses

import app.kaizen.source.AnimeAv1
import app.kaizen.source.AnimeSources
import app.kaizen.source.SeriesLedger
import app.kaizen.source.userCover

import androidx.annotation.Keep
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.TypeConverters
import com.google.gson.annotations.SerializedName
import app.kaizen.commons.roundedString
import app.kaizen.database.BaseConverter
import app.kaizen.database.CacheDB
import kotlinx.coroutines.flow.Flow

/** La serie (id del libro) de la ficha [source]/[slug], para usar dentro de una consulta: `seriesId IN ($SERIES_OF_FICHA)`. Los ids del libro (SeriesMap) son siempre los vigentes. */
const val SERIES_OF_FICHA = "SELECT seriesId FROM Ficha WHERE fuente = :source AND slug = :slug"

/**
 * Un capítulo visto, por serie y número: lo visto en dos fuentes de la misma serie es un solo registro (el más reciente). La ficha (fuente y
 * slug), el aid y el eid son datos de dónde se vio; la identidad es [seriesId] + [number].
 */
@Keep
@Entity(primaryKeys = ["seriesId", "number"])
data class Record(
    @SerializedName("eid")
    val eid: Int,
    @SerializedName("name")
    val name: String,
    @SerializedName("number")
    val number: Double,
    @SerializedName("aid")
    val aid: Int,
    @SerializedName("slug")
    val slug: String,
    @SerializedName("date")
    val date: Long,
    @ColumnInfo(defaultValue = "'animeav1'")
    val source: String = AnimeAv1.ID,
    /** Id de la serie en el libro de ids ([SeriesLedger]); 0 = aún sin dar (se da al guardar: [withSeries]). */
    val seriesId: Long = 0L
) {

    constructor(): this(0, "", 0.0, 0, "", 0L)

    /** Esta fila con el id de su serie (se lo da al libro si no lo tiene). Puede crear el id: hilo de fondo. */
    fun withSeries(): Record = if (seriesId != 0L) this else copy(seriesId = SeriesLedger.idOf(source, slug))

    fun animeUrl() = AnimeSources.of(source).animeUrl(slug)
    fun imageUrl() = userCover(slug, source) ?: AnimeSources.of(source).coverUrl(aid, slug)
    fun chapterUrl() = AnimeSources.of(source).chapterUrl(slug, number.roundedString())
    fun chapter() = "Episodio ${number.roundedString()}"
    fun isValid(): Boolean = eid != 0

    /** La clave de su progreso en [app.kaizen.commons.KaizenProgress]. */
    fun progressKey() = app.kaizen.commons.KaizenProgress.Key(seriesId, number)

    /** Marca o desmarca el capítulo como visto. Escribe en la base de datos: no llamar desde el hilo principal. */
    fun markSeen(seen: Boolean) {
        val dao = CacheDB.INSTANCE.recordAV1DAO()
        if (seen) dao.addChapter(this) else dao.deleteChapter(source, slug, number)
        // Un capítulo visto pone la serie en «Viendo» (si no tenía estado o estaba pendiente); sin ninguno visto vuelve a «Ninguna».
        if (seen) SeriesStatuses.autoWatching(name, source, slug, aid)
        else if (dao.getAllBySeries(source, slug).isEmpty()) SeriesStatuses.autoNone(name, source, slug, aid)
        else SeriesStatuses.autoReopen(name, source, slug, aid)
    }

}

@Dao
@TypeConverters(BaseConverter::class)
abstract class RecordDao {
    @get:Query("SELECT * FROM Record")
    abstract val all: MutableList<Record>

    /** Las series (id) de las que hay algún capítulo visto. */
    @Query("SELECT DISTINCT seriesId FROM Record")
    abstract fun seriesIds(): List<Long>

    @get:Query("SELECT * FROM Record ORDER BY date DESC")
    abstract val allFlow: Flow<List<Record>>

    @get:Query("SELECT count(*) FROM Record")
    abstract val count: Int

    @get:Query("SELECT MIN(date) FROM Record")
    abstract val lastDate: Long?

    // Visto = hay un registro de ese número de la serie de la ficha ([source], [slug]): la serie sale del libro de ids (tabla Ficha) en la propia
    // consulta, así un capítulo visto en una fuente cuenta como visto en las demás y no depende de que el libro esté cargado en memoria.
    // EXISTS y no count(*): el convertidor de Boolean solo da true con 1.
    @Query("SELECT EXISTS(SELECT 1 FROM Record WHERE number = :number AND seriesId IN ($SERIES_OF_FICHA))")
    abstract fun chapterIsSeen(source: String, slug: String, number: Double): Boolean

    /** Todo lo visto de la serie de la ficha [source]/[slug]. */
    @Query("SELECT * FROM Record WHERE seriesId IN ($SERIES_OF_FICHA)")
    abstract fun getAllBySeries(source: String, slug: String): List<Record>

    // Las altas dan de alta la ficha en el libro de ids: ver [Record.withSeries]. Pueden crear ids: no llamar desde el hilo principal.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertChapter(chapter: Record)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertChapterIgnore(chapter: Record)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertAllIgnore(list: List<Record>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertAll(list: List<Record>)

    open fun addChapter(chapter: Record) = insertChapter(chapter.withSeries())

    open fun addChapterIgnore(chapter: Record) = insertChapterIgnore(chapter.withSeries())

    open fun addAllIgnore(list: List<Record>) = insertAllIgnore(list.map(Record::withSeries))

    open fun addAll(list: List<Record>) = insertAll(list.map(Record::withSeries))

    @Delete
    abstract fun delete(chapter: Record)

    @Delete
    abstract fun deleteAll(chapters: List<Record>)

    // Desmarca por donde se marca como visto: ese número de la serie de la ficha.
    @Query("DELETE FROM Record WHERE number = :number AND seriesId IN ($SERIES_OF_FICHA)")
    abstract fun deleteChapter(source: String, slug: String, number: Double)

}
