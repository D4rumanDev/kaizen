package app.kaizen.domain

import app.kaizen.commons.KaizenSettings
import app.kaizen.source.normalizeTitle
import app.kaizen.source.seriesIdOf

/**
 * Series que el usuario no quiere que se le recomienden, guardadas por título normalizado (así valen en cualquier fuente) y, si se
 * conoce la ficha, por su id de serie («#id»): así vale para todas las fichas unidas aunque se llamen distinto.
 */
object NotRecommended {
    val titles: Set<String> get() = KaizenSettings.notRecommended

    private fun idKey(source: String?, slug: String?): String? =
        if (source == null || slug == null) null else seriesIdOf(source, slug).takeIf { it != 0L }?.let { "#$it" }

    /** True si [name] (o, con [source] y [slug], el grupo de fichas de su serie) está en [excluded]. */
    fun isExcluded(excluded: Set<String>, name: String, source: String? = null, slug: String? = null): Boolean =
        normalizeTitle(name) in excluded || idKey(source, slug)?.let { it in excluded } == true

    fun allows(name: String, source: String? = null, slug: String? = null): Boolean = !isExcluded(titles, name, source, slug)

    fun set(name: String, excluded: Boolean, source: String? = null, slug: String? = null) {
        val keys = listOfNotNull(normalizeTitle(name), idKey(source, slug))
        KaizenSettings.notRecommended = if (excluded) titles + keys else titles - keys.toSet()
    }
}
