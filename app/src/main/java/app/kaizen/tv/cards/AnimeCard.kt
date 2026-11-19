package app.kaizen.tv.cards

/** Nota en estrellas (máximo 5), con votos si se conocen. Vacío si no hay nota: igual en tarjetas, ficha y paneles. */
internal fun formatScore(stars: Double?, votes: Int? = null): String = when {
    stars == null -> ""
    votes == null -> "★ %.1f".format(stars)
    else -> "★ %.1f (%d)".format(stars, votes)
}

internal const val SCORE_LOADING = "★ …"
internal const val SCORE_NONE = "★ —"
