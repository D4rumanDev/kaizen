package app.kaizen.source

import app.kaizen.pojos.av1.SeriesSummary

/** Une las fichas de las fuentes en series: reglas de unión y entrada del catálogo de cada una. */
object SeriesResolver {
    /**
     * Agrupa las fichas de todas las fuentes en series. Reglas, en orden:
     *  1. dentro de una misma fuente, «x» y «x-latino» son la misma serie ([SeriesMatch.slugBase]), con años compatibles;
     *  2. mismo anime de AniList ([anilistOf], el enlace aplicado de cada ficha) en otra fuente, salvo que el usuario las haya separado
     *     o que sus años de estreno no quepan juntos ([LinkEvidence.compatible]): es la única regla automática entre fuentes;
     *  3. mismo id de serie ([idOf]): lo que ya se unió antes y las uniones del usuario ([rules]) siguen unidos.
     * No se une por título ni por el mismo slug entre fuentes: dos fichas sin enlace con AniList en común quedan como series distintas
     * (se pueden unir a mano en «Repetidas»). Dos fichas de la misma fuente solo se unen por la regla 3 o por el sufijo «-latino».
     * Devuelve las fichas de cada serie con su fuente.
     */
    fun resolveSeries(
        items: List<Pair<AnimeSource, SeriesSummary>>,
        rules: TitleRules = UserTitles.rules,
        anilistOf: (sourceId: String, slug: String) -> Long? = { _, _ -> null },
        idOf: (sourceId: String, slug: String) -> Long? = SeriesLedger::peek
    ): List<List<Pair<AnimeSource, SeriesSummary>>> {
        val groups = mutableListOf<MutableList<Pair<AnimeSource, SeriesSummary>>>()
        // Claves de reglas (ids de serie, ya unificadas por las uniones del usuario) de las fichas de cada grupo.
        val keys = mutableListOf<MutableSet<String>>()
        val byAnilist = HashMap<Long, MutableList<Int>>()
        val bySlug = items.groupBy { (source, item) -> source.id to SeriesMatch.slugBase(item.slug) }.values.flatMap { splitApart(it, rules, idOf) }
        for (members in bySlug) {
            val memberKeys = members.mapNotNull { (source, item) -> idOf(source.id, item.slug)?.toString()?.let(rules::canonical) }.toSet()
            val anilists = members.mapNotNull { (source, item) -> anilistOf(source.id, item.slug) }.toSet()
            val sources = members.map { it.first.id }.toSet()
            val years = members.map { it.second.year }
            // Dos fichas con el mismo anime de AniList pero años de estreno que no caben juntos no son la misma serie (una temporada o un remake).
            fun fits(index: Int) = keys[index].none { a -> memberKeys.any { b -> rules.isSplit(a, b) } } && groups[index].none { it.first.id in sources } &&
                LinkEvidence.compatible(groups[index].map { it.second.year } + years)
            val target = anilists.firstNotNullOfOrNull { id -> byAnilist[id].orEmpty().firstOrNull(::fits) }
            val index = if (target != null) {
                groups[target].addAll(members)
                keys[target].addAll(memberKeys)
                target
            } else {
                groups += members.toMutableList()
                keys += memberKeys.toMutableSet()
                groups.lastIndex
            }
            anilists.forEach { byAnilist.getOrPut(it) { mutableListOf() }.add(index) }
        }
        return joinSharingKeys(groups, keys)
    }

    /**
     * Parte las fichas que comparten slug cuando no son la misma serie: las que el usuario separó (nunca van juntas, ni por slug)
     * y las de años conocidos y distintos.
     */
    private fun splitApart(
        members: List<Pair<AnimeSource, SeriesSummary>>,
        rules: TitleRules,
        idOf: (sourceId: String, slug: String) -> Long?
    ): List<List<Pair<AnimeSource, SeriesSummary>>> {
        if (members.size < 2) return listOf(members)
        fun keyOf(member: Pair<AnimeSource, SeriesSummary>) = idOf(member.first.id, member.second.slug)?.toString()?.let(rules::canonical)
        val clusters = mutableListOf<MutableList<Pair<AnimeSource, SeriesSummary>>>()
        for (member in members) {
            val key = keyOf(member)
            val cluster = clusters.firstOrNull { other ->
                (key == null || other.none { keyOf(it)?.let { k -> rules.isSplit(k, key) } == true }) &&
                    other.all { SeriesMatch.yearsFit(it.second.year, member.second.year) }
            }
            if (cluster != null) cluster += member else clusters += mutableListOf(member)
        }
        return clusters
    }

    /** Junta los grupos que comparten alguna clave de regla (mismo id de serie); el resultado conserva el orden de la primera aparición. */
    private fun joinSharingKeys(
        groups: List<List<Pair<AnimeSource, SeriesSummary>>>,
        keys: List<Set<String>>
    ): List<List<Pair<AnimeSource, SeriesSummary>>> {
        val root = IntArray(groups.size) { it }
        fun find(i: Int): Int = if (root[i] == i) i else find(root[i]).also { root[i] = it }
        val owner = HashMap<String, Int>()
        for (index in groups.indices) {
            for (key in keys[index]) {
                val other = owner.getOrPut(key) { index }
                if (find(other) != find(index)) root[find(index)] = find(other)
            }
        }
        val joined = LinkedHashMap<Int, MutableList<Pair<AnimeSource, SeriesSummary>>>()
        for (index in groups.indices) joined.getOrPut(find(index)) { mutableListOf() }.addAll(groups[index])
        return joined.values.toList()
    }

    /** Nombre real de una serie: el de la primera ficha cuyo nombre no es solo su slug con espacios. */
    private fun realName(group: List<SeriesSummary>): String =
        // Con «x» y «x-latino» unidas, el nombre de la serie sale de la ficha base si tiene nombre real.
        group.sortedBy { it.slug.endsWith("-latino") }.firstOrNull { !it.name.equals(it.slug.replace('-', ' '), ignoreCase = true) }?.name ?: group.first().name

    /** Une las fichas de una misma serie en una entrada: nombre real, nota ponderada y año más antiguo. */
    fun entryOf(key: String, group: List<SeriesSummary>, updatedAt: Long): CatalogEntry {
        val weighted = weightedStarsOf(group)
        return CatalogEntry(
            key = key,
            name = realName(group),
            score = weighted?.first ?: group.firstNotNullOfOrNull { it.score },
            votes = weighted?.second ?: 0,
            year = group.mapNotNull { it.year }.minOrNull(),
            updatedAt = updatedAt
        )
    }
}
