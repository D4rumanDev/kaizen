package app.kaizen.source

import androidx.core.content.edit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import app.kaizen.commons.AppScope
import app.kaizen.commons.DebugLog
import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.Network
import app.kaizen.commons.logError
import app.kaizen.database.CacheDB
import app.kaizen.source.spec.RepoConfig
import app.kaizen.commons.runCatchingCoop
import app.kaizen.pojos.av1.RecentEpisode
import app.kaizen.pojos.av1.Series
import app.kaizen.pojos.av1.SeriesSummary
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes

/**
 * Catálogo interno, lado de construcción: lo genera recorriendo cada fuente, como mucho una vez cada 14 días o cuando
 * se activa una nueva. La fecha es por fuente: si una está caída, el catálogo sirve con las que respondieron, se
 * conserva lo que había de la caída y se reintenta pasado un rato. Si una fuente anuncia cuántas series tiene y
 * el total no ha cambiado, no se recorren sus páginas ([SourceState]). Lo que se genera lo lee [Catalog].
 */
object CatalogBuilder {
    private val core by lazy {
        CatalogBuildCore(
            dao = catalogDao,
            ids = CacheDB.INSTANCE.seriesIdDao(),
            states = CatalogStates,
            inTransaction = { CacheDB.INSTANCE.runInTransaction(it) },
            idsTransaction = { app.kaizen.database.UserDB.INSTANCE.runInTransaction(it) },
            ensureIds = SeriesLedger::ensureLoaded,
            userRows = app.kaizen.database.UserDB.INSTANCE.userRowsDao(),
            enabled = { AnimeSources.enabled },
            all = { AnimeSources.all },
            scope = AppScope,
            retry = { ensureFresh() },
            followMoves = SeriesLedger::followMoves,
            afterWrite = SeriesLedger::load,
            anilistLinks = ::appliedAnilistLinks,
            anilistStars = AnilistScores::byAnilistId,
            snapshots = CatalogSnapshotFile,
            saveSeedLinks = { app.kaizen.database.UserDB.INSTANCE.animeFuenteDao().insertIfAbsent(it) }
        )
    }

    /** El anime de AniList enlazado a cada ficha (enlaces aplicados): dos fichas del mismo anime son la misma serie. */
    private fun appliedAnilistLinks(): Map<Pair<String, String>, Long> =
        app.kaizen.database.UserDB.INSTANCE.animeFuenteDao().all().let { rows ->
            // Un enlace cuyo anime no se parece a la ficha (serie mezclada, emparejado antiguo) no une series.
            val bad = AnilistLinkCheck.doubtful(rows)
            rows.filter { it.estado == AnimeFuente.APLICADO && (it.fuente to it.slug) !in bad }
                .mapNotNull { row -> row.anilistId?.let { (row.fuente to row.slug) to it } }.toMap()
        }

    /**
     * Con «Recorrido automático» desactivado en Configuración › Catálogo no se recorre ninguna fuente por iniciativa propia
     * (cientos de peticiones). Solo el botón «Recorrer ahora» de esa pantalla lo pide igualmente, con `ensureFresh(force = true)`.
     */
    fun wanted(): Boolean = KaizenSettings.catalogAuto

    /** Si hay un recorrido en marcha (automático o pedido a mano). */
    val isWalking: Boolean get() = core.isRunning

    /** Borra el catálogo generado y cuándo se recorrió cada fuente: el siguiente recorrido lo rehace. */
    fun clear() = core.clear()

    /** Guarda la fotografía del catálogo si toca (una a la semana): la usa [ensureFresh] si la base se vacía. */
    suspend fun saveSnapshotIfDue(): Unit = withContext(Dispatchers.IO) { core.saveSnapshotIfDue() }

    /**
     * Vuelve a agrupar lo guardado con las reglas del usuario de ahora (uniones, separaciones), sin pedir nada a las fuentes ni
     * vaciar el catálogo: se puede seguir buscando y uniendo series sin esperar a que se recorra todo otra vez.
     */
    suspend fun regroup(): Unit = withContext(app.kaizen.commons.BackgroundCpu) {
        // Varias peticiones seguidas (unir o separar varias series) se reducen a una pasada más: cada una solo marca que hay
        // reglas nuevas, y quien tiene el cerrojo repite mientras haya marca.
        regroupDirty.set(true)
        regroupLock.withLock {
            while (regroupDirty.getAndSet(false)) {
                // La firma es la de antes de la pasada: si las reglas cambian mientras tanto, la siguiente pasada la renueva.
                val signature = currentRegroupSignature()
                core.regroup()
                regroupPrefs.edit { putString(REGROUP_KEY, signature) }
            }
        }
    }

    /**
     * Reagrupa (en el hilo de fondo: son decenas de segundos de CPU y no deben frenar la pantalla) solo si las reglas del usuario o la instalación de la app cambiaron desde el último reagrupado. Es lo que se llama al
     * arrancar: reescribir el catálogo entero cuesta unos 8 s de CPU y disco en el tvbox, y sin cambios sale igual.
     */
    suspend fun regroupIfStale(): Unit = withContext(app.kaizen.commons.BackgroundCpu) {
        if (currentRegroupSignature() != regroupPrefs.getString(REGROUP_KEY, null)) regroup()
    }

    private fun currentRegroupSignature() = regroupSignature(REGROUP_LOGIC_VERSION, UserTitles.rawText())

    /**
     * Versión de la lógica de agrupar: súbela cuando cambie cómo se agrupan las series (el catálogo se reagrupa una vez al primer arranque). Antes la firma llevaba la fecha de
     * instalación y cada actualización de la app reescribía el catálogo entero (de 35 a 60 s de CPU y memoria en el tvbox, con «Últimos vistos» en esqueleto todo ese rato).
     */
    private const val REGROUP_LOGIC_VERSION = 1L

    private val regroupPrefs by lazy { app.kaizen.commons.KaizenStore.prefs("kaizen_regroup") }
    private const val REGROUP_KEY = "signature"

    private val regroupDirty = AtomicBoolean(false)
    private val regroupLock = Mutex()

    /**
     * Da de alta en el catálogo las series que aparecen en «recientes» y aún no están (no hace peticiones: los recientes ya se han
     * descargado). Sin catálogo no hace nada; el recorrido completo rellena después lo que a esas fichas les falte.
     */
    suspend fun addFromRecents(recents: List<RecentEpisode>): Unit = withContext(Dispatchers.IO) {
        // Con el recorrido automático desactivado el catálogo solo cambia a mano.
        if (!wanted()) return@withContext
        val sources = AnimeSources.all.associateBy { it.id }
        val found = recents.mapNotNull { recent ->
            sources[recent.source]?.let { it to SeriesSummary(recent.aid, recent.name, recent.slug, category = 1, source = recent.source) }
        }
        core.addNew(found)
    }

    /**
     * Primero el catálogo de `kaizen-sources` y después las webs: con el catálogo vacío se escribe entero (sin recorrer nada); con catálogo propio, una vez al día, se compara y
     * se añade lo que le falta al local. Solo se aplica uno **más nuevo** que el último aplicado ([KaizenSettings.catalogSeedAt]): un fichero antiguo no hace retroceder.
     */
    private suspend fun syncWithRepository() {
        val empty = catalogDao.count() == 0
        val now = System.currentTimeMillis()
        if (!empty && now - KaizenSettings.catalogSeedCheckedAt < 1.days.inWholeMilliseconds) return
        if (!empty) KaizenSettings.catalogSeedCheckedAt = now
        val data = CatalogSeed.download(RepoConfig.baseUrl + CatalogSeed.FILE)
        val signature = CatalogSeed.download(RepoConfig.baseUrl + CatalogSeed.SIGNATURE)
        val seed = CatalogSeed.read(data, signature, RepoConfig.publicKey, AnimeSources.all.map { it.id }.toSet()) ?: return
        if (seed.createdAt <= KaizenSettings.catalogSeedAt) return
        val done = if (empty) core.seedIfEmpty(seed.entries) > 0 else core.mergeSeed(seed.entries) >= 0
        if (done) KaizenSettings.catalogSeedAt = seed.createdAt
    }

    /** Completa en el catálogo la ficha recién abierta con su año (ver [CatalogBuildCore.noteFicha]). La nota de la fuente se descarta: solo vale la de AniList. */
    suspend fun noteFicha(series: Series): Unit = withContext(Dispatchers.IO) { core.noteFicha(series.source, series.slug, series.year) }

    /** Pone en cada serie la nota de AniList que toca (tras descargar fichas nuevas, o la primera vez que se dejan las notas de las fuentes). */
    suspend fun rescore(): Unit = withContext(Dispatchers.IO) { core.rescore() }

    /** Una sola vez: quita del catálogo ya guardado las notas de las webs y pone las de AniList (las fichas que aún no estén descargadas quedan sin nota hasta que lo estén). */
    suspend fun rescoreIfNeeded() {
        if (KaizenSettings.catalogScores >= SCORES_VERSION) return
        rescore()
        KaizenSettings.catalogScores = SCORES_VERSION
    }

    private const val SCORES_VERSION = 1

    /**
     * Recorre las fuentes activas que lo necesitan (todas si no hay datos). Sin red no hace nada. Con el recorrido automático
     * desactivado no se recorre nada, salvo con [force]: solo lo usa el botón «Recorrer ahora» de Configuración › Catálogo.
     */
    suspend fun ensureFresh(force: Boolean = false): Unit = withContext(Dispatchers.IO) {
        // Con la base vacía se rehace desde la fotografía, que no necesita red ni «Recorrido automático».
        runCatchingCoop { core.restoreSnapshotIfEmpty() }.onFailure(::logError)
        // Instalación limpia (sin catálogo ni fotografía): el catálogo ya hecho del repositorio de fuentes, firmado, en lugar de recorrer cada web.
        if (RepoConfig.enabled && Network.isConnected && !core.isRunning) runCatchingCoop { syncWithRepository() }.onFailure { DebugLog.info("Catálogo", "Catálogo de GitHub no disponible (${it.message}); se recorren las webs") }
        if (!Network.isConnected || (!force && !wanted())) return@withContext
        // El recorrido del catálogo cuenta aparte en el recuento de peticiones (RequestCounter): son cientos de páginas.
        withContext(app.kaizen.commons.RequestCounter.Background) { core.ensureFresh() }
    }
}

/** Lo que determina cómo queda agrupado el catálogo: la instalación de la app (su lógica de agrupar) y las reglas del usuario. */
internal fun regroupSignature(installTime: Long, rulesText: String?): String =
    rulesText.orEmpty().let { "$installTime:${it.length}:${it.hashCode()}" }

/**
 * Lógica del recorrido, sin Android a la vista: la base de datos, los estados, las fuentes y el reloj llegan por el
 * constructor para poder probarla. [retry] es lo que se vuelve a lanzar pasado un rato si una fuente cayó.
 */
internal class CatalogBuildCore(
    private val dao: CatalogDao,
    private val ids: SeriesIdDao,
    private val states: CatalogStateStore,
    private val inTransaction: (() -> Unit) -> Unit,
    /** Transacción de la base donde vive el libro de ids (`user-db`); en las pruebas, la misma. */
    private val idsTransaction: (() -> Unit) -> Unit = inTransaction,
    /**
     * Antes de repartir o restaurar ids: carga el libro si no está cargado y falla si no puede. Con el libro vacío por no haber copiado
     * aún el antiguo, repartir ids empezaría en 1 y chocaría con los que las reglas del usuario ya nombran.
     */
    private val ensureIds: () -> Unit = {},
    /** Lleva favoritos y vistos al id de serie vigente de su ficha, en la misma transacción que cambia el libro. */
    private val userRows: UserRows = NoUserRows,
    private val enabled: () -> List<AnimeSource>,
    private val all: () -> List<AnimeSource>,
    private val scope: CoroutineScope,
    private val retry: suspend () -> Unit,
    /** Pasa lo guardado por id de serie (progreso y reglas del usuario) de los ids absorbidos por una unión a los vigentes. */
    private val followMoves: (Map<Long, Long>) -> Unit = {},
    /** Se llama al terminar de escribir el catálogo: los ids han podido cambiar. */
    private val afterWrite: () -> Unit = {},
    /** Los enlaces con AniList de las fichas (fuente, slug) → anime; sin ellos las series de distintas fuentes no se unen por anime. */
    private val anilistLinks: () -> Map<Pair<String, String>, Long> = { emptyMap() },
    /** Las estrellas de AniList de cada anime con la ficha descargada: la única nota que guarda el catálogo (ver [AnilistScores]). */
    private val anilistStars: () -> Map<Long, Double> = { emptyMap() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val onError: (Throwable) -> Unit = ::logError,
    /** Dónde se guarda la fotografía del catálogo; null = sin fotografías. */
    private val snapshots: CatalogSnapshots? = null,
    /** Guarda los enlaces con AniList del catálogo descargado (sin pisar ninguno que ya exista): las reglas de unión los leen por [anilistLinks]. */
    private val saveSeedLinks: (List<AnimeFuente>) -> Unit = {}
) {
    companion object {
        val SNAPSHOT_EVERY = 7.days          // una fotografía a la semana
        val SNAPSHOT_KEEP_BIGGER = 60.days   // una fotografía mayor no se sustituye por una menor hasta pasados 60 días
        val PAGE_DELAY = 300.milliseconds                  // pausa entre páginas: no saturar al servidor
        val RETRY_PAGE = 2.seconds               // primera espera tras un fallo de página (se duplica)
        const val PAGE_ATTEMPTS = 6                     // intentos por página antes de dar la fuente por caída
        const val MAX_PAGES = 2000                      // tope por fuente (protege de listas que no terminan)
        val RETRY_DELAY = 10.minutes         // espera antes de reintentar una fuente caída
        const val MAX_PARALLEL_WALKS = 2                // fuentes que se recorren a la vez
        const val MAX_RETRIES = 3                       // reintentos seguidos en una sesión
    }

    /** Sube al vaciar el catálogo: un recorrido empezado antes descarta lo que traiga (si no, devolvería lo borrado). */
    @Volatile private var epoch = 0

    private val running = AtomicBoolean(false)

    /** Si hay un recorrido en marcha. */
    val isRunning: Boolean get() = running.get()
    private val retryScheduled = AtomicBoolean(false)
    private val retries = AtomicInteger(0)

    /** Lo que dio una fuente: sus series (null = sin cambios, se conserva lo guardado) y el total que anunciaba. */
    private class Walk(val items: List<SeriesSummary>?, val total: Int?)

    /**
     * Pide el total a la fuente (una petición) y solo recorre sus páginas si ha cambiado, toca el recorrido completo o
     * [force] (catálogo vacío).
     */
    private suspend fun walkIfChanged(source: AnimeSource, now: Long, force: Boolean): Walk {
        val total = runCatchingCoop { source.catalogTotal() }.getOrNull()
        if (!force && states[source.id].covers(total, now) && hasSeries(source.id)) return Walk(null, total)
        // Un recorrido sin series es un fallo (la web cambió o no respondió bien), no un catálogo vacío: se reintenta.
        val items = walk(source).also { check(it.isNotEmpty()) { "${source.id}: recorrido vacío" } }
        return Walk(items, total)
    }

    /**
     * Con la base vacía (sin catálogo ni ids de serie, como tras perderse la base de datos) rehace el catálogo, los ids y los estados
     * de recorrido desde la última fotografía, sin pedir nada a las fuentes. Sin fotografía válida, o con reglas de unión distintas,
     * no hace nada y el recorrido normal lo rehace entero. Solo actúa con las dos tablas vacías: no puede pisar nada que ya exista.
     */
    fun restoreSnapshotIfEmpty(): Boolean {
        val store = snapshots ?: return false
        if (dao.count() != 0 || ids.hasRegistry() || ids.allMap().isNotEmpty()) return false
        val snap = store.read()?.takeIf { it.usable(CatalogStateStore.RULES_VERSION) } ?: return false
        // Las fotografías hechas antes de quitar las redirecciones las traen aparte: cada ficha toma el id vigente de su serie.
        val moves = snap.redirects.orEmpty().associate { it.fromId to it.toId }
        val map = snap.map.orEmpty().map { SeriesMap(it.sourceId, it.slug, moves.resolve(it.seriesId)) }
        val registry = (map.map { it.seriesId } + moves.keys + moves.values + snap.entries.orEmpty().map { it.seriesId } +
            snap.links.orEmpty().map { it.seriesId }).filter { it > 0 }.distinct().map { SeriesRegistry(it) }
        // Los ids (libro, en `user-db`) primero y el catálogo después, cada uno en su transacción: si el catálogo falla, el libro ya está y
        // el recorrido normal lo rehace con los mismos ids; al revés quedarían series del catálogo con ids que el libro no conoce.
        // Se vuelve a comprobar dentro de cada transacción: leer la fotografía tarda, y en ese rato otro hilo pudo dar un id nuevo
        // (un favorito o un capítulo visto recién marcado) que chocaría con los de la fotografía. Se mira el registro y no solo el
        // mapa: un id nuevo se escribe en el registro y, en otra sentencia, en el mapa.
        ensureIds()
        if (!ids.restoreLedgerIfEmpty(registry, map)) return false
        followMoves(moves)
        var done = false
        inTransaction {
            if (dao.count() != 0) return@inTransaction
            dao.putEntries(snap.entries.orEmpty().map { it.toEntity() })
            dao.putLinks(snap.links.orEmpty().map { it.toEntity() })
            done = true
        }
        if (!done) return false
        // Los estados van con los datos que los respaldan: una fuente con estado «recorrida» y sin series no se recorrería.
        snap.states.orEmpty().forEach { (sourceId, raw) -> states[sourceId] = SourceState.decode(raw) }
        afterWrite()
        DebugLog.info("Catálogo", "Restaurado desde la fotografía: ${snap.entries.orEmpty().size} series, ${snap.links.orEmpty().size} fichas")
        return true
    }

    /**
     * Con la base vacía y sin fotografía propia, escribe el catálogo descargado del repositorio de fuentes ([CatalogSeed], ya verificado) sin pedir nada a las webs: cada serie
     * entra como sus fichas (fuente + slug), con su nombre, año, nota y votos, y las reglas de unión de esta instalación (y los ids locales) las agrupan como siempre. Las fuentes
     * que aporta quedan como recién comprobadas: el recorrido normal las revisa pasado el plazo de siempre (14 días). Solo entran fuentes de [all]; solo actúa con el catálogo
     * vacío. Devuelve las series que escribió (0 si no hizo nada).
     */
    @Synchronized
    fun seedIfEmpty(entries: List<CatalogSeed.Entry>): Int {
        if (running.get() || dao.count() != 0) return 0
        val items = seedItems(entries, all().associateBy { it.id })
        if (items.isEmpty()) return 0
        val now = clock()
        // Los enlaces con AniList primero: el reparto de series los lee para unir las fichas del mismo anime.
        saveSeedLinks(seedLinks(entries, all().map { it.id }.toSet(), now))
        writeCatalog(emptyList(), now, replaceAll = true, extra = items)
        // Solo las fuentes activas quedan como comprobadas: una desactivada que se active después se recorre entera, como siempre.
        val active = enabled().map { it.id }.toSet()
        items.map { it.first.id }.distinct().filter { it in active }.forEach { states[it] = SourceState(now, now, -1) }
        DebugLog.info("Catálogo", "Catálogo de partida del repositorio de fuentes: ${entries.size} series, ${items.size} fichas")
        return entries.size
    }

    /** Las fichas del catálogo descargado como las da una fuente (el `aid` 0 se resuelve al usarlas). Solo las de [sources]. */
    private fun seedItems(entries: List<CatalogSeed.Entry>, sources: Map<String, AnimeSource>): List<Pair<AnimeSource, SeriesSummary>> = entries.flatMap { entry ->
        entry.fichas.mapNotNull { (sourceId, slug) ->
            val source = sources[sourceId] ?: return@mapNotNull null
            source to SeriesSummary(0, entry.name, slug, category = 1, source = sourceId, score = entry.score, votes = entry.votes, year = entry.year)
        }
    }

    /** Los enlaces con AniList que trae el catálogo descargado, como automáticos del mismo anime («mismo-id»); solo de las fuentes [sources]. */
    private fun seedLinks(entries: List<CatalogSeed.Entry>, sources: Set<String>, now: Long): List<AnimeFuente> = entries.flatMap { entry ->
        val id = entry.anilistId ?: return@flatMap emptyList()
        entry.fichas.filter { it.first in sources }.map { (source, slug) -> AnimeFuente(source, slug, id, AnimeFuente.APLICADO, AnimeFuente.AUTO, "mismo-id", 1.0, now) }
    }

    /**
     * Con catálogo propio: lo compara con el descargado y añade las fichas de fuentes activas que este tiene y el local no (las demás no se tocan: lo local manda, y las webs lo
     * confirman después en su recorrido). Deja en el registro cuántas coincidían, cuántas eran nuevas y cuántas fichas locales no figuran en el descargado. Devuelve las nuevas (-1 si no pudo aplicarse: recorrido en marcha o catálogo vacío).
     */
    @Synchronized
    fun mergeSeed(entries: List<CatalogSeed.Entry>): Int {
        if (running.get() || dao.count() == 0) return -1
        val items = seedItems(entries, enabled().associateBy { it.id })
        val remote = items.map { (source, item) -> source.id to item.slug }.toSet()
        val local = dao.allLinks().map { it.sourceId to it.slug }.toSet()
        val fresh = items.filter { (source, item) -> (source.id to item.slug) !in local }.distinctBy { (source, item) -> source.id to item.slug }
        val activeIds = enabled().map { it.id }.toSet()
        // Los enlaces con AniList solo de las fichas nuevas: lo que el emparejado local aún no miró no se da por emparejado desde fuera.
        val freshKeys = fresh.map { (source, item) -> source.id to item.slug }.toSet()
        saveSeedLinks(seedLinks(entries, enabled().map { it.id }.toSet(), clock()).filter { (it.fuente to it.slug) in freshKeys })
        val onlyLocal = local.count { it.first in activeIds && it !in remote }
        DebugLog.info("Catálogo", "Catálogo de GitHub: ${remote.size} fichas de fuentes activas, ${remote.size - fresh.size} ya estaban, ${fresh.size} nuevas; $onlyLocal fichas locales que él no tiene")
        // Se reescribe todo, como al reagrupar: una ficha nueva puede ser otra ficha de una serie que ya está.
        if (fresh.isNotEmpty()) writeCatalog(emptyList(), clock(), replaceAll = true, extra = fresh)
        return fresh.size
    }

    /**
     * Guarda la fotografía si hace una semana de la anterior. No sustituye una fotografía bastante mayor y no muy antigua: un catálogo
     * recortado (fuentes inactivas, base recién vaciada) no debe pisar al bueno.
     */
    fun saveSnapshotIfDue() {
        val store = snapshots ?: return
        if (running.get() || dao.count() == 0) return
        val now = clock()
        val lastAt = store.createdAt()
        if (lastAt != null && now - lastAt < SNAPSHOT_EVERY.inWholeMilliseconds) return
        val old = store.read()
        val states = all().associate { it.id to this.states[it.id] }.filterValues { it != SourceState.NEVER }.mapValues { it.value.encode() }
        // Las lecturas del catálogo en una transacción: una escritura a la vez no deja una fotografía a medias. El libro está en otra base:
        // se lee después, así que no le falta ningún id de lo leído (los ids se dan antes de escribir el catálogo).
        var entries: List<CatalogEntry> = emptyList()
        var links: List<CatalogLink> = emptyList()
        inTransaction {
            entries = dao.allEntries()
            links = dao.allLinks()
        }
        val fresh = CatalogSnapshot(
            CatalogSnapshot.VERSION, CatalogStateStore.RULES_VERSION, now,
            entries.map { it.toSnap() }, links.map { it.toSnap() },
            ids.allMap().map { SnapMap(it.sourceId, it.slug, it.seriesId) }, null, states
        )
        if (old != null && fresh.entries.orEmpty().size < old.entries.orEmpty().size * 0.8 && now - old.createdAt < SNAPSHOT_KEEP_BIGGER.inWholeMilliseconds) return
        store.write(fresh)
        DebugLog.info("Catálogo", "Fotografía guardada: ${fresh.entries.orEmpty().size} series")
    }

    /** Si la base de datos tiene series de la fuente: el estado guardado (en preferencias) puede decir «recorrida» con la base vacía. */
    private fun hasSeries(sourceId: String): Boolean = dao.countsBySource().any { it.sourceId == sourceId && it.total > 0 }

    /** Fuentes activas sin recorrer, con el recorrido caducado o sin series en la base de datos. */
    private fun dueSources(): List<AnimeSource> {
        val now = clock()
        return enabled().filter { states[it.id].due(now) || !hasSeries(it.id) }
    }

    /** Borra el catálogo y cuándo se recorrió cada fuente; el próximo [ensureFresh] lo rehace entero. */
    @Synchronized
    fun clear() {
        // Con el cerrojo de la escritura: un reagrupado en marcha termina antes y no repuebla lo recién borrado.
        epoch++
        inTransaction {
            dao.clearLinks()
            dao.clearEntries()
        }
        states.clear()
        retries.set(0)
    }

    /** Recorre las fuentes activas que lo necesitan (todas si no hay datos). */
    suspend fun ensureFresh() {
        // Sin datos (aunque queden estados guardados) se recorre todo, sin fiarse del total.
        val empty = dao.count() == 0
        val targets = if (empty) enabled() else dueSources()
        if (targets.isNotEmpty()) rebuild(targets, force = empty)
    }

    /**
     * Recorre [targets] y rehace el catálogo. Las fuentes que no se recorren (frescas, o caídas con datos antiguos)
     * conservan lo que tenían: se reconstruyen desde la base de datos para unirlas con lo nuevo. Una fuente que
     * falla no marca su fecha y se reintenta pasado un rato.
     */
    private suspend fun rebuild(targets: List<AnimeSource>, force: Boolean) {
        if (!running.compareAndSet(false, true)) return
        try {
            val startedAt = clock()
            val startEpoch = epoch
            // Como mucho dos fuentes a la vez: cada recorrido son cientos de peticiones.
            val gate = Semaphore(MAX_PARALLEL_WALKS)
            val ok = coroutineScope {
                targets.map { source ->
                    async { gate.withPermit { runCatchingCoop { walkIfChanged(source, startedAt, force) }.onFailure(onError).getOrNull()?.let { source to it } } }
                }.awaitAll().filterNotNull()
            }
            // Se vació el catálogo mientras se recorría: lo traído es de antes del vaciado y se descarta.
            if (epoch != startEpoch) return
            if (ok.size < targets.size) scheduleRetry() else retries.set(0)
            val listed = ok.mapNotNull { (source, walk) -> walk.items?.let { source to it } }
            if (listed.isNotEmpty()) writeCatalog(listed, startedAt)
            // Una fuente sin cambios conserva la fecha de su recorrido completo (el estado sigue vigente: si no, se habría recorrido).
            ok.forEach { (source, walk) ->
                val walkedAt = if (walk.items != null) startedAt else states[source.id].walkedAt
                states[source.id] = SourceState(startedAt, walkedAt, walk.total ?: -1)
            }
        } finally {
            running.set(false)
        }
    }

    /** Agrupa de nuevo el catálogo guardado con las reglas actuales (ver [CatalogBuilder.regroup]). Sin catálogo no hace nada. */
    @Synchronized
    fun regroup() {
        // Todas las fichas se reescriben: las que cambian de serie (se unen, se separan) no deben dejar su enlace antiguo.
        if (dao.count() > 0) writeCatalog(emptyList(), clock(), replaceAll = true)
    }

    /**
     * Da de alta las fichas de [found] (lo que las fuentes enseñan en «recientes») que el catálogo aún no tiene, sin esperar al
     * siguiente recorrido. Entran solo con nombre: la nota, los votos y el año los trae el recorrido o, antes, la ficha al abrirla
     * ([noteFicha]). Sin catálogo, o con un recorrido en marcha, no hace nada: el recorrido ya las trae.
     */
    @Synchronized
    fun addNew(found: List<Pair<AnimeSource, SeriesSummary>>) {
        if (running.get() || dao.count() == 0) return
        val fresh = found.distinctBy { (source, item) -> source.id to item.slug }.filter { (source, item) -> dao.keyOf(source.id, item.slug) == null }
        // Se reescribe todo, como al reagrupar: la ficha nueva puede ser otra ficha de una serie que ya está (misma serie en otra fuente).
        if (fresh.isNotEmpty()) writeCatalog(emptyList(), clock(), replaceAll = true, extra = fresh)
    }

    /**
     * Completa la ficha ([sourceId], [slug]) del catálogo con el año que acaba de leer de su página, solo si la ficha no lo tenía (el del
     * listado manda). Si cambia, la serie recalcula su año. La nota no se toma de la página: solo vale la de AniList ([rescore]). Sin esa
     * ficha en el catálogo no hace nada.
     */
    @Synchronized
    fun noteFicha(sourceId: String, slug: String, year: Int?) {
        val key = dao.keyOf(sourceId, slug) ?: return
        val links = dao.links(key)
        val link = links.firstOrNull { it.sourceId == sourceId && it.slug == slug } ?: return
        val changed = link.copy(year = link.year ?: year)
        if (changed == link) return
        inTransaction {
            val entry = dao.entriesOf(listOf(key)).firstOrNull() ?: return@inTransaction
            dao.putLinks(listOf(changed))
            val group = (links - link + changed).map { it.asSummary(entry.name) }
            dao.putEntries(listOf(SeriesResolver.entryOf(key, group, entry.updatedAt).copy(seriesId = entry.seriesId)))
        }
    }

    /**
     * Vuelve a poner en cada ficha y en cada serie la nota de AniList que le toca (la de las webs se descarta): se llama al descargar fichas nuevas y una vez al
     * dejar de usar las notas de las fuentes. Solo toca la nota y los votos; no reagrupa nada. Sin enlace con AniList, o con la ficha sin descargar, queda sin nota.
     */
    @Synchronized
    fun rescore() {
        // Si no se pueden leer las notas o los enlaces, el fallo sube: con mapas vacíos se borrarían todas las notas del catálogo y la versión quedaría marcada como hecha.
        val starsById = runCatchingCoop { anilistStars() }.onFailure(onError).getOrThrow()
        val linked = runCatchingCoop { anilistLinks() }.onFailure(onError).getOrThrow()
        val byKey = dao.allLinks().groupBy { it.key }
        val newLinks = ArrayList<CatalogLink>()
        val newEntries = ArrayList<CatalogEntry>()
        for (entry in dao.allEntries()) {
            val links = byKey[entry.key].orEmpty()
            val scored = links.map { link ->
                val stars = linked[link.sourceId to link.slug]?.let(starsById::get)
                link.copy(score = stars, votes = if (stars != null) 1 else 0)
            }
            scored.forEachIndexed { i, link -> if (link != links[i]) newLinks += link }
            val weighted = weightedStarsOf(scored.map { it.asSummary(entry.name) })
            val rescored = entry.copy(score = weighted?.first, votes = weighted?.second ?: 0)
            if (rescored != entry) newEntries += rescored
        }
        if (newLinks.isEmpty() && newEntries.isEmpty()) return
        inTransaction {
            newEntries.chunked(500).forEach { dao.putEntries(it) }
            newLinks.chunked(500).forEach { dao.putLinks(it) }
        }
    }

    /** La ficha tal como la dio su fuente (el año es el de la ficha: el de la serie es el más antiguo de sus fichas y no vale para reagrupar). */
    private fun CatalogLink.asSummary(fallbackName: String) = SeriesSummary(
        aid = aid.takeIf { it > 0 } ?: AnimeIds.forSlug(slug, sourceId),
        name = name ?: fallbackName,
        slug = slug,
        category = 1,
        source = sourceId,
        score = score,
        votes = votes,
        year = year
    )

    /**
     * Rehace el catálogo con lo recién listado por [listed], lo conservado de las demás fuentes y las fichas [extra] (altas sueltas,
     * ver [addNew]). Una escritura a la vez.
     */
    @Synchronized
    private fun writeCatalog(
        listed: List<Pair<AnimeSource, List<SeriesSummary>>>,
        startedAt: Long,
        replaceAll: Boolean = false,
        extra: List<Pair<AnimeSource, SeriesSummary>> = emptyList()
    ) {
        val okIds = listed.map { it.first.id }
        val items = listed.flatMap { (source, items) -> items.map { source to it } } + keptItems(okIds.toSet()) + extra
        // Los ids se leen y se reparten en una transacción del libro (`user-db`), para que un alta o una separación hecha a la vez desde la
        // ficha no se pise; viven en su propia base, que vaciar el catálogo no toca: una serie conserva su id al reconstruirlo. El catálogo
        // se escribe después en la suya, entero o nada (si falla a medias, no queda un catálogo con los enlaces borrados). Si falla el
        // catálogo, los ids ya dados quedan sin usar, sin daño; nunca queda un enlace con un id que el libro no conozca.
        var series: List<List<Pair<AnimeSource, SeriesSummary>>> = emptyList()
        var assignment: SeriesIds.Assignment? = null
        var linkedAnilist: Map<Pair<String, String>, Long> = emptyMap()
        ensureIds()
        idsTransaction {
            val known = ids.allMap().associate { (it.sourceId to it.slug) to it.seriesId }
            val linked = runCatchingCoop { anilistLinks() }.onFailure(onError).getOrDefault(emptyMap())
            linkedAnilist = linked
            series = SeriesResolver.resolveSeries(items, idOf = { source, slug -> known[source to slug] },
                anilistOf = { source, slug -> linked[source to slug] })
            val assigned = SeriesIds.assign(
                groups = series.map { members -> members.map { (source, item) -> source.id to item.slug } },
                known = known,
                newId = { ids.newSeries(SeriesRegistry()) }
            )
            ids.putMap(assigned.map)
            ids.moveSeries(assigned.moves)
            userRows.followLedger()
            assignment = assigned
        }
        val assigned = checkNotNull(assignment) { "no se repartieron los ids de serie" }
        followMoves(assigned.moves)
        // La nota que traen los listados de las webs se descarta: cada ficha lleva la de AniList (con un voto, para que pese igual) o ninguna.
        val stars = runCatchingCoop { anilistStars() }.onFailure(onError).getOrDefault(emptyMap())
        series = series.map { members ->
            members.map { (source, item) ->
                val own = linkedAnilist[source.id to item.slug]?.let(stars::get)
                source to item.copy(score = own, votes = if (own != null) 1 else 0)
            }
        }
        val entries = series.mapIndexed { index, members ->
            val id = assigned.ids[index]
            SeriesResolver.entryOf(id.toString(), members.map { it.second }, startedAt).copy(seriesId = id)
        }
        val links = series.flatMapIndexed { index, members ->
            members.map { (source, item) -> CatalogLink(assigned.ids[index].toString(), source.id, item.slug, item.aid, assigned.ids[index], item.year, item.name, item.score, item.votes) }
        }.distinctBy { Triple(it.key, it.sourceId, it.slug) }
        inTransaction {
            if (replaceAll) { dao.clearLinks(); dao.clearEntries() } else dao.deleteLinks(okIds)
            entries.chunked(500).forEach { dao.putEntries(it) }
            links.chunked(500).forEach { dao.putLinks(it) }
            // Todo lo conservado se ha vuelto a escribir con startedAt: lo que queda más antiguo ya no existe
            // (también las entradas de ids que ahora se han unido a otra serie).
            dao.purgeEntries(startedAt)
            dao.purgeOrphanLinks()
        }
        // Que falle el refresco de la memoria no es que falle el catálogo, que ya está escrito.
        runCatchingCoop { afterWrite() }.onFailure(onError)
    }

    /** Fichas de las fuentes no recorridas, reconstruidas desde el catálogo guardado (nota, votos, nombre y año, los de cada ficha). */
    private fun keptItems(walkedIds: Set<String>): List<Pair<AnimeSource, SeriesSummary>> {
        val entries = dao.allEntries().associateBy { it.key }
        return dao.allLinks().filter { it.sourceId !in walkedIds }.mapNotNull { link ->
            val source = all().firstOrNull { it.id == link.sourceId } ?: return@mapNotNull null
            val entry = entries[link.key] ?: return@mapNotNull null
            source to link.asSummary(entry.name)
        }
    }

    /** Vuelve a intentar las fuentes pendientes pasado un rato (máximo [MAX_RETRIES] veces seguidas). */
    private fun scheduleRetry() {
        if (retries.get() >= MAX_RETRIES || !retryScheduled.compareAndSet(false, true)) return
        retries.incrementAndGet()
        scope.launch {
            delay(RETRY_DELAY)
            retryScheduled.set(false)
            runCatchingCoop { retry() }.onFailure(onError)
        }
    }

    /** Una página del recorrido. Si el servidor limita el ritmo (429) o falla, espera cada vez más antes de rendirse. */
    private suspend fun pageWithRetry(source: AnimeSource, cursor: String?, query: SearchQuery = SearchQuery()): CatalogPage {
        var wait = RETRY_PAGE
        repeat(PAGE_ATTEMPTS - 1) {
            runCatchingCoop { return source.search(query, cursor) }
            delay(wait)
            wait *= 2
        }
        return source.search(query, cursor)
    }

    /** Recorre todas las páginas de la fuente (A-Z, sin filtros) y devuelve las fichas básicas. */
    private suspend fun walk(source: AnimeSource): List<SeriesSummary> {
        val items = walkPages(source, SearchQuery()).toMutableList()
        // Si la web recorta el listado completo, se completa pidiéndolo año a año (cada año cabe bajo el tope).
        val years = source.catalogYears ?: return items
        for (year in years) items += walkPages(source, SearchQuery(years = year..year))
        return items.distinctBy { it.slug }
    }

    private suspend fun walkPages(source: AnimeSource, query: SearchQuery): List<SeriesSummary> {
        val items = mutableListOf<SeriesSummary>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = pageWithRetry(source, cursor, query)
            items += page.items
            cursor = page.next
            pages++
            delay(PAGE_DELAY)
        } while (cursor != null && pages < MAX_PAGES)
        return items
    }
}
