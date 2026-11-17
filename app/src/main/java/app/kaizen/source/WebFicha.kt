package app.kaizen.source

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.json.JSONObject

/**
 * Lo que dio la web de una ficha al hacer scrap, guardado como **un único JSON con la estructura de [WebFichaSchema]** (la de `Media` de AniList, con todas
 * las claves siempre), igual que [AnimeFicha] guarda lo de AniList. Una fila por ficha de una fuente `(fuente, slug)`. Vive en `catalog-db`, que se puede borrar
 * sin perder nada del usuario: se vuelve a rellenar al abrir las fichas.
 *
 * El JSON es la fuente de verdad; las columnas se derivan de él ([FichaData]) solo para buscar sin descomprimirlo.
 */
@Entity(tableName = "WebFicha", primaryKeys = ["fuente", "slug"], indices = [Index("malId")])
data class WebFicha(
    val fuente: String,
    val slug: String,
    /** El id de MyAnimeList de la ficha, si la web lo da. */
    val malId: Long?,
    val descargada: Long,
    /** El JSON del registro, comprimido ([AnimeFicha.gzip]). */
    val json: ByteArray,
    /** Todos los nombres de la serie (título, inglés, sinónimos) normalizados, más el nativo tal cual, entre barras: `|nombre|otro nombre|`. Se busca con un «contiene». */
    val nombres: String,
    /** Año de estreno (el de la fecha de inicio o, si falta, el de la temporada). */
    val anio: Int?,
    /** Fecha de inicio como número `AAAAMMDD` (0 en lo que falte): para ordenar y comparar. */
    val inicio: Int?,
    val formato: String?,
    val estado: String?,
    val temporada: String?,
    val episodios: Int?,
    val adulto: Boolean?
) {
    /** El JSON ya descomprimido. */
    fun jsonText(): String = AnimeFicha.gunzip(json)

    override fun equals(other: Any?) = other is WebFicha && other.fuente == fuente && other.slug == slug && other.descargada == descargada

    override fun hashCode() = 31 * fuente.hashCode() + slug.hashCode()

    companion object {
        /** La fila de [media] (lo que devuelve [WebFichaSchema.build]) para la ficha [slug] de [fuente]. */
        fun of(fuente: String, slug: String, media: JSONObject, at: Long): WebFicha {
            val d = FichaData(media, fuente, slug, at)
            return WebFicha(
                fuente, slug, d.idMal, at, AnimeFicha.gzip(media.toString()),
                nombres = d.nombresBuscables, anio = d.anio, inicio = d.inicio, formato = d.formato, estado = d.estado, temporada = d.temporada, episodios = d.episodios, adulto = d.adulto
            )
        }
    }
}

/** Cuántas fichas guardadas hay de una fuente. */
data class FuenteCount(val fuente: String, val total: Int)

@Dao
interface WebFichaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(ficha: WebFicha)

    @Query("SELECT * FROM WebFicha WHERE fuente = :fuente AND slug = :slug")
    fun get(fuente: String, slug: String): WebFicha?

    /**
     * Las fichas con un nombre (título, inglés, sinónimo o nativo) que contiene el texto: [norm] es el texto normalizado ([AnilistMatcher.normalize]) y [raw]
     * el escrito tal cual (para el nombre en japonés). Es un «contiene», no una igualdad.
     */
    @Query("SELECT * FROM WebFicha WHERE (:norm != '' AND nombres LIKE '%' || :norm || '%') OR (:raw != '' AND nombres LIKE '%' || :raw || '%') ORDER BY fuente, slug LIMIT :limit")
    fun byName(norm: String, raw: String, limit: Int): List<WebFicha>

    /** Un tramo de fichas por orden (para recorrerlas por partes sin cargar todos los JSON a la vez). */
    @Query("SELECT * FROM WebFicha ORDER BY fuente, slug LIMIT :limit OFFSET :offset")
    fun page(limit: Int, offset: Int): List<WebFicha>

    @Query("SELECT COUNT(*) FROM WebFicha")
    fun count(): Int

    @Query("SELECT COUNT(*) FROM WebFicha WHERE malId IS NOT NULL")
    fun countWithMal(): Int

    @Query("SELECT fuente, COUNT(*) AS total FROM WebFicha GROUP BY fuente ORDER BY fuente")
    fun countByFuente(): List<FuenteCount>
}

/**
 * Las sentencias de `catalog-db` para [WebFicha]; una prueba las compara con el esquema que exporta Room. Es una caché que se vuelve a llenar al abrir las
 * fichas, así que al pasar a la versión 3 (el esquema único) se recrea en lugar de ir añadiendo columnas; no toca `AnimeFicha`.
 */
internal object WebFichaTables {
    val REBUILD: List<String> = listOf(
        "DROP TABLE IF EXISTS `WebFicha`",
        "CREATE TABLE IF NOT EXISTS `WebFicha` (`fuente` TEXT NOT NULL, `slug` TEXT NOT NULL, `malId` INTEGER, `descargada` INTEGER NOT NULL, `json` BLOB NOT NULL, " +
            "`nombres` TEXT NOT NULL, `anio` INTEGER, `inicio` INTEGER, `formato` TEXT, `estado` TEXT, `temporada` TEXT, `episodios` INTEGER, `adulto` INTEGER, " +
            "PRIMARY KEY(`fuente`, `slug`))",
        "CREATE INDEX IF NOT EXISTS `index_WebFicha_malId` ON `WebFicha` (`malId`)"
    )
}

/**
 * Las fichas guardadas que sirven para emparejar con AniList (traen el id de MyAnimeList, el mismo que `idMal` de AniList y sin ambigüedad, o otros nombres de la
 * serie): son [FichaData] y el emparejador lee de ellas `idMal`, `alternativas` y `at` (cuándo se guardó, para saber si es más nueva que la decisión que ya hay).
 */
internal object WebHints {
    private const val PAGE = 200

    fun of(ficha: WebFicha): FichaData? = runCatching { FichaData.of(ficha) }.getOrNull()?.takeIf { it.aportaPistas }

    /** Los datos de todas las fichas guardadas que aportan algo, por (fuente, slug); se lee por tramos. */
    fun load(dao: WebFichaDao): Map<Pair<String, String>, FichaData> = buildMap {
        var offset = 0
        while (true) {
            val rows = dao.page(PAGE, offset)
            rows.forEach { row -> of(row)?.let { put(row.fuente to row.slug, it) } }
            if (rows.size < PAGE) break
            offset += PAGE
        }
    }
}

/** Un dato sacado de la ficha guardada de una serie: de qué fuente y slug, y su valor ([value] null si esa ficha no tiene ese campo). */
data class WebFichaValue(val fuente: String, val slug: String, val value: String?, /** De dónde sale el dato en una consulta resuelta (`anilist` o la fuente); null si no se resolvió o no hay dato. */ val origen: String? = null)

/**
 * Consulta de lo guardado en [WebFicha] a partir del nombre de la serie: encuentra las fichas por un trozo de cualquiera de sus nombres y extrae de su JSON
 * cualquier campo con [FichaData.valor]: por el nombre de un getter (`generos`, `anio`, `nota`…) o por su ruta en el JSON (`title.native`, `genres[0]`, `web.score`).
 * La lectura del dato es de [FichaData]; aquí solo se buscan las fichas.
 */
object WebFichaQuery {
    /** Las fichas guardadas con un nombre (de cualquier idioma, sinónimos incluidos) que contiene [name]. */
    fun find(dao: WebFichaDao, name: String, limit: Int = Int.MAX_VALUE): List<WebFicha> {
        val norm = AnilistMatcher.normalize(name)
        val raw = name.trim()
        if (norm.isEmpty() && raw.isEmpty()) return emptyList()
        return dao.byName(norm, raw, limit)
    }

    /** El campo [path] de cada ficha que coincide con [name] (como mucho [limit]: se limita en la consulta, antes de descomprimir nada). */
    fun get(dao: WebFichaDao, name: String, path: String, limit: Int = Int.MAX_VALUE): List<WebFichaValue> =
        find(dao, name, limit).map { WebFichaValue(it.fuente, it.slug, runCatching { FichaData.of(it).valor(path) }.getOrNull()) }

    /**
     * Como [get], pero cada ficha de la web se resuelve con la de AniList a la que está enlazada ([anilistOf] la da; null si no hay enlace): el dato sale de AniList y, si
     * AniList no lo tiene, de la web ([FichaResuelta]). [WebFichaValue.origen] dice de cuál salió.
     */
    fun resolved(dao: WebFichaDao, name: String, path: String, limit: Int, anilistOf: (WebFicha) -> FichaData?): List<WebFichaValue> =
        find(dao, name, limit).map { row ->
            runCatching {
                val ficha = FichaResuelta(runCatching { anilistOf(row) }.getOrNull(), FichaData.of(row))
                WebFichaValue(row.fuente, row.slug, ficha.valor(path), ficha.origen(path))
            }.getOrElse { WebFichaValue(row.fuente, row.slug, null) }
        }
}
