package app.kaizen.pojos.av1

import app.kaizen.source.seriesIdentity
import app.kaizen.source.seriesKeyOf
import app.kaizen.commons.logError
import androidx.room.ColumnInfo

import app.kaizen.commons.KaizenProgress
import app.kaizen.database.CacheDB
import app.kaizen.domain.Favorites
import app.kaizen.domain.SeriesStatuses
import app.kaizen.domain.WatchState
import app.kaizen.source.AnimeAv1
import app.kaizen.source.AnimeSources
import app.kaizen.source.BestCovers
import app.kaizen.source.seriesIdOf
import app.kaizen.source.userCover

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import app.kaizen.commons.SharedGson
import com.google.gson.annotations.SerializedName
import app.kaizen.commons.roundedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import app.kaizen.source.AnimeSource
import app.kaizen.domain.Taste
import app.kaizen.source.normalizeTitle
import app.kaizen.commons.runCatchingCoop
import app.kaizen.domain.AlreadyHad
import app.kaizen.domain.Had
import app.kaizen.domain.NotRecommended

data class Recommended(
    @SerializedName("aid")
    val aid: Int,
    @SerializedName("name")
    val name: String,
    @SerializedName("slug")
    val slug: String,
    @SerializedName("type")
    val type: String
) {
    /** Nota ponderada de las fuentes activas; no viene del JSON de sugerencias (ver StaffRecommendations). */
    @Transient
    var score: Double? = null
    /** Si ya se pidió la nota (al pintar la tarjeta), para no volver a pedirla cuando la tarjeta sale y vuelve a entrar en pantalla. */
    @Transient
    var scoreRequested: Boolean = false
    // Las sugerencias vienen del catálogo de animeav1: si está desmarcada, se abren en la primera fuente activa.
    /** Fuente activa y slug que tiene esta serie según el catálogo interno (se resuelve al pintar la tarjeta). */
    @Transient
    var resolved: Pair<AnimeSource, String>? = null
    val animeUrl: String get() = resolved?.let { (source, slug) -> source.animeUrl(slug) }
        ?: AnimeSources.enabled.let { sources ->
            if (sources.any { it.id == AnimeAv1.ID }) AnimeAv1.animeUrl(slug) else sources.first().animeUrl(slug)
        }
    val imageUrl: String get() = AnimeAv1.coverUrl(aid)
    val typeText: String get() = SeriesKind.fromSlug(type).label
}

@androidx.annotation.Keep
data class SeriesSummary(
    @SerializedName("aid") val aid: Int,
    @SerializedName("name") val name: String,
    @SerializedName("slug") val slug: String,
    @SerializedName("category") val category: Int,
    @SerializedName("source") val source: String = AnimeAv1.ID,
    // Nota en estrellas (/5), como en la ficha; votos para ponderarla entre fuentes.
    @SerializedName("score") val score: Double? = null,
    @SerializedName("votes") val votes: Int = 0,
    // Año de estreno si la fuente lo publica en sus listados.
    @SerializedName("year") val year: Int? = null
) {
    val animeUrl: String get() = AnimeSources.of(source).animeUrl(slug)
    val imageUrl: String get() = userCover(slug, source) ?: AnimeSources.of(source).coverUrl(aid, slug)

    fun asFavorite(category: String = FavoriteAV1.CATEGORY_NONE) = FavoriteAV1(
        aid, name, slug,
        SeriesKind.fromCode(this.category).slug,
        category, source
    )

    /** Alterna el favorito; devuelve si queda como favorito. */
    suspend fun toggleFavorite(): Boolean = Favorites.toggle(name, animeUrl, asFavorite())

    val type: String get() = SeriesKind.fromCode(category).label

    companion object {
        fun fromJson(item: JSONObject) : SeriesSummary {
            return SeriesSummary(
                item.getInt("id"),
                item.getString("title"),
                item.getString("slug"),
                item.getInt("categoryId")
            )
        }
    }
}

@androidx.annotation.Keep
@Entity(tableName = "AiringSeries")
data class AiringSeries(
    @PrimaryKey
    @SerializedName("aid") val aid: Int,
    @SerializedName("name") val name: String,
    @SerializedName("slug") val slug: String,
    @SerializedName("day") val day: Int,
    @SerializedName("category") val category: Int,
    @SerializedName("isHidden") var isHidden: Boolean = false
) {
    /** Fuente de la que viene la tarjeta (el horario se pide a todas las activas; ver AnimeSources.scheduleAll). */
    @Ignore
    @SerializedName("source")
    var source: String = AnimeSources.current.id
    val animeUrl: String get() = AnimeSources.of(source).animeUrl(slug)
    val imageUrl: String get() = userCover(slug, source) ?: AnimeSources.of(source).coverUrl(aid, slug)
    @Ignore
    @SerializedName("isFavorite")
    var isFavorite: Boolean = false
    /** Nota (estrellas /5) y votos, si la fuente los publica para el horario. */
    @Ignore
    @SerializedName("score")
    var score: Double? = null
    @Ignore
    @SerializedName("votes")
    var votes: Int = 0

    fun asMin(): SeriesSummary {
        return SeriesSummary(aid, name, slug, category, source, score, votes)
    }
}

@Entity(tableName = "SourceFicha")
@TypeConverters(DirectoryConverter::class)
data class Series(
    @PrimaryKey
    @SerializedName("aid")
    val aid: Int,
    @SerializedName("name")
    val name: String,
    @SerializedName("slug")
    val slug: String,
    @SerializedName("description")
    val description: String,
    @SerializedName("rate_stars")
    val rateStars: Float,
    @SerializedName("rate_count")
    val rateCount: Int,
    @SerializedName("type")
    val type: String,
    @SerializedName("state")
    val state: Int,
    @SerializedName("day")
    val day: Int?,
    @SerializedName("genres")
    val genres: List<Genre>,
    @SerializedName("relations")
    val relations: List<Relation>,
    @SerializedName("chapters")
    val chapters: List<ChapterEntry>,
    @ColumnInfo(defaultValue = "'animeav1'")
    val source: String = AnimeAv1.ID,
    // Año de estreno si la ficha de la fuente lo publica.
    @SerializedName("year")
    val year: Int? = null,
    // Estudio de animación si la ficha de la fuente lo publica.
    @SerializedName("studio")
    val studio: String? = null
) {
    val animeUrl: String get() = AnimeSources.of(source).animeUrl(slug)
    val imageUrl: String get() = userCover(slug, source) ?: AnimeSources.of(source).coverUrl(aid, slug)

    val typeText: String get() = SeriesKind.fromSlug(type).label

    fun asFavorite(category: String = FavoriteAV1.CATEGORY_NONE): FavoriteAV1 {
        return FavoriteAV1(
            aid,
            name,
            slug,
            type,
            category,
            source
        )
    }

    /** Capítulos por número, ya con los datos de la serie (listos para pintar y reproducir). */
    fun episodes(): List<Chapter> = chapters.sortedBy { it.number }.map { it.withID(this) }

    /** Capítulo [step] posiciones después (o antes, si es negativo) del [number], o null si no existe. */
    fun episodeNear(number: Double, step: Int): Chapter? {
        val list = episodes()
        val index = list.indexOfFirst { it.number == number }
        return if (index < 0) null else list.getOrNull(index + step)
    }

    /** Alterna el favorito; devuelve si queda como favorito. */
    suspend fun toggleFavorite(): Boolean = Favorites.toggle(name, animeUrl, asFavorite())

    /** Qué reproducir ahora: lo empezado, el siguiente al último visto o el primero. Consulta la base de datos. */
    suspend fun watchState(): WatchState = withContext(Dispatchers.IO) {
        val database = CacheDB.INSTANCE
        val episodes = episodes()
        // Mismo criterio que el ojo de cada tarjeta (el mismo número de la serie, aunque se viera en otra fuente): si no, contador y marcas no cuadran.
        val seenEids = episodes.filter { database.recordAV1DAO().chapterIsSeen(source, slug, it.number) }.map { it.eid }.toSet()
        WatchState.compute(
            episodes,
            seenEids,
            { chapter -> KaizenProgress.startedOf(chapter.progressKey(), chapter.eid) },
            database.favoriteAV1DAO().isFav(source, slug)
        )
    }

    /**
     * Marca o desmarca todos los capítulos. Al marcar solo se insertan los que faltan, para no
     * cambiar la fecha (y el orden en «Últimos vistos») de los ya vistos.
     */
    suspend fun setAllSeen(seen: Boolean) = withContext(Dispatchers.IO) {
        val dao = CacheDB.INSTANCE.recordAV1DAO()
        if (seen) {
            val seenNumbers = dao.getAllBySeries(source, slug).map { it.number }.toSet()
            episodes().filter { it.number !in seenNumbers }.forEach { dao.addChapterIgnore(it.asRecord()) }
        } else {
            dao.deleteAll(dao.getAllBySeries(source, slug))
            // Sin nada visto, la serie vuelve a «Ninguna» si estaba «Viendo» o «Completada».
            SeriesStatuses.autoNone(name, source, slug, aid)
        }
    }

    /**
     * Series parecidas: las que más géneros comparten (en proporción) y mejor nota tienen ([Taste.similarity]). No salen
     * las relacionadas, las de nota baja ([Taste.MIN_STARS]), las que el usuario no quiere que se le recomienden, sus favoritas,
     * las que ya ha visto enteras ([AlreadyHad]) ni la misma serie con otro nombre.
     */
    suspend fun similar(): List<SeriesSummary> = withContext(Dispatchers.IO) {
        val dao = CacheDB.INSTANCE.sourceFichaDAO()
        val own = genres.map { it.slug }.toSet()
        val related = relations.map { it.aid }.toSet()
        // Una consulta por género (LIKE sin índice), en paralelo.
        val candidates = coroutineScope {
            own.map { slug ->
                async { dao.findByGenrePattern(aid, "%:$slug%").filter { candidate -> candidate.aid !in related && candidate.genres.any { it.slug == slug } } }
            }.awaitAll()
        }.flatten().associateBy { it.aid }
        val ownTitle = normalizeTitle(name)
        // Sin las favoritas ni las que ya se han visto enteras.
        val had = runCatchingCoop { AlreadyHad.current() }.getOrDefault(Had.NONE)
        candidates.values
            .filter { (it.rateStars == 0f || it.rateStars >= Taste.MIN_STARS) && normalizeTitle(it.name) != ownTitle && seriesKeyOf(it.source, it.slug) != seriesKeyOf(source, slug) && NotRecommended.allows(it.name, it.source, it.slug) && !had.has(it.name, it.slug, it.source) }
            .map { it to Taste.similarity(own, it.genres.map { genre -> genre.slug }.toSet(), it.rateStars) }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { seriesIdentity(it.name, it.source, it.slug) }
            .take(SIMILAR_COUNT)
            .map { candidate ->
                SeriesSummary(candidate.aid, candidate.name, candidate.slug, SeriesKind.fromSlug(candidate.type).code, candidate.source)
            }
    }

    companion object {
        private const val SIMILAR_COUNT = 20

        /** Ficha de la serie de [url]: la de la fuente puede venir de la copia de hasta 60 min (se renueva en segundo plano pasados 10 min); nota y relaciones se recalculan al unir las demás fuentes. */
        suspend fun open(url: String): Series? = AnimeSources.openAnime(url)?.let { AnimeSources.complete(it) }
    }
}

data class Chapter(
    val eid: Int,
    val number: Double,
    val aid: Int,
    val slug: String,
    val name: String,
    val source: String = AnimeAv1.ID
) {
    /**
     * Si la fuente da una captura por capítulo, la portada de la serie para ver ya mientras llega la captura (la tarjeta la
     * sustituye al cargar). Sin captura propia la tarjeta usa la portada y no hace falta nada debajo.
     */
    val coverUnderThumbnail: List<String>
        get() = if (AnimeSources.of(source).thumbnailUrl(aid, number.roundedString()).isBlank()) emptyList()
        else listOfNotNull(userCover(slug, source), BestCovers.get(slug), AnimeSources.of(source).coverUrl(aid, slug)).distinct()

    // Sin miniatura de capítulo (animegratis, jkanime) se usa la carátula de la serie.
    val thumbnail: String get() = AnimeSources.of(source).thumbnailUrl(aid, number.roundedString())
        .ifBlank { userCover(slug, source) ?: BestCovers.get(slug) ?: AnimeSources.of(source).coverUrl(aid, slug) }
    val link: String get() = AnimeSources.of(source).chapterUrl(slug, number.roundedString())
    val episodeName: String get() = "Episodio ${number.roundedString()}"

    /** Si el capítulo está visto, y cada vez que cambia (marcar o desmarcar desde cualquier pantalla). */
    fun seenFlow(): Flow<Boolean> = CacheDB.INSTANCE.recordAV1DAO().chapterIsSeenFlow(source, slug, number)

    fun asRecord() : Record {
        return Record(
            eid,
            name,
            number,
            aid,
            slug,
            System.currentTimeMillis(),
            source
        )
    }

    /** La clave de su progreso en [app.kaizen.commons.KaizenProgress]: su serie (0 si el libro aún no la conoce) y su número. */
    fun progressKey() = app.kaizen.commons.KaizenProgress.Key(seriesIdOf(source, slug), number)
}

data class ChapterEntry(
    @SerializedName("eid")
    val eid: Int,
    @SerializedName("number")
    val number: Double
) {
    @Ignore
    val name: String = "Episodio ${number.roundedString()}"
    fun thumbnail(anime: Series): String = AnimeSources.of(anime.source).thumbnailUrl(anime.aid, number.roundedString())
        .ifBlank { AnimeSources.of(anime.source).coverUrl(anime.aid, anime.slug) }
    fun asRecord(anime: Series, date: Long = System.currentTimeMillis()) : Record {
        return Record(
            eid,
            anime.name,
            number,
            anime.aid,
            anime.slug,
            date,
            anime.source
        )
    }

    fun withID(anime: Series): Chapter = Chapter(eid, number, anime.aid, anime.slug, anime.name, anime.source)

    fun link(anime: Series): String {
        return AnimeSources.of(anime.source).chapterUrl(anime.slug, number.roundedString())
    }

    fun episodeName(anime: Series): String {
        return "${anime.name} - Episodio ${number.roundedString()}"
    }
}

data class Relation(
    @SerializedName("aid")
    val aid: Int,
    @SerializedName("name")
    val name: String,
    @SerializedName("relation")
    val relation: Int,
    @SerializedName("slug")
    val slug: String,
    // Fuente donde está la serie relacionada; null en datos guardados antes de multifuente = animeav1.
    @SerializedName("source")
    val source: String? = null
) {
    private val owner get() = AnimeSources.of(source)
    val animeUrl: String get() = owner.animeUrl(slug)

    /** La relación como ficha básica de serie (la que entienden los menús y las tarjetas). */
    fun asSummary() = SeriesSummary(aid, name, slug, 1, owner.id, null, 0, null)
    val imageUrl: String get() = userCover(slug, owner.id) ?: owner.coverUrl(aid, slug)
}

/** Códigos de relación entre series (los de animeav1; las demás fuentes se traducen a ellos). */
const val RELATION_PREQUEL = 1
const val RELATION_SEQUEL = 2

data class Genre(
    @SerializedName("name")
    val name: String,
    @SerializedName("slug")
    val slug: String
)

@Dao
abstract class SourceFichaDao {

    @get:Query("SELECT count(*) FROM SourceFicha")
    abstract val count: Int

    @Query("SELECT * FROM SourceFicha WHERE slug = :slug")
    abstract fun findBySlug(slug: String): Series?

    /** Animes con el género dado (el campo guarda «nombre:slug;…»); se filtra después por slug exacto. */
    @Query("SELECT * FROM SourceFicha WHERE aid != :aid AND genres LIKE :pattern ORDER BY rateStars DESC LIMIT 150")
    abstract fun findByGenrePattern(aid: Int, pattern: String): List<Series>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun add(directoryAV1: Series)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun addAll(directoryAV1: List<Series>)

    @Query("DELETE FROM SourceFicha")
    abstract fun nuke()
}

@Dao
interface AiringSeriesDao {

    @Query("SELECT * FROM AiringSeries")
    suspend fun all(): List<AiringSeries>

    @Query("SELECT * FROM AiringSeries WHERE aid = :aid")
    suspend fun find(aid: Int): AiringSeries?

    @Query("DELETE FROM AiringSeries")
    suspend fun nuke()

    @Transaction
    suspend fun update(list: List<AiringSeries>) {
        nuke()
        addAll(list)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(directoryAV1: AiringSeries)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addAll(list: List<AiringSeries>)
}

class DirectoryConverter {
    @TypeConverter
    fun chaptersToString(chapters: List<ChapterEntry>): String {
        return chapters.joinToString(";") { "${it.eid}:${it.number}" }
    }
    @TypeConverter
    fun stringToChapters(string: String): List<ChapterEntry> {
        // Una lista vacía se guarda como texto vacío: no es un error (antes saltaba un E22 «Index: 1, Size: 1» cada vez que se leía una ficha sin capítulos).
        return string.split(";").filter { it.isNotBlank() }.mapNotNull {
            val parts = it.split(":")
            if (parts.size != 2) return@mapNotNull null
            try {
                ChapterEntry(parts[0].toInt(), parts[1].toDouble())
            } catch (e: NumberFormatException) {
                logError(e)
                null
            }
        }
    }
    @TypeConverter
    fun genresToString(genres: List<Genre>): String {
        return genres.joinToString(";") { "${it.name}:${it.slug}" }
    }
    @TypeConverter
    fun stringToGenres(string: String): List<Genre> {
        return string.split(";").mapNotNull {
            try {
                val (name, slug) = it.split(":")
                Genre(name, slug)
            } catch (e: Exception) {
                null
            }
        }
    }
    @TypeConverter
    fun relationsToString(relations: List<Relation>?): String {
        return SharedGson.gson.toJson(relations?:emptyList<Relation>())
    }

    @TypeConverter
    fun stringToRelations(string: String): List<Relation> {
        return SharedGson.gson.fromJson(string, Array<Relation>::class.java).toList()
    }

}
