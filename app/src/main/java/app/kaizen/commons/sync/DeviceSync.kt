package app.kaizen.commons.sync

/**
 * Un lugar remoto donde cada dispositivo deja su propia copia de estados y lee las de los demás (hoy, un servidor WebDAV: Nextcloud, un NAS…). Solo guarda ficheros por nombre.
 */
interface RemoteStore {
    /** Los nombres de los ficheros que hay. */
    suspend fun list(): List<String>

    /** El contenido de [name]; null si no existe. */
    suspend fun read(name: String): ByteArray?

    /** Crea o reemplaza [name] con [bytes]. */
    suspend fun write(name: String, bytes: ByteArray)

    /** Borra [name] (que no exista no es un error). Por defecto no hace nada. */
    suspend fun delete(name: String) {}
}

/**
 * **Sincronizar varios dispositivos sin que dos escriban el mismo fichero**: cada dispositivo escribe solo el suyo (`kaizen-estados-<id>.json`) y lee los de los demás, que
 * funde en lo suyo: añade lo que falta (como la restauración de [app.kaizen.commons.KaizenBackup]) y quita lo que otro dispositivo quitó a propósito ([Removals]). Repetirlo no
 * cambia nada más. El orden importa: primero se leen los de los demás, [finish] los funde, y después se sube el propio, que así ya lleva lo de todos.
 *
 * Los ajustes y el catálogo no se sincronizan aquí: los ajustes tienen su copia aparte y el catálogo sale del repositorio.
 */
object DeviceSync {
    const val PREFIX = "kaizen-estados-"
    const val SUFFIX = ".json"
    private val NAME = Regex("kaizen-estados-([0-9a-f]{8})[.]json")

    /** El nombre del fichero de un dispositivo. */
    fun fileName(deviceId: String): String = "$PREFIX$deviceId$SUFFIX"

    /** El id de dispositivo de un nombre de fichero de copia, o null si no es de los nuestros (ni de un servidor que devuelve rutas largas). */
    fun deviceOf(name: String): String? = NAME.matchEntire(name.substringAfterLast('/'))?.groupValues?.get(1)

    /** Un id de dispositivo nuevo: 8 cifras hexadecimales al azar. */
    fun newDeviceId(random: kotlin.random.Random = kotlin.random.Random.Default): String = (1..8).joinToString("") { "0123456789abcdef"[random.nextInt(16)].toString() }

    /** Qué pasó en una sincronización. */
    class Report(val merged: Int, val skipped: Int, val uploadedBytes: Int, val removed: Int = 0, val heldBack: Boolean = false, val deleted: Int = 0) {
        override fun toString() = "fundidos $merged dispositivo(s)" + (if (skipped > 0) ", $skipped copia(s) ajena(s) no válida(s) omitida(s)" else "") +
            (if (removed > 0) ", $removed quitado(s) por otro dispositivo" else "") + (if (heldBack) ", borrado masivo retenido (no se propaga)" else "") + (if (deleted > 0) ", $deleted copia(s) de dispositivos abandonados borrada(s)" else "") + ", subida la propia ($uploadedBytes bytes)"
    }

    /**
     * Sincroniza con [store]: lee las copias de los demás dispositivos ([deviceId] es el propio), las funde con [mergeOther] (true si era una copia válida), y después sube la propia
     * con [ownJson]. Una copia ajena ilegible, enorme o no válida se omite y no impide las demás; un fallo al subir la propia sí se propaga (no se dice «hecho» si no se hizo).
     */
    suspend fun run(
        store: RemoteStore, deviceId: String, mergeOther: suspend (ByteArray) -> Boolean, ownJson: suspend () -> ByteArray, maxBytes: Int = MAX_BYTES, finish: suspend () -> Unit = {},
        isAbandoned: (ByteArray) -> Boolean = { false },
        canDelete: () -> Boolean = { true }
    ): Report {
        val own = fileName(deviceId)
        var merged = 0
        var skipped = 0
        val abandoned = mutableListOf<String>()
        for (name in store.list().mapNotNull { n -> deviceOf(n)?.takeIf { it != deviceId }?.let(::fileName) }.distinct()) {
            val bytes = runCatching { store.read(name) }.getOrNull()
            if (bytes == null) { skipped++; continue }
            if (bytes.size > maxBytes || !runCatching { mergeOther(bytes) }.getOrDefault(false)) skipped++ else {
                merged++
                if (runCatching { isAbandoned(bytes) }.getOrDefault(false)) abandoned += name
            }
        }
        finish()
        val mine = ownJson()
        require(mine.size <= maxBytes) { "la copia propia es demasiado grande (${mine.size} bytes)" }
        store.write(own, mine)
        // Solo después de fundirla y de subir lo propio (que ya la lleva): una copia abandonada se borra; si falla, se intentará la próxima vez.
        val deleted = if (!canDelete()) 0 else abandoned.count { runCatching { store.delete(it) }.isSuccess }
        return Report(merged, skipped, mine.size, deleted = deleted)
    }

    /** Tope de una copia: las de estados son de cientos de KB; esto evita que un servidor ajeno llene la memoria. */
    const val MAX_BYTES = 16 * 1024 * 1024
}
