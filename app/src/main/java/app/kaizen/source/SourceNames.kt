package app.kaizen.source

/** El nombre con que se muestra cada fuente (los de las especificaciones son el dominio, en minúsculas: «animeav1», «animeflv.one»). */
internal object SourceNames {
    private val known = mapOf(
        "animeav1" to "AnimeAV1", "animeflv" to "AnimeFLV", "animeflvar" to "AnimeFLV.ar", "animegratis" to "AnimeGratis",
        "jkanime" to "JKAnime", "monoschinos" to "MonosChinos", "verani" to "Verani"
    )

    /** El nombre conocido de la fuente [id]; una fuente nueva sale con la primera letra en mayúscula. */
    fun display(id: String, raw: String): String = known[id] ?: raw.replaceFirstChar(Char::uppercase)
}
