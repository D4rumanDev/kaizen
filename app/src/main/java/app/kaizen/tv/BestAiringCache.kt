package app.kaizen.tv

import androidx.annotation.Keep
import androidx.core.content.edit
import app.kaizen.commons.KaizenStore
import app.kaizen.commons.logError
import app.kaizen.pojos.av1.SeriesSummary
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds

/**
 * «Mejores en emisión» guardado: calcularlo pide a cada web su lista de emisión y las ordena por la nota de AniList, y eso en una tele lenta tardaba en salir cada vez
 * que se abría la sección. Se guarda el resultado y se rehace como mucho una vez a la semana (o con «Actualizar»). Si cambian las fuentes activas, el guardado no vale.
 */
internal object BestAiringCache {
    /** Cuánto vale lo guardado antes de volver a pedirlo a las webs. */
    val MAX_AGE: Duration = 7.days

    // Nombres fijos: el APK se ofusca (R8) y el JSON guardado debe sobrevivir a las actualizaciones.
    @Keep
    class Stored(
        @SerializedName("at") val at: Long,
        @SerializedName("sources") val sources: List<String>,
        @SerializedName("items") val items: List<SeriesSummary>
    )

    private const val KEY = "best"
    private val gson = Gson()
    private val prefs get() = KaizenStore.prefs("kaizen_best_airing")

    /** Lo guardado para estas fuentes (cualquiera que sea su edad), o null si no hay o es de otro conjunto de fuentes. */
    @Synchronized fun load(sources: List<String>): Stored? = try {
        prefs.getString(KEY, null)?.let { gson.fromJson(it, Stored::class.java) }?.takeIf { it.sources == sources.sorted() && it.items.isNotEmpty() }
    } catch (e: Exception) {
        // JSON ilegible o de otra versión (Gson deja a null lo que falta aunque Kotlin lo declare no nulo): se descarta y se rehace; no debe tumbar la sección.
        logError(e)
        prefs.edit { remove(KEY) }
        null
    }

    @Synchronized fun save(sources: List<String>, items: List<SeriesSummary>, now: Long = System.currentTimeMillis()) {
        prefs.edit { putString(KEY, gson.toJson(Stored(now, sources.sorted(), items))) }
    }

    /** Si lo guardado el [at] aún vale en [now]. Un reloj retrasado (guardado «en el futuro») no vale: se rehace. */
    fun isFresh(at: Long, now: Long): Boolean = now >= at && (now - at).milliseconds < MAX_AGE
}
