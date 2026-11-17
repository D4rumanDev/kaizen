package app.kaizen.domain

import app.kaizen.source.seriesIdentity
import app.kaizen.pojos.av1.SeriesSummary
import kotlin.random.Random

/** Una serie que cuenta para el gusto del usuario: sus géneros (slugs) y cuánto pesa (favorita > vista). */
data class TasteItem(val genres: List<String>, val weight: Double)

/** Lo que el usuario ve de un género: [weight] suma el peso de sus series y [series] cuántas son. */
data class GenreTaste(val slug: String, val weight: Double, val series: Int)

/**
 * Géneros que más le gustan al usuario, sacados de sus favoritos y de lo que ha visto. Sin datos (ninguna serie con
 * géneros conocidos) está vacío y las recomendaciones caen a la lista general.
 */
class TasteProfile(val genres: List<GenreTaste>) {
    val isEmpty: Boolean get() = genres.isEmpty()

    fun top(count: Int): List<GenreTaste> = genres.take(count)

    companion object {
        val EMPTY = TasteProfile(emptyList())

        fun build(items: List<TasteItem>): TasteProfile {
            val weights = HashMap<String, Double>()
            val counts = HashMap<String, Int>()
            for (item in items) {
                for (slug in item.genres.distinct()) {
                    weights[slug] = (weights[slug] ?: 0.0) + item.weight
                    counts[slug] = (counts[slug] ?: 0) + 1
                }
            }
            return TasteProfile(
                weights.map { (slug, weight) -> GenreTaste(slug, weight, counts.getValue(slug)) }
                    .sortedWith(compareByDescending<GenreTaste> { it.weight }.thenBy { it.slug })
            )
        }
    }
}

object Taste {
    /** Nota mínima (estrellas de 5) de lo que se recomienda: de 3 a 5. */
    const val MIN_STARS = 3.0

    /** Cuántos géneros del perfil se consultan en cada tanda de recomendados. */
    const val GENRES_PER_BATCH = 4

    /** Cuántas precuelas o secuelas se proponen como extras al principio de la lista. */
    const val SEQUELS_SHOWN = 4

    /** Se elige entre las mejores [POOL_FACTOR] veces lo que se muestra, para que no salga siempre lo mismo. */
    private const val POOL_FACTOR = 5

    /** Suma a cada candidata al sortear: las de poco encaje siguen teniendo alguna opción. */
    private const val BASE_CHANCE = 0.25

    /**
     * Géneros de esta tanda: se sortean según su peso (los favoritos salen más, pero no siempre los mismos tres),
     * sin repetir. Con [random] fijo el resultado es siempre el mismo (tests).
     */
    fun pickGenres(profile: TasteProfile, count: Int, random: Random): List<GenreTaste> =
        sampleWeighted(profile.genres, count, random) { it.weight }

    /**
     * Une las listas por género en una sola. Una serie que sale en varios de tus géneros puntúa más (suma el peso
     * relativo de cada uno) y, a igualdad, la de mejor nota. Se quitan las que ya tienes en favoritos o vistas
     * ([known]: títulos normalizados e ids de serie «#id») y las de nota por debajo de [MIN_STARS].
     *
     * Sin [random] sale el orden de mejor a peor encaje. Con él se sortean [limit] entre las mejores [POOL_FACTOR] veces
     * [limit], con más opciones cuanto mejor encajan: cada vez se ve algo distinto sin salirse de tu gusto.
     */
    fun rank(
        byGenre: Map<String, List<SeriesSummary>>,
        profile: TasteProfile,
        known: Set<String>,
        limit: Int,
        random: Random? = null
    ): List<SeriesSummary> {
        val maxWeight = profile.genres.maxOfOrNull { it.weight } ?: return emptyList()
        val relative = profile.genres.associate { it.slug to it.weight / maxWeight }
        val affinity = HashMap<String, Double>()
        val best = LinkedHashMap<String, SeriesSummary>()
        for ((genre, list) in byGenre) {
            for (item in list) {
                val key = seriesIdentity(item.name, item.source, item.slug)
                if (NotRecommended.isExcluded(known, item.name, item.source, item.slug) || (item.score != null && item.score < MIN_STARS)) continue
                affinity[key] = (affinity[key] ?: 0.0) + (relative[genre] ?: 0.0)
                val current = best[key]
                if (current == null || (item.score ?: 0.0) > (current.score ?: 0.0)) best[key] = item
            }
        }
        val ordered = best.entries
            .map { (key, item) -> item to affinity.getValue(key) + quality(item.score) }
            .sortedWith(compareByDescending<Pair<SeriesSummary, Double>> { it.second }.thenByDescending { it.first.score ?: 0.0 })
        if (random == null) return ordered.take(limit).map { it.first }
        return sampleWeighted(ordered.take(limit * POOL_FACTOR), limit, random) { it.second + BASE_CHANCE }.map { it.first }
    }

    /** [count] elementos de [options] sorteados sin repetir, con más probabilidad cuanto mayor es su [weight]. */
    private fun <T> sampleWeighted(options: List<T>, count: Int, random: Random, weight: (T) -> Double): List<T> {
        val remaining = options.toMutableList()
        val picked = ArrayList<T>()
        repeat(minOf(count, remaining.size)) {
            val chosen = weightedPick(remaining, random, weight)
            picked += chosen
            remaining.remove(chosen)
        }
        return picked
    }

    /**
     * Las precuelas y secuelas de tus series (sorteadas, [SEQUELS_SHOWN] como mucho) delante, y detrás los recomendados
     * por género sin repetirlas, hasta [limit]. Ver una secuela o una precuela suma a tus estadísticas.
     */
    fun withSequels(sequels: List<SeriesSummary>, recommended: List<SeriesSummary>, limit: Int, random: Random): List<SeriesSummary> {
        val extras = sequels.shuffled(random).take(SEQUELS_SHOWN)
        val taken = extras.map { seriesIdentity(it.name, it.source, it.slug) }.toSet()
        return (extras + recommended.filter { seriesIdentity(it.name, it.source, it.slug) !in taken }).take(limit)
    }

    private fun <T> weightedPick(options: List<T>, random: Random, weight: (T) -> Double): T {
        var target = random.nextDouble() * options.sumOf(weight)
        for (option in options) {
            target -= weight(option)
            if (target <= 0.0) return option
        }
        return options.last()
    }

    /** [items] sin las series cuyo título normalizado está en [excluded] (las que el usuario no quiere que se le recomienden). */
    fun withoutExcluded(items: List<SeriesSummary>, excluded: Set<String>): List<SeriesSummary> =
        if (excluded.isEmpty()) items else items.filterNot { NotRecommended.isExcluded(excluded, it.name, it.source, it.slug) }

    /** Peso de los géneros en común frente a la nota en [similarity]. */
    private const val GENRE_SHARE = 0.7

    /**
     * Cuánto se parece una serie con géneros [other] y nota [stars] (de 5) a otra con géneros [own]: el solape de géneros
     * (comunes entre el total de ambas, así una serie con muchos géneros no gana solo por tener más) y la nota. Sin
     * géneros en común, 0.
     */
    fun similarity(own: Set<String>, other: Set<String>, stars: Float): Double {
        val shared = own.intersect(other).size
        if (shared == 0) return 0.0
        return GENRE_SHARE * shared / own.union(other).size + (1 - GENRE_SHARE) * quality(stars.toDouble())
    }

    /** 0 en [MIN_STARS] y 1 en 5 estrellas; sin nota, 0. */
    private fun quality(score: Double?): Double = score?.let { ((it - MIN_STARS) / (5.0 - MIN_STARS)).coerceIn(0.0, 1.0) } ?: 0.0
}
