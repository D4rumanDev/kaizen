package app.kaizen.source

/**
 * La portada de mayor resolución ya medida de cada serie (por slug), elegida entre las de todas las fuentes que la tienen
 * (ver `sharpestCover`). Las tarjetas la usan antes que la de su propia fuente, que a veces es diminuta. Si la propia ya
 * era buena se guarda ella misma: así la serie no se vuelve a medir.
 */
internal object BestCovers {
    private val store = CoverStore("kaizen_best_covers", fallback = { "" }) { _, url, _ -> url.startsWith("https://") }

    fun get(slug: String): String? = store.stored(slug)

    fun put(slug: String, url: String) = store.remember(slug, url)

    /** Las elegidas dependen de qué fichas estaban unidas: al deshacer las uniones ya no valen. */
    fun clear() = store.clear()
}
