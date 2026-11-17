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
 * Copia del libro de series (qué ficha es de qué serie y qué ids se unieron a otros), aparte de la base de datos y de la fotografía del
 * catálogo (que solo existe si el catálogo se ha recorrido). Las reglas del usuario ([UserTitles]) nombran las series por esos ids: si la
 * base se vacía y los ids se reparten de nuevo, las reglas quedan apuntando a otras series. Con esta copia los ids vuelven a ser los mismos.
 * Mismos cuidados que la fotografía: campos con nombre propio y `@Keep` (lo escribe una compilación y lo lee otra).
 */
@Keep
class LedgerBackup(
    @SerializedName("version") val version: Int,
    @SerializedName("createdAt") val createdAt: Long,
    @SerializedName("map") val map: List<SnapMap>?,
    @SerializedName("redirects") val redirects: List<SnapRedirect>?
) {
    /** Sin fichas o con filas dañadas (Gson deja nulos en un fichero roto) no se usa. */
    fun usable(): Boolean {
        fun ok(text: String?) = !text.isNullOrBlank()
        return version == VERSION && !map.isNullOrEmpty() &&
            map.all { ok(it.sourceId as String?) && ok(it.slug as String?) && it.seriesId > 0 } &&
            redirects.orEmpty().all { it.fromId > 0 && it.toId > 0 }
    }

    companion object {
        const val VERSION = 1
    }
}

/** Dónde se guarda la copia del libro; en las pruebas, en memoria. */
internal interface LedgerBackups {
    /** null si no hay o no se puede leer. */
    fun read(): LedgerBackup?

    fun write(backup: LedgerBackup)
}

/** Un fichero comprimido junto a las copias de seguridad (su nombre no empieza por `kaizen-backup-`: no cuenta como copia ni caduca). */
internal object LedgerBackupFile : LedgerBackups {
    private val file get() = File(KaizenBackup.directory, "series-ledger.json.gz")

    override fun read(): LedgerBackup? = runCatching {
        file.takeIf { it.exists() }?.let { f -> f.inputStream().use { input -> GZIPInputStream(input).bufferedReader().use { Gson().fromJson(it, LedgerBackup::class.java) } } }
    }.getOrNull()

    /**
     * Se escribe aparte y se renombra al final: si falla a medias, la copia anterior sigue intacta. La anterior se guarda como
     * `series-ledger.prev.json.gz`: si el libro se rehízo con otros ids antes de restaurar la copia, la del día anterior sigue siendo buena.
     */
    override fun write(backup: LedgerBackup) {
        val dir = file.parentFile ?: error("sin carpeta de copias")
        dir.mkdirs()
        val tmp = File(dir, "series-ledger.tmp")
        FileOutputStream(tmp).use { out ->
            // Se cierra el GZIPOutputStream (libera su Deflater nativo); el fichero se sincroniza antes, con el flujo aún abierto.
            GZIPOutputStream(out).use { gzip ->
                val writer = gzip.bufferedWriter()
                Gson().toJson(backup, writer)
                writer.flush()
                gzip.finish()
                out.fd.sync() // en disco antes de sustituir la anterior: un corte de luz no la deja truncada
            }
        }
        if (file.exists()) file.copyTo(File(dir, "series-ledger.prev.json.gz"), overwrite = true)
        check(tmp.renameTo(file)) { "no se pudo guardar la copia del libro de series" }
    }
}
