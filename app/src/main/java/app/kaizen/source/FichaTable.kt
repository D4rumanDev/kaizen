package app.kaizen.source

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import java.util.concurrent.atomic.AtomicLong

/**
 * Una fila por ficha de una fuente `(fuente, slug)`: a qué serie pertenece ([seriesId]) y, aparte, a qué anime de AniList (las demás columnas).
 * Sustituye a tres tablas que guardaban lo mismo por separado (`SeriesMap`, `AnimeFuente` y `SeriesRegistry`, esta solo repartía ids). Las columnas
 * del enlace con AniList son null mientras la ficha no tiene ninguna decisión (ni del sistema ni del usuario): una fila de enlace «existe» si
 * [estado] no es null. Una unión de series cambia el `seriesId` de las fichas del id absorbido: no hay tabla de redirecciones.
 */
@Entity(tableName = "Ficha", primaryKeys = ["fuente", "slug"], indices = [Index("seriesId"), Index("anilistId")])
data class FichaRow(
    val fuente: String,
    val slug: String,
    val seriesId: Long,
    val anilistId: Long? = null,
    val estado: String? = null,
    val origen: String? = null,
    val nivel: String? = null,
    val puntuacion: Double? = null,
    val fecha: Long? = null
)

/** Las consultas de la tabla [FichaRow] . No se usa directamente: la app pasa por [SeriesIdStore] y [AnimeFuenteStore]. */
@Dao
abstract class FichaDao {
    // ---- libro de ids -------------------------------------------------------------------------------------------------------------
    @Query("SELECT fuente AS sourceId, slug, seriesId FROM Ficha")
    abstract fun allMap(): List<SeriesMap>

    @Query("SELECT EXISTS(SELECT 1 FROM Ficha)")
    abstract fun any(): Boolean

    @Query("SELECT seriesId FROM Ficha WHERE fuente = :fuente AND slug = :slug LIMIT 1")
    abstract fun idOf(fuente: String, slug: String): Long?

    @Query("INSERT OR IGNORE INTO Ficha (fuente, slug, seriesId) VALUES (:fuente, :slug, :seriesId)")
    abstract fun insertIfAbsent(fuente: String, slug: String, seriesId: Long)

    @Query("UPDATE Ficha SET seriesId = :seriesId WHERE fuente = :fuente AND slug = :slug")
    abstract fun setSeries(fuente: String, slug: String, seriesId: Long)

    @Query("UPDATE Ficha SET seriesId = :toId WHERE seriesId = :fromId")
    abstract fun moveSeries(fromId: Long, toId: Long)

    /** El mayor id de serie que hay en cualquier sitio (fichas, favoritos y vistos); null si no hay ninguno. */
    @Query(
        "SELECT MAX(m) FROM (SELECT MAX(seriesId) AS m FROM Ficha UNION ALL SELECT MAX(seriesId) FROM FavoriteAV1 UNION ALL SELECT MAX(seriesId) FROM Record)"
    )
    abstract fun highWater(): Long?

    // ---- enlace con AniList --------------------------------------------------------------------------------------------------------
    @Query(
        "SELECT fuente, slug, anilistId, estado, COALESCE(origen, 'auto') AS origen, COALESCE(nivel, '') AS nivel, COALESCE(puntuacion, 0.0) AS puntuacion, " +
            "COALESCE(fecha, 0) AS fecha FROM Ficha WHERE estado IS NOT NULL"
    )
    abstract fun links(): List<AnimeFuente>

    @Query(
        "SELECT fuente, slug, anilistId, estado, COALESCE(origen, 'auto') AS origen, COALESCE(nivel, '') AS nivel, COALESCE(puntuacion, 0.0) AS puntuacion, " +
            "COALESCE(fecha, 0) AS fecha FROM Ficha WHERE fuente = :fuente AND slug = :slug AND estado IS NOT NULL"
    )
    abstract fun link(fuente: String, slug: String): AnimeFuente?

    @Query("SELECT DISTINCT anilistId FROM Ficha WHERE estado = 'aplicado' AND anilistId IS NOT NULL")
    abstract fun appliedIds(): List<Long>

    @Query("SELECT estado, COUNT(*) AS total FROM Ficha WHERE estado IS NOT NULL GROUP BY estado")
    abstract fun countByState(): List<AnimeFuenteDao.StateCount>

    @Query("UPDATE Ficha SET fecha = 0 WHERE origen = 'auto'")
    abstract fun expireAuto()

    /** Quita el enlace automático de la ficha (la ficha y su serie se quedan; el del usuario no se toca). */
    @Query("UPDATE Ficha SET anilistId = NULL, estado = NULL, origen = NULL, nivel = NULL, puntuacion = NULL, fecha = NULL WHERE fuente = :fuente AND slug = :slug AND origen = 'auto'")
    abstract fun clearAutoLink(fuente: String, slug: String)

    @Query("UPDATE Ficha SET anilistId = :anilistId, estado = :estado, origen = :origen, nivel = :nivel, puntuacion = :puntuacion, fecha = :fecha WHERE fuente = :fuente AND slug = :slug")
    abstract fun setLink(fuente: String, slug: String, anilistId: Long?, estado: String, origen: String, nivel: String, puntuacion: Double, fecha: Long)

    /** Como [setLink] pero solo si la ficha aún no tiene enlace. */
    @Query(
        "UPDATE Ficha SET anilistId = :anilistId, estado = :estado, origen = :origen, nivel = :nivel, puntuacion = :puntuacion, fecha = :fecha " +
            "WHERE fuente = :fuente AND slug = :slug AND estado IS NULL"
    )
    abstract fun setLinkIfAbsent(fuente: String, slug: String, anilistId: Long?, estado: String, origen: String, nivel: String, puntuacion: Double, fecha: Long)
}

/**
 * El libro de ids ([SeriesIdDao]) sobre la tabla [FichaRow]. Los ids nuevos salen de un contador en memoria que arranca del mayor id que hay en
 * la base: no hace falta una tabla que reparta ids, y dos hilos nunca reciben el mismo. Un id repartido que no llega a guardarse queda sin usar;
 * tras reiniciar la app podría repartirse otra vez, y no pasa nada porque nada lo nombraba. [inTransaction] es la transacción de `user-db`.
 */
internal class SeriesIdStore(
    private val dao: FichaDao,
    private val inTransaction: (() -> Unit) -> Unit,
    /** El mayor id que nombran las reglas del usuario: un id absorbido que ya no tiene ficha no debe repartirse otra vez. */
    private val ruleHighWater: () -> Long = { 0L }
) : SeriesIdDao {
    private val high = AtomicLong(UNSEEDED)

    private fun seed() {
        if (high.get() == UNSEEDED) high.compareAndSet(UNSEEDED, maxOf(dao.highWater() ?: 0L, ruleHighWater()))
    }

    override fun newSeries(registry: SeriesRegistry): Long {
        seed()
        return high.incrementAndGet()
    }

    override fun putRegistry(rows: List<SeriesRegistry>) {
        seed()
        rows.forEach { row -> high.accumulateAndGet(row.seriesId) { a, b -> maxOf(a, b) } }
    }

    override fun hasRegistry() = dao.any()

    override fun allMap() = dao.allMap()

    override fun idOf(sourceId: String, slug: String) = dao.idOf(sourceId, slug)

    override fun putMap(rows: List<SeriesMap>) = inTransaction {
        rows.forEach { dao.insertIfAbsent(it.sourceId, it.slug, it.seriesId); dao.setSeries(it.sourceId, it.slug, it.seriesId) }
    }

    override fun putMapIfAbsent(rows: List<SeriesMap>) = inTransaction {
        rows.forEach { dao.insertIfAbsent(it.sourceId, it.slug, it.seriesId) }
    }

    override fun moveSeries(moves: Map<Long, Long>) = inTransaction { moves.forEach { (from, to) -> dao.moveSeries(from, to) } }

    override fun restoreLedgerIfEmpty(registry: List<SeriesRegistry>, map: List<SeriesMap>): Boolean {
        var restored = false
        inTransaction { restored = super.restoreLedgerIfEmpty(registry, map) }
        return restored
    }

    override fun splitAllSeries() = inTransaction { super.splitAllSeries() }

    private companion object {
        const val UNSEEDED = -1L
    }
}

/** Los enlaces con AniList ([AnimeFuenteDao]) sobre la tabla [FichaRow]. Un enlace de una ficha que aún no tiene fila crea la ficha con un id nuevo. */
internal class AnimeFuenteStore(
    private val dao: FichaDao,
    private val ids: SeriesIdDao,
    private val inTransaction: (() -> Unit) -> Unit
) : AnimeFuenteDao {
    override fun all() = dao.links()

    override fun get(fuente: String, slug: String) = dao.link(fuente, slug)

    override fun appliedIds() = dao.appliedIds()

    override fun countByState() = dao.countByState()

    override fun expireAuto() = dao.expireAuto()

    override fun deleteAuto(fuente: String, slug: String) = dao.clearAutoLink(fuente, slug)

    override fun insertIfAbsent(rows: List<AnimeFuente>) = inTransaction {
        rows.forEach { ensureFicha(it); dao.setLinkIfAbsent(it.fuente, it.slug, it.anilistId, it.estado, it.origen, it.nivel, it.puntuacion, it.fecha) }
    }

    override fun put(rows: List<AnimeFuente>) = inTransaction {
        rows.forEach { ensureFicha(it); dao.setLink(it.fuente, it.slug, it.anilistId, it.estado, it.origen, it.nivel, it.puntuacion, it.fecha) }
    }

    private fun ensureFicha(row: AnimeFuente) {
        if (dao.idOf(row.fuente, row.slug) == null) dao.insertIfAbsent(row.fuente, row.slug, ids.newSeries(SeriesRegistry()))
    }
}
