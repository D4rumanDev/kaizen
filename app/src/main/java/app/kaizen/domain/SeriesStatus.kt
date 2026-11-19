package app.kaizen.domain

import androidx.annotation.Keep
import app.kaizen.commons.KaizenSettings
import app.kaizen.source.SeriesLedger
import app.kaizen.source.normalizeTitle
import app.kaizen.source.seriesIdOf
import app.kaizen.source.seriesKeyOf
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Estado que el usuario da a una serie. [color] es ARGB. */
enum class SeriesStatus(val id: String, val label: String, val color: Long) {
    NONE("none", "Ninguna", 0x00000000),
    WATCHING("watching", "Viendo", 0xFF1E88E5),
    COMPLETED("completed", "Completada", 0xFF2E7D32),
    PLAN("plan", "Pendiente", 0xFFF9A825),
    DROPPED("dropped", "Descartada", 0xFFC62828);

    companion object {
        // «Siguiendo» se retiró (se solapaba con «Viendo» para las series en emisión): lo guardado con ese estado pasa a «Viendo».
        fun of(id: String?): SeriesStatus = if (id == "following") WATCHING else entries.firstOrNull { it.id == id } ?: NONE
    }
}

/** Una serie con estado: lo justo para pintar su tarjeta y abrir su ficha desde «Mi lista». */
@Keep
data class StatusEntry(val status: String, val name: String, val source: String, val slug: String, val aid: Int, val at: Long)

/**
 * Estados de las series, guardados por título normalizado y resueltos al leer también por ficha y por id de serie (valen en cualquier fuente).
 * «Descartada» además la excluye de las recomendaciones, y al salir de ese estado vuelve a permitirla.
 * Los cambios automáticos solo actúan en los casos de [autoWatching] y [autoCompleted]: nunca tocan una serie descartada.
 */
object SeriesStatuses {
    private val type = object : TypeToken<Map<String, StatusEntry>>() {}.type
    private val state by lazy { MutableStateFlow(load()) }

    val all: StateFlow<Map<String, StatusEntry>> get() = state

    // «Siguiendo» se retiró: lo guardado con ese estado pasa a «Viendo» (así también lo cuentan los filtros de «Mi lista»).
    private fun load(): Map<String, StatusEntry> =
        runCatching { Gson().fromJson<Map<String, StatusEntry>>(KaizenSettings.seriesStatuses.orEmpty(), type) }.getOrNull().orEmpty()
            .mapValues { (_, entry) -> if (entry.status == "following") entry.copy(status = SeriesStatus.WATCHING.id) else entry }

    /**
     * La entrada de la serie: por título normalizado o, si la tarjeta o la ficha la llaman distinto (otra fuente, otro título), por su
     * ficha ([source] y [slug]) o por su id de serie.
     */
    fun lookup(map: Map<String, StatusEntry>, name: String, source: String? = null, slug: String? = null): StatusEntry? {
        val byTitle = map[normalizeTitle(name)]
        if (source == null || slug == null) return byTitle
        val index = indexOf(map)
        val byFicha = index.byFicha["$source:$slug"]
        val byId = seriesIdOf(source, slug).takeIf { it != 0L }?.let { index.byId[it] }
        // Si la serie tiene entradas con otro título (fichas unidas guardadas por separado), manda la más reciente.
        return listOfNotNull(byTitle, byFicha, byId).maxByOrNull { it.at }
    }

    /** Las entradas de un mapa por ficha y por id de serie, para resolver cada tarjeta sin recorrerlas todas. */
    private class Index(val byFicha: Map<String, StatusEntry>, val byId: Map<Long, StatusEntry>)

    private class IndexKey(val map: Map<String, StatusEntry>, val ledger: Int, val index: Index)

    @Volatile private var indexKey: IndexKey? = null

    // Se rehace al cambiar el mapa o al cargar el libro de ids (los ids se leen en vivo).
    private fun indexOf(map: Map<String, StatusEntry>): Index {
        val ledger = SeriesLedger.version.value
        indexKey?.takeIf { it.map === map && it.ledger == ledger }?.let { return it.index }
        val byFicha = map.values.associateBy { "${it.source}:${it.slug}" }
        val byId = HashMap<Long, StatusEntry>()
        map.values.forEach { entry -> seriesIdOf(entry.source, entry.slug).takeIf { it != 0L }?.let { byId.merge(it, entry) { old, new -> if (new.at > old.at) new else old } } }
        return Index(byFicha, byId).also { indexKey = IndexKey(map, ledger, it) }
    }

    // El id se lee ahora (no el guardado): las series que se unen después comparten id aunque se guardaran por separado.
    private fun sameSeries(entry: StatusEntry, source: String, slug: String, id: Long) =
        (entry.source == source && entry.slug == slug) || (id != 0L && seriesIdOf(entry.source, entry.slug) == id)

    /** Las series de [entries] una sola vez cada una (las unidas de varias fuentes cuentan como una): la de estado más reciente. */
    fun distinctSeries(entries: Collection<StatusEntry>): List<StatusEntry> =
        entries.sortedByDescending { it.at }.distinctBy { entry -> seriesKeyOf(entry.source, entry.slug) }

    /** El estado de la serie en [map] (el de [all] o el que lleva una pantalla); «Ninguna» si no tiene. */
    fun statusIn(map: Map<String, StatusEntry>, name: String, source: String? = null, slug: String? = null): SeriesStatus =
        SeriesStatus.of(lookup(map, name, source, slug)?.status)

    fun of(name: String, source: String? = null, slug: String? = null): SeriesStatus = statusIn(state.value, name, source, slug)

    @Synchronized
    fun set(name: String, status: SeriesStatus, source: String, slug: String, aid: Int) {
        val key = normalizeTitle(name)
        val before = of(name, source, slug)
        val id = seriesIdOf(source, slug)
        val map = state.value.toMutableMap()
        // Una sola entrada por serie: se quitan las que se guardaron con otro título (de otra tarjeta u otra ficha).
        val removed = map.entries.filter { sameSeries(it.value, source, slug, id) }
        map.entries.removeAll(removed.toSet())
        map.remove(key)
        if (status != SeriesStatus.NONE) map[key] = StatusEntry(status.id, name, source, slug, aid, System.currentTimeMillis())
        save(map)
        if (status == SeriesStatus.DROPPED) NotRecommended.set(name, excluded = true, source = source, slug = slug)
        else if (before == SeriesStatus.DROPPED) {
            // También se libera el título bajo el que estaba guardada (puede ser otro: la misma serie en otra fuente).
            NotRecommended.set(name, excluded = false, source = source, slug = slug)
            removed.forEach { NotRecommended.set(it.value.name, excluded = false, source = it.value.source, slug = it.value.slug) }
        }
    }

    /** Como [set], pero solo si la serie sigue sin estado (la comprobación y el cambio van juntos). Devuelve si lo puso. */
    @Synchronized
    fun setIfNone(name: String, status: SeriesStatus, source: String, slug: String, aid: Int): Boolean {
        if (of(name, source, slug) != SeriesStatus.NONE) return false
        set(name, status, source, slug, aid)
        return true
    }

    /** Al reproducir un capítulo: una serie sin estado o pendiente pasa a «Viendo». */
    @Synchronized
    fun autoWatching(name: String, source: String, slug: String, aid: Int) {
        if (of(name, source, slug) in setOf(SeriesStatus.NONE, SeriesStatus.PLAN)) set(name, SeriesStatus.WATCHING, source, slug, aid)
    }

    /** Sin ningún capítulo visto: una serie «Viendo» o «Completada» vuelve a «Ninguna». «Pendiente» y «Descartada» son decisiones del usuario y no se tocan. */
    @Synchronized
    fun autoNone(name: String, source: String, slug: String, aid: Int) {
        if (of(name, source, slug) in setOf(SeriesStatus.WATCHING, SeriesStatus.COMPLETED)) set(name, SeriesStatus.NONE, source, slug, aid)
    }

    /**
     * El estado que toca al marcar todos los capítulos como vistos, o null si no cambia. Una serie **en emisión** no puede quedar «Completada» aunque
     * tenga todo visto (saldrán más capítulos): pasa a «Viendo» si no tenía estado o estaba pendiente. Una «Descartada» es decisión del usuario y no se toca.
     */
    fun afterAllSeen(current: SeriesStatus, airing: Boolean): SeriesStatus? = when {
        current == SeriesStatus.DROPPED -> null
        !airing -> SeriesStatus.COMPLETED.takeIf { current != SeriesStatus.COMPLETED }
        current == SeriesStatus.NONE || current == SeriesStatus.PLAN -> SeriesStatus.WATCHING
        else -> null
    }

    /** Al desmarcar un capítulo de una serie «Completada»: ya no está entera vista, vuelve a «Viendo». */
    @Synchronized
    fun autoReopen(name: String, source: String, slug: String, aid: Int) {
        if (of(name, source, slug) == SeriesStatus.COMPLETED) set(name, SeriesStatus.WATCHING, source, slug, aid)
    }

    /** Con una serie finalizada y todos sus capítulos vistos: de «Viendo» o «Pendiente» pasa a «Completada». Una «Ninguna» no vuelve sola. */
    @Synchronized
    fun autoCompleted(name: String, source: String, slug: String, aid: Int) {
        if (of(name, source, slug) in setOf(SeriesStatus.WATCHING, SeriesStatus.PLAN)) set(name, SeriesStatus.COMPLETED, source, slug, aid)
    }

    fun export(): String = Gson().toJson(state.value)

    /** Añade los estados de una copia que esta instalación no tiene; devuelve cuántos. */
    @Synchronized
    fun importMissing(json: String): Int {
        val incoming = runCatching { Gson().fromJson<Map<String, StatusEntry>>(json, type) }.getOrNull().orEmpty()
        // Lo que ya está (por título o porque es la misma serie con otro título) no se duplica.
        val missing = incoming.filter { (key, entry) ->
            key !in state.value && state.value.values.none { sameSeries(it, entry.source, entry.slug, seriesIdOf(entry.source, entry.slug)) }
        }
        if (missing.isNotEmpty()) save(state.value + missing)
        return missing.size
    }

    /**
     * Funde los estados de una copia de otro dispositivo: uno que aquí no hay entra, y uno que ya hay se sustituye si el de la copia es **más reciente** ([StatusEntry.at]); lo que [skip]
     * descarta (una lápida más nueva que la copia) no entra. Devuelve cuántos entraron nuevos y cuántos se sustituyeron (la lógica, en [StatusMerge]).
     */
    @Synchronized
    internal fun importNewer(json: String, skip: (StatusEntry) -> Boolean = { false }): StatusMerge.Result {
        val incoming = runCatching { Gson().fromJson<Map<String, StatusEntry>>(json, type) }.getOrNull().orEmpty()
            .filterValues { (it as StatusEntry?) != null && !(it.source as String?).isNullOrBlank() && !(it.slug as String?).isNullOrBlank() }
        val before = state.value
        val result = StatusMerge.mergeCounted(before, incoming, { local, other -> sameSeries(local, other.source, other.slug, seriesIdOf(other.source, other.slug)) }, skip)
        if (result.map == before) return result
        save(result.map)
        // «Descartada» excluye la serie de las recomendaciones: se alinea con lo que quedó tras la fusión.
        val (release, exclude) = StatusMerge.droppedChanges(before, result.map)
        release.forEach { NotRecommended.set(it.name, excluded = false, source = it.source, slug = it.slug) }
        exclude.forEach { NotRecommended.set(it.name, excluded = true, source = it.source, slug = it.slug) }
        return result
    }

    /** La hora del último cambio del estado de la serie [source]:[slug]; 0 si no tiene estado o la hora es de un reloj roto (más de un día en el futuro). */
    fun latestChange(source: String, slug: String): Long {
        val id = seriesIdOf(source, slug)
        return StatusMerge.latestChange(state.value, { sameSeries(it, source, slug, id) })
    }

    /** Quita el estado de la serie [source]:[slug] por lo que dice la sincronización (una lápida de otro dispositivo); false si aquí no tenía. */
    @Synchronized
    fun removeSeries(source: String, slug: String): Boolean {
        val id = seriesIdOf(source, slug)
        val (rest, removed) = StatusMerge.removeSeries(state.value) { sameSeries(it, source, slug, id) }
        if (removed.isEmpty()) return false
        save(rest)
        removed.filter { it.status == SeriesStatus.DROPPED.id }.forEach { NotRecommended.set(it.name, excluded = false, source = it.source, slug = it.slug) }
        return true
    }

    private fun save(map: Map<String, StatusEntry>) {
        state.value = map
        KaizenSettings.seriesStatuses = Gson().toJson(map)
    }
}
