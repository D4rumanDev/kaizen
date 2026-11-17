package app.kaizen.source

import app.kaizen.commons.DebugLog
import kotlinx.coroutines.flow.update

/**
 * Un id de serie ya repartido. Ya no es una tabla (desde `user-db` v6 los ids salen de [FichaRow] y de un contador, ver [SeriesIdStore]): queda como
 * el tipo con el que se pasan ids sueltos al libro y a las copias.
 */
data class SeriesRegistry(val seriesId: Long = 0)

/** Qué serie es cada ficha de cada fuente: la vista `(fuente, slug) → seriesId` de la tabla [FichaRow]. */
data class SeriesMap(
    val sourceId: String,
    val slug: String,
    val seriesId: Long
)

/**
 * El libro de ids que usa la app. En producción lo implementa [SeriesIdStore] sobre la tabla [FichaRow]; las pruebas lo sustituyen por uno en
 * memoria. No es de Room: sus métodos de varias escrituras ([restoreLedgerIfEmpty], [splitAllSeries]) los envuelve en una transacción quien lo implementa.
 */
interface SeriesIdDao {
    /** Reparte un id de serie nuevo, por encima de todos los que hay. */
    fun newSeries(registry: SeriesRegistry): Long

    /** Da de alta ids concretos (al restaurar la fotografía del catálogo): el siguiente id nuevo irá por encima de todos. */
    fun putRegistry(rows: List<SeriesRegistry>)

    /** Si ya hay alguna ficha con id: `idOf` da el id y luego escribe la ficha, y entre ambos el libro aún está vacío. */
    fun hasRegistry(): Boolean

    fun allMap(): List<SeriesMap>

    fun idOf(sourceId: String, slug: String): Long?

    /** Da (o cambia) el id de la serie de cada ficha, sin tocar su enlace con AniList. */
    fun putMap(rows: List<SeriesMap>)

    /** Como [putMap] pero sin pisar: si otro hilo ya dio id a la ficha, se queda el suyo. */
    fun putMapIfAbsent(rows: List<SeriesMap>)

    /**
     * Pasa a la serie viva ([moves]: id absorbido -> id que sigue) todas las fichas que tenían un id absorbido. Ya no se guardan uniones aparte:
     * quien une series cambia el id de sus fichas aquí y pasa lo guardado del usuario al id vivo con [SeriesLedger.followMoves].
     */
    fun moveSeries(moves: Map<Long, Long>)

    /**
     * Vuelve a dar de alta ids y fichas de una copia ([LedgerBackup]) en una sola transacción: no queda a medias. Se vuelve a
     * comprobar que el libro está vacío dentro de ella (otro hilo pudo dar un id entre la primera comprobación y ahora). Devuelve si restauró.
     */
    fun restoreLedgerIfEmpty(registry: List<SeriesRegistry>, map: List<SeriesMap>): Boolean {
        if (hasRegistry() || allMap().isNotEmpty()) return false
        putRegistry(registry)
        putMap(map)
        // Si no quedó todo lo pedido se lanza: la transacción se deshace y el libro sigue vacío (no queda un libro a medias que ya no se pueda copiar).
        check(allMap().size == map.distinctBy { it.sourceId to it.slug }.size) {
            "libro de series: restauración incompleta"
        }
        return true
    }

    /**
     * Deshace todas las uniones: cada ficha que compartía serie con otras se queda con un id propio (la primera, por fuente y
     * slug, conserva el actual). Lo guardado del usuario no se toca: sigue a su ficha (fuente, slug).
     */
    fun splitAllSeries() {
        for (rows in allMap().groupBy { it.seriesId }.values) {
            for (row in rows.sortedWith(compareBy({ it.sourceId }, { it.slug })).drop(1)) {
                val fresh = newSeries(SeriesRegistry())
                putMap(listOf(row.copy(seriesId = fresh)))
            }
        }
    }
}

/** Sigue las uniones hasta el id vigente (con guarda contra ciclos). */
internal fun Map<Long, Long>.resolve(id: Long): Long {
    var current = id
    val seen = HashSet<Long>()
    while (seen.add(current)) current = this[current] ?: return current
    return current
}

/** Reparto de ids a los grupos de fichas que [SeriesResolver] decide que son la misma serie. */
object SeriesIds {
    /** Lo que sale de [assign]: el id de cada grupo (en su orden), las filas a guardar y las uniones nuevas (id absorbido -> id vigente). */
    class Assignment(val ids: List<Long>, val map: List<SeriesMap>, val moves: Map<Long, Long>)

    /**
     * Da un id a cada grupo de [groups] (pares fuente/slug), conservando los que ya había en [known]:
     *  - un grupo cuyas fichas ya tenían id lo conserva; si traía varios (se han unido), se queda con el más bajo y los demás
     *    pasan a ser uniones hacia él (`moves`);
     *  - si dos grupos reclaman el mismo id (una serie se ha separado), se lo queda el que más fichas tenía con él y el otro
     *    recibe uno nuevo;
     *  - un grupo sin ningún id conocido recibe uno nuevo de [newId].
     */
    fun assign(
        groups: List<List<Pair<String, String>>>,
        known: Map<Pair<String, String>, Long>,
        newId: () -> Long
    ): Assignment {
        // Cuántas fichas de cada grupo tenían ya cada id.
        val counts = groups.map { group -> group.mapNotNull { known[it] }.groupingBy { it }.eachCount() }
        // Quién se queda con cada id reclamado: el grupo con más fichas suyas; a igualdad, el más grande y luego el primero.
        val winner = HashMap<Long, Int>()
        for ((index, claimed) in counts.withIndex()) {
            for ((id, count) in claimed) {
                val current = winner[id]
                val better = current == null || count > counts[current].getValue(id) ||
                    (count == counts[current].getValue(id) && groups[index].size > groups[current].size)
                if (better) winner[id] = index
            }
        }

        val ids = ArrayList<Long>(groups.size)
        val moves = LinkedHashMap<Long, Long>()
        for ((index, claimed) in counts.withIndex()) {
            val owned = claimed.keys.filter { winner[it] == index }.sorted()
            val id = owned.firstOrNull() ?: newId()
            owned.drop(1).forEach { moves[it] = id }
            ids += id
        }
        val map = groups.flatMapIndexed { index, group -> group.map { (source, slug) -> SeriesMap(source, slug, ids[index]) } }
        return Assignment(ids, map, moves)
    }
}

/**
 * Los ids de serie en memoria: de cada ficha (fuente, slug original) el id de su serie. Se carga de la base de datos una vez
 * (en segundo plano, al arrancar) y después se lee sin tocarla, porque las pantallas lo piden desde el hilo principal.
 * Recargar construye el estado nuevo aparte y lo cambia de golpe: nadie ve un estado
 * a medias. No toma ningún cerrojo propio: las altas se resuelven en la base de datos (`putMapIfAbsent`), que decide quién gana,
 * así que no puede bloquearse contra una transacción de Room.
 */
object SeriesLedger {
    private class State(
        val ids: java.util.concurrent.ConcurrentHashMap<Pair<String, String>, Long> = java.util.concurrent.ConcurrentHashMap(),
        // Una ficha cualquiera de cada slug, para cuando se pide por slug sin saber de qué fuente viene (solo lectura).
        val bySlug: java.util.concurrent.ConcurrentHashMap<String, Long> = java.util.concurrent.ConcurrentHashMap(),
        // Las fichas de cada serie (por id ya resuelto), para pedir las de una serie sin recorrer todas.
        val bySeries: java.util.concurrent.ConcurrentHashMap<Long, MutableSet<Pair<String, String>>> = java.util.concurrent.ConcurrentHashMap()
    ) {
        fun index(ficha: Pair<String, String>, id: Long) {
            bySeries.computeIfAbsent(id) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(ficha)
        }
    }

    @Volatile
    private var state = State()

    @Volatile
    private var loaded = false

    private val _version = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Sube cada vez que se leen los ids: las listas que deduplican con ellos se rehacen (al arrancar salen antes de que carguen). */
    val version: kotlinx.coroutines.flow.StateFlow<Int> = _version

    /** La base de datos de ids; las pruebas la sustituyen. */
    internal var dao: () -> SeriesIdDao = { app.kaizen.database.CacheDB.INSTANCE.seriesIdDao() }

    /** Las filas del usuario que siguen al libro, y la transacción de `user-db` que las cambia junto a él; las pruebas las sustituyen. */
    internal var userRows: () -> UserRows = { app.kaizen.database.UserDB.INSTANCE.userRowsDao() }
    internal var transaction: (() -> Unit) -> Unit = { app.kaizen.database.UserDB.INSTANCE.runInTransaction(it) }

    /** Dónde está la copia del libro; las pruebas la sustituyen. */
    internal var backups: LedgerBackups = LedgerBackupFile

    /** Si hay una fotografía del catálogo que se puede usar: ella rehace también los ids (y el catálogo), así que la copia del libro no actúa. */
    internal var snapshotUsable: () -> Boolean = { CatalogSnapshotFile.read()?.usable(CatalogStateStore.RULES_VERSION) == true }

    /**
     * Guarda una copia del libro (fichas con su serie) junto a las copias de seguridad. Un libro vacío no sustituye a una copia buena.
     * La hace cada copia de seguridad. Llamar desde un hilo de fondo.
     */
    fun saveBackup() {
        val store = dao()
        val map = store.allMap()
        if (map.isEmpty()) return
        backups.write(
            LedgerBackup(
                LedgerBackup.VERSION, System.currentTimeMillis(),
                map.map { SnapMap(it.sourceId, it.slug, it.seriesId) }, null
            )
        )
    }

    /**
     * Con el libro vacío (la base de datos se vació) y sin fotografía del catálogo que lo rehaga, lo restaura de la copia: los mismos ids
     * de siempre, y el siguiente id nuevo va por encima de todos. No toca nada si ya hay ids, para no pisar lo que se haya dado desde el vaciado.
     * Devuelve si restauró.
     */
    internal fun restoreBackupIfEmpty(store: SeriesIdDao): Boolean {
        if (store.hasRegistry() || store.allMap().isNotEmpty()) return false
        val backup = backups.read()?.takeIf { it.usable() } ?: return false
        if (snapshotUsable()) return false
        val map = backup.map.orEmpty()
        // Las copias hechas antes de quitar las redirecciones las traen aparte: cada ficha toma el id vigente de su serie.
        val moves = backup.redirects.orEmpty().associate { it.fromId to it.toId }
        val rows = map.map { SeriesMap(it.sourceId, it.slug, moves.resolve(it.seriesId)) }
        val registry = (rows.map { it.seriesId } + moves.keys + moves.values).distinct().map { SeriesRegistry(it) }
        val restored = store.restoreLedgerIfEmpty(registry, rows)
        if (restored) {
            DebugLog.info("Series", "Libro de series restaurado de la copia: ${map.size} fichas")
            followMoves(moves)
        }
        return restored
    }

    /**
     * Lee las tablas de ids (también para recargarlas tras reconstruir el catálogo). Llamar desde un hilo de fondo. No está sincronizado:
     * dos cargas a la vez son seguras porque la copia y la restauración (`restoreLedgerIfEmpty`) vuelven a comprobar el vacío dentro de su transacción.
     */
    fun load() {
        val store = dao()
        // Con el libro vacío, la copia en disco.
        runCatching { restoreBackupIfEmpty(store) }.onFailure { app.kaizen.commons.logError(it) }
        val next = State()
        store.allMap().forEach { next.ids[it.sourceId to it.slug] = it.seriesId; next.bySlug.putIfAbsent(it.slug, it.seriesId) }
        for ((ficha, id) in next.ids) next.index(ficha, id)
        state = next
        loaded = true
        _version.update { it + 1 }
        // Las reglas del usuario sin ninguna ficha viva no tienen efecto: se cuentan para el registro.
        runCatching {
            val dead = UserTitles.rules.idKeys().count { key -> key.toLongOrNull()?.let { next.bySeries[it].isNullOrEmpty() } == true }
            if (dead > 0) DebugLog.info("Series", "Reglas del usuario con $dead ids sin ninguna ficha (sin efecto)")
        }.onFailure { runCatching { app.kaizen.commons.logError(it) } }
    }

    /**
     * Lo guardado por id de serie (el progreso y las reglas del usuario) pasa del id absorbido al vigente tras una unión. Se llama justo después de
     * confirmar la unión en la base de datos; las filas de favoritos y vistos ya la siguieron dentro de su transacción ([UserRows.followLedger]).
     */
    internal fun followMoves(moves: Map<Long, Long>) {
        if (moves.isEmpty()) return
        runCatching { app.kaizen.commons.KaizenProgress.rekey { id -> moves.resolve(id) } }.onFailure { runCatching { app.kaizen.commons.logError(it) } }
        runCatching {
            val moved = UserTitles.followLedger { id -> moves.resolve(id) }
            DebugLog.info("Series", "Uniones: ${moves.size} ids absorbidos; $moved ids de reglas del usuario pasados a la serie vigente")
        }.onFailure { runCatching { app.kaizen.commons.logError(it) } }
    }

    /** Carga el libro si aún no se ha cargado (y falla si no puede): lo que reparte ids sin pasar por [idOf] lo llama antes. */
    internal fun ensureLoaded() {
        if (!loaded) load()
    }

    /** El id de la serie de la ficha, si ya lo tiene. Solo mira la memoria: null si aún no se cargó o la ficha no tiene id. */
    fun peek(source: String, slug: String): Long? = state.let { s -> s.ids[source to slug] }

    /** El id de la serie de alguna ficha con ese slug, de la fuente que sea. */
    fun peekBySlug(slug: String): Long? = state.let { s -> s.bySlug[slug] }

    /** [peek] como texto: la clave con la que las reglas del usuario ([TitleRules]) nombran la serie. */
    fun peekKey(source: String, slug: String): String? = peek(source, slug)?.toString()

    /** Las series (por id vigente) con las fichas (fuente, slug) de cada una. */
    fun seriesFichas(): Map<Long, List<Pair<String, String>>> = state.bySeries.mapValues { it.value.toList() }

    /** Las fichas (fuente, slug) que ya tienen id. */
    fun fichas(): Set<Pair<String, String>> = state.ids.keys.toSet()

    /** Las fichas de las series con esas claves (ids en texto), sin recorrer todas las fichas. */
    fun fichasOfKeys(keys: Collection<String>): Set<Pair<String, String>> = state.let { s ->
        keys.mapNotNull { it.toLongOrNull() }.toSet().flatMapTo(HashSet()) { s.bySeries[it].orEmpty() }
    }

    /**
     * Las fichas «fuente:slug» que hoy forman la serie [seriesId] (la propia ficha incluida), resolviendo uniones; vacío con 0 o un id
     * desconocido. Es como se resuelve al leer qué guardado del usuario es de una serie: las filas llevan solo su ficha, no el grupo.
     */
    fun fichaKeysOf(seriesId: Long): List<String> =
        if (seriesId == 0L) emptyList() else fichasOfKeys(listOf(seriesId.toString())).map { "${it.first}:${it.second}" }

    /** El id de la serie de la ficha; se lo da si no lo tiene. Llamar desde un hilo de fondo. */
    fun idOf(source: String, slug: String): Long {
        if (!loaded) load()
        peek(source, slug)?.let { return it }
        val store = dao()
        // Si otro hilo se adelantó, `putMapIfAbsent` no pisa su id y aquí se lee el que ganó (el id sobrante queda sin usar).
        store.putMapIfAbsent(listOf(SeriesMap(source, slug, store.newSeries(SeriesRegistry()))))
        val s = state
        val id = checkNotNull(store.idOf(source, slug)) { "sin id para $source/$slug" }
        s.ids[source to slug] = id
        s.bySlug.putIfAbsent(slug, id)
        s.index(source to slug, id)
        return id
    }

    /**
     * Une en una sola serie las fichas de cada grupo de [groups] (cada ficha es «fuente», «slug»); lo guardado del usuario sigue a su ficha.
     * Se usa al importar una copia que sabe qué fichas son la misma serie. Las fichas que no conoce el libro reciben id. Llamar desde un hilo de fondo.
     */
    fun join(groups: List<List<Pair<String, String>>>) {
        val joinable = groups.map { it.distinct() }.filter { it.size > 1 }
        if (joinable.isEmpty()) return
        ensureLoaded()
        val store = dao()
        val moves = LinkedHashMap<Long, Long>()
        transaction {
            for (group in joinable) {
                group.forEach { (source, slug) -> idOf(source, slug) }
                // Los ids se leen de la base: una unión anterior del bucle ya movió fichas.
                val resolved = group.map { (source, slug) -> checkNotNull(store.idOf(source, slug)) }.distinct()
                val target = resolved.min()
                val step = resolved.filter { it != target }.associateWith { target }
                store.moveSeries(step)
                moves += step
            }
            userRows().followLedger()
        }
        load()
        followMoves(moves)
    }

    /** Deshace todas las uniones (ver [SeriesIdDao.splitAllSeries]) y recarga la memoria. Llamar desde un hilo de fondo. */
    fun splitAll() {
        transaction {
            dao().splitAllSeries()
            userRows().followLedger()
        }
        load()
    }

    /**
     * Saca la ficha de su serie dándole un id nuevo, que se devuelve. Es el primer paso de separar dos fichas que el catálogo
     * había juntado: sin id propio no hay forma de decir «estas dos no». Llamar desde un hilo de fondo.
     */
    fun detach(source: String, slug: String): Long {
        if (!loaded) load()
        val store = dao()
        var id = 0L
        transaction {
            id = store.newSeries(SeriesRegistry())
            store.putMap(listOf(SeriesMap(source, slug, id)))
            userRows().moveFavorites(listOf(source to slug), id)
            userRows().followLedger()
        }
        val s = state
        s.ids[source to slug]?.let { old -> s.bySeries[old]?.remove(source to slug) }
        s.ids[source to slug] = id
        s.index(source to slug, id)
        // El slug ya no apunta con seguridad a la serie de la que se ha separado.
        s.bySlug.remove(slug)
        // Los grupos han cambiado: lo que se calcula con ellos (listas, perfil de gustos) se rehace.
        _version.update { it + 1 }
        return id
    }

    /**
     * Saca las fichas de [fichas] de la serie en que estén y las deja juntas en una serie nueva (un id nuevo, que se devuelve). Sirve para
     * deshacer una unión equivocada devolviendo cada grupo de fichas a su serie. Ignora las fichas que el libro no conoce; null si no queda
     * ninguna. No toca las reglas del usuario. Llamar desde un hilo de fondo.
     */
    fun detachGroup(fichas: List<Pair<String, String>>): Long? {
        if (!loaded) load()
        val known = fichas.distinct().filter { (source, slug) -> peek(source, slug) != null }
        if (known.isEmpty()) return null
        val store = dao()
        var id = 0L
        transaction {
            id = store.newSeries(SeriesRegistry())
            store.putMap(known.map { (source, slug) -> SeriesMap(source, slug, id) })
            userRows().moveFavorites(known, id)
            userRows().followLedger()
        }
        // Se recarga: cambian los grupos de varias series a la vez.
        load()
        return id
    }
}

/** El id de la serie de la ficha, o 0 si aún no lo tiene. Solo mira la memoria: sirve desde cualquier hilo. */
fun seriesIdOf(source: String, slug: String): Long = SeriesLedger.peek(source, slug) ?: 0L

