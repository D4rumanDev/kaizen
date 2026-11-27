package app.kaizen.source

import app.kaizen.commons.DebugLog
import app.kaizen.database.CatalogDB
import app.kaizen.database.UserDB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Los enlaces con AniList que enseñan las pantallas de revisión (Configuración › Series): qué fichas quedan dudosas, decidir, saltar y deshacer. Es la única puerta de la interfaz a las
 * tablas de enlaces y de fichas de AniList (lo comprueba `ArquitecturaTest`); la lógica está en [AnilistReview].
 */
internal object AnilistReviewRepository {
    private val links get() = UserDB.INSTANCE.animeFuenteDao()
    private val fichas get() = CatalogDB.INSTANCE.animeFichaDao()

    /** Las series con un candidato dudoso de AniList, leídas de la base (sin red). Antes enlaza las de un anime que ya has confirmado en otra ficha. */
    suspend fun pending(adopt: Boolean = true): List<PendingSeries> = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        val work = AnilistSyncRunner.work()
        var rows = links.all().associateBy { it.fuente to it.slug }
        // Con adopt = false (la prueba por ADB) no se escribe nada.
        val titles by lazy { fichas.titles().associateBy { it.anilistId } }
        // La fila tal como se guardaría: si el comprobador de enlaces la quitaría en el siguiente arranque, no se adopta.
        val sound = { row: AnimeFuente -> AnilistLinkCheck.inconsistent(listOf(row), titles).isEmpty() }
        val adopted = if (adopt) AnilistReview.adoptions(work, { f, s -> rows[f to s] }, links.appliedIds().toSet(), System.currentTimeMillis(), sound) else emptyList()
        if (adopted.isNotEmpty()) {
            DebugLog.info("AniList", "Enlazadas por anime ya confirmado: ${adopted.size} fichas (${adopted.map { it.anilistId }.distinct().joinToString()})")
            links.put(adopted)
            rows = links.all().associateBy { it.fuente to it.slug }
        }
        onlyActive(AnilistReview.pending(work) { f, s -> rows[f to s] }, activeSourceIds()).also {
            DebugLog.info("Medición", "Series por revisar: ${work.size} series, ${rows.size} filas, ${it.size} pendientes, ${(System.nanoTime() - start) / 1_000_000} ms")
        }
    }

    /** Los ids de las fuentes activas (marcadas en Configuración). */
    private fun activeSourceIds(): Set<String> = AnimeSources.selected.map { it.id }.toSet()

    /**
     * Solo las fichas de fuentes activas: las uniones con AniList de una fuente desactivada no se enseñan (ni se preguntan). Una serie que se queda sin ninguna
     * ficha activa desaparece de la lista; si la fuente se vuelve a activar, vuelve a salir. Lo ya guardado de esas fichas no se toca.
     */
    internal fun onlyActive(list: List<PendingSeries>, active: Set<String>): List<PendingSeries> = list.mapNotNull { series ->
        val fichas = series.fichas.filter { (source, _) -> source in active }
        when {
            fichas.isEmpty() -> null
            fichas.size == series.fichas.size -> series
            else -> PendingSeries(fichas, series.anilistId, series.nivel, series.score, series.priority)
        }
    }

    /** Enlaza o separa la serie con el anime propuesto; devuelve lo que había antes de cada ficha para poder deshacerlo. */
    fun decide(series: PendingSeries, same: Boolean, raw: AnilistFichaRaw?, at: Long = System.currentTimeMillis()): List<AnimeFuente?> =
        AnilistReview.decide(series, same, links, fichas, raw, at)

    /** Da la propuesta por buena sin preguntar (ya hay pruebas de sobra). */
    fun accept(series: PendingSeries, score: Double, raw: AnilistFichaRaw, at: Long = System.currentTimeMillis()) =
        AnilistReview.accept(series, score, links, fichas, raw, at)

    /** Descarta la propuesta: no se vuelve a preguntar. */
    fun discard(series: PendingSeries, at: Long = System.currentTimeMillis()) = AnilistReview.discard(series, links, at)

    /** Deja cada ficha como estaba ([before]) antes de decidir. */
    fun undo(series: PendingSeries, before: List<AnimeFuente?>, at: Long = System.currentTimeMillis()) = AnilistReview.undo(series, before, links, at)

    /** Fichas con enlace aplicado y su anime de AniList: lo que usa la comprobación de las series unidas. Solo las de fuentes activas ([activeOnly]): no se enseñan las uniones de una desactivada. */
    fun appliedLinks(activeOnly: Boolean = true): Map<Pair<String, String>, Long> {
        val active = if (activeOnly) activeSourceIds() else null
        return links.all().filter { it.estado == AnimeFuente.APLICADO && (active == null || it.fuente in active) }
            .mapNotNull { row -> row.anilistId?.let { (row.fuente to row.slug) to it } }.toMap()
    }
}
