package app.kaizen.commons

import androidx.annotation.Keep
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import app.kaizen.pojos.av1.RecentEpisode
import app.kaizen.source.AnimeAv1
import app.kaizen.source.AnimeSources
import app.kaizen.source.SeriesDedupe
import app.kaizen.source.SeriesMatch
import app.kaizen.source.seriesIdentity

/**
 * Kaizen: animeav1 solo publica los 20 últimos capítulos. Se van acumulando aquí (por eid,
 * que crece con el tiempo) hasta [KaizenSettings.recentsCount] para mostrar más días.
 */
object RecentsArchive {
    private const val KEY = "items"

    // A igual fecha el orden no depende de qué fuente contestó antes: episodio más alto primero y, si aún empatan, fuente, slug y por último el id (que solo se puede comparar dentro de una misma fuente).
    private val TIE_BREAK = compareByDescending<Entry> { it.number }.thenBy { it.source.orEmpty() }.thenBy { it.slug }.thenByDescending { it.eid }

    // Nombres fijos: el APK se ofusca (R8) y el JSON guardado debe sobrevivir a las actualizaciones.
    // addedAt = momento en que se vio por primera vez: el orden de «recientes» es ese, no el id del episodio.
    // source puede faltar en datos guardados antes de multifuente: se trata como animeav1.
    @Keep
    private data class Entry(
        @SerializedName("aid") val aid: Int,
        @SerializedName("eid") val eid: Int,
        @SerializedName("name") val name: String,
        @SerializedName("number") val number: Double,
        @SerializedName("slug") val slug: String,
        @SerializedName("source") val source: String? = null,
        @SerializedName("addedAt") val addedAt: Long = 0L,
        @SerializedName("released") val released: Long = 0L
    )

    private val prefs get() = app.kaizen.commons.KaizenStore.prefs("kaizen_recents")
    private val gson = Gson()
    private val listType = object : TypeToken<List<Entry>>() {}.type

    /**
     * Añade los recientes de la web y devuelve la lista completa, del más nuevo al más viejo.
     * [latest] llega en el orden de la fuente (más nuevo primero): dentro de una tanda nueva se conserva ese orden.
     */
    @Synchronized fun merge(latest: List<RecentEpisode>): List<RecentEpisode> {
        // Solo las fuentes activas: lo guardado de una fuente desmarcada (o sin source) se descarta.
        val enabledIds = AnimeSources.enabled.map { it.id }.toSet()
        val stored = load().filter { (it.source ?: AnimeAv1.ID) in enabledIds }
            // Guardados sin fecha (antes de saber leerla): se completan con la que traen ahora.
            .map { old ->
                latest.firstOrNull { it.source == (old.source ?: AnimeAv1.ID) && it.eid == old.eid && it.released > 0 }
                    ?.takeIf { old.released == 0L }?.let { old.copy(released = it.released) } ?: old
            }
        val now = System.currentTimeMillis()
        val known = stored.mapTo(HashSet()) { (it.source ?: AnimeAv1.ID) to it.eid }
        // La caché de la base de datos puede traer recientes de fuentes que ya se desmarcaron.
        val fresh = latest.filter { it.source in enabledIds }.mapIndexedNotNull { index, item ->
            if ((item.source to item.eid) in known) null
            else Entry(item.aid, item.eid, item.name, item.number, item.slug, item.source, addedAt = now - index, released = item.released)
        }
        val dedupe = SeriesDedupe()
        // Un mismo episodio por su serie (id o título) y, aparte, por su slug sin sufijos: con otro título en otra fuente sigue siendo el mismo.
        val identity = { e: Entry -> listOf("${seriesIdentity(e.name, e.source ?: AnimeAv1.ID, e.slug)}|${e.number}", "s:${SeriesMatch.slugBase(e.slug)}|${e.number}") }
        val merged = newestFirst(fresh + stored, { it.released }, { it.addedAt }, identity, TIE_BREAK)
            // Un episodio por serie y número, aunque venga de dos plataformas con nombre distinto
            // («2nd Season» / «2nda Temporada»): mismo slug, mismo id de serie o mismo título normalizado (ver [SeriesDedupe]).
            .filter { entry -> dedupe.isNew(entry.slug, entry.name, entry.source ?: AnimeAv1.ID, scope = entry.number.toString()) }
            .take(KaizenSettings.recentsCount)
            // Orden final por fecha y hora, de más nuevo a más viejo (a igual fecha manda [TIE_BREAK]).
            .let { newestFirst(it, { e -> e.released }, { e -> e.addedAt }, identity, TIE_BREAK) }
        if (merged != stored) prefs.edit { putString(KEY, gson.toJson(merged)) }
        return merged.mapIndexed { index, entry ->
            RecentEpisode(index, entry.aid, entry.eid, entry.name, entry.number, entry.slug, entry.source ?: AnimeAv1.ID, entry.released)
        }
    }

    /** True si «recientes» ya tiene de la ficha ([source], [slug]) un episodio posterior a [latest]: la copia de su ficha se quedó atrás. */
    @Synchronized fun hasNewerThan(source: String, slug: String, latest: Double): Boolean =
        load().any { (it.source ?: AnimeAv1.ID) == source && it.slug == slug && it.number > latest }

    private fun load(): List<Entry> = try {
        prefs.getString(KEY, null)?.let { gson.fromJson<List<Entry>>(it, listType) }.orEmpty()
    } catch (e: com.google.gson.JsonParseException) {
        logError(e)
        emptyList()
    }
}

/**
 * [items] del más nuevo al más viejo por su fecha y hora de publicación ([released], 0 = la fuente no la da). Una entrada sin fecha usa la de
 * otra fuente para el mismo episodio ([identity] = serie y número) y solo si ninguna la tiene, cuándo se vio por primera vez ([added]):
 * así un episodio sin fecha no se cuela por delante de los que sí la tienen solo porque se leyó hace poco. A igual fecha gana el que
 * trae fecha propia, y después manda [tieBreak] (sin él se conserva el orden de entrada).
 */
internal fun <T> newestFirst(items: List<T>, released: (T) -> Long, added: (T) -> Long, identity: (T) -> List<String>, tieBreak: Comparator<T>? = null): List<T> {
    // Cada dato se lee una sola vez: las claves salen del id de serie, que otro hilo puede cargar o cambiar mientras se ordena, y un
    // comparador que da fechas distintas a la misma entrada hace fallar la ordenación («Comparison method violates its general contract»).
    val releasedAt = items.map(released)
    val keys = items.map(identity)
    val known = HashMap<String, Long>()
    items.indices.forEach { i -> if (releasedAt[i] > 0) keys[i].forEach { key -> known.merge(key, releasedAt[i]) { a, b -> maxOf(a, b) } } }
    val dates = items.indices.map { i -> if (releasedAt[i] > 0) releasedAt[i] else keys[i].mapNotNull { known[it] }.maxOrNull() ?: added(items[i]) }
    val byDate = compareByDescending<Int> { dates[it] }.thenByDescending { releasedAt[it] > 0 }
    val order = if (tieBreak == null) byDate else byDate.then { a, b -> tieBreak.compare(items[a], items[b]) }
    return items.indices.sortedWith(order).map { items[it] }
}
