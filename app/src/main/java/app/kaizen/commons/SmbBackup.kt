package app.kaizen.commons

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Copias de seguridad en una carpeta de red SMB (un NAS): las mismas copias locales (`kaizen-backup-*.json`, estados, y `kaizen-ajustes-*.json`, ajustes) se **añaden** a la carpeta y se
 * pueden bajar de ella para restaurar en otro equipo. Nunca borra ni sobrescribe una copia que ya esté (los nombres llevan fecha y hora): solo poda las más viejas de la carpeta
 * según «Máximo de copias». Es lo que permite pasar los datos de una tele a otra sin servidor WebDAV.
 */
object SmbBackup {
    private val PREFIXES = listOf("kaizen-backup-", "kaizen-ajustes-")

    val configured: Boolean get() = !KaizenSettings.smbUrl.isNullOrBlank()

    internal fun remote(): RemoteFolder = folder()

    private fun folder(): RemoteFolder = SmbFolder(checkNotNull(KaizenSettings.smbUrl) { "sin carpeta de red" }, KaizenSettings.smbUser, KaizenSettings.smbPassword)

    private fun isBackupName(name: String) = name.endsWith(".json") && PREFIXES.any { name.startsWith(it) }

    /** Las copias locales que la carpeta aún no tiene (por nombre). */
    internal fun toUpload(local: List<File>, remote: List<RemoteFolder.Entry>): List<File> {
        val have = remote.map { it.name }.toSet()
        return local.filter { isBackupName(it.name) && it.name !in have }.sortedBy { it.name }
    }

    /** Las copias de la carpeta que este equipo aún no tiene (por nombre). */
    internal fun toDownload(remote: List<RemoteFolder.Entry>, local: List<File>): List<RemoteFolder.Entry> {
        val have = local.map { it.name }.toSet()
        return remote.filter { isBackupName(it.name) && it.name !in have }.sortedBy { it.name }
    }

    /** Nombres a quitar de la carpeta para que de cada tipo de copia queden las [keep] más recientes (los nombres llevan la fecha, así que ordenar por nombre es ordenar por fecha). */
    internal fun toPrune(names: List<String>, keep: Int): List<String> =
        PREFIXES.flatMap { prefix -> names.filter { isBackupName(it) && it.startsWith(prefix) }.sortedDescending().drop(keep.coerceAtLeast(1)) }

    /** Sube a la carpeta las copias locales que le faltan y poda las más viejas; devuelve cuántas subió. */
    suspend fun push(remote: RemoteFolder = folder(), local: File = KaizenBackup.directory, keep: Int = KaizenSettings.smbKeep): Int = withContext(Dispatchers.IO) {
        val have = remote.list()
        val send = toUpload(local.listFiles().orEmpty().toList(), have)
        send.forEach { remote.upload(it) }
        val names = have.map { it.name } + send.map { it.name }
        val prune = toPrune(names, keep)
        prune.forEach { remote.delete(it) }
        DebugLog.info("Carpeta de red", "Copias subidas: ${send.size}; podadas en la carpeta: ${prune.size}")
        send.size
    }

    /** Baja de la carpeta las copias que faltan aquí y devuelve cuántas. Con la fecha de la carpeta, para que «la más reciente» siga siendo la más reciente. */
    suspend fun pull(remote: RemoteFolder = folder(), local: File = KaizenBackup.directory): Int = withContext(Dispatchers.IO) {
        local.mkdirs()
        val get = toDownload(remote.list(), local.listFiles().orEmpty().toList())
        get.forEach { entry -> File(local, entry.name).also { remote.download(entry.name, it); if (entry.modified > 0) it.setLastModified(entry.modified) } }
        DebugLog.info("Carpeta de red", "Copias bajadas: ${get.size}")
        get.size
    }

    /** Baja lo que falta y restaura los estados de la copia más completa (solo añade, como siempre); null si ninguna copia vale. */
    suspend fun pullAndRestore(): Pair<Int, KaizenBackup.Summary?> {
        val downloaded = pull()
        val summary = KaizenBackup.restoreBest()
        DebugLog.info("Carpeta de red", if (summary == null) "Restaurar: ninguna copia válida" else "Restaurar: copias bajadas $downloaded y datos restaurados")
        return downloaded to summary
    }
}
