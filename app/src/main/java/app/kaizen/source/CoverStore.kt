package app.kaizen.source

import android.content.SharedPreferences
import app.kaizen.commons.runCatchingCoop
import java.util.concurrent.ConcurrentHashMap

/**
 * Portadas recordadas de una fuente (slug → URL real). Muchas webs ponen en el nombre del fichero una marca que no se
 * puede deducir del slug, así que se guarda la URL que traen sus listados y fichas; persiste para pintar sin haber
 * visitado la web otra vez. Cada fuente decide qué URLs valen con [accepts] y cuál usar mientras no se sabe ([fallback]).
 *
 * @param accepts (slug, url, hasStored): si [url] se guarda como portada de [slug]; `hasStored` indica que ya hay una guardada.
 */
internal class CoverStore(
    private val fallback: (slug: String) -> String,
    private val accepts: (slug: String, url: String, hasStored: Boolean) -> Boolean,
    prefs: () -> SharedPreferences
) {
    constructor(
        prefsName: String,
        fallback: (slug: String) -> String,
        accepts: (slug: String, url: String, hasStored: Boolean) -> Boolean
    ) : this(fallback, accepts, { app.kaizen.commons.KaizenStore.prefs(prefsName) })

    private val bySlug = ConcurrentHashMap<String, String>()
    private val prefs by lazy(prefs)

    /** La portada recordada de [slug], o la de reserva. */
    fun url(slug: String): String = stored(slug) ?: fallback(slug)

    /** La guardada, si la fuente aún la acepta: una portada que ya no vale (p. ej. un logo de relleno guardado antes) se ignora. */
    internal fun stored(slug: String): String? {
        val value = bySlug[slug] ?: runCatchingCoop { prefs.getString(slug, null)?.also { bySlug[slug] = it } }.getOrNull()
        return value?.takeIf { it != fallback(slug) && accepts(slug, it, false) }
    }

    fun remember(slug: String, src: String?) = rememberAll(listOf(slug to src))

    /** Olvida todas las portadas guardadas. */
    fun clear() {
        bySlug.clear()
        runCatchingCoop { prefs.edit().clear().apply() }
    }

    /** Guarda las portadas aceptadas de [covers] en una sola escritura. */
    fun rememberAll(covers: List<Pair<String, String?>>) {
        val changed = LinkedHashMap<String, String>()
        for ((slug, src) in covers) {
            val url = src ?: continue
            // La portada de reserva no se guarda: url() ya la da cuando no hay otra.
            if (bySlug[slug] != url && url != fallback(slug) && accepts(slug, url, slug in changed || stored(slug) != null)) changed[slug] = url
        }
        if (changed.isEmpty()) return
        bySlug.putAll(changed)
        runCatchingCoop { prefs.edit().apply { changed.forEach { (slug, url) -> putString(slug, url) } }.apply() }
    }
}
