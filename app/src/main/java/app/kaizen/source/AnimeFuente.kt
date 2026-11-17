package app.kaizen.source


/**
 * A qué anime de AniList pertenece una ficha de una fuente (`fuente`, `slug`). Guarda tanto lo que decide el sistema como lo que decide el usuario, y lo del usuario nunca lo toca un recálculo automático.
 *
 * [anilistId] es null cuando se sabe que la ficha no tiene anime (o no se encontró). [estado] dice si el enlace se usa
 * ([APLICADO]), es solo una propuesta para revisar ([PENDIENTE]) o no hubo resultado ([SIN_RESULTADO]).
 */
data class AnimeFuente(
    val fuente: String,
    val slug: String,
    val anilistId: Long?,
    val estado: String,
    val origen: String,
    val nivel: String,
    val puntuacion: Double,
    val fecha: Long
) {
    companion object {
        const val APLICADO = "aplicado"
        const val PENDIENTE = "pendiente"
        const val SIN_RESULTADO = "sin_resultado"
        const val AUTO = "auto"

        /** Nivel de un enlace automático hecho por el id de MyAnimeList que da la web: es la misma obra, no un parecido de nombres. */
        const val NIVEL_MAL = "mal"

        /** Nivel de un enlace automático que casó con un nombre alternativo de la web y no con el slug de la ficha: el slug puede no parecerse al anime, y no es una duda. */
        const val NIVEL_WEB = "web"
        const val USUARIO = "usuario"
    }
}

/**
 * Los enlaces de las fichas con AniList. En producción los implementa [AnimeFuenteStore] sobre las columnas de enlace de la tabla [FichaRow]
 * (antes tenían tabla propia, `AnimeFuente`); las pruebas lo sustituyen por uno en memoria.
 */
interface AnimeFuenteDao {
    fun all(): List<AnimeFuente>

    fun get(fuente: String, slug: String): AnimeFuente?

    /** Los anime de AniList a los que hoy pertenece alguna ficha con enlace en uso. */
    fun appliedIds(): List<Long>

    fun countByState(): List<StateCount>

    /** Deja todas las filas automáticas como «caducadas»: el emparejado las vuelve a mirar (las del usuario no se tocan). */
    fun expireAuto()

    /** Borra el enlace automático de estas fichas, para poder reescribirlo; el del usuario se queda. */
    fun deleteAuto(fuente: String, slug: String)

    /** Solo inserta si la ficha no tiene ya enlace (el del usuario, o uno automático que no se haya borrado). */
    fun insertIfAbsent(rows: List<AnimeFuente>)

    fun put(rows: List<AnimeFuente>)

    data class StateCount(val estado: String, val total: Int)
}
