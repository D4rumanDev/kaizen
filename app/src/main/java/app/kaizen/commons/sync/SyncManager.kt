package app.kaizen.commons.sync

import app.kaizen.commons.DebugLog
import app.kaizen.commons.KaizenBackup
import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.SmbBackup
import app.kaizen.commons.logError
import app.kaizen.commons.runCatchingCoop
import app.kaizen.database.CatalogDB
import app.kaizen.database.UserDB
import app.kaizen.source.AnilistShare
import app.kaizen.source.CatalogBuilder
import app.kaizen.source.spec.RepoConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.days

/**
 * Sincronización de los estados de este dispositivo con el servidor WebDAV elegido (ver [DeviceSync]): la lleva el botón «Sincronizar ahora» de Configuración › Sincronización y, si
 * está activada, la copia diaria. La URL, el usuario y la contraseña son de confianza (a dónde salen tus datos): solo se cambian desde el menú o por ADB, nunca al restaurar una copia.
 */
object SyncManager {
    /** El id de este dispositivo en las copias; se crea la primera vez y no cambia. */
    val deviceId: String
        get() = KaizenSettings.syncDeviceId?.takeIf { DeviceSync.deviceOf(DeviceSync.fileName(it)) != null }
            ?: DeviceSync.newDeviceId().also { KaizenSettings.syncDeviceId = it }

    /** La URL normalizada del destino, o null si no hay o no vale. */
    val url: String? get() = KaizenSettings.syncUrl?.let(RepoConfig::normalizeUrl)

    /** ¿Hay un destino configurado? */
    val configured: Boolean get() = url != null || SmbBackup.configured

    /** Sin servidor WebDAV, la sincronización va por la carpeta de red SMB (la de las copias), si está elegida. */
    val viaSmb: Boolean get() = url == null && SmbBackup.configured

    /**
     * Cambia el destino. Si cambia el **servidor** (el host), la contraseña (y con ella el usuario) se borra: una credencial guardada para un servidor no viaja a otro sin que
     * se vuelva a escribir. Lo usan el menú y ADB.
     */
    fun setUrl(value: String?) {
        val old = url?.let { runCatching { java.net.URI(it).host }.getOrNull() }
        val new = value?.let(RepoConfig::normalizeUrl)?.let { runCatching { java.net.URI(it).host }.getOrNull() }
        if (old != new) { KaizenSettings.syncPassword = null; KaizenSettings.syncUser = null }
        KaizenSettings.syncUrl = value?.takeIf { it.isNotBlank() }
    }

    internal fun store(): RemoteStore =
        if (viaSmb) SmbStore(SmbBackup.remote(), app.kaizen.App.context.cacheDir)
        else WebDavStore(checkNotNull(url) { "sin destino de sincronización" }, KaizenSettings.syncUser.orEmpty(), KaizenSettings.syncPassword.orEmpty())

    private val lock = kotlinx.coroutines.sync.Mutex()

    /** Una copia sin actualizar desde hace tanto se da por abandonada (otra tele que ya no existe): se funde una última vez y se borra del servidor. */
    private const val MIN_PLAUSIBLE = 1_577_836_800_000L // 1-ene-2020

    private val ABANDONED = 180.days

    /** El dispositivo de verdad: la base de datos de la app (ver [Removals.step]). */
    private object Device : Removals.Local<KaizenBackup.Data> {
        override suspend fun presence(): Removals.Presence = KaizenBackup.presence()
        override suspend fun join(item: KaizenBackup.Data) = KaizenBackup.joinGroups(item)
        override suspend fun merge(item: KaizenBackup.Data, skip: (String) -> Boolean) { KaizenBackup.merge(item, skip) }
        override suspend fun remove(keys: List<String>) = KaizenBackup.remove(keys)
        override fun canon(key: String) = KaizenBackup.canon(key)
    }

    /**
     * Sincroniza ahora (una a la vez: la del botón y la diaria no se pisan). Falla (con el motivo) si no hay destino o el servidor no responde. Lee las copias de los demás, las
     * funde (añade lo que falta y quita lo que otro quitó a propósito, ver [Removals.step]) y sube la propia con las lápidas de lo que se quitó aquí. Las copias de dispositivos
     * abandonados (más de [ABANDONED] sin actualizar) se funden y se borran del servidor.
     */
    suspend fun now(): DeviceSync.Report = lock.withLock { withContext(Dispatchers.IO) {
        val state = Removals.load()
        val incoming = mutableListOf<KaizenBackup.Data>()
        var outcome: Removals.Outcome? = null
        val report = DeviceSync.run(
            store(), deviceId,
            // Aquí solo se lee y se valida; se funde todo junto en `finish`, cuando ya se conocen las lápidas de todas las copias.
            mergeOther = { bytes -> KaizenBackup.decode(String(bytes, Charsets.UTF_8))?.also { incoming += it } != null },
            ownJson = { KaizenBackup.snapshotBytes(outcome?.removed.orEmpty()) },
            finish = {
                val done = Removals.step(state, System.currentTimeMillis(), Device, incoming, { it.createdAt }, { it.removed })
                outcome = done
                if (done.heldRemote) DebugLog.error("Datos", "Sincronización: otro dispositivo quitó demasiado de una vez; no se aplica (parece un restablecer)")
                Removals.save(done.state)
            },
            // Ni con un borrado masivo retenido, ni una copia con la fecha de un reloj roto (antes de 2020).
            canDelete = { outcome?.heldRemote != true },
            isAbandoned = { bytes -> KaizenBackup.decode(String(bytes, Charsets.UTF_8))?.createdAt?.let { it > MIN_PLAUSIBLE && System.currentTimeMillis() - it > ABANDONED.inWholeMilliseconds } == true }
        )
        val done = outcome
        val full = DeviceSync.Report(report.merged, report.skipped, report.uploadedBytes, done?.removedHere ?: 0, (done?.heldBack ?: false) || (done?.heldRemote ?: false), report.deleted)
        DebugLog.info("Datos", "Sincronización: $full")
        // Las uniones con AniList viajan con el catálogo; un fallo aquí no invalida la sincronización de estados.
        runCatchingCoop {
            val share = AnilistShare.run(store(), UserDB.INSTANCE.animeFuenteDao(), CatalogDB.INSTANCE.animeFichaDao(), CatalogDB.INSTANCE.fichaExportDao())
            DebugLog.info("Datos", share.toString())
            // Con enlaces nuevos, las series de distintas webs que son el mismo anime se unen.
            if (share.linksIn > 0) runCatchingCoop { CatalogBuilder.regroup() }.onFailure(::logError)
        }.onFailure(::logError)
        KaizenSettings.syncLastAt = System.currentTimeMillis()
        full
    } }
}
