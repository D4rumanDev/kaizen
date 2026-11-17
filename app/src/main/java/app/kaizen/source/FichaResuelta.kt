package app.kaizen.source

/**
 * La ficha de una serie «a ver»: los datos de AniList si la serie está enlazada y, **campo a campo**, los de la web de la fuente para lo que AniList no da. No guarda ni
 * copia nada: [anilist] y [web] siguen siendo cada una lo que dijo su origen (la de la web, limpia, porque es la prueba con la que se empareja y se revisa el enlace),
 * y esta clase solo decide de cuál sale cada dato. Sin enlace ([anilist] null) todo sale de la web.
 *
 * Un dato «vacío» (null, texto vacío, lista `[]`) no tapa al de la otra: AniList sin géneros deja pasar los de la web. Lo que solo existe en la web (`calidad`, `nota`,
 * `votos`…) sale siempre de ella.
 */
class FichaResuelta(val anilist: FichaData?, val web: FichaData?) {
    private fun fromWeb(campo: String) = campo.trim() in IDENTIDAD

    /** El dato que da [get]: el de AniList si lo tiene y, si no, el de la web. */
    fun <T> dato(get: (FichaData) -> T?): T? = anilist?.let(get)?.takeUnless(::vacio) ?: web?.let(get)?.takeUnless(::vacio)

    /** Como [FichaData.valor] (por nombre de getter o ruta del JSON), con la misma regla: AniList primero, la web si AniList no lo da. */
    fun valor(campo: String): String? =
        if (fromWeb(campo)) web?.valor(campo)?.takeUnless(::vacio)
        else anilist?.valor(campo)?.takeUnless(::vacio) ?: web?.valor(campo)?.takeUnless(::vacio)

    /** De dónde sale [campo]: `anilist`, la fuente de la web (`jkanime`…) o null si ninguna lo da. */
    fun origen(campo: String): String? = when {
        fromWeb(campo) -> web?.takeIf { it.valor(campo)?.let(::vacio) == false }?.fuente
        anilist?.valor(campo)?.takeUnless(::vacio) != null -> FichaData.ANILIST
        web?.valor(campo)?.takeUnless(::vacio) != null -> web.fuente
        else -> null
    }

    companion object {
        /** La identidad de la ficha (de dónde sale) es siempre la de la web: AniList no tiene «fuente» ni «slug». */
        private val IDENTIDAD = setOf("fuente", "slug", "url")

        /** La ficha de AniList a la que está enlazada una ficha de la web: solo si el enlace está aplicado y su ficha descargada se puede leer; null si no. */
        fun anilistOf(link: AnimeFuente?, ficha: (Long) -> AnimeFicha?): FichaData? =
            link?.takeIf { it.estado == AnimeFuente.APLICADO }?.anilistId?.let(ficha)?.let(FichaData::ofAnilist)
    }

    private fun vacio(value: Any?): Boolean = value == null || (value is CharSequence && (value.isBlank() || value == "[]")) || (value is Collection<*> && value.isEmpty())
}
