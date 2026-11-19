package app.kaizen.home

import androidx.annotation.Keep
import androidx.core.content.edit
import app.kaizen.commons.KaizenStore
import app.kaizen.commons.logError
import app.kaizen.pojos.av1.SeriesSummary
import app.kaizen.source.normalizeTitle
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * «Recomendados» cambia una vez al día. Antes se sorteaba de nuevo cada vez que se abría la app, pero con las mismas candidatas y con las secuelas sin ver siempre delante, así que salía
 * casi lo mismo. Ahora la tanda de hoy se guarda y se enseña igual todo el día, y la de mañana evita lo enseñado en los últimos días ([RECENT_KEEP] títulos) mientras queden candidatas.
 * Es una caché: se puede borrar sin perder nada (se rehace y vuelve a sortear).
 */
internal object DailyRecommendations {
    /** Cuántos títulos de días anteriores se evitan (unos tres días de 15). */
    const val RECENT_KEEP = 45

    // Nombres fijos: el APK se ofusca (R8) y el JSON guardado debe sobrevivir a las actualizaciones.
    @Keep
    class Stored(
        @SerializedName("day") val day: Long,
        @SerializedName("items") val items: List<SeriesSummary>,
        @SerializedName("recent") val recent: List<String>
    )

    private const val KEY = "daily"
    private val gson = Gson()
    private val prefs get() = KaizenStore.prefs("kaizen_recommended_daily")

    private fun read(): Stored? = try {
        prefs.getString(KEY, null)?.let { gson.fromJson(it, Stored::class.java) }?.takeIf { it.items != null && it.recent != null }
    } catch (e: Exception) {
        // Ilegible o de otra versión: se descarta y se rehace; no debe tumbar la sección.
        logError(e)
        prefs.edit { remove(KEY) }
        null
    }

    /** La tanda guardada de [day], si es de hoy; null si no hay o es de otro día. */
    @Synchronized fun savedFor(day: Long): List<SeriesSummary>? = read()?.takeIf { it.day == day && it.items.isNotEmpty() }?.items

    /** Títulos normalizados de lo enseñado en los últimos días, **incluida la tanda guardada** (sea de hoy o de otro día): al rehacer, ni lo de hoy ni lo de ayer deben repetirse. */
    @Synchronized fun recentTitles(): Set<String> = read()?.let { rotate(it.recent, it.items.map { s -> normalizeTitle(s.name) }) }.orEmpty().toSet()

    /** Guarda la tanda de [day]. Lo que había guardado (de ayer, o una tanda anterior de hoy que se rehace) pasa a la lista de recientes. */
    @Synchronized fun save(day: Long, items: List<SeriesSummary>) {
        val previous = read()
        val recent = previous?.let { rotate(it.recent, it.items.map { s -> normalizeTitle(s.name) }) }.orEmpty()
        prefs.edit { putString(KEY, gson.toJson(Stored(day, items, recent))) }
    }

    /** Lo enseñado ayer delante y lo anterior detrás, sin repetidos y sin pasar de [keep]. */
    fun rotate(recent: List<String>, yesterday: List<String>, keep: Int = RECENT_KEEP): List<String> = (yesterday + recent).distinct().take(keep)
}
