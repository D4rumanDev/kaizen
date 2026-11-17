package app.kaizen.domain

/** Una precuela o secuela de una serie que el usuario conoce. */
data class StoredRelation(val type: Int, val aid: Int, val source: String, val slug: String, val name: String)

/**
 * Lo que se guarda de la ficha de una serie para el gusto: sus géneros y sus precuelas y secuelas. En un texto simple
 * (preferencias) con separadores que no aparecen en los títulos: `fecha;géneros;relaciones`.
 */
data class StoredTaste(val genres: List<String>, val relations: List<StoredRelation>) {
    fun encode(stampMs: Long): String =
        "$stampMs;${genres.joinToString(",")};" + relations.joinToString(RECORD) {
            listOf(it.type, it.aid, it.source, it.slug, it.name).joinToString(FIELD)
        }

    companion object {
        private const val RECORD = "\u001e"
        private const val FIELD = "\u001f"

        /**
         * Fecha y contenido, o null si el texto no es de este formato (también el antiguo, sin relaciones: se vuelve a
         * pedir la ficha para conocerlas) o si no tiene géneros.
         */
        fun decode(raw: String): Pair<Long, StoredTaste>? {
            val parts = raw.split(';', limit = 3).takeIf { it.size == 3 } ?: return null
            val stamp = parts[0].toLongOrNull() ?: return null
            val genres = parts[1].split(',').filter { it.isNotBlank() }.takeIf { it.isNotEmpty() } ?: return null
            val relations = parts[2].split(RECORD).filter { it.isNotEmpty() }.mapNotNull { record ->
                val fields = record.split(FIELD).takeIf { it.size == 5 } ?: return@mapNotNull null
                StoredRelation(
                    fields[0].toIntOrNull() ?: return@mapNotNull null,
                    fields[1].toIntOrNull() ?: return@mapNotNull null,
                    fields[2], fields[3], fields[4]
                )
            }
            return stamp to StoredTaste(genres, relations)
        }
    }
}
