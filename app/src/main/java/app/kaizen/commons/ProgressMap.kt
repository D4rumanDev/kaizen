package app.kaizen.commons

import kotlin.time.Duration.Companion.days

/**
 * La lógica del progreso guardado (ver [KaizenProgress]) sobre un mapa en memoria, sin Android: claves «s:serie#número» (por serie), «p:fuente:slug#número»
 * (por ficha, de versiones anteriores y de las copias de otros dispositivos) y eids sueltos (versiones aún más antiguas); junto a cada una, «t:clave» con la
 * hora de su último cambio. [now] da la hora de ahora. Cada clave que cambia queda en [touched], para que quien la use la escriba en disco.
 */
internal class ProgressMap(private val entries: MutableMap<String, String>, private val now: () -> Long = System::currentTimeMillis) {
    /** Las claves puestas o quitadas desde que se creó (incluidas las «t:»). */
    val touched = LinkedHashSet<String>()

    fun timeOf(key: String): Long = entries["$TIME_PREFIX$key"]?.toLongOrNull() ?: 0L

    fun store(key: String, value: String, at: Long = now()) {
        entries[key] = value
        if (at > 0) entries["$TIME_PREFIX$key"] = at.toString() else entries.remove("$TIME_PREFIX$key")
        touched += key; touched += "$TIME_PREFIX$key"
    }

    fun forget(key: String) {
        entries.remove(key)
        entries.remove("$TIME_PREFIX$key")
        touched += key; touched += "$TIME_PREFIX$key"
    }

    /** Todo el progreso (clave → «posición/duración»), sin las marcas internas. */
    fun exportAll(): Map<String, String> = entries.filterKeys(::isProgressKey).toMap()

    /** El progreso por serie sale con la clave de una de sus fichas ([fichaOf]); uno por serie sin fichas no sale (su id es local). Con cada clave, su hora «t:». */
    fun exportPortable(fichaOf: (seriesId: Long) -> Pair<String, String>?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((text, value) in exportAll()) {
            val key = KaizenProgress.Key.parse(text)
            val ficha = key?.let { fichaOf(it.seriesId) }
            val portable = if (key != null && ficha != null) "$FICHA_PREFIX${ficha.first}:${ficha.second}#${KaizenProgress.numberText(key.number)}" else text
            if (portable.startsWith(KEY_PREFIX)) continue
            out[portable] = value
            timeOf(text).takeIf { it > 0 }?.let { out["$TIME_PREFIX$portable"] = it.toString() }
        }
        return out
    }

    /** Añade lo de [saved] que aquí no existe (con su hora, si la trae); devuelve cuántos. */
    fun importMissing(saved: Map<String, String>): Int {
        val missing = saved.filter { (key, value) -> isProgressKey(key) && !entries.containsKey(key) && KaizenProgress.parse(value) != null }
        for ((key, value) in missing) store(key, value, at = saved["$TIME_PREFIX$key"]?.toLongOrNull()?.takeIf { it > 0 } ?: 0L)
        return missing.size
    }

    /** Los capítulos con progreso: (serie, número) de las claves «s:» y (ficha, número) de las «p:». */
    fun chapters(): Pair<List<Pair<Long, Double>>, List<Pair<String, Double>>> {
        val series = entries.keys.mapNotNull { KaizenProgress.Key.parse(it) }.map { it.seriesId to it.number }
        val fichas = entries.keys.filter { it.startsWith(FICHA_PREFIX) }.mapNotNull { text ->
            val body = text.removePrefix(FICHA_PREFIX)
            val number = body.substringAfterLast('#', "").toDoubleOrNull() ?: return@mapNotNull null
            body.substringBeforeLast('#') to number
        }
        return series to fichas
    }

    private fun keysOf(seriesId: Long?, fichas: Collection<String>, number: Double) =
        listOfNotNull(seriesId?.takeIf { it > 0 }?.let { KaizenProgress.Key(it, number).text }) + fichas.map { "$FICHA_PREFIX$it#${KaizenProgress.numberText(number)}" }

    /** La hora del último cambio de un capítulo (por serie o por ficha); 0 si no consta. Una hora de más de un día en el futuro (reloj roto) cuenta como 0. */
    fun latestChange(seriesId: Long?, ficha: String, number: Double): Long {
        val limit = now() + MAX_FUTURE.inWholeMilliseconds
        return keysOf(seriesId, listOf(ficha), number).maxOfOrNull { timeOf(it).takeIf { at -> at <= limit } ?: 0L } ?: 0L
    }

    /** Quita el progreso de un capítulo (por serie y por sus fichas); false si no había. */
    fun removeChapter(seriesId: Long?, fichas: Collection<String>, number: Double): Boolean {
        val present = keysOf(seriesId, fichas, number).filter { entries.containsKey(it) }
        present.forEach(::forget)
        return present.isNotEmpty()
    }

    /** Lo que hizo [migrateFichaKeys]: cuántas claves pasó y en cuántas sustituyó un progreso que ya había por otro más reciente. */
    class Moved(val moved: Int, val replaced: Int)

    /** Pasa a su serie ([seriesOf]; 0 = no se conoce) el progreso por ficha. Si ya hay progreso de ese capítulo gana el de [KaizenProgress.takeIncoming]. Idempotente. */
    fun migrateFichaKeys(seriesOf: (source: String, slug: String) -> Long): Moved {
        var moved = 0
        var replaced = 0
        for (text in entries.keys.filter { it.startsWith(FICHA_PREFIX) }) {
            val body = text.removePrefix(FICHA_PREFIX)
            val colon = body.indexOf(':')
            val hash = body.lastIndexOf('#')
            if (colon <= 0 || hash <= colon + 1) continue
            val number = body.substring(hash + 1).toDoubleOrNull() ?: continue
            val id = seriesOf(body.substring(0, colon), body.substring(colon + 1, hash)).takeIf { it > 0 } ?: continue
            if (text !in entries) continue
            if (moveInto(text, KaizenProgress.Key(id, number).text)) replaced++
            moved++
        }
        return Moved(moved, replaced)
    }

    /** Pasa el progreso de series unidas a otra a la serie vigente ([resolve]). Devuelve cuántas claves movió. Idempotente. */
    fun rekey(resolve: (Long) -> Long): Int {
        var moved = 0
        for (text in entries.keys.filter { it.startsWith(KEY_PREFIX) }) {
            val key = KaizenProgress.Key.parse(text) ?: continue
            val current = resolve(key.seriesId)
            if (current == key.seriesId || text !in entries) continue
            moveInto(text, KaizenProgress.Key(current, key.number).text)
            moved++
        }
        return moved
    }

    /** Mueve [from] a [to]; si [to] ya tenía progreso, se queda el que diga [KaizenProgress.takeIncoming]. true si sustituyó uno que ya había. */
    private fun moveInto(from: String, to: String): Boolean {
        val value = entries[from] ?: return false
        val existing = entries[to]
        val take = KaizenProgress.takeIncoming(existing, timeOf(to), value, timeOf(from), now())
        if (take) store(to, value, at = timeOf(from))
        forget(from)
        return take && existing != null
    }

    companion object {
        const val KEY_PREFIX = "s:"
        const val FICHA_PREFIX = "p:"
        const val TIME_PREFIX = "t:"
        val MAX_FUTURE = 1.days

        /** Las claves de progreso son series («s:…»), fichas («p:…») o eids; el resto son marcas internas. */
        fun isProgressKey(key: String) = key.startsWith(KEY_PREFIX) || key.startsWith(FICHA_PREFIX) || key.toIntOrNull() != null
    }
}
