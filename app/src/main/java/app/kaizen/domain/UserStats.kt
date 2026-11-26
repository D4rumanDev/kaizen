package app.kaizen.domain

import app.kaizen.pojos.av1.Record
import app.kaizen.source.seriesIdentity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.days

/** Lo que se enseña en «Estadísticas», calculado del historial. */
data class UserStats(
    val chapters: Int,
    val series: Int,
    val favorites: Int,
    val inProgress: Int,
    val lastWeek: Int,
    val lastMonth: Int,
    val bySource: List<Pair<String, Int>>,
    val firstWatchedAt: Long?,
    /** Las series con más capítulos vistos (nombre, capítulos), de más a menos. */
    val topSeries: List<Pair<String, Int>> = emptyList(),
    /** Capítulos vistos en cada uno de los últimos [MONTHS] meses (etiqueta del mes, capítulos), del más antiguo al actual. */
    val byMonth: List<Pair<String, Int>> = emptyList(),
    /** Días seguidos viendo algo hasta hoy (o hasta ayer, si hoy aún no has visto nada). */
    val currentStreak: Int = 0,
    val bestStreak: Int = 0
) {
    /** Horas aproximadas: el historial no guarda la duración de cada capítulo, así que se cuentan [MINUTES_PER_CHAPTER] min. */
    val estimatedHours: Int get() = chapters * MINUTES_PER_CHAPTER / 60

    companion object {
        const val MINUTES_PER_CHAPTER = 24
        const val MONTHS = 6
        const val TOP_SERIES = 5

        fun compute(history: List<Record>, favorites: Int, inProgress: Int, now: Long, zone: TimeZone = TimeZone.getDefault()): UserStats {
            val streaks = streaks(history, now, zone)
            return UserStats(
                chapters = history.size,
                series = history.map { seriesIdentity(it.name, it.source, it.slug) }.toSet().size,
                favorites = favorites,
                inProgress = inProgress,
                lastWeek = history.count { now - it.date <= 7.days.inWholeMilliseconds },
                lastMonth = history.count { now - it.date <= 30.days.inWholeMilliseconds },
                bySource = history.groupingBy { it.source }.eachCount().toList().sortedByDescending { it.second },
                firstWatchedAt = history.minOfOrNull { it.date },
                topSeries = topSeries(history),
                byMonth = byMonth(history, now, zone),
                currentStreak = streaks.first,
                bestStreak = streaks.second
            )
        }

        private fun topSeries(history: List<Record>): List<Pair<String, Int>> =
            history.groupBy { seriesIdentity(it.name, it.source, it.slug) }
                .map { (_, chapters) -> chapters.maxBy { it.date }.name to chapters.size }
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })
                .take(TOP_SERIES)

        private fun byMonth(history: List<Record>, now: Long, zone: TimeZone): List<Pair<String, Int>> {
            fun monthStart(offset: Int) = Calendar.getInstance(zone).apply {
                timeInMillis = now
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.MONTH, offset)
            }.timeInMillis
            val label = SimpleDateFormat("MMM", Locale.getDefault()).apply { timeZone = zone }
            return (-(MONTHS - 1)..0).map { offset ->
                val from = monthStart(offset)
                val to = monthStart(offset + 1)
                label.format(java.util.Date(from)) to history.count { it.date in from until to }
            }
        }

        /** (racha actual, mejor racha) en días naturales de [zone]. */
        private fun streaks(history: List<Record>, now: Long, zone: TimeZone): Pair<Int, Int> {
            fun day(time: Long) = Math.floorDiv(time + zone.getOffset(time), 1.days.inWholeMilliseconds)
            val days = history.map { day(it.date) }.toSortedSet().toList()
            if (days.isEmpty()) return 0 to 0
            var best = 1
            var run = 1
            for (i in 1 until days.size) {
                run = if (days[i] == days[i - 1] + 1) run + 1 else 1
                best = maxOf(best, run)
            }
            val today = day(now)
            var current = 0
            var cursor = if (today in days) today else today - 1
            while (cursor in days) { current++; cursor-- }
            return current to best
        }
    }
}
