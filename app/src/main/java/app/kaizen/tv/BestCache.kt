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
 * «Mejores» guardado («en emisión» y «de siempre», cada una con su clave): calcularlo pide el ranking a AniList y lo resuelve a series, y eso en una tele lenta tardaba en salir cada vez
 * que se abría la sección. Se guarda el resultado y se rehace como mucho una vez a la semana. Si cambian las fuentes activas, el guardado no vale.
 */
internal object BestCache {
    /** Cuánto vale lo guardado antes de volver a pedirlo a las webs. */
    val MAX_AGE: Duration = 7.days

    // Nombres fijos: el APK se ofusca (R8) y el JSON guardado debe sobrevivir a las actualizaciones.
    @Keep
    class Stored(
        @SerializedName("at") val at: Long,
        @SerializedName("sources") val sources: List<String>,
        @SerializedName("items") val items: List<SeriesSummary>
    )

    private val gson = Gson()
    private val prefs get() = KaizenStore.prefs("kaizen_best_airing")

    /** Lo guardado para estas fuentes (cualquiera que sea su edad), o null si no hay o es de otro conjunto de fuentes. */
    @Synchronized fun load(sources: List<String>, key: String): Stored? = try {
        decode(prefs.getString(key, null), sources)
    } catch (e: Exception) {
        // JSON ilegible o de otra versión (Gson deja a null lo que falta aunque Kotlin lo declare no nulo): se descarta y se rehace; no debe tumbar la sección.
        logError(e)
        prefs.edit { remove(key) }
        null
    }

    /**
     * Lo guardado en [json] si vale para [sources]: no vacío, de las mismas fuentes y con todos sus campos (Gson deja a null lo que falta aunque Kotlin lo declare no nulo). Un JSON que no
     * se puede leer lanza la excepción de Gson: [load] la recoge y descarta lo guardado.
     */
    internal fun decode(json: String?, sources: List<String>): Stored? {
        val stored = json?.let { gson.fromJson(it, Stored::class.java) } ?: return null
        @Suppress("SENSELESS_COMPARISON")
        if (stored.sources == null || stored.items == null || stored.items.isEmpty() || stored.sources != sources.sorted()) return null
        return stored
    }

    @Synchronized fun save(sources: List<String>, items: List<SeriesSummary>, key: String, now: Long = System.currentTimeMillis()) {
        prefs.edit { putString(key, gson.toJson(Stored(now, sources.sorted(), items))) }
    }

    /** Si lo guardado el [at] aún vale en [now]. Un reloj retrasado (guardado «en el futuro») no vale: se rehace. */
    fun isFresh(at: Long, now: Long): Boolean = now >= at && (now - at).milliseconds < MAX_AGE
}
