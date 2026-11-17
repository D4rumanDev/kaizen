package app.kaizen.source

/** Una serie local por emparejar: su id, las fichas que tiene en las fuentes y lo urgente que es (favoritos y vistos primero). */
internal class SeriesWork(val seriesId: Long, val fichas: List<Pair<String, String>>, val priority: Int, val years: List<Int?> = emptyList())

/**
 * Una ficha por trabajo: cada ficha se empareja con su propio título y no hereda el anime de las demás de su serie (una serie mezclada,
 * como «Black Clover» con su 2ª temporada, contagiaba el mismo anime a todas). Conserva la prioridad de su serie.
 */
internal fun List<SeriesWork>.perFicha(): List<SeriesWork> = flatMap { series ->
    series.fichas.mapIndexed { i, ficha -> SeriesWork(series.seriesId, listOf(ficha), series.priority, listOf(series.years.getOrNull(i))) }
}

/** Lo que hizo una pasada de [AnilistSync]. */
internal class SyncReport(
    val series: Int = 0,
    val applied: Int = 0,
    val pending: Int = 0,
    val none: Int = 0,
    val fichasDownloaded: Int = 0,
    /** Por qué paró antes de acabar (null = acabó). */
    val stopped: String? = null,
    /** Series que tocaba emparejar al empezar la pasada (para el contador). */
    val total: Int = 0,
    /** Textos de Animeoteca guardados en esta pasada. */
    val esDownloaded: Int = 0
)

/**
 * Empareja las series locales con AniList y descarga la ficha de cada anime emparejado (ver `docs/wiki/emparejado-anilist.md`).
 * No une ni separa series locales: solo escribe [AnimeFuente] y [AnimeFicha]. Se puede parar entre lotes ([canRun]) y reanudar: lo ya
 * decidido no se repite hasta pasado [RETRY_AFTER_MS], y lo escrito del usuario no se toca.
 */
internal class AnilistSync(
    private val api: AnilistApi,
    private val fuentes: AnimeFuenteDao,
    private val fichas: AnimeFichaDao,
    private val work: () -> List<SeriesWork>,
    private val canRun: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
    private val onProgress: (SyncReport) -> Unit = {},
    /** El texto en español de Animeoteca de cada ficha descargada; sin él (null) esa fase se salta. */
    private val animeoteca: AnimeotecaApi? = null,
    /** Cuánto importa cada anime (favoritos 2, vistos 1, el resto 0): el texto de Animeoteca se pide primero para los que más importan. */
    private val priority: () -> Map<Long, Int> = { emptyMap() },
    /** El id de MyAnimeList y los otros nombres que dio la web de cada ficha que se ha abierto (ver [WebHints]); sin él, el emparejado va solo por el título. */
    private val hints: () -> Map<Pair<String, String>, FichaData> = { emptyMap() }
) {
    suspend fun runOnce(): SyncReport {
        var report = SyncReport()
        if (!canRun()) return report.copy(stopped = "pausa")
        try {
            val known = fuentes.all().associateBy { it.fuente to it.slug }
            val hints = hints()
            // El título normalizado se calcula una vez por serie (no en cada comparación: con 26.000 fichas la ordenación tardaba minutos de CPU).
            val todo = work().filter { needsWork(it, known, hints) }.map { it to AnilistLinkCheck.slugTitle(it.fichas.first().second) }
                .sortedWith(compareByDescending<Pair<SeriesWork, String>> { it.first.priority }.thenByDescending { it.first.fichas.size }.thenBy { it.second }).map { it.first }
            report = report.copy(total = todo.size)
            for (batch in todo.chunked(SERIES_PER_BATCH)) {
                if (!canRun()) return report.copy(stopped = "pausa")
                report = report.plus(matchBatch(batch, hints))
                onProgress(report)
            }
            val withFichas = downloadFichas(report)
            return if (withFichas.stopped != null) withFichas else downloadSpanish(withFichas)
        } catch (e: AnilistUnavailable) {
            return report.copy(stopped = e.message ?: "AniList no responde")
        }
    }

    /** Sin enlace del usuario en ninguna ficha y sin una decisión automática reciente en todas (un enlace automático solo se revisa si un criterio nuevo lo caducó). */
    private fun needsWork(series: SeriesWork, known: Map<Pair<String, String>, AnimeFuente>, hints: Map<Pair<String, String>, FichaData>): Boolean {
        val rows = series.fichas.map { known[it] }
        if (rows.any { it?.estado == AnimeFuente.APLICADO && it.origen == AnimeFuente.USUARIO }) return false
        // Una ficha decidida antes de tener la pista de su web se vuelve a mirar mientras la pista sea más nueva que la decisión (la decisión nueva es posterior, así
        // que no se repite hasta que la ficha se guarde de nuevo). Una ya enlazada solo si la pista trae el id de MyAnimeList: los nombres alternativos no la mejoran.
        val hint = hintOf(series, hints)
        val row = rows.singleOrNull()
        if (hint != null && row != null && row.origen == AnimeFuente.AUTO && row.nivel != AnimeFuente.NIVEL_MAL && row.fecha < hint.at &&
            (row.estado != AnimeFuente.APLICADO || hint.idMal != null)
        ) return true
        // Un enlace automático es estable: solo se revisa si lo dejó caducado un criterio nuevo (fecha 0, ver `expireAuto`), no por el paso del tiempo.
        val linked = rows.filterNotNull().filter { it.estado == AnimeFuente.APLICADO }
        if (linked.isNotEmpty() && linked.none { it.fecha == 0L }) return false
        // Una decisión del usuario cuenta siempre como reciente.
        return rows.any { it == null || (it.origen == AnimeFuente.AUTO && now() - it.fecha > RETRY_AFTER_MS) }
    }

    /** Lo que dio la web de la ficha de la serie; solo si la serie es una sola ficha (con varias, el dato de una no vale para las otras). */
    private fun hintOf(series: SeriesWork, hints: Map<Pair<String, String>, FichaData>): FichaData? = series.fichas.singleOrNull()?.let { hints[it] }

    private suspend fun matchBatch(batch: List<SeriesWork>, hints: Map<Pair<String, String>, FichaData>): SyncReport {
        // Primero, por el id de MyAnimeList que dio la web: AniList lo conoce (`idMal`) y es la misma obra, sin adivinar por el nombre.
        val webHints = batch.map { hintOf(it, hints) }
        val byMal = api.byMal(webHints.mapNotNull { it?.idMal }.distinct())
        val at0 = now()
        var viaMal = 0
        val viaMalAt = HashSet<Int>()
        batch.forEachIndexed { i, series ->
            val candidate = webHints[i]?.idMal?.let { byMal[it] } ?: return@forEachIndexed
            val (fuente, slug) = series.fichas.single()
            // El id de la web no se da por bueno a ciegas (una web puede repetir el de la primera temporada en sus secuelas): el año de estreno tiene que cuadrar y
            // el slug o algún nombre de la web tiene que parecerse al anime, con su temporada. Si no, la ficha sigue por el nombre como cualquier otra.
            // Una película, un OVA o un especial tampoco se enlaza con una serie de TV por mucho que la web repita su id (ver [AnilistMatcher.formatClash]).
            val agrees = LinkEvidence.yearsClose(LinkEvidence.seriesYear(series.years), candidate.year) &&
                !AnilistMatcher.formatClash(AnilistLinkCheck.slugTitle(slug), candidate.format, candidate.names) &&
                (AnilistLinkCheck.fits(slug, candidate) || webHints[i]?.alternativas.orEmpty().any { AnilistLinkCheck.fits(it, candidate) })
            if (!agrees) return@forEachIndexed
            fuentes.deleteAuto(fuente, slug)
            fuentes.insertIfAbsent(listOf(AnimeFuente(fuente, slug, candidate.id, AnimeFuente.APLICADO, AnimeFuente.AUTO, AnimeFuente.NIVEL_MAL, 1.0, at0)))
            viaMalAt += i
            viaMal++
        }
        val rest = batch.indices.filter { it !in viaMalAt }
        val others = if (rest.isEmpty()) SyncReport() else matchByTitle(rest.map { batch[it] }, rest.map { webHints[it] })
        return SyncReport(series = batch.size, applied = viaMal + others.applied, pending = others.pending, none = others.none)
    }

    /** Los otros nombres de la web (hasta dos distintos del título de la ficha) con los que buscar además del título. */
    private fun alternativesOf(hint: FichaData?, own: List<String>): List<String> {
        val taken = own.map(AnilistMatcher::normalize).toSet()
        return hint?.alternativas.orEmpty().map(AnilistMatcher::normalize).filter { it.isNotEmpty() && it !in taken }.distinct().take(MAX_ALTERNATIVES)
    }

    private suspend fun matchByTitle(batch: List<SeriesWork>, batchHints: List<FichaData?>): SyncReport {
        // Hasta dos títulos por serie (los slugs más cortos suelen ser el título sin sufijos), en una sola petición.
        val titles = batch.map { series -> series.fichas.map { it.second }.distinct().sortedBy { it.length }.take(2).map(AnilistLinkCheck::slugTitle).filter { it.isNotEmpty() } }
        val flat = titles.flatten()
        val found = searchCached(flat).toMutableList()
        // Sin resultado para el primer título: se reintenta con el título sin ruido y con las primeras palabras.
        for (retry in listOf<(String) -> String>({ AnilistMatcher.cleanQuery(AnilistMatcher.normalize(it)) }, { it.split(' ').take(3).joinToString(" ") })) {
            // Solo las series sin resultado, y solo si la nueva consulta es distinta de la que ya falló.
            val retries = titles.indices.filter { i -> titles[i].isNotEmpty() && found[offset(titles, i)].isEmpty() }
                .filter { i -> AnilistMatcher.searchPlan(titles[i].first()) == null }.mapNotNull { i -> retry(titles[i].first()).takeIf { it.isNotBlank() && it != titles[i].first() }?.let { i to it } }
            if (retries.isEmpty()) continue
            val again = searchCached(retries.map { it.second })
            retries.forEachIndexed { k, (i, _) -> found[offset(titles, i)] = again[k] }
        }
        // Los nombres alternativos de la web, buscados aparte: sus resultados suman candidatos al del título.
        val alternatives = titles.indices.map { alternativesOf(batchHints[it], titles[it]) }
        val alternativeFound = searchCached(alternatives.flatten())
        var applied = 0
        var pending = 0
        var none = 0
        val at = now()
        titles.forEachIndexed { i, own ->
            val results = own.indices.map { found[offset(titles, i) + it] }
            val altStart = alternatives.take(i).sumOf { it.size }
            val match = AnilistMatcher.decide(
                own, results, LinkEvidence.seriesYear(batch[i].years),
                alternatives[i], alternatives[i].indices.map { alternativeFound[altStart + it] }, batchHints[i]
            )
            // Un enlace automático ya aplicado (no caducado: fecha distinta de 0) no se cambia por el nombre: si se vuelve a mirar es por una pista nueva, y una búsqueda que no
            // encuentra (o que falla) no debe deshacerlo. Se conserva y se anota la fecha para no repetirlo; no cuenta como un enlace nuevo en el informe.
            val locked = batch[i].fichas.filter { (fuente, slug) ->
                fuentes.get(fuente, slug)?.let { old -> old.estado == AnimeFuente.APLICADO && old.origen == AnimeFuente.AUTO && old.fecha != 0L } == true
            }.toSet()
            val settled = locked.size == batch[i].fichas.size
            val (estado, id) = when (match.level) {
                AnilistMatcher.Level.SEGURO, AnilistMatcher.Level.PROBABLE -> { if (!settled) applied++; AnimeFuente.APLICADO to match.candidate?.id }
                AnilistMatcher.Level.DUDOSO -> { if (!settled) pending++; AnimeFuente.PENDIENTE to match.candidate?.id }
                AnilistMatcher.Level.NINGUNO -> { if (!settled) none++; AnimeFuente.SIN_RESULTADO to null }
            }
            val rows = batch[i].fichas.map { (fuente, slug) ->
                // Una ficha que no se parece al anime elegido (serie mezclada con otra) no hereda su enlace: queda sin resultado. Se parece si lo hace
                // su slug o, para una ficha sola, alguno de los otros nombres que dio su web (nivel «web»: su slug puede estar en otro idioma).
                val candidate = match.candidate
                val byName = estado != AnimeFuente.APLICADO || candidate == null || AnilistLinkCheck.fits(slug, candidate)
                val byWeb = candidate != null && !byName && batch[i].fichas.size == 1 && alternatives[i].any { AnilistLinkCheck.fits(it, candidate) }
                when {
                    byName -> AnimeFuente(fuente, slug, id, estado, AnimeFuente.AUTO, match.level.name.lowercase(), match.score, at)
                    byWeb -> AnimeFuente(fuente, slug, id, estado, AnimeFuente.AUTO, AnimeFuente.NIVEL_WEB, match.score, at)
                    else -> AnimeFuente(fuente, slug, null, AnimeFuente.SIN_RESULTADO, AnimeFuente.AUTO, "mezcla", 0.0, at)
                }
            }
            val kept = rows.filter { (it.fuente to it.slug) in locked }
            // Se vuelve a leer justo antes de escribir y solo se toca si sigue siendo automática y aplicada: si el usuario la enlazó entretanto, no se le revierte.
            kept.forEach { row ->
                fuentes.get(row.fuente, row.slug)?.takeIf { it.estado == AnimeFuente.APLICADO && it.origen == AnimeFuente.AUTO }?.let { fuentes.put(listOf(it.copy(fecha = at))) }
            }
            val fresh = rows.filter { it !in kept }
            fresh.forEach { fuentes.deleteAuto(it.fuente, it.slug) }
            fuentes.insertIfAbsent(fresh)
        }
        return SyncReport(series = batch.size, applied = applied, pending = pending, none = none)
    }

    /** Lo ya buscado en esta pasada: varias fuentes llaman igual a una serie y cada título se pide a AniList una sola vez. */
    private val searched = HashMap<String, List<AnilistMatcher.Candidate>>()

    private suspend fun searchCached(titles: List<String>): List<List<AnilistMatcher.Candidate>> {
        // Lo ya conocido y lo recién pedido salen de esta llamada y no del mapa: tras vaciarlo por tamaño, los títulos de la petición siguen respondiendo.
        val known = titles.filter { it in searched }.associateWith { searched.getValue(it) }
        val missing = titles.filter { it !in known }.distinct()
        val fresh = HashMap<String, List<AnilistMatcher.Candidate>>()
        if (missing.isNotEmpty()) {
            val found = api.search(missing)
            missing.forEachIndexed { i, title -> fresh[title] = found.getOrElse(i) { emptyList() } }
            if (searched.size + missing.size > MAX_CACHED) searched.clear()
            searched.putAll(known)
            searched.putAll(fresh)
        }
        return titles.map { fresh[it] ?: known[it].orEmpty() }
    }

    /** Posición del primer resultado de la serie [index] en la lista plana de búsquedas. */
    private fun offset(titles: List<List<String>>, index: Int) = titles.take(index).sumOf { it.size }

    private suspend fun downloadFichas(report: SyncReport): SyncReport {
        val fresh = fichas.freshIds(AnilistClient.FICHA_VERSION, now() - FICHA_TTL_MS).toSet()
        val wanted = fuentes.appliedIds().filter { it !in fresh }
        var downloaded = 0
        for (ids in wanted.chunked(FICHAS_PER_REQUEST)) {
            if (!canRun()) return report.copy(fichasDownloaded = downloaded, stopped = "pausa")
            val raws = api.fichas(ids)
            // El texto de Animeoteca se conserva al renovar la ficha de AniList.
            fichas.put(raws.map { raw -> raw.toFicha(now(), fichas.get(raw.id)?.es) })
            downloaded += raws.size
            onProgress(report.copy(fichasDownloaded = downloaded))
        }
        return report.copy(fichasDownloaded = downloaded)
    }

    /** Mira en Animeoteca cada ficha descargada que aún no tiene su texto en español. Lo que no tiene se guarda como «sin datos». */
    private suspend fun downloadSpanish(report: SyncReport): SyncReport {
        val api = animeoteca ?: return report
        var done = 0
        var failures = 0
        val important = priority()
        // Las que Animeoteca no tenía hace más de un mes se vuelven a pedir: puede que ya las tenga.
        val wanted = fichas.idsWithoutEs() + fichas.idsToRetryEs(now() - ES_RETRY_MS)
        for (id in wanted.sortedByDescending { important[it] ?: 0 }) {
            if (!canRun()) return report.copy(esDownloaded = done, stopped = "pausa")
            val text = try {
                api.text(id)
            } catch (e: AnilistUnavailable) {
                // Una ficha que siempre falla no debe detener a las demás; varias seguidas sí es una caída de Animeoteca: se para.
                if (++failures >= MAX_FAILURES_IN_A_ROW) return report.copy(esDownloaded = done, stopped = e.message ?: "Animeoteca no responde")
                continue
            }
            failures = 0
            fichas.putEs(id, text, now())
            done++
            onProgress(report.copy(esDownloaded = done))
        }
        return report.copy(esDownloaded = done)
    }

    private fun SyncReport.plus(other: SyncReport) = SyncReport(
        series + other.series, applied + other.applied, pending + other.pending, none + other.none, fichasDownloaded + other.fichasDownloaded, stopped, total, esDownloaded
    )

    private fun SyncReport.copy(fichasDownloaded: Int = this.fichasDownloaded, stopped: String? = this.stopped, total: Int = this.total, esDownloaded: Int = this.esDownloaded) =
        SyncReport(series, applied, pending, none, fichasDownloaded, stopped, total, esDownloaded)

    companion object {
        /** 5 series (hasta 10 títulos) por petición de búsqueda. */
        const val SERIES_PER_BATCH = 5
        const val FICHAS_PER_REQUEST = 10
        private const val MAX_CACHED = 4000
        private const val MAX_ALTERNATIVES = 2
        private const val MAX_FAILURES_IN_A_ROW = 5
        const val RETRY_AFTER_MS = 30L * 24 * 60 * 60 * 1000
        const val ES_RETRY_MS = 30L * 24 * 60 * 60 * 1000
        const val FICHA_TTL_MS = 14L * 24 * 60 * 60 * 1000
    }
}
