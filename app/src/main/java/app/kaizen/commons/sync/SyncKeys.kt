package app.kaizen.commons.sync

/**
 * La parte sin Android de lo que la copia de estados ([app.kaizen.commons.KaizenBackup]) da a la sincronización: qué claves portables hay, cuál es la clave canónica de cada una,
 * si cambió aquí después de una lápida, qué se quita y qué progreso entra. [idOf] da el id local de la serie de una ficha (null si no se conoce); en la app es `SeriesLedger.peek`.
 */
internal object SyncKeys {
    /** Lo que hay en un dispositivo. Los pares (serie, número) son capítulos; [progressByFicha] es el progreso guardado por ficha (versiones anteriores y copias ajenas). */
    class Presence(
        private val favoriteIds: Set<Long>,
        private val records: Set<Pair<Long, Double>>,
        override val keys: Set<String>,
        private val progress: Set<Pair<Long, Double>>,
        private val progressByFicha: Set<Pair<String, Double>>,
        private val statusIds: Set<Long>,
        private val idOf: (source: String, slug: String) -> Long?
    ) : Removals.Presence {
        /** ¿Sigue [key]? null si no se sabe (la ficha no es conocida). */
        override fun isPresent(key: String): Boolean? {
            val parsed = Removals.parse(key) ?: return null
            val id = idOf(parsed.source, parsed.slug)
            if (parsed.progress) {
                val byFicha = ("${parsed.source}:${parsed.slug}" to parsed.number!!) in progressByFicha
                // Sin serie conocida solo cuenta la ficha de versiones anteriores; si tampoco está, no se sabe.
                return if (id == null) byFicha.takeIf { it } else (id to parsed.number) in progress || byFicha
            }
            if (id == null) return null
            if (parsed.status) return id in statusIds
            return if (parsed.favorite) id in favoriteIds else (id to parsed.number!!) in records
        }
    }

    /**
     * Las claves portables de lo que hay: favoritos y capítulos vistos por la ficha de su serie ([fichaOf]; sin ficha no salen), el progreso por serie y por ficha, y un estado por
     * cada ficha con estado.
     */
    fun keys(
        favoriteIds: Collection<Long>,
        records: Collection<Pair<Long, Double>>,
        progress: Collection<Pair<Long, Double>>,
        progressByFicha: Collection<Pair<String, Double>>,
        statusFichas: Collection<String>,
        fichaOf: (seriesId: Long) -> String?
    ): Set<String> {
        val keys = HashSet<String>()
        favoriteIds.forEach { id -> fichaOf(id)?.let { keys += Removals.favoriteKey(it) } }
        records.forEach { (id, number) -> fichaOf(id)?.let { keys += Removals.recordKey(it, number) } }
        progress.forEach { (id, number) -> fichaOf(id)?.let { keys += Removals.progressKey(it, number) } }
        progressByFicha.forEach { (ficha, number) -> keys += Removals.progressKey(ficha, number) }
        statusFichas.forEach { keys += Removals.statusKey(it) }
        return keys
    }

    /** La clave que iguala la misma serie en dos dispositivos aunque la ficha sea otra: su id local. null si la ficha no es conocida. */
    fun canon(key: String, idOf: (source: String, slug: String) -> Long?): String? {
        val parsed = Removals.parse(key) ?: return null
        val id = idOf(parsed.source, parsed.slug) ?: return null
        return when {
            parsed.favorite -> "f/$id"
            parsed.status -> "e/$id"
            parsed.progress -> "c/$id#${parsed.number}"
            else -> "r/$id#${parsed.number}"
        }
    }

    /** ¿Cambió aquí [key] después de [at]? Solo los estados ([statusAt]) y el progreso ([progressAt]) llevan hora; favoritos y capítulos vistos, no (false). */
    fun changedAfter(key: String, at: Long, statusAt: (source: String, slug: String) -> Long, progressAt: (source: String, slug: String, number: Double) -> Long): Boolean {
        val parsed = Removals.parse(key) ?: return false
        return when {
            parsed.status -> statusAt(parsed.source, parsed.slug) > at
            parsed.progress -> progressAt(parsed.source, parsed.slug, parsed.number!!) > at
            else -> false
        }
    }

    /** Dónde se quita cada cosa; cada función devuelve si había algo que quitar. */
    interface Target {
        fun removeFavorite(source: String, slug: String): Boolean
        fun removeRecord(source: String, slug: String, number: Double): Boolean
        fun removeProgress(source: String, slug: String, number: Double): Boolean
        fun removeStatus(source: String, slug: String): Boolean
    }

    /** Quita lo que dicen [keys] (las mal formadas se ignoran); devuelve cuántas había. */
    fun remove(keys: Collection<String>, target: Target): Int = keys.count { key ->
        val parsed = Removals.parse(key) ?: return@count false
        when {
            parsed.favorite -> target.removeFavorite(parsed.source, parsed.slug)
            parsed.status -> target.removeStatus(parsed.source, parsed.slug)
            parsed.progress -> target.removeProgress(parsed.source, parsed.slug, parsed.number!!)
            else -> target.removeRecord(parsed.source, parsed.slug, parsed.number!!)
        }
    }

    /**
     * El progreso de una copia que puede entrar: nunca el guardado por serie («s:», sus ids son de otro equipo) ni el que una lápida más nueva que la copia ([skip]) dice que
     * aquí se quitó de «Seguir viendo». Las horas «t:» y las claves antiguas por eid pasan (quien importa decide).
     */
    fun progressToImport(progress: Map<String, String>, skip: (String) -> Boolean): Map<String, String> = progress.filterKeys { key ->
        if (key.startsWith("s:")) return@filterKeys false
        if (!key.startsWith("p:")) return@filterKeys true
        val body = key.removePrefix("p:")
        val number = body.substringAfterLast('#', "").toDoubleOrNull()
        !(number != null && skip(Removals.progressKey(body.substringBeforeLast('#'), number)))
    }

    /** Los grupos de fichas de una copia que se pueden unir: solo «fuente:slug» de una fuente de [sources], con tope de grupos y de tamaño (una copia ajena o corrupta no inventa fichas). */
    fun groups(groups: List<List<String>>, sources: Set<String>, maxGroups: Int, maxSize: Int): List<List<Pair<String, String>>> =
        groups.take(maxGroups).map { group ->
            group.take(maxSize).filter { ':' in it && it.substringBefore(':') in sources && it.substringAfter(':').isNotBlank() }
                .map { it.substringBefore(':') to it.substringAfter(':') }
        }
}
