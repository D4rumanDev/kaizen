package app.kaizen.source


/**
 * Búsqueda de series del catálogo para elegir una al unir o separar. Por nombre: todas las palabras escritas, en cualquier orden,
 * sin distinguir acentos, símbolos ni vocales largas ([nameMatches]). Por slug: el texto como fragmento de slug.
 */
internal object EntrySearch {
    private const val NAME_LIMIT = 100

    /** Las series de [entries] cuyo nombre contiene todas las palabras de [text] ([nameMatches]), por orden de nombre. */
    fun byNormalizedName(entries: List<CatalogEntry>, text: String): List<CatalogEntry> =
        byFoldedName(entries.map { it to foldTitle(it.name) }, text)

    /** Lo mismo con los nombres ya plegados ([foldTitleUncached]): el catálogo se pliega una vez y cada búsqueda solo compara. */
    fun byFoldedName(entries: List<Pair<CatalogEntry, String>>, text: String, limit: Int = NAME_LIMIT): List<CatalogEntry> {
        val words = searchWords(text)
        return entries.filter { foldedNameMatches(it.second, words) }.map { it.first }.sortedBy { it.name.lowercase() }.take(limit)
    }

    /** El texto como fragmento de slug («Shin Tennis» -> «shin-tennis»); vacío si no queda nada. */
    fun slugFragment(text: String): String = text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /** Las series de [byName] y [bySlug] sin repetir, por orden de nombre y como mucho [limit]. */
    fun merge(byName: List<CatalogEntry>, bySlug: List<CatalogEntry>, limit: Int = 20): List<CatalogEntry> =
        (byName + bySlug).distinctBy { it.key }.sortedBy { it.name.lowercase() }.take(limit)
}
