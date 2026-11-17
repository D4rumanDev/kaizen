package app.kaizen.source

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * La ficha de un anime tal como la devuelve AniList, en bruto. Vive en `catalog-db`,
 * que se puede borrar sin perder nada del usuario. Las columnas de títulos, año, formato y nota se derivan del [json] para listar y buscar
 * sin leerlo entero; si cambia la [consulta] se vuelven a rellenar.
 */
@Entity(tableName = "AnimeFicha")
data class AnimeFicha(
    @PrimaryKey val anilistId: Long,
    val malId: Long?,
    /** Versión de la consulta GraphQL con que se pidió ([AnilistClient.FICHA_VERSION]). */
    val consulta: Int,
    val descargada: Long,
    /** Respuesta de AniList para este anime, en JSON y comprimida ([gzip]). */
    val json: ByteArray,
    /** Capa de texto en español (Animeoteca), también comprimida; null hasta que exista (etapa C). */
    val es: ByteArray? = null,
    val tituloRomaji: String? = null,
    val tituloIngles: String? = null,
    val tituloNativo: String? = null,
    val anio: Int? = null,
    val formato: String? = null,
    val nota: Int? = null
) {
    /** El JSON de AniList ya descomprimido. */
    fun jsonText(): String = gunzip(json)

    override fun equals(other: Any?) = other is AnimeFicha && other.anilistId == anilistId && other.consulta == consulta && other.descargada == descargada
    override fun hashCode() = anilistId.hashCode()

    companion object {
        fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) } }.toByteArray()

        fun gunzip(bytes: ByteArray): String = GZIPInputStream(ByteArrayInputStream(bytes)).use { String(it.readBytes(), Charsets.UTF_8) }
    }
}

/** La ficha tal como llegó de AniList, lista para guardar; [es] conserva el texto de Animeoteca que ya tuviera. */
internal fun AnilistFichaRaw.toFicha(at: Long, es: ByteArray?) = AnimeFicha(
    id, malId, AnilistClient.FICHA_VERSION, at, AnimeFicha.gzip(json), es = es,
    tituloRomaji = AnilistMatcher.normalize(romaji), tituloIngles = AnilistMatcher.normalize(english),
    tituloNativo = native, anio = year, formato = format, nota = score
)

@Dao
interface AnimeFichaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(fichas: List<AnimeFicha>)

    @Query("SELECT * FROM AnimeFicha WHERE anilistId = :id")
    fun get(id: Long): AnimeFicha?

    /** Los anime cuya ficha está descargada con esta versión de la consulta y no es anterior a [since]. */
    @Query("SELECT anilistId FROM AnimeFicha WHERE consulta = :version AND descargada >= :since")
    fun freshIds(version: Int, since: Long): List<Long>

    /** Para decidir qué caduca antes: los que están en emisión se renuevan antes que los acabados. */
    @Query("SELECT anilistId, descargada FROM AnimeFicha WHERE consulta = :version")
    fun downloaded(version: Int): List<Downloaded>

    /** Los títulos (ya normalizados) de cada ficha descargada: para comprobar que un enlace de una ficha de fuente tiene sentido. */
    @Query("SELECT anilistId, tituloRomaji, tituloIngles, formato FROM AnimeFicha")
    fun titles(): List<Titles>

    @Query("SELECT COUNT(*) FROM AnimeFicha")
    fun count(): Int

    /** Fichas descargadas a las que aún no se ha mirado el texto en español (las que se miraron sin resultado llevan un marcador). */
    @Query("SELECT anilistId FROM AnimeFicha WHERE es IS NULL")
    fun idsWithoutEs(): List<Long>

    /** Las fichas con algo en la capa `es` (texto o marcador «sin datos»), para saber cuáles volver a pedir pasado un tiempo. */
    @Query("SELECT anilistId, es FROM AnimeFicha WHERE es IS NOT NULL")
    fun esMarks(): List<EsMark>

    @Query("UPDATE AnimeFicha SET es = :es WHERE anilistId = :id")
    fun setEs(id: Long, es: ByteArray)

    @Query("SELECT COUNT(*) FROM AnimeFicha WHERE es IS NOT NULL")
    fun countWithEs(): Int

    class EsMark(val anilistId: Long, val es: ByteArray?)

    data class Downloaded(val anilistId: Long, val descargada: Long)

    data class Titles(val anilistId: Long, val tituloRomaji: String?, val tituloIngles: String?, val formato: String? = null)
}
