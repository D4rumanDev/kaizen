package app.kaizen.source

/**
 * Comprueba que una portada elegida por el usuario es de la serie a la que está asignada. Las reglas de `titles.json` nombran la serie por su id y, si los ids cambian
 * (uniones, separaciones, reagrupados), una regla puede quedar apuntando a otra serie y enseñar su portada (Chainsaw Man con la de Tensei Kizoku). Varias webs ponen el slug
 * de la serie en la dirección de la imagen: si el slug de la imagen es de otra serie, la portada no vale.
 */
internal object CoverGuard {
    private val SLUG_IN_URL = listOf(
        Regex("^https://cdn\\.jkdesa\\.com/assets/images/animes/image/([a-z0-9-]+)\\.(?:jpg|jpeg|webp|png)$"),
        Regex("^https://cdn\\.animegratis\\.net/anime/([a-z0-9-]+)/images/"),
        // monoschinos añade al slug una marca numérica larga: «slug-1791392025.jpg».
        Regex("^https://monoschinos[a-z0-9.]*/assets/img/serie/imagen/([a-z0-9-]+?)(?:-\\d{6,})?\\.(?:jpg|jpeg|webp|png)$")
    )

    /** El slug de serie que nombra la dirección de la imagen, o null si la web no lo pone en ella. */
    fun slugIn(url: String): String? = SLUG_IN_URL.firstNotNullOfOrNull { it.find(url)?.groupValues?.get(1) }

    /**
     * Si la portada [url] puede ser de la serie [key]. [ownersOf] da las series (ids) que tienen una ficha con ese slug; si ninguna lo tiene, o la imagen no lleva slug,
     * no hay nada que contradiga y vale. Con dueños conocidos, vale solo si alguno es la serie ([same] compara dos ids, p. ej. tras una unión del usuario).
     */
    fun fits(url: String, key: String, ownersOf: (String) -> List<String>, same: (String, String) -> Boolean): Boolean {
        val named = slugIn(url) ?: return true
        val owners = ownersOf(named)
        return owners.isEmpty() || owners.any { same(it, key) }
    }
}
