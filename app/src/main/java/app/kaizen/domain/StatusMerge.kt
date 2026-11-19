package app.kaizen.domain

import kotlin.time.Duration.Companion.days

/**
 * La fusión de los estados de serie de dos dispositivos, sin Android: una serie que aquí no tiene estado lo recibe, y una que ya lo tiene lo cambia solo si el estado de la copia es más
 * reciente (`StatusEntry.at`). Una hora de más de un día en el futuro (un reloj roto) no cuenta, para que un dispositivo con la fecha mal puesta no gane siempre.
 */
internal object StatusMerge {
    private val MAX_FUTURE = 1.days

    /**
     * [local] con lo que [incoming] aporta. [same] dice si dos entradas son la misma serie (aunque se guardaran con otro título); [skip] descarta una entrada ajena (una lápida más nueva
     * que la copia la quitó aquí). El resultado conserva una sola entrada por serie.
     */
    fun merge(
        local: Map<String, StatusEntry>,
        incoming: Map<String, StatusEntry>,
        same: (local: StatusEntry, incoming: StatusEntry) -> Boolean,
        skip: (StatusEntry) -> Boolean,
        now: Long = System.currentTimeMillis()
    ): Map<String, StatusEntry> {
        val result = LinkedHashMap(local)
        for ((key, entry) in incoming) {
            if (skip(entry)) continue
            val existing = result.entries.filter { same(it.value, entry) }
            val theirs = entry.at.takeIf { it in 1..(now + MAX_FUTURE.inWholeMilliseconds) } ?: 0L
            when {
                existing.isEmpty() -> result[key] = entry
                existing.all { theirs > it.value.at } -> {
                    existing.forEach { result.remove(it.key) }
                    result[key] = entry
                }
            }
        }
        return result
    }
}
