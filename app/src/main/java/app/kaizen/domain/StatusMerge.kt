package app.kaizen.domain

import kotlin.time.Duration.Companion.days

/**
 * La fusión de los estados de serie de dos dispositivos, sin Android: una serie que aquí no tiene estado lo recibe, y una que ya lo tiene lo cambia solo si el estado de la copia es más
 * reciente (`StatusEntry.at`). Una hora de más de un día en el futuro (un reloj roto) cuenta como ninguna, venga de fuera o sea de aquí: un dispositivo con la fecha mal puesta no
 * gana siempre, y si es este, acepta el estado del otro. Con la misma hora decide el estado (y después la clave), para que los dos dispositivos acaben con lo mismo.
 */
internal object StatusMerge {
    private val MAX_FUTURE = 1.days

    /** Lo que dejó [merge]: el mapa, cuántas series entraron nuevas y cuántas cambiaron su estado por uno más reciente (una sustitución cuenta una vez aunque cambie de clave). */
    class Result(val map: Map<String, StatusEntry>, val added: Int, val replaced: Int) {
        val changed: Int get() = added + replaced
    }

    private fun plausible(at: Long, now: Long): Long = at.takeIf { it in 1..(now + MAX_FUTURE.inWholeMilliseconds) } ?: 0L

    /** ¿Gana [entry] (con clave [key]) a la entrada de aquí? Más reciente gana; con la misma hora, el mayor estado y después la mayor clave; sin hora en ninguno, se queda la de aquí. */
    private fun wins(key: String, entry: StatusEntry, theirs: Long, localKey: String, local: StatusEntry, now: Long): Boolean {
        val mine = plausible(local.at, now)
        return when {
            theirs != mine -> theirs > mine
            theirs == 0L -> false
            entry.status != local.status -> entry.status > local.status
            else -> key > localKey // misma clave, hora y estado: ya es lo mismo y no cambia
        }
    }

    /**
     * [local] con lo que [incoming] aporta. [seriesOf] da la serie de una entrada (dos entradas con la misma son la misma serie aunque se guardaran con otro título); [skip] descarta una
     * entrada ajena (una lápida más nueva que la copia la quitó aquí). El resultado conserva una sola entrada por serie. Lineal: las entradas de aquí se buscan por serie, no una a una.
     */
    fun mergeCounted(
        local: Map<String, StatusEntry>,
        incoming: Map<String, StatusEntry>,
        seriesOf: (StatusEntry) -> Any,
        skip: (StatusEntry) -> Boolean,
        now: Long = System.currentTimeMillis()
    ): Result {
        val result = LinkedHashMap(local)
        val keysBySeries = HashMap<Any, MutableSet<String>>()
        for ((key, entry) in result) keysBySeries.getOrPut(seriesOf(entry)) { LinkedHashSet() } += key
        // Poner [entry] con [key]; si esa clave era de otra serie (el mismo título para dos series), deja de contar para aquella.
        fun put(key: String, entry: StatusEntry, keys: MutableSet<String>) {
            result.put(key, entry)?.let { old -> keysBySeries[seriesOf(old)]?.remove(key) }
            keys += key
        }
        var added = 0
        var replaced = 0
        for ((key, entry) in incoming) {
            if (skip(entry)) continue
            val keys = keysBySeries.getOrPut(seriesOf(entry)) { LinkedHashSet() }
            val existing = keys.map { it to result.getValue(it) }
            val theirs = plausible(entry.at, now)
            when {
                existing.isEmpty() -> { put(key, entry, keys); added++ }
                existing.all { (localKey, localEntry) -> wins(key, entry, theirs, localKey, localEntry, now) } -> {
                    existing.forEach { result.remove(it.first) }
                    keys.clear()
                    put(key, entry, keys)
                    replaced++
                }
            }
        }
        return Result(result, added, replaced)
    }

    fun merge(
        local: Map<String, StatusEntry>,
        incoming: Map<String, StatusEntry>,
        seriesOf: (StatusEntry) -> Any,
        skip: (StatusEntry) -> Boolean,
        now: Long = System.currentTimeMillis()
    ): Map<String, StatusEntry> = mergeCounted(local, incoming, seriesOf, skip, now).map

    /** La hora del último cambio de las entradas de una serie ([matches]); 0 si no tiene. Una hora de más de un día en el futuro (reloj roto) cuenta como 0. */
    fun latestChange(map: Map<String, StatusEntry>, matches: (StatusEntry) -> Boolean, now: Long = System.currentTimeMillis()): Long =
        map.values.filter(matches).maxOfOrNull { plausible(it.at, now) } ?: 0L

    /** [map] sin las entradas de una serie ([matches]), y las que se quitaron. */
    fun removeSeries(map: Map<String, StatusEntry>, matches: (StatusEntry) -> Boolean): Pair<Map<String, StatusEntry>, List<StatusEntry>> {
        val removed = map.filterValues(matches)
        return (map - removed.keys) to removed.values.toList()
    }

    /** De [incoming], lo que es de una serie que en [local] no tiene estado ni con esa clave ni con otra ([seriesOf]): lo que añade una copia local al restaurarla. */
    fun missing(local: Map<String, StatusEntry>, incoming: Map<String, StatusEntry>, seriesOf: (StatusEntry) -> Any): Map<String, StatusEntry> {
        val known = local.values.mapTo(HashSet(), seriesOf)
        return incoming.filter { (key, entry) -> key !in local && seriesOf(entry) !in known }
    }

    /**
     * Cómo alinear la exclusión de recomendaciones («Descartada») tras pasar de [before] a [after]: qué entradas liberar (dejaron de estar descartadas) y cuáles excluir (lo están
     * ahora y antes no). Primero se libera y después se excluye: una serie que sigue «Descartada» con otro título comparte la clave de exclusión y la liberación la desharía.
     */
    fun droppedChanges(before: Map<String, StatusEntry>, after: Map<String, StatusEntry>): Pair<List<StatusEntry>, List<StatusEntry>> {
        val dropped = SeriesStatus.DROPPED.id
        val droppedBefore = before.filterValues { it.status == dropped }
        val droppedAfter = after.filterValues { it.status == dropped }
        return droppedBefore.filterKeys { it !in droppedAfter }.values.toList() to droppedAfter.filterKeys { it !in droppedBefore }.values.toList()
    }
}
