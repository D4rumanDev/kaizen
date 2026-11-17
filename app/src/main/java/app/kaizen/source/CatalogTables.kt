package app.kaizen.source

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import app.kaizen.database.CacheDB

/**
 * Una serie del catálogo interno: solo datos básicos. [key] es el id único de la serie como texto (igual que [seriesId], que se
 * guarda aparte para consultas numéricas; ver [SeriesIds]); no es un slug. [seriesId] es 0 hasta que el catálogo se lo da.
 */
@Entity(tableName = "CatalogEntry", indices = [Index("seriesId")])
data class CatalogEntry(
    @PrimaryKey
    val key: String,
    @androidx.room.ColumnInfo(defaultValue = "0")
    val seriesId: Long = 0,
    val name: String,
    val score: Double?,
    val votes: Int,
    val year: Int?,
    val updatedAt: Long
)

/**
 * En qué fuente está una serie y con qué slug; una serie puede tener varios slugs en la misma fuente. Los índices sirven a
 * las búsquedas por slug (con o sin fuente) y al filtro por fuentes del listado, que si no recorrerían la tabla entera por
 * cada tarjeta.
 */
@Entity(
    tableName = "CatalogLink",
    primaryKeys = ["key", "sourceId", "slug"],
    indices = [Index("slug"), Index("sourceId", "slug")]
)
data class CatalogLink(
    val key: String,
    val sourceId: String,
    val slug: String,
    // Id de la fuente (animeav1 lo usa en la URL de la portada); 0 = no guardado, se usa el interno.
    @androidx.room.ColumnInfo(defaultValue = "0")
    val aid: Int = 0,
    @androidx.room.ColumnInfo(defaultValue = "0")
    val seriesId: Long = 0,
    // Año de estreno que da la fuente a esta ficha: la serie guarda el más antiguo, y una ficha unida a otra más vieja lo heredaría.
    val year: Int? = null,
    // Nombre que la fuente da a esta ficha: la serie lleva el de una de ellas (o el del usuario) y no sirve para reagrupar.
    val name: String? = null,
    // Nota (estrellas) y votos que la fuente da a esta ficha: la serie guarda la media de todas, y reagrupar no puede partir de ella
    // (cada reagrupado sumaría los votos de la serie entera una vez por ficha).
    val score: Double? = null,
    val votes: Int = 0
)

/** Cuántas fichas tiene una fuente en el catálogo. */
data class SourceCount(val sourceId: String, val total: Int)

@Dao
interface CatalogDao {
    /** Series presentes en alguna de [sources], filtradas y ordenadas (A-Z, o por nota si [byRating] = 1). */
    @Query(
        """SELECT * FROM CatalogEntry
        WHERE `key` IN (SELECT `key` FROM CatalogLink WHERE sourceId IN (:sources))
        AND (:text = '' OR name LIKE '%' || :text || '%')
        AND (:minScore IS NULL OR score >= :minScore)
        AND (:fromYear IS NULL OR year >= :fromYear)
        AND (:toYear IS NULL OR year <= :toYear)
        ORDER BY CASE WHEN :byRating = 1 THEN score END DESC, name COLLATE NOCASE ASC"""
    )
    fun entries(
        sources: List<String>, text: String, minScore: Double?, fromYear: Int?, toYear: Int?, byRating: Int
    ): PagingSource<Int, CatalogEntry>

    /** Series con alguna ficha cuyo slug contiene [fragment]. */
    @Query("SELECT * FROM CatalogEntry WHERE `key` IN (SELECT `key` FROM CatalogLink WHERE slug LIKE '%' || :fragment || '%') LIMIT 100")
    fun searchBySlug(fragment: String): List<CatalogEntry>

    @Query("SELECT * FROM CatalogEntry WHERE `key` IN (:keys)")
    fun entriesOf(keys: List<String>): List<CatalogEntry>

    @Query("SELECT * FROM CatalogLink WHERE `key` = :key")
    fun links(key: String): List<CatalogLink>

    @Query("SELECT COUNT(*) FROM CatalogEntry")
    fun count(): Int

    @Query("SELECT * FROM CatalogEntry")
    fun allEntries(): List<CatalogEntry>

    @Query("SELECT * FROM CatalogLink")
    fun allLinks(): List<CatalogLink>

    /** Cuántas fichas tiene cada fuente. */
    @Query("SELECT sourceId, COUNT(*) AS total FROM CatalogLink GROUP BY sourceId")
    fun countsBySource(): List<SourceCount>

    /** Las fichas de una fuente. */
    @Query("SELECT * FROM CatalogLink WHERE sourceId = :sourceId")
    fun linksOfSource(sourceId: String): List<CatalogLink>

    /** Borra los enlaces de las fuentes recién recorridas (se vuelven a escribir con lo que ahora listan). */
    @Query("DELETE FROM CatalogLink WHERE sourceId IN (:sourceIds)")
    fun deleteLinks(sourceIds: List<String>)

    /** Serie (clave) a la que pertenece un slug de una fuente. */
    @Query("SELECT `key` FROM CatalogLink WHERE sourceId = :sourceId AND slug = :slug LIMIT 1")
    fun keyOf(sourceId: String, slug: String): String?

    /** Serie (clave) de un slug cuando no se sabe de qué fuente viene. */
    @Query("SELECT `key` FROM CatalogLink WHERE slug = :slug ORDER BY sourceId LIMIT 1")
    fun keyOfSlug(slug: String): String?

    /** Slug de una serie en una fuente concreta. */
    @Query("SELECT slug FROM CatalogLink WHERE `key` = :key AND sourceId = :sourceId ORDER BY slug LIKE '%-latino', length(slug), slug LIMIT 1")
    fun slugOf(key: String, sourceId: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putEntries(entries: List<CatalogEntry>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putLinks(links: List<CatalogLink>)

    @Query("DELETE FROM CatalogEntry WHERE updatedAt < :before")
    fun purgeEntries(before: Long)

    @Query("DELETE FROM CatalogLink WHERE `key` NOT IN (SELECT `key` FROM CatalogEntry)")
    fun purgeOrphanLinks()

    @Query("DELETE FROM CatalogEntry")
    fun clearEntries()

    @Query("DELETE FROM CatalogLink")
    fun clearLinks()
}

/** Acceso a las tablas del catálogo (el mismo para quien lo lee y quien lo construye). */
internal val catalogDao: CatalogDao get() = CacheDB.INSTANCE.catalogDao()
