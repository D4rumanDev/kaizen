package app.kaizen.source

import app.kaizen.pojos.av1.Relation

/**
 * Decide si una ficha es la misma serie que otra ya vista. Es la única regla de «misma serie» para los listados
 * (horario, recientes, listados en vivo), así todos coinciden:
 *  - mismo id de serie ([idOf]) = misma serie, venga de donde venga;
 *  - mismo slug («x» y «x-latino» cuentan como el mismo) = misma serie, salvo que el usuario las separara o los años (si se dan) sean distintos;
 *  - mismo título = misma serie si lo repite OTRA fuente y los años son compatibles: ver [SeriesMatch].
 * [scope] separa los casos que comparten serie pero no ficha, p. ej. el número de episodio en los recientes.
 * No es seguro entre hilos: quien lo comparta debe sincronizar.
 */
class SeriesDedupe(
    private val rules: TitleRules = UserTitles.rules,
    private val idOf: (source: String, slug: String) -> Long? = SeriesLedger::peek
) {
    // Claves de serie y año de las fichas vistas con cada slug (null = desconocido), para no dar por iguales dos fichas separadas
    // por el usuario ni de años distintos.
    private val slugs = HashMap<String, MutableList<Pair<String?, Int?>>>()
    private val seen = HashSet<String>()
    private class Seen(val title: String, val source: String, val scope: String, val key: String?, val year: Int?)

    private val titles = ArrayList<Seen>()

    /** True si la ficha es nueva (queda registrada); false si ya había una igual. */
    fun isNew(slug: String, name: String, source: String, scope: String = "", year: Int? = null): Boolean {
        val key = idOf(source, slug)?.toString()?.let(rules::canonical)
        val slugKey = "${SeriesMatch.slugBase(slug)}|$scope"
        val idKey = key?.let { "i:$it|$scope" }
        val title = normalizeTitle(name).takeIf { it.length >= 3 }
        val sameSlug = slugs[slugKey].orEmpty().any { (otherKey, otherYear) ->
            (otherKey == null || key == null || !rules.isSplit(otherKey, key)) && SeriesMatch.yearsFit(otherYear, year)
        }
        val duplicate = sameSlug || (idKey != null && idKey in seen) ||
            (title != null && titles.any { other ->
                other.scope == scope && SeriesMatch.sameSeries(other.title, other.year, other.source, title, year, source) &&
                    !(other.key != null && key != null && rules.isSplit(other.key, key))
            })
        if (!duplicate) {
            slugs.getOrPut(slugKey) { mutableListOf() }.add(key to year)
            idKey?.let(seen::add)
            if (title != null) titles.add(Seen(title, source, scope, key, year))
        }
        return !duplicate
    }
}

/**
 * Identidad de una ficha para quitar repetidos en un listado: su id de serie (con las uniones del usuario aplicadas) y, si aún no
 * tiene, su fuente y su slug. Dos fichas unidas dan la misma clave aunque tengan otro título u otra fuente.
 */
fun seriesKeyOf(source: String, slug: String, rules: TitleRules = UserTitles.rules): String =
    SeriesLedger.peek(source, slug)?.toString()?.let(rules::canonical)?.let { "i:$it" } ?: "f:$source:$slug"

/**
 * Identidad de una serie para quitar repetidos en listas de sugerencias: su id de serie si lo tiene ([seriesKeyOf]: las fichas unidas
 * coinciden aunque cambie el título) y, si no, su título normalizado. Úsala en todo listado que no pase por [SeriesDedupe].
 */
fun seriesIdentity(name: String, source: String, slug: String): String =
    seriesKeyOf(source, slug).takeIf { it.startsWith("i:") } ?: "t:${normalizeTitle(name)}"

/**
 * Relaciones de la ficha [slug]/[name] de [source] reunidas de varias fuentes, en orden y sin repetir serie
 * (ni la propia ficha). [defaultSource] es la fuente de las relaciones guardadas sin ella.
 */
fun mergeRelations(slug: String, name: String, source: String, relations: List<Relation>, defaultSource: String): List<Relation> {
    val dedupe = SeriesDedupe().apply { isNew(slug, name, source) }
    return relations.filter { dedupe.isNew(it.slug, it.name, it.source ?: defaultSource) }
}
