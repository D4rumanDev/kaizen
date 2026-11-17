package app.kaizen.source

import app.kaizen.commons.AppScope
import app.kaizen.commons.DebugLog
import app.kaizen.commons.KaizenProgress
import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.Network
import app.kaizen.commons.logError
import app.kaizen.database.CatalogDB
import app.kaizen.database.UserDB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pone en marcha [AnilistSync] dentro de la app: solo si «Emparejar con AniList» está activado en Configuración › Catálogo, con red y
 * mientras no se reproduce nada. Al acabar duerme [IDLE_MS] y mira si hay algo nuevo (series nuevas, decisiones de hace más de 30 días,
 * fichas caducadas). Si AniList no responde, espera [RETRY_MS] y sigue donde lo dejó.
 */
internal object AnilistSyncRunner {
    private const val ANILIST_EVIDENCE_VERSION = 1
    private const val ANILIST_FORMAT_SEARCH_VERSION = 1
    private const val IDLE_MS = 12L * 60 * 60 * 1000
    private const val RETRY_MS = 30L * 60 * 1000
    private const val PAUSE_MS = 30_000L
    /** Cada cuánto, como mucho, se revisan los enlaces que no se parecen a su ficha cuando no hay ninguna migración pendiente (el emparejado se reinicia varias veces al día con el relleno). */
    private const val CLEAN_EVERY_MS = 6L * 60 * 60 * 1000
    private const val LOG_EVERY = 50
    private var lastLogged = 0

    private val players = java.util.concurrent.atomic.AtomicInteger(0)

    /** Hay un reproductor abierto. Un contador y no una bandera: al pasar al capítulo siguiente el reproductor nuevo arranca antes de que pare el viejo. */
    val playing: Boolean get() = players.get() > 0

    fun playerStarted() { players.incrementAndGet() }
    fun playerStopped() { players.updateAndGet { maxOf(0, it - 1) } }
    @Volatile private var job: Job? = null

    private val _status = MutableStateFlow("")

    /** El resumen para Configuración › Catálogo: series revisadas, fichas enlazadas, por revisar, sin resultado y descargadas. Solo lo cambia [refreshStatus]. */
    val status: StateFlow<String> = _status.asStateFlow()

    private val _activity = MutableStateFlow("")

    /** Lo que hace ahora el trabajo («Emparejando… 120 de 800», «En pausa: reproduciendo»); vacío si no hace nada. Va aparte del resumen para no taparlo. */
    val activity: StateFlow<String> = _activity.asStateFlow()

    private val _conflicts = MutableStateFlow("")

    /** Qué uniones saldrían de lo emparejado (solo se informa; en esta etapa no se une nada): series con fichas de anime distinto y anime repartido en varias series. */
    val conflicts: StateFlow<String> = _conflicts.asStateFlow()

    /** Lo que llama la casilla de Configuración › Catálogo: guarda el ajuste, lo deja en el registro y enciende o apaga el trabajo. */
    fun setEnabled(on: Boolean) {
        KaizenSettings.anilistSync = on
        DebugLog.info("AniList", "Emparejar con AniList: ${if (on) "activado" else "apagado"}")
        apply()
    }

    /** Enciende o apaga el trabajo según el ajuste. Llamar al arrancar (con el libro de series ya cargado) y al cambiar el ajuste. */
    @Synchronized
    fun apply() {
        AppScope.launch(Dispatchers.IO) { refreshStatus() }
        if (!KaizenSettings.anilistSync) { job?.cancel(); job = null; fillJob?.cancel(); fillJob = null; _activity.value = ""; return }
        applyFill()
        if (job?.isActive == true) return
        restartSync()
    }

    /** Un solo emparejado vivo: cancela el que haya y lanza otro. Lo usan [apply] y el relleno de fichas (cuando hay fichas nuevas que revisar). */
    @Synchronized
    private fun restartSync() {
        job?.cancel()
        job = startSync()
    }

    private fun startSync(): Job {
        return AppScope.launch(app.kaizen.commons.BackgroundAnilist) {
            // Instalación limpia: los enlaces de partida del repositorio de fuentes (firmados) evitan buscar cada ficha en AniList. Se marcan los criterios al día para que la migración no los borre.
            if (app.kaizen.source.spec.RepoConfig.enabled && Network.isConnected) {
                val seeded = runCatching { AnilistSeed.seedIfEmpty(UserDB.INSTANCE.animeFuenteDao(), AnilistMatcher.VERSION, System.currentTimeMillis(), fetch = AnilistSeed::download) }
                    .onFailure { logError(it) }.getOrDefault(0)
                if (seeded > 0) {
                    KaizenSettings.anilistMatcher = AnilistMatcher.VERSION
                    KaizenSettings.anilistEvidence = ANILIST_EVIDENCE_VERSION
                    KaizenSettings.anilistFormatSearch = ANILIST_FORMAT_SEARCH_VERSION
                    DebugLog.info("AniList", "Instalación limpia: $seeded enlaces de partida del repositorio de fuentes")
                }
            }
            val migrating = KaizenSettings.anilistMatcher < AnilistMatcher.VERSION || KaizenSettings.anilistEvidence < ANILIST_EVIDENCE_VERSION || KaizenSettings.anilistFormatSearch < ANILIST_FORMAT_SEARCH_VERSION
            // Con un criterio de emparejado nuevo, lo que guardó el automático se vuelve a mirar entero (lo del usuario no se toca).
            if (KaizenSettings.anilistMatcher < AnilistMatcher.VERSION) {
                runCatching { UserDB.INSTANCE.animeFuenteDao().expireAuto() }.onFailure { logError(it) }.onSuccess {
                    KaizenSettings.anilistMatcher = AnilistMatcher.VERSION
                    DebugLog.info("AniList", "Criterio de emparejado v${AnilistMatcher.VERSION}: se vuelve a emparejar todo lo automático")
                }
            }
            if (migrating || System.currentTimeMillis() - KaizenSettings.anilistLastClean >= CLEAN_EVERY_MS) { DebugLog.timed("Arranque", "limpieza de enlaces del emparejado") { cleanInconsistent() }; KaizenSettings.anilistLastClean = System.currentTimeMillis() }
            loop()
        }
    }

    /** Lo que llama la casilla de Configuración › Catálogo para completar fichas de las webs en segundo plano: guarda el ajuste y enciende o apaga el trabajo. */
    fun setFillEnabled(on: Boolean) {
        KaizenSettings.fillFichas = on
        DebugLog.info("AniList", "Completar fichas de las webs en segundo plano: ${if (on) "activado" else "apagado"}")
        apply()
    }

    @Volatile private var fillJob: Job? = null
    private val passes = java.util.concurrent.atomic.AtomicInteger(0)
    private val passing: Boolean get() = passes.get() > 0

    /**
     * Completa, a ritmo lento y solo con el emparejado encendido, las fichas de las webs que no están enlazadas con AniList ([FichaFill]): una tanda pequeña cada pocos minutos, con
     * tope diario, sin reproducir y con red. Cada ficha nueva trae datos más recientes que la decisión que había: si el emparejado está parado, se despierta para revisarlas.
     */
    private fun applyFill() {
        if (!KaizenSettings.fillFichas) { fillJob?.cancel(); fillJob = null; return }
        if (fillJob?.isActive == true) return
        fillJob = AppScope.launch(Dispatchers.IO) {
            while (KaizenSettings.fillFichas && KaizenSettings.anilistSync) {
                delay(FichaFill.MIN_INTERVAL_MS)
                if (playing || !Network.isConnected) continue
                val now = System.currentTimeMillis()
                val n = FichaFill.allowance(now, KaizenSettings.fillLastAt, KaizenSettings.fillDay, KaizenSettings.fillCount, KaizenSettings.fillBatch, KaizenSettings.fillDaily)
                if (n <= 0) continue
                val report = fillBatch(n, now) ?: continue
                val tried = report.tried
                if (tried > 0) DebugLog.info("AniList", "Fichas de las webs completadas en segundo plano: ${report.total} de $tried ($report)")
                // Con fichas nuevas, el emparejado (dormido hasta 12 h) las revisa ya, salvo que esté en plena pasada.
                if (report.total > 0 && !passing && KaizenSettings.anilistSync) restartSync()
            }
        }
    }

    /**
     * Una tanda de fichas: elige hasta [n], cuenta lo previsto antes de abrir nada (si la app muere a mitad, esa tanda no queda sin contar) y las abre.
     * null si otra tanda está en marcha, si falló al elegir (se anota y se sigue en la próxima tanda: el trabajo no muere) o al abrir.
     */
    private suspend fun fillBatch(n: Int, now: Long): FichaFill.Report? {
        if (!FichaFill.tryLock()) return null
        try {
            val todo = runCatching { FichaFill.planNow(n) }.onFailure(::logUnlessCancelled).getOrNull() ?: return null
            KaizenSettings.fillCount = (if (KaizenSettings.fillDay == FichaFill.dayOf(now)) KaizenSettings.fillCount else 0) + todo.size
            KaizenSettings.fillDay = FichaFill.dayOf(now)
            KaizenSettings.fillLastAt = now
            val report = runCatching { FichaFill.runNow(todo) { !playing && KaizenSettings.fillFichas && Network.isConnected } }
                .onFailure(::logUnlessCancelled).getOrNull() ?: return null
            // El tope diario cuenta lo abierto de verdad: lo previsto que no se llegó a intentar (tanda parada, fuente dejada) se devuelve.
            val unused = todo.size - report.tried
            if (unused > 0) KaizenSettings.fillCount = (KaizenSettings.fillCount - unused).coerceAtLeast(0)
            return report
        } finally {
            FichaFill.unlock()
        }
    }

    private fun logUnlessCancelled(e: Throwable) { if (e !is kotlinx.coroutines.CancellationException) logError(e) }

    private var lastLine: String? = null

    /** Quita los enlaces automáticos que no se parecen a su ficha (de una serie mezclada o un emparejado antiguo): el emparejado los rehace. */
    private suspend fun cleanInconsistent() {
        runCatching {
            val dao = UserDB.INSTANCE.animeFuenteDao()
            val titles = CatalogDB.INSTANCE.animeFichaDao().titles().associateBy { it.anilistId }
            val all = dao.all()
            // Además de los enlaces que no valen, una vez cada migración: los «probable» aplicados solo por el nombre (criterio por pruebas) y las «sin resultado» de película, OVA o
            // especial (búsqueda por formato) se vuelven a mirar; los que tienen pruebas o entrada propia se aplican de nuevo solos.
            val markEvidence = KaizenSettings.anilistEvidence < ANILIST_EVIDENCE_VERSION
            val markFormat = KaizenSettings.anilistFormatSearch < ANILIST_FORMAT_SEARCH_VERSION
            val redo = AnilistLinkCheck.redo(all, titles, CatalogDB.INSTANCE.animeFichaDao()::get, markEvidence, markFormat) { f, s -> CatalogDB.INSTANCE.webFichaDao().get(f, s)?.malId }
            val bad = redo.rows
            if (markEvidence) DebugLog.info("AniList", "Criterio por pruebas: ${redo.weak} enlaces «probable» aplicados solo por el nombre se vuelven a mirar")
            if (markFormat) DebugLog.info("AniList", "Búsqueda por formato: ${redo.format} fichas de película, OVA o especial sin resultado se vuelven a buscar")
            bad.forEach { dao.deleteAuto(it.fuente, it.slug) }
            // Las uniones hechas con esos enlaces se deshacen ya, no en el próximo recorrido.
            if (bad.isNotEmpty()) runCatching { DebugLog.timed("Arranque", "reagrupar tras quitar ${bad.size} enlaces") { CatalogBuilder.regroup() } }.onFailure { logError(it) }
            // Las marcas se ponen al final: si el proceso muere antes de borrar, se revisan en el próximo arranque.
            if (markEvidence) KaizenSettings.anilistEvidence = ANILIST_EVIDENCE_VERSION
            if (markFormat) KaizenSettings.anilistFormatSearch = ANILIST_FORMAT_SEARCH_VERSION
            if (bad.isNotEmpty()) DebugLog.info("AniList", "Enlaces que no se parecen a su ficha, quitados para rehacerlos: ${bad.size} (${bad.take(5).joinToString { "${it.slug}→${it.anilistId}" }})")
        }.onFailure { logError(it) }
    }

    private suspend fun loop() {
        while (KaizenSettings.anilistSync) {
            lastLogged = 0
            passes.incrementAndGet()
            val report = try { runCatching { kotlinx.coroutines.withContext(app.kaizen.commons.RequestCounter.Background) { sync().runOnce() } }.onFailure { if (it !is kotlinx.coroutines.CancellationException) logError(it) }.getOrNull() } finally { passes.decrementAndGet() }
            refreshStatus()
            _activity.value = if (report?.stopped == "pausa" && KaizenSettings.anilistSync)
                if (playing) "En pausa: reproduciendo" else if (!Network.isConnected) "En pausa: sin red" else "En pausa"
            else ""
            // Una pausa que dura (reproduciendo, sin red) no repite la misma línea cada medio minuto.
            val line = report?.describe()
            if (line != null && !(report.stopped == "pausa" && line == lastLine)) DebugLog.info("AniList", line)
            lastLine = line
            // Los enlaces nuevos pueden unir series de distintas fuentes: el catálogo se reagrupa con ellos.
            if (report != null && report.applied > 0) runCatching { CatalogBuilder.regroup() }.onFailure { logError(it) }
            delay(
                when (report?.stopped) {
                    null -> if (report == null) RETRY_MS else IDLE_MS
                    "pausa" -> PAUSE_MS
                    else -> RETRY_MS
                }
            )
        }
    }

    private fun sync() = AnilistSync(
        api = AnilistClient(AnilistHttp::post),
        fuentes = UserDB.INSTANCE.animeFuenteDao(),
        fichas = CatalogDB.INSTANCE.animeFichaDao(),
        work = { work().perFicha() },
        hints = { WebHints.load(CatalogDB.INSTANCE.webFichaDao()) },
        canRun = { !playing && KaizenSettings.anilistSync && app.kaizen.commons.Network.isConnected },
        onProgress = { report ->
            // Una línea en el registro cada ~50 series: una pasada entera dura horas y hasta ahora no decía nada hasta el final.
            if (report.series / LOG_EVERY != lastLogged) {
                lastLogged = report.series / LOG_EVERY
                DebugLog.info("AniList", "Avance: ${report.series} de ${report.total} fichas (${report.applied} enlazadas, ${report.pending} por revisar, ${report.none} sin resultado)")
            }
            _activity.value = if (report.series > 0 || report.total > 0) "Emparejando… ${report.series} de ${report.total} fichas en esta pasada" +
                (if (report.fichasDownloaded > 0) " · ${report.fichasDownloaded} fichas descargadas" else "")
            else "Descargando fichas… ${report.fichasDownloaded}"
        }
    )

    private val requested = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /**
     * El texto en español de Animeoteca de un anime, pedido al abrir su ficha y guardado en la base (Animeoteca tarda unos 10 s por página:
     * no se recorre entera, solo lo que se abre). true si ahora está guardado y la ficha puede releerlo. Una petición por anime y sesión;
     * sin red o con un fallo no se da por pedido y se intenta al abrir otra vez.
     */
    suspend fun fetchSpanish(anilistId: Long): Boolean = withContext(Dispatchers.IO) {
        if (!KaizenSettings.anilistFicha || !requested.add(anilistId)) return@withContext false
        runCatching {
            val dao = CatalogDB.INSTANCE.animeFichaDao()
            when {
                dao.get(anilistId) == null -> { requested.remove(anilistId); false }
                dao.get(anilistId)?.es != null -> true
                !Network.isConnected -> { requested.remove(anilistId); false }
                else -> { dao.putEs(anilistId, AnimeotecaHttp.text(anilistId), System.currentTimeMillis()); true }
            }
        }.onFailure { requested.remove(anilistId) }.getOrDefault(false)
    }

    /** Las series de hoy con sus fichas; las de favoritos, las vistas y las que tienen progreso van primero. */
    internal fun work(): List<SeriesWork> {
        val db = UserDB.INSTANCE
        val favorites = db.favoriteAV1DAO().allRaw.map { it.seriesId }.toSet()
        val seen = db.recordAV1DAO().seriesIds().toSet() + KaizenProgress.exportAll().keys.mapNotNull { KaizenProgress.Key.parse(it)?.seriesId }
        // El año de estreno que da cada fuente a su ficha: es evidencia para el emparejado (el de las fichas frente al del anime).
        val years = catalogDao.allLinks().associate { (it.sourceId to it.slug) to it.year }
        return SeriesLedger.seriesFichas().map { (id, fichas) ->
            SeriesWork(id, fichas, if (id in favorites) 2 else if (id in seen) 1 else 0, fichas.map { years[it] })
        }
    }

    suspend fun refreshStatus() = withContext(Dispatchers.IO) {
        runCatching {
            val counts = UserDB.INSTANCE.animeFuenteDao().countByState().associate { it.estado to it.total }
            val total = counts.values.sum()
            // Fichas (no series): el emparejado va ficha a ficha. Las uniones hechas y las que solo se podrían hacer van separadas (ver [AnilistStats]).
            val catalogEmpty = runCatching { catalogDao.count() == 0 }.getOrDefault(false)
            val rows = UserDB.INSTANCE.animeFuenteDao().all()
            val stats = AnilistStats.of(rows, SeriesLedger.seriesFichas(), catalogEmpty, AnilistLinkCheck.doubtful(rows))
            _status.value = if (total == 0) "Sin empezar · fichas revisadas por AniList: 0 de ${stats.fichas}" else
                "Fichas revisadas por AniList: ${stats.revisadas} de ${stats.fichas} · " +
                "Fichas enlazadas: ${counts[AnimeFuente.APLICADO] ?: 0} · Por revisar: ${counts[AnimeFuente.PENDIENTE] ?: 0} · Sin resultado: ${counts[AnimeFuente.SIN_RESULTADO] ?: 0} · " +
                    "Fichas de AniList descargadas: ${CatalogDB.INSTANCE.animeFichaDao().count()} · Textos de Animeoteca: ${CatalogDB.INSTANCE.animeFichaDao().countWithEs()}"
            _conflicts.value = stats.conflictsText()
        }
    }

    private fun SyncReport.describe() =
        "Pasada: $series fichas (${applied} enlazadas, $pending por revisar, $none sin resultado), $fichasDownloaded fichas descargadas, $esDownloaded textos de Animeoteca" +
            (stopped?.let { ". Parada: $it" } ?: ". Terminada")
}
