package app.kaizen.source

import androidx.annotation.Keep
import app.kaizen.commons.KaizenBackup
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Copia de los enlaces de cada ficha con AniList ([AnimeFuente]): lo que decidió el sistema y, sobre todo, lo que decidió el usuario, que solo
 * vive en `user-db` y no sale en la copia JSON de favoritos y vistos. Es la red de seguridad para cambiar el esquema de esas tablas: se pueden
 * volver a poner con [AnilistLinksBackups.restoreMissing]. Mismos cuidados que [LedgerBackup]: campos con nombre propio y `@Keep` (lo escribe
 * una compilación y lo lee otra).
 */
@Keep
class AnilistLinksBackup(
    @SerializedName("version") val version: Int,
    @SerializedName("createdAt") val createdAt: Long,
    @SerializedName("links") val links: List<Link>?
) {
    @Keep
    class Link(
        @SerializedName("fuente") val fuente: String,
        @SerializedName("slug") val slug: String,
        @SerializedName("anilistId") val anilistId: Long?,
        @SerializedName("estado") val estado: String,
        @SerializedName("origen") val origen: String,
        @SerializedName("nivel") val nivel: String,
        @SerializedName("puntuacion") val puntuacion: Double,
        @SerializedName("fecha") val fecha: Long
    )

    /** Sin filas o con filas dañadas (Gson deja nulos en un fichero roto) no se usa. */
    fun usable(): Boolean = version == VERSION && !links.isNullOrEmpty() &&
        links.all { !(it.fuente as String?).isNullOrBlank() && !(it.slug as String?).isNullOrBlank() && !(it.estado as String?).isNullOrBlank() && !(it.origen as String?).isNullOrBlank() }

    fun rows(): List<AnimeFuente> = links.orEmpty().map { AnimeFuente(it.fuente, it.slug, it.anilistId, it.estado, it.origen, it.nivel, it.puntuacion, it.fecha) }

    companion object {
        const val VERSION = 1
        fun of(rows: List<AnimeFuente>, at: Long) =
            AnilistLinksBackup(VERSION, at, rows.map { Link(it.fuente, it.slug, it.anilistId, it.estado, it.origen, it.nivel, it.puntuacion, it.fecha) })
    }
}

/** El fichero comprimido junto a las copias de seguridad (su nombre no empieza por `kaizen-backup-`: no cuenta como copia ni caduca). */
internal object AnilistLinksBackupFile {
    /** La carpeta de las copias; las pruebas la sustituyen. */
    internal var directory: () -> File = { KaizenBackup.directory }
    private val file get() = File(directory(), "anilist-links.json.gz")
    private val previous get() = File(directory(), "anilist-links.prev.json.gz")

    private fun load(f: File): AnilistLinksBackup? = runCatching {
        // El flujo del fichero se cierra aunque no sea un gzip válido (el constructor de GZIPInputStream lanza y, si no, quedaría abierto).
        f.takeIf { it.isFile }?.inputStream()?.use { input -> GZIPInputStream(input).bufferedReader().use { r -> Gson().fromJson(r, AnilistLinksBackup::class.java) } }
    }.getOrNull()

    /** La copia buena más reciente: la principal y, si falta o está dañada, la anterior (`.prev`). */
    fun read(): AnilistLinksBackup? = load(file)?.takeIf { it.usable() } ?: load(previous)?.takeIf { it.usable() }

    /**
     * Se escribe aparte y se renombra al final; la anterior queda como `anilist-links.prev.json.gz`, pero solo si la principal es buena: una
     * principal dañada no sustituye a una `.prev` buena. Lanza si no puede dejar la copia nueva en su sitio.
     */
    fun write(backup: AnilistLinksBackup) {
        val dir = file.parentFile ?: error("sin carpeta de copias")
        dir.mkdirs()
        val tmp = File(dir, "anilist-links.tmp")
        FileOutputStream(tmp).use { out ->
            // Se cierra el GZIPOutputStream (libera su Deflater nativo); el fichero se sincroniza antes, con el flujo aún abierto.
            GZIPOutputStream(out).use { gzip ->
                val writer = gzip.bufferedWriter()
                Gson().toJson(backup, writer)
                writer.flush()
                gzip.finish()
                out.fd.sync()
            }
        }
        if (load(file)?.usable() == true) file.copyTo(previous, overwrite = true)
        // En Android `renameTo` sustituye al existente; en otros sistemas (pruebas en Windows) no: se reintenta tras borrarlo (la anterior ya está en `.prev`).
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "no se pudo guardar la copia de los enlaces con AniList" }
        }
    }
}

internal object AnilistLinksBackups {
    /** Guarda la copia con todas las filas. Una tabla vacía no sustituye a una copia buena. La hace cada copia de seguridad. */
    fun save(dao: AnimeFuenteDao, now: Long = System.currentTimeMillis()): Int {
        val rows = dao.all()
        if (rows.isEmpty()) return 0
        AnilistLinksBackupFile.write(AnilistLinksBackup.of(rows, now))
        return rows.size
    }

    /** Vuelve a poner las filas de la copia que falten; lo que ya existe (y lo que el usuario cambió después) no se toca. Devuelve cuántas añadió. */
    fun restoreMissing(dao: AnimeFuenteDao): Int {
        val backup = AnilistLinksBackupFile.read()?.takeIf { it.usable() } ?: return 0
        val before = dao.all().size
        dao.insertIfAbsent(backup.rows())
        return dao.all().size - before
    }
}
