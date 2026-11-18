package app.kaizen.commons

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Los **plazos** de lo que la app guarda y renueva, todos ajustables en Configuración › Actualización y caché (y por ADB). Cada uno sale de [KaizenSettings] con su valor de
 * siempre por defecto; sin Android a la vista (pruebas) o con un valor que no es una de las opciones, vale el de siempre.
 */
object Timings {
    private fun value(options: List<Int>, default: Int, read: () -> Int): Int = runCatching(read).getOrDefault(default).takeIf { it in options } ?: default

    /** Ficha de una serie en emisión: cuánto se sirve sin volver a pedirla. */
    fun fichaAiring(): Duration = value(KaizenSettings.FICHA_AIRING_OPTIONS, 60) { KaizenSettings.fichaAiringMinutes }.minutes

    /** Ficha de una serie finalizada y completa (con AniList que lo confirma): la copia casi no caduca. */
    fun fichaSettled(): Duration = value(KaizenSettings.FICHA_SETTLED_OPTIONS, 30) { KaizenSettings.fichaSettledDays }.days

    /** Cada cuánto se pregunta a una web cuántas series tiene (si cambió, se recorre entera: cientos de peticiones). */
    fun catalogCheck(): Duration = value(KaizenSettings.CATALOG_CHECK_OPTIONS, 14) { KaizenSettings.catalogCheckDays }.days

    /** Aunque el total no cambie, el catálogo de una web se recorre entero cada tanto (bajas, notas, años). */
    fun catalogWalk(): Duration = value(KaizenSettings.CATALOG_WALK_OPTIONS, 30) { KaizenSettings.catalogWalkDays }.days

    /** Horario de emisión (estrenos y cambios de día). */
    fun schedule(): Duration = value(KaizenSettings.SCHEDULE_HOURS_OPTIONS, 24) { KaizenSettings.scheduleHours }.hours

    /** Episodios publicados de una serie (una serie en emisión suma uno cada semana). */
    fun episodes(): Duration = value(KaizenSettings.EPISODES_HOURS_OPTIONS, 6) { KaizenSettings.episodesHours }.hours

    /** Notas de las series. */
    fun rating(): Duration = value(KaizenSettings.RATING_DAYS_OPTIONS, 15) { KaizenSettings.ratingDays }.days

    /** Listados, géneros y búsquedas de las webs. */
    fun pages(): Duration = value(KaizenSettings.PAGES_DAYS_OPTIONS, 7) { KaizenSettings.pagesDays }.days

    /**
     * Cuánto se conserva una respuesta guardada antes de borrarla: lo que pida el plazo más largo configurado más la dispersión máxima (el plazo × 1,5), y nunca menos de 30 días.
     * Así ningún plazo elegido en el menú (hasta 90 días) ni su dispersión chocan con la limpieza de la caché.
     */
    fun retention(): Duration = maxOf(30.days, listOf(fichaSettled(), rating(), pages(), schedule(), episodes()).max() * 3 / 2)

    /** Dispersión elegida en %; sin Android (pruebas) o sin ajuste, ninguna (los plazos son exactos). */
    private fun spreadPercent(): Int = runCatching { KaizenSettings.spreadPercent }.getOrDefault(0).takeIf { it in KaizenSettings.SPREAD_OPTIONS } ?: 0

    /**
     * El plazo [duration] repartido entre ±[spreadPercent] % según [seed] (la clave de lo que se guarda): el mismo elemento siempre cae en el mismo punto, y elementos distintos
     * caducan en días distintos en vez de todos a la vez, así las webs no reciben todas las peticiones de golpe. Solo desde 6 h: un plazo corto no necesita repartirse.
     */
    fun spread(duration: Duration, seed: Any): Duration = spread(duration, seed, spreadPercent())

    internal fun spread(duration: Duration, seed: Any, percent: Int): Duration {
        if (percent <= 0 || duration < 6.hours) return duration
        // Un número estable en [-1, 1] sacado de la clave (el hashCode de un String es el mismo en cada arranque).
        val unit = ((seed.hashCode().toLong() and 0xFFFF) / 32767.5) - 1.0
        return duration * (1.0 + unit * percent / 100.0)
    }
}
