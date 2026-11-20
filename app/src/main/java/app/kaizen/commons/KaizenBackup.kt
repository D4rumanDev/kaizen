package app.kaizen.commons

import app.kaizen.domain.SeriesStatuses
import app.kaizen.commons.sync.SyncKeys
import androidx.annotation.Keep
import app.kaizen.App
import app.kaizen.database.CacheDB
import app.kaizen.pojos.av1.FavoriteAV1
import app.kaizen.pojos.av1.Record
import app.kaizen.source.ApiCache
import app.kaizen.source.SeriesLedger
import app.kaizen.source.UserTitles
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.kaizen.tv.ui.KaizenToast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.days

/**
 * Kaizen: copia local de los **estados** del usuario (favoritos, historial, progreso, estados de las series y reglas de unión; los ajustes, aparte en [SettingsBackup]) en un JSON dentro de la carpeta de la app
 * (`Android/data/app.kaizen/files/backups`, se recoge con `adb pull`). Sin red. Importar solo añade lo que falta.
 * Se hace sola una vez al día ([app.kaizen.jobscheduler.BackupWork]) y se borran las de más de [KEEP_DAYS] días.
 */
object KaizenBackup {
    /** 2: las filas van sin ids locales de serie y [Data.groups] dice qué fichas son la misma serie; se siguen leyendo las copias de la versión 1. */
    private const val VERSION = 2
    private const val PREFIX = "kaizen-backup-"
    const val KEEP_DAYS = 7
    private const val MAX_FILES = 30
    private const val MAX_GROUPS = 20_000
    private const val MAX_GROUP_SIZE = 30

    /** Con la última copia de hace menos de esto no toca otra: deja margen a que el trabajo diario llegue algo antes de 24 h. */
    private val DUE_AFTER = 20.hours // 20 horas

    @Keep
    data class Data(
        @SerializedName("version") val version: Int,
        @SerializedName("createdAt") val createdAt: Long,
        @SerializedName("favorites") val favorites: List<FavoriteAV1>,
        @SerializedName("records") val records: List<Record>,
        @SerializedName("progress") val progress: Map<String, String>,
        /** Reglas de títulos del usuario (titles.json) como texto; vacío = sin reglas. */
        @SerializedName("titles") val titles: String = "",
        /** Estados de las series (SeriesStatuses) como JSON; vacío = ninguno. */
        @SerializedName("statuses") val statuses: String = "",
        /** Fichas «fuente:slug» que son la misma serie (solo los grupos de más de una); al importar se unen. */
        @SerializedName("groups") val groups: List<List<String>> = emptyList(),
        /** Lo que se quitó (clave portable → fecha), solo en las copias de sincronización entre dispositivos; ver [app.kaizen.commons.sync.Removals]. */
        @SerializedName("removed") val removed: Map<String, Long> = emptyMap()
    )

    /** Lo que Gson puede dejar a null si falta un campo en un fichero a mano o de otra versión. */
    @Keep
    private class Raw(
        @SerializedName("version") val version: Int?,
        @SerializedName("createdAt") val createdAt: Long?,
        @SerializedName("favorites") val favorites: List<FavoriteAV1>?,
        @SerializedName("records") val records: List<Record>?,
        @SerializedName("progress") val progress: Map<String, String>?,
        @SerializedName("titles") val titles: String?,
        @SerializedName("statuses") val statuses: String?,
        @SerializedName("groups") val groups: List<List<String>?>?,
        @SerializedName("removed") val removed: Map<String, Long?>?
    )

    /** Lo que trajo una copia; [progressReplaced] y [statusesReplaced] cuentan lo de aquí que se sustituyó por algo más reciente de la copia. */
    class Summary(val favorites: Int, val records: Int, val progress: Int, val progressReplaced: Int = 0, val statusesReplaced: Int = 0) {
        override fun toString() = "$favorites favoritos, $records capítulos vistos, $progress en curso"
    }

    fun encode(data: Data): String = SharedGson.gson.toJson(data)

    /** null si no es un JSON válido o es de una versión posterior. */
    fun decode(json: String): Data? {
        val raw = runCatching { SharedGson.gson.fromJson(json, Raw::class.java) }.getOrNull() ?: return null
        val version = raw.version ?: return null
        if (version !in 2..VERSION) return null
        return Data(version, raw.createdAt ?: 0L, raw.favorites.orEmpty().filter { (it as FavoriteAV1?)?.isValid() == true },
            raw.records.orEmpty().filter { (it as Record?)?.isValid() == true }, raw.progress.orEmpty(), raw.titles.orEmpty(), raw.statuses.orEmpty(),
            raw.groups.orEmpty().map { group -> group.orEmpty().filterNotNull() }, app.kaizen.commons.sync.Removals.sanitize(raw.removed))
    }

    val directory: File get() = File(App.context.getExternalFilesDir(null), "backups")

    private fun backupFiles(): List<File> =
        directory.listFiles { file -> file.name.startsWith(PREFIX) && file.extension == "json" }.orEmpty().toList()

    fun latest(): File? = backupFiles().maxByOrNull { it.lastModified() }

    /** Las copias hechas desde la aceptada (0 = todas): tras aceptar unos datos más pequeños, las anteriores dejan de ser la referencia. */
    // Una fecha de aceptación en el futuro (reloj adelantado) cuenta como ninguna: así falla hacia el lado de avisar.
    internal fun since(files: List<File>, acceptedAt: Long, now: Long = System.currentTimeMillis()): List<File> {
        val from = if (acceptedAt > now) 0L else acceptedAt
        return files.filter { it.lastModified() >= from }
    }

    /** La copia de referencia: la más completa (la de más tamaño; a igualdad, la más reciente) desde la aceptada. La que se restaura y contra la que se avisa. */
    fun best(): File? = best(since(backupFiles(), KaizenSettings.acceptedBackupAt))

    // shortcut: el tamaño como medida de «completa»; si se guardan más cosas que no crecen con los datos, contar filas.
    internal fun best(files: List<File>): File? = files.maxWithOrNull(compareBy({ it.length() }, { it.lastModified() }))

    /**
     * Las copias de más de [KEEP_DAYS] días, salvo la más reciente, la más completa de todas y la más completa desde [acceptedAt]
     * (una pérdida de datos no debe borrar las buenas copias, ni antes ni después de aceptar unos datos más pequeños).
     */
    internal fun expired(files: List<File>, now: Long, acceptedAt: Long = 0L, keepDays: Int = KEEP_DAYS, maxFiles: Int = MAX_FILES): List<File> {
        val byRecent = files.sortedByDescending { it.lastModified() }
        val kept = setOfNotNull(best(files), best(since(files, acceptedAt)))
        // Nunca la más reciente ni las protegidas; fuera las de más de keepDays días y las que pasan de maxFiles (copias manuales repetidas).
        return byRecent.drop(1).filterIndexed { index, file -> file !in kept && (index + 1 >= maxFiles || now - file.lastModified() > keepDays.days.inWholeMilliseconds) }
    }

    /** Aviso si los datos actuales son menos de la mitad de los de la mejor copia; null si todo cuadra. */
    internal fun lossMessage(favorites: Int, records: Int, best: Summary): String? =
        if ((best.favorites >= 10 && favorites * 2 < best.favorites) || (best.records >= 50 && records * 2 < best.records))
            "Faltan datos: hay $favorites favoritos y $records capítulos vistos; la mejor copia tiene ${best.favorites} y ${best.records}. " +
                "Restáuralos en Configuración › Copia de seguridad › Local."
        else null

    /** El último aviso de [checkData]; lo enseña Configuración › Copia de seguridad. */
    @Volatile
    var warning: String? = null
        private set

    /**
     * Al abrir la app: anota cuántos favoritos y capítulos hay (registro de depuración, para saber cuándo desaparece algo) y avisa si faltan
     * respecto a la mejor copia. Llamar fuera del hilo principal.
     */
    suspend fun checkData() = withContext(Dispatchers.IO) {
        val db = CacheDB.INSTANCE
        val favorites = db.favoriteAV1DAO().allRaw.size
        val records = db.recordAV1DAO().all.size
        DebugLog.info("Datos", "Datos al abrir la app: $favorites favoritos y $records capítulos vistos")
        val best = best()?.let { file -> decode(file.readText())?.let { Summary(it.favorites.size, it.records.size, it.progress.size) } }
        warning = best?.let { lossMessage(favorites, records, it) }
        warning?.let { DebugLog.error("Datos", it); KaizenToast.toastLong(it) }
    }

    /**
     * El usuario da por buenos los datos actuales (los borró a propósito): se guarda una copia y pasa a ser la referencia, de modo que una
     * pérdida posterior vuelve a avisar y a poder restaurarse. Las copias anteriores no se borran.
     */
    suspend fun acceptCurrent() = withContext(Dispatchers.IO) {
        val (file, _) = export()
        KaizenSettings.acceptedBackupAt = file.lastModified()
        warning = null
        DebugLog.info("Datos", "Datos actuales aceptados; referencia del aviso: ${file.name}")
    }

    /** Restaura la copia más completa desde la aceptada y, si no es válida o falla, la siguiente en tamaño; null si ninguna lo es. */
    suspend fun restoreBest(): Summary? {
        val candidates = since(backupFiles(), KaizenSettings.acceptedBackupAt)
            .sortedWith(compareByDescending<File> { it.length() }.thenByDescending { it.lastModified() })
        for (file in candidates) runCatchingCoop { import(file) }.onFailure(::logError).getOrNull()?.let { return it }
        return null
    }

    /** Si hace falta la copia del día: no hay ninguna o la última tiene casi un día. */
    internal fun isDue(latestModified: Long?, now: Long): Boolean {
        if (latestModified == null) return true
        val age = now - latestModified
        // Una fecha en el futuro (reloj que se adelantó) no puede bloquear las copias: cuenta como que toca.
        return age < 0 || age >= DUE_AFTER.inWholeMilliseconds
    }

    /** La copia del día, solo si hace falta (la usa el trabajo diario y el arranque, por si Android lo retrasó). null = no tocaba. */
    suspend fun exportIfDue(): Pair<File, Summary>? =
        if (isDue(latest()?.lastModified(), System.currentTimeMillis())) export() else null

    /** Los estados de ahora serializados, sin escribir nada ni rotar copias: lo que sube la sincronización entre dispositivos. */
    suspend fun snapshotBytes(removed: Map<String, Long> = emptyMap()): ByteArray = withContext(Dispatchers.IO) { encode(buildData().copy(removed = removed)).toByteArray(Charsets.UTF_8) }

    /** La clave que iguala la misma serie en dos dispositivos aunque la ficha sea otra: su id local. null si la ficha no es conocida (la lógica, en [SyncKeys]). */
    fun canon(key: String): String? = SyncKeys.canon(key, SeriesLedger::peek)

    /** ¿Cambió aquí la clave [key] (un estado o un capítulo en curso) después de [at]? Solo ellos llevan hora; los favoritos y los capítulos vistos no. */
    fun changedAfter(key: String, at: Long): Boolean = SyncKeys.changedAfter(key, at,
        statusAt = { source, slug -> SeriesStatuses.latestChange(source, slug) },
        progressAt = { source, slug, number -> KaizenProgress.latestChange(SeriesLedger.peek(source, slug), "$source:$slug", number) })

    /** Lo que hay ahora, por claves portables, para saber qué se quitó entre dos sincronizaciones ([app.kaizen.commons.sync.Removals]). */
    suspend fun presence(): app.kaizen.commons.sync.Removals.Presence = withContext(Dispatchers.IO) {
        SeriesLedger.ensureLoaded()
        val db = CacheDB.INSTANCE
        val favorites = db.favoriteAV1DAO().allRaw.map { it.seriesId }
        val records = db.recordAV1DAO().all.map { it.seriesId to it.number }
        // «Seguir viendo»: el progreso por serie («s:») y el de fichas de versiones anteriores («p:»).
        val (bySeries, byFicha) = KaizenProgress.chapters()
        // Estados de serie: una clave por serie con estado (la ficha de la entrada) y sus ids para saber si sigue.
        val statusEntries = SeriesStatuses.all.value.values
        val statusIds = statusEntries.mapNotNull { app.kaizen.source.seriesIdOf(it.source, it.slug).takeIf { id -> id != 0L } }.toSet()
        val keys = SyncKeys.keys(favorites, records, bySeries, byFicha, statusEntries.map { "${it.source}:${it.slug}" }) { id -> SeriesLedger.fichaKeysOf(id).minOrNull() }
        SyncKeys.Presence(favorites.toSet(), records.toSet(), keys, bySeries.toSet(), byFicha.toSet(), statusIds, SeriesLedger::peek)
    }

    /** Quita lo que dicen estas claves (favoritos, capítulos vistos, capítulos en curso y estados); devuelve cuántas había. */
    suspend fun remove(keys: Collection<String>): Int = withContext(Dispatchers.IO) {
        val db = CacheDB.INSTANCE
        SeriesLedger.ensureLoaded()
        SyncKeys.remove(keys, object : SyncKeys.Target {
            override fun removeFavorite(source: String, slug: String) =
                db.favoriteAV1DAO().isFav(source, slug).also { if (it) db.favoriteAV1DAO().removeFav(source, slug) }
            override fun removeRecord(source: String, slug: String, number: Double) =
                db.recordAV1DAO().chapterIsSeen(source, slug, number).also { if (it) db.recordAV1DAO().deleteChapter(source, slug, number) }
            override fun removeProgress(source: String, slug: String, number: Double) =
                KaizenProgress.removeChapter(SeriesLedger.peek(source, slug), listOf("$source:$slug"), number)
            override fun removeStatus(source: String, slug: String) = SeriesStatuses.removeSeries(source, slug)
        })
    }

    private fun buildData(): Data {
        val db = CacheDB.INSTANCE
        SeriesLedger.ensureLoaded()
        val favorites = db.favoriteAV1DAO().allRaw
        val records = db.recordAV1DAO().all.toList()
        // Los ids de serie son locales: no salen. Las fichas que son la misma serie salen agrupadas, y con ellas se vuelve a unir al importar.
        val groups = (favorites.map { it.seriesId } + records.map { it.seriesId }).distinct()
            .map { SeriesLedger.fichaKeysOf(it).sorted() }.filter { it.size > 1 }
        val data = Data(VERSION, System.currentTimeMillis(), favorites.map { it.copy(seriesId = 0L) }, records.map { it.copy(seriesId = 0L) },
            KaizenProgress.exportPortable { id -> SeriesLedger.fichaKeysOf(id).minOrNull()?.let { it.substringBefore(':') to it.substringAfter(':') } },
            UserTitles.export().takeUnless { UserTitles.rules.isEmpty }.orEmpty(), SeriesStatuses.export(), groups)
        return data
    }

    /** Guarda una copia nueva y borra las de más de [KEEP_DAYS] días. */
    suspend fun export(): Pair<File, Summary> = withContext(Dispatchers.IO) {
        val data = buildData()
        val dir = directory.also { it.mkdirs() }
        val file = File(dir, "$PREFIX${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(data.createdAt)}.json")
        file.writeText(encode(data))
        expired(backupFiles(), System.currentTimeMillis(), KaizenSettings.acceptedBackupAt, KaizenSettings.backupDays.coerceAtLeast(1), KaizenSettings.backupFiles.coerceAtLeast(2)).forEach { it.delete() }
        // Los ajustes van en su propio JSON (SettingsBackup): la copia de estados y la de configuración se pueden llevar por separado.
        runCatchingCoop { SettingsBackup.export() }.onFailure(::logError)
        // Con la copia, el libro de series (las reglas nombran las series por sus ids): si la base se vacía, los ids vuelven a ser los mismos.
        runCatchingCoop { SeriesLedger.saveBackup() }.onFailure(::logError)
        // Los enlaces de cada ficha con AniList (también los del usuario) solo viven en `user-db`: se guardan aparte para poder cambiar esas tablas.
        runCatchingCoop { app.kaizen.source.AnilistLinksBackups.save(app.kaizen.database.UserDB.INSTANCE.animeFuenteDao()) }.onFailure(::logError)
        file to Summary(data.favorites.size, data.records.size, data.progress.size)
    }

    /** Añade lo que falta (favoritos, capítulos vistos y progreso); lo que ya existe no se toca. null = fichero no válido. */
    suspend fun import(file: File): Summary? = withContext(Dispatchers.IO) { decode(file.readText())?.let { merge(it) } }

    /** Une los grupos de fichas que dice [data] (para poder resolver las series antes de fundir). */
    suspend fun joinGroups(data: Data) = withContext(Dispatchers.IO) {
        SeriesLedger.ensureLoaded()
        joinGroupsNow(data)
    }

    // Una copia ajena o corrupta no puede inventar fichas ni unir sin tope: solo grupos con «fuente:slug» de una fuente conocida.
    private fun joinGroupsNow(data: Data) =
        SeriesLedger.join(SyncKeys.groups(data.groups, app.kaizen.source.AnimeSources.all.map { it.id }.toSet(), MAX_GROUPS, MAX_GROUP_SIZE))

    /** Funde [data] añadiendo lo que falta; [skip] deja fuera un favorito o un capítulo (por su clave portable): lo que una lápida más nueva que la copia dice que se quitó. */
    suspend fun merge(data: Data, skip: (String) -> Boolean = { false }): Summary = withContext(Dispatchers.IO) {
        val db = CacheDB.INSTANCE
        SeriesLedger.ensureLoaded()
        // Primero se unen las fichas que la copia dice que son la misma serie; después cada fila toma el id de su serie (el de la copia, si
        // trae alguno, es de otro equipo o de antes y no vale).
        joinGroupsNow(data)
        val dao = db.favoriteAV1DAO()
        val newFavorites = data.favorites.map { it.copy(seriesId = 0L) }
            .filter { !skip(app.kaizen.commons.sync.Removals.favoriteKey("${it.source}:${it.slug}")) && !dao.isFav(it.source, it.slug) }
        dao.addAll(newFavorites)
        db.recordAV1DAO().addAllIgnore(data.records.map { it.copy(seriesId = 0L) }
            .filter { !skip(app.kaizen.commons.sync.Removals.recordKey("${it.source}:${it.slug}", it.number)) })
        // El progreso de otro dispositivo no vuelve si una lápida más nueva que su copia dice que aquí se quitó de «Seguir viendo».
        val newProgress = KaizenProgress.importMissing(SyncKeys.progressToImport(data.progress, skip))
        // Lo que entró por ficha pasa a su serie; si allí ya había progreso, gana el más reciente.
        val moved = KaizenProgress.migrateFichaKeys { source, slug -> app.kaizen.source.seriesIdOf(source, slug) }
        if (data.titles.isNotEmpty()) UserTitles.import(data.titles)
        // Un estado nuevo entra y uno ya existente se sustituye si el de la copia es más reciente; una lápida más nueva que la copia lo impide.
        val statuses = if (data.statuses.isEmpty()) null
            else SeriesStatuses.importNewer(data.statuses) { entry -> skip(app.kaizen.commons.sync.Removals.statusKey("${entry.source}:${entry.slug}")) }
        Summary(newFavorites.size, data.records.size, newProgress, moved.replaced, statuses?.replaced ?: 0)
    }
}
