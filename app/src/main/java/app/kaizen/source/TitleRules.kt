package app.kaizen.source

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reglas del usuario sobre cómo se juntan y se muestran las series (fichero `titles.json`, ver [UserTitles]). Una serie se
 * identifica por su clave: su id de serie como texto ([SeriesLedger.peekKey]).
 *  - **merges**: grupos de claves que son la misma serie aunque las fuentes la llamen distinto;
 *  - **splits**: pares de claves que nunca se juntan aunque su título coincida;
 *  - **covers**: portada que se prefiere para una serie.
 * Es inmutable: cada cambio devuelve unas reglas nuevas.
 */
class TitleRules private constructor(
    private val covers: Map<String, String>,
    private val merges: List<List<String>>,
    private val splits: Set<Pair<String, String>>
) {
    /** Cuántas uniones y separaciones hechas por el usuario hay (para «Series › Estado»). */
    internal fun mergeCount() = merges.size

    internal fun splitCount() = splits.size

    /** Cada clave de un grupo de unión apunta al primer miembro del grupo (los grupos que comparten una clave se funden). */
    private val representative: Map<String, String> = buildMap {
        val groups = ArrayList<MutableList<String>>()
        for (group in merges) {
            val overlapping = groups.filter { existing -> existing.any { it in group } }
            val joined = LinkedHashSet<String>()
            overlapping.forEach { joined.addAll(it) }
            joined.addAll(group)
            groups.removeAll(overlapping.toSet())
            groups.add(joined.toMutableList())
        }
        for (group in groups) group.forEach { put(it, group.first()) }
    }

    // Todas las claves que nombran alguna regla; se calculan una vez porque las reglas no cambian.
    private val allKeys: List<String> by lazy { (covers.keys + merges.flatten() + splits.flatMap { listOf(it.first, it.second) }).toList() }
    private val ids: Set<String> by lazy { allKeys.filter { it.all(Char::isDigit) }.toSet() }

    val isEmpty: Boolean get() = covers.isEmpty() && merges.isEmpty() && splits.isEmpty()

    /** La clave con la que se agrupa una serie: la del primer miembro de su grupo de unión, o la suya. */
    fun canonical(key: String): String = representative[key] ?: key

    /**
     * Las claves del grupo de unión de [key], ella incluida: las series que el usuario unió con esa. Si una regla nombra un id que
     * luego se redirigió a otro, al buscar fichas se resuelve: desde la serie con el id viejo salen también las del nuevo, no al revés.
     */
    fun groupKeys(key: String): Set<String> {
        val rep = canonical(key)
        return representative.filterValues { it == rep }.keys + key
    }

    /** Si el usuario ha dicho que estas dos series no se juntan nunca. */
    fun isSplit(a: String, b: String): Boolean = splitsBetween(canonical(a), canonical(b)).isNotEmpty()

    /** Las separaciones declaradas entre los grupos de [ka] y [kb] (claves ya canónicas), aunque se escribieran con otro miembro. */
    private fun splitsBetween(ka: String, kb: String) = splits.filter { (x, y) ->
        val cx = canonical(x)
        val cy = canonical(y)
        (cx == ka && cy == kb) || (cx == kb && cy == ka)
    }

    /** Estas reglas más las de [other] que no choquen: portada propia manda, uniones y separaciones se suman. */
    fun fillFrom(other: TitleRules): TitleRules = TitleRules(
        covers = other.covers + covers,
        // Sin repetir: importar o sincronizar la misma copia otra vez no debe añadir de nuevo las mismas uniones.
        merges = (merges + other.merges).unique(),
        // Una separación de la copia no se suma si estas reglas ya unen esas dos series (la unión es más reciente que la copia).
        splits = splits + other.splits.filterNot { (x, y) -> canonical(x) == canonical(y) }
    )

    fun cover(key: String): String? = covers[canonical(key)] ?: covers[key]

    fun withCover(key: String, url: String?): TitleRules {
        val clean = url?.trim()?.takeIf { it.startsWith("https://") && it.length <= MAX_URL }
        return copy(covers = covers.updated(canonical(key), clean))
    }

    /** Une [a] y [b] como la misma serie (quita cualquier separación entre sus grupos). */
    fun withMerge(a: String, b: String): TitleRules {
        if (canonical(a) == canonical(b)) return this
        val ka = canonical(a)
        val kb = canonical(b)
        val keep = splits - splitsBetween(ka, kb).toSet()
        return copy(merges = merges + listOf(listOf(ka, kb)), splits = keep)
    }

    /** Separa [a] de [b]: nunca se juntan; si estaban unidas, [b] sale del grupo. */
    fun withSplit(a: String, b: String): TitleRules {
        val ka = canonical(a)
        val kb = canonical(b)
        if (ka == kb) {
            // Sale del grupo la que no es la representante; si se pide separar de ella misma, sale la otra.
            val out = if (b == ka) a else b
            val rest = merges.map { group -> group.filterNot { it == out } }.filter { it.size > 1 }
            return copy(merges = rest, splits = splits + ordered(out, ka))
        }
        return copy(splits = splits + ordered(ka, kb))
    }

    /** Quita todas las reglas de [key]: su portada, sus uniones y sus separaciones. */
    fun without(key: String): TitleRules {
        val rep = canonical(key)
        return copy(
            covers = covers - key - rep,
            merges = merges.map { group -> group.filterNot { it == key } }.filter { it.size > 1 },
            splits = splits.filterNot { (x, y) -> key == x || key == y || rep == x || rep == y }.toSet()
        )
    }

    /** Estas reglas sin ninguna unión; las portadas y separaciones se conservan. */
    fun withoutMerges(): TitleRules = copy(merges = emptyList())

    /** Las claves que son ids de serie (solo dígitos). */
    fun idKeys(): Set<String> = ids

    /**
     * Cambia todas las claves que son ids por las que da [translate] (los ids de otra instalación, de una copia de seguridad, por
     * los de esta); lo que no traduce se pierde, porque sin saber qué serie es no se puede aplicar. A la vez: no se encadena.
     */
    fun translateIds(translate: (String) -> List<String>?): TitleRules {
        fun to(key: String): List<String> = if (key.all(Char::isDigit)) translate(key).orEmpty() else listOf(key)
        val joined = allKeys.filter { it.all(Char::isDigit) }.map(::to).filter { it.size > 1 }
        return TitleRules(
            covers.entries.survivorLast().mapNotNull { (key, value) -> to(key).firstOrNull()?.let { it to value } }.toMap(),
            (merges.map { group -> group.flatMap(::to).distinct() }.filter { it.size > 1 } + joined).unique(),
            splits.flatMap { (x, y) -> to(x).flatMap { a -> to(y).filter { it != a }.map { b -> ordered(a, b) } } }.toSet()
        )
    }

    // Dos ids de la copia pueden acabar en la misma clave (uno unido a otro): `toMap` se queda con el último, y el que sobrevive a una unión es
    // el id más bajo (ver [SeriesIds.assign]), así que va al final y su portada manda.
    private fun Collection<Map.Entry<String, String>>.survivorLast() = sortedByDescending { it.key.toLongOrNull() ?: Long.MAX_VALUE }

    /**
     * Cambia la clave antigua [old] por las de [replacement] (los ids de las fichas que la compartían). Su portada
     * pasa a la primera; si son varias, se unen entre sí, porque antes eran una sola serie. No hace nada sin reglas sobre [old].
     */
    fun remapKey(old: String, replacement: List<String>): TitleRules {
        val to = replacement.distinct()
        if (to.isEmpty() || old in to) return this
        fun List<String>.swap() = flatMap { if (it == old) to else listOf(it) }.distinct()
        val joined = if (to.size > 1) listOf(to) else emptyList()
        val oldMerges = merges.map { it.swap() }.filter { it.size > 1 }
        val touched = old in covers || merges.any { old in it } || splits.any { old == it.first || old == it.second }
        if (!touched) return this
        return copy(
            covers = covers[old]?.let { covers - old + mapOf(to.first() to (covers[to.first()] ?: it)) } ?: covers,
            merges = (oldMerges + joined).unique(),
            splits = splits.flatMap { (x, y) ->
                val xs = if (x == old) to else listOf(x)
                val ys = if (y == old) to else listOf(y)
                xs.flatMap { a -> ys.map { b -> ordered(a, b) } }
            }.filter { (a, b) -> a != b }.toSet() // un id absorbido por la serie con la que estaba separado no deja una separación de la serie consigo misma
        )
    }

    private fun copy(
        covers: Map<String, String> = this.covers,
        merges: List<List<String>> = this.merges,
        splits: Set<Pair<String, String>> = this.splits
    ) = TitleRules(covers, merges, splits)

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("covers", JSONObject(covers))
        put("merges", JSONArray(merges.map { JSONArray(it) }))
        put("splits", JSONArray(splits.map { JSONArray(listOf(it.first, it.second)) }))
    }

    companion object {
        // 2: las claves son ids de serie. En la 1 eran slugs sin sufijos.
        private const val VERSION = 2
        const val MAX_URL = 500
        private val KEY = Regex("[a-z0-9][a-z0-9-]{0,119}")

        val EMPTY = TitleRules(emptyMap(), emptyList(), emptySet())

        private fun ordered(a: String, b: String) = if (a <= b) a to b else b to a

        /** Los grupos de unión sin repetir: dos grupos con las mismas claves (en cualquier orden) son la misma unión; se queda el primero. */
        private fun List<List<String>>.unique(): List<List<String>> = distinctBy { it.toSet() }

        private fun Map<String, String>.updated(key: String, value: String?) = if (value == null) this - key else this + (key to value)

        /** Lee el fichero; lo que no cumple el formato (claves raras, portadas que no son https, textos largos) se ignora. */
        fun parse(text: String): TitleRules {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return EMPTY
            fun strings(obj: JSONObject?, max: Int, accept: (String) -> Boolean): Map<String, String> = buildMap {
                obj ?: return@buildMap
                for (key in obj.keys()) {
                    val value = obj.optString(key).trim()
                    if (KEY.matches(key) && value.isNotEmpty() && value.length <= max && accept(value)) put(key, value)
                }
            }
            fun lists(array: JSONArray?, minSize: Int): List<List<String>> = buildList {
                array ?: return@buildList
                for (i in 0 until array.length()) {
                    val inner = array.optJSONArray(i) ?: continue
                    val keys = List(inner.length()) { inner.optString(it) }.filter { KEY.matches(it) }.distinct()
                    if (keys.size >= minSize) add(keys)
                }
            }
            return TitleRules(
                covers = strings(json.optJSONObject("covers"), MAX_URL) { it.startsWith("https://") },
                // Un fichero que ya se infló con uniones repetidas se limpia al leerlo.
                merges = lists(json.optJSONArray("merges"), 2).unique(),
                splits = lists(json.optJSONArray("splits"), 2).map { ordered(it[0], it[1]) }.toSet()
            )
        }
    }
}

/**
 * Las fichas (fuente, slug) que son la misma serie que ([sourceId], [slug]) según los ids de serie ([keyOf]) y los grupos de
 * unión de [rules], sin mirar el catálogo. [fichasOf] da las fichas de unas series (por clave): solo se piden las del grupo, no
 * todas. Una unión del usuario sigue viéndose aunque el catálogo no tenga alguna ficha.
 */
internal fun siblingFichas(
    rules: TitleRules,
    fichasOf: (Collection<String>) -> Collection<Pair<String, String>>,
    keyOf: (String, String) -> String?,
    sourceId: String,
    slug: String
): List<Pair<String, String>> {
    val mine = keyOf(sourceId, slug) ?: return emptyList()
    return fichasOf(rules.groupKeys(mine)).filterNot { it == sourceId to slug }.sortedWith(compareBy({ it.first }, { it.second }))
}
