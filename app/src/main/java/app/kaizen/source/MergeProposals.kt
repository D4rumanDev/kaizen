package app.kaizen.source

/**
 * Dos series del catálogo que parecen la misma: comparten el slug sin sufijos en alguna de sus fichas ([cleanSlug] y los
 * sufijos aprendidos de las uniones existentes) o, si [inferred], están enlazadas por otras propuestas: cada serie de un grupo de
 * propuestas encadenadas (A con B, B con C) se propone también con la de id más bajo del grupo (A con C).
 */
class MergeProposal(val first: CatalogEntry, val second: CatalogEntry, val cleanSlug: String, val inferred: Boolean = false)

/**
 * Propone al usuario unir series que el catálogo mantiene separadas aunque una ficha de cada una tenga el mismo slug una vez
 * quitados los sufijos de plataforma («x-anime» de una fuente y «x» de otra). Solo propone: unir o no es decisión del usuario,
 * porque «una película o una OVA no debe fusionarse con la serie» y un mismo slug no lo garantiza.
 */
object MergeProposals {
    /** Un sufijo aprendido tiene que verse en este número de series ya unidas como mínimo. */
    private const val MIN_SERIES = 2
    private const val MAX_SUFFIX = 20

    /** Un sufijo con alguna de estas palabras (o con dígitos) distingue temporadas o películas, no una plataforma: no se aprende. */
    private val NOT_PLATFORM = setOf(
        "season", "temporada", "part", "parte", "ova", "ovas", "oad", "ona", "movie", "movies", "pelicula", "peliculas", "film", "special",
        "specials", "especial", "final", "ii", "iii", "iv", "z", "zero", "gaiden", "shippuden", "kai", "remake", "reboot", "sequel", "recap",
        "origins", "extra"
    )

    /**
     * Sufijos de slug que las fuentes añaden a una misma serie, sacados de las series que ya tienen varias fichas: si una ficha
     * es «x» y otra «x-latino», «-latino» es un sufijo de plataforma. Hace falta verlo en [MIN_SERIES] series para fiarse de él.
     */
    internal fun learnedSuffixes(links: List<CatalogLink>): Set<String> {
        val seen = HashMap<String, MutableSet<String>>()
        for ((key, group) in links.groupBy { it.key }) {
            for (short in group) for (long in group) {
                // Una plataforma distingue fuentes: «x» y «x-sequel» de la misma fuente son dos obras distintas.
                if (short.sourceId == long.sourceId || long.slug.length <= short.slug.length || !long.slug.startsWith(short.slug)) continue
                val tail = long.slug.substring(short.slug.length)
                if (!tail.startsWith("-") || tail.length > MAX_SUFFIX || tail.any(Char::isDigit)) continue
                if (tail.split('-').any { it in NOT_PLATFORM }) continue
                seen.getOrPut(tail) { mutableSetOf() } += key
            }
        }
        return seen.filterValues { it.size >= MIN_SERIES }.keys
    }

    /** [cleanSlug] y, además, el sufijo aprendido más largo que le quede. */
    internal fun cleanSlugWith(slug: String, learned: Set<String>): String {
        val base = cleanSlug(slug)
        val suffix = learned.filter { base.length >= it.length + 3 && base.endsWith(it) }.maxByOrNull { it.length } ?: return base
        return base.removeSuffix(suffix)
    }

    /**
     * Pares de series de [entries] con un slug limpio en común en [links]. No propone lo que ya está unido ni lo que el
     * usuario separó en [rules]. Una serie que coincide con varias se empareja con la de id más bajo.
     */
    fun find(entries: List<CatalogEntry>, links: List<CatalogLink>, rules: TitleRules = UserTitles.rules): List<MergeProposal> {
        val byKey = entries.associateBy { it.key }
        val learned = learnedSuffixes(links)
        val proposals = LinkedHashMap<Pair<String, String>, MergeProposal>()
        for ((slug, group) in links.groupBy { cleanSlugWith(it.slug, learned) }) {
            val series = group.map { it.key }.distinct().mapNotNull(byKey::get).sortedBy { it.seriesId }
            val anchor = series.firstOrNull() ?: continue
            for (other in series.drop(1)) {
                if (rules.canonical(anchor.key) == rules.canonical(other.key) || rules.isSplit(anchor.key, other.key)) continue
                proposals.putIfAbsent(anchor.key to other.key, MergeProposal(anchor, other, slug))
            }
        }
        addChained(proposals, rules)
        return proposals.values.sortedBy { it.first.name.lowercase() }
    }

    /** Dentro de cada grupo de series enlazadas por propuestas, propone la de id más bajo con las que aún no tiene propuesta. */
    private fun addChained(proposals: MutableMap<Pair<String, String>, MergeProposal>, rules: TitleRules) {
        val neighbours = HashMap<String, MutableSet<String>>()
        val series = HashMap<String, CatalogEntry>()
        val slugOf = HashMap<String, String>()
        for (proposal in proposals.values) {
            slugOf.putIfAbsent(proposal.first.key, proposal.cleanSlug)
            slugOf.putIfAbsent(proposal.second.key, proposal.cleanSlug)
            neighbours.getOrPut(proposal.first.key) { mutableSetOf() } += proposal.second.key
            neighbours.getOrPut(proposal.second.key) { mutableSetOf() } += proposal.first.key
            series[proposal.first.key] = proposal.first
            series[proposal.second.key] = proposal.second
        }
        val visited = HashSet<String>()
        for (start in neighbours.keys.sorted()) {
            if (!visited.add(start)) continue
            val group = ArrayList<CatalogEntry>()
            val queue = ArrayDeque(listOf(start))
            while (queue.isNotEmpty()) {
                val key = queue.removeFirst()
                group += series.getValue(key)
                neighbours.getValue(key).filter(visited::add).forEach(queue::add)
            }
            val ordered = group.sortedBy { it.seriesId }
            val anchor = ordered.first()
            for (other in ordered.drop(1)) {
                if (other.key in neighbours.getValue(anchor.key)) continue
                // Si ya está unida a una serie que el ancla propone, aceptar esa la une también.
                if (neighbours.getValue(anchor.key).any { rules.canonical(it) == rules.canonical(other.key) }) continue
                if (rules.canonical(anchor.key) == rules.canonical(other.key) || rules.isSplit(anchor.key, other.key)) continue
                val via = slugOf.getValue(other.key)
                proposals.putIfAbsent(anchor.key to other.key, MergeProposal(anchor, other, via, inferred = true))
            }
        }
    }
}
