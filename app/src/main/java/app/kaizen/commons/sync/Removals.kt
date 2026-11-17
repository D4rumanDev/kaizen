package app.kaizen.commons.sync

import androidx.annotation.Keep
import app.kaizen.App
import com.google.gson.Gson
import java.io.File

/**
 * Lo que se **quita** también viaja entre dispositivos (un favorito, un capítulo visto que se desmarca). La regla es la de las «lápidas»: cada dispositivo compara lo que tiene con
 * lo que tenía en la última sincronización y anota con la fecha lo que ya no está; los demás, al leer esa lista, lo quitan una sola vez. Dos cautelas evitan que una lápida
 * rehaga lo que no debe:
 * - una copia **anterior** a la lápida no vuelve a poner lo quitado (la copia vieja de otro dispositivo que aún no ha sincronizado), y
 * - un borrado **masivo** (más de la mitad de lo que había) no se propaga ni se aplica solo: es más probable un «restablecer» que quitar a mano tantas cosas.
 *
 * Las claves son portables (no llevan ids locales): `f/fuente:slug` para un favorito y `r/fuente:slug#número` para un capítulo visto. Los estados y el progreso de las series
 * siguen siendo solo-añadir.
 */
object Removals {
    const val MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000
    private const val DAY_MS = 24L * 60 * 60 * 1000
    const val MAX_ENTRIES = 50_000
    private const val MAX_KEY = 450
    private const val MIN_FOR_SHARE = 10
    private const val MAX_SHARE = 0.5

    fun favoriteKey(ficha: String) = "f/$ficha"
    fun recordKey(ficha: String, number: Double) = "r/$ficha#$number"

    /** Una clave leída: favorito (`number` null) o capítulo, con su ficha. */
    class Parsed(val favorite: Boolean, val source: String, val slug: String, val number: Double?)

    fun parse(key: String): Parsed? {
        if (key.length > MAX_KEY) return null
        val favorite = key.startsWith("f/")
        if (!favorite && !key.startsWith("r/")) return null
        val body = key.substring(2)
        val number = if (favorite) null else body.substringAfterLast('#', "").toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        val ficha = if (favorite) body else body.substringBeforeLast('#')
        val source = ficha.substringBefore(':', "")
        val slug = ficha.substringAfter(':', "")
        return if (source.isEmpty() || slug.isEmpty()) null else Parsed(favorite, source, slug, number)
    }

    /** Las claves de [base] que ya no están; [present] es null si no se sabe (la ficha no es conocida): entonces no cuenta como quitada. */
    fun deleted(base: Collection<String>?, present: (String) -> Boolean?): List<String> = base.orEmpty().filter { present(it) == false }

    /** Las lápidas propias que quedan: las de antes salvo las viejas y las que se han vuelto a poner, más las nuevas con la fecha de [now]. */
    fun nextRemoved(old: Map<String, Long>, newlyDeleted: Collection<String>, present: (String) -> Boolean?, now: Long): Map<String, Long> =
        old.filter { (key, at) -> now - at <= MAX_AGE_MS && present(key) != true } + newlyDeleted.associateWith { now }

    /** ¿Es [count] de [total] un borrado masivo (más de la mitad, con al menos 10 cosas)? */
    fun massive(count: Int, total: Int): Boolean = total >= MIN_FOR_SHARE && count > total * MAX_SHARE

    /** Como [massive] pero por tipo (favoritos y capítulos vistos por separado): quitar todos los favoritos no se esconde tras miles de capítulos. */
    fun massiveAny(removing: Collection<String>, total: Collection<String>): Boolean =
        massive(removing.count { it.startsWith("f/") }, total.count { it.startsWith("f/") }) || massive(removing.count { it.startsWith("r/") }, total.count { it.startsWith("r/") })

    /** Las lápidas de las copias ajenas, por clave canónica ([canon] iguala la misma serie aunque la ficha sea otra) y con la fecha más reciente de cada una. */
    fun blocked(files: List<Map<String, Long>>, canon: (String) -> String?): Map<String, Long> {
        val out = HashMap<String, Long>()
        for (map in files) for ((key, at) in map) canon(key)?.let { c -> if (at > (out[c] ?: 0L)) out[c] = at }
        return out
    }

    /** ¿Una copia hecha en [createdAt] que contiene [key] es anterior a su lápida? Entonces esa copia no sabía del borrado y no lo deshace. */
    fun stale(key: String, createdAt: Long, blocked: Map<String, Long>, canon: (String) -> String?): Boolean = canon(key)?.let { (blocked[it] ?: 0L) > createdAt } == true

    /** Una clave limpia de una copia ajena: solo claves bien formadas, con fecha, y con tope. */
    fun sanitize(raw: Map<String, Long?>?, now: Long = System.currentTimeMillis()): Map<String, Long> =
        raw.orEmpty().entries.asSequence().filter { (key, at) -> at != null && at > 0 && at <= now + DAY_MS && parse(key) != null }.take(MAX_ENTRIES).associate { it.key to it.value!! }

    /** Identifica una lápida ya aplicada (clave y fecha). */
    fun seenId(key: String, at: Long) = "$key@$at"

    /** Lo que hay en un dispositivo, por claves portables. [isPresent] es null si no se sabe (la ficha no es conocida). */
    interface Presence {
        val keys: Set<String>
        fun isPresent(key: String): Boolean?
    }

    /** Lo que el algoritmo necesita de un dispositivo; [T] es una copia ajena ya leída. En la app lo da `SyncManager`; en las pruebas, un dispositivo simulado. */
    interface Local<T> {
        suspend fun presence(): Presence
        suspend fun join(item: T)
        suspend fun merge(item: T, skip: (String) -> Boolean)
        suspend fun remove(keys: List<String>): Int
        fun canon(key: String): String?
    }

    /** El resultado de [step]: las lápidas propias a subir, el estado a guardar y qué pasó. */
    class Outcome(val removed: Map<String, Long>, val state: State, val removedHere: Int, val heldBack: Boolean, val heldRemote: Boolean)

    /**
     * Un paso de sincronización (la lógica entera, sin red ni Android): anota lo quitado aquí desde la última vez, funde las copias ajenas sin devolver lo que una lápida (propia o
     * ajena) más nueva que la copia dice que se quitó, y quita lo que otros quitaron, solo si ya lo teníamos en la última sincronización (lo añadido después no se toca) y una vez.
     * [now] es la hora de ahora: las lápidas nuevas llevan esa hora.
     */
    suspend fun <T> step(state: State, now: Long, local: Local<T>, incoming: List<T>, createdAt: (T) -> Long, removedOf: (T) -> Map<String, Long>): Outcome {
        val before = local.presence()
        val deleted = deleted(state.base, before::isPresent)
        val heldBack = massiveAny(deleted, state.base.orEmpty())
        val removed = nextRemoved(state.removed, if (heldBack) emptyList() else deleted, before::isPresent, now)
        incoming.forEach { local.join(it) }
        val blocked = blocked(incoming.map(removedOf) + removed, local::canon)
        incoming.forEach { item -> local.merge(item) { key -> stale(key, createdAt(item), blocked, local::canon) } }
        val seen = state.seen.toHashSet()
        val fresh = incoming.flatMap { removedOf(it).entries }.filter { now - it.value <= MAX_AGE_MS && seenId(it.key, it.value) !in seen }
        val baseCanon = state.base.orEmpty().mapNotNull(local::canon).toSet()
        val after = local.presence()
        val toRemove = fresh.map { it.key }.distinct().filter { after.isPresent(it) == true && local.canon(it) in baseCanon }
        val heldRemote = massiveAny(toRemove, after.keys + toRemove)
        val removedHere = if (heldRemote) 0 else local.remove(toRemove)
        // Si se retuvo por masivo no se anotan como vistas, para que no se den por hechas.
        val applied = if (heldRemote) emptyList() else fresh
        val keep = (seen + applied.map { seenId(it.key, it.value) }).filter { id -> now - (id.substringAfterLast('@').toLongOrNull() ?: 0L) <= MAX_AGE_MS }
        return Outcome(removed, State(local.presence().keys.toList(), removed, keep), removedHere, heldBack, heldRemote)
    }

    /** El estado local de la sincronización: lo que había en la última (`base`, null = nunca), las lápidas propias y las ajenas ya aplicadas. */
    @Keep
    class State(val base: List<String>? = null, val removed: Map<String, Long> = emptyMap(), val seen: List<String> = emptyList())

    private val file: File get() = File(App.context.filesDir, "sync-state.json")

    fun load(): State {
        val read = runCatching { Gson().fromJson(file.readText(), State::class.java) }.getOrNull() ?: return State()
        // Gson puede dejar a null lo que falte en un fichero dañado.
        @Suppress("USELESS_ELVIS") return State(read.base, read.removed ?: emptyMap(), read.seen ?: emptyList())
    }

    fun save(state: State) {
        val temp = File(file.parentFile, "sync-state.json.tmp")
        temp.writeText(Gson().toJson(state))
        if (!temp.renameTo(file)) { file.delete(); check(temp.renameTo(file)) { "no se pudo guardar el estado de la sincronización" } }
    }
}
