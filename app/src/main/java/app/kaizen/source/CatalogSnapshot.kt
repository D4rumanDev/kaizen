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
 * Fotografía del catálogo interno y de los ids de serie, para rehacer el catálogo sin recorrer las fuentes (cientos de peticiones)
 * si la base de datos se vacía. Las clases llevan nombres de campo propios y `@Keep`: el fichero lo escribe una compilación y
 * lo lee otra, y R8 no puede cambiarlos entre una y otra.
 */
@Keep
class CatalogSnapshot(
    @SerializedName("version") val version: Int,
    /** Versión de las reglas de unión con las que se hizo ([CatalogStateStore.RULES_VERSION]): con otras no se usa. */
    @SerializedName("rules") val rules: Int,
    @SerializedName("createdAt") val createdAt: Long,
    @SerializedName("entries") val entries: List<SnapEntry>?,
    @SerializedName("links") val links: List<SnapLink>?,
    @SerializedName("map") val map: List<SnapMap>?,
    @SerializedName("redirects") val redirects: List<SnapRedirect>?,
    /** Estado de recorrido de cada fuente (`SourceState.encode`). */
    @SerializedName("states") val states: Map<String, String>?
) {
    /** Falta algo de lo necesario o es de otra versión: no se usa. */
    fun usable(rulesNow: Int): Boolean =
        version == VERSION && rules == rulesNow && !entries.isNullOrEmpty() && !links.isNullOrEmpty() && map != null && rowsValid()

    // Gson puede dejar nulos o ceros en campos que no lo admiten si el fichero está dañado o es de otra compilación: se descarta entero.
    private fun rowsValid(): Boolean {
        fun ok(text: String?) = !text.isNullOrBlank()
        return entries.orEmpty().all { ok(it.key as String?) && ok(it.name as String?) && it.seriesId > 0 } &&
            links.orEmpty().all { ok(it.key as String?) && ok(it.sourceId as String?) && ok(it.slug as String?) && it.seriesId > 0 } &&
            map.orEmpty().all { ok(it.sourceId as String?) && ok(it.slug as String?) && it.seriesId > 0 } &&
            redirects.orEmpty().all { it.fromId > 0 && it.toId > 0 }
    }

    companion object {
        const val VERSION = 1
    }
}

@Keep
class SnapEntry(
    @SerializedName("key") val key: String,
    @SerializedName("seriesId") val seriesId: Long,
    @SerializedName("name") val name: String,
    @SerializedName("score") val score: Double?,
    @SerializedName("votes") val votes: Int,
    @SerializedName("year") val year: Int?,
    @SerializedName("updatedAt") val updatedAt: Long
) {
    fun toEntity() = CatalogEntry(key, seriesId, name, score, votes, year, updatedAt)
}

@Keep
class SnapLink(
    @SerializedName("key") val key: String,
    @SerializedName("sourceId") val sourceId: String,
    @SerializedName("slug") val slug: String,
    @SerializedName("aid") val aid: Int,
    @SerializedName("seriesId") val seriesId: Long,
    @SerializedName("year") val year: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("score") val score: Double?,
    @SerializedName("votes") val votes: Int
) {
    fun toEntity() = CatalogLink(key, sourceId, slug, aid, seriesId, year, name, score, votes)
}

@Keep
class SnapMap(
    @SerializedName("sourceId") val sourceId: String,
    @SerializedName("slug") val slug: String,
    @SerializedName("seriesId") val seriesId: Long
)

@Keep
class SnapRedirect(
    @SerializedName("fromId") val fromId: Long,
    @SerializedName("toId") val toId: Long
)

internal fun CatalogEntry.toSnap() = SnapEntry(key, seriesId, name, score, votes, year, updatedAt)
internal fun CatalogLink.toSnap() = SnapLink(key, sourceId, slug, aid, seriesId, year, name, score, votes)

/** Dónde se guarda la fotografía; en las pruebas, en memoria. */
internal interface CatalogSnapshots {
    /** null si no hay, no se puede leer o no es válida. */
    fun read(): CatalogSnapshot?

    /** Cuándo se hizo la fotografía guardada (sin leerla entera); null si no hay. */
    fun createdAt(): Long?

    fun write(snapshot: CatalogSnapshot)
}

/** Un solo fichero comprimido junto a las copias de seguridad (su nombre no empieza por `kaizen-backup-`: no cuenta como copia). */
internal object CatalogSnapshotFile : CatalogSnapshots {
    private val file get() = File(KaizenBackup.directory, "catalog-snapshot.json.gz")

    override fun createdAt(): Long? = file.takeIf { it.exists() }?.lastModified()

    override fun read(): CatalogSnapshot? = runCatching {
        file.takeIf { it.exists() }?.let { f -> f.inputStream().use { input -> GZIPInputStream(input).bufferedReader().use { Gson().fromJson(it, CatalogSnapshot::class.java) } } }
    }.getOrNull()

    /** Se escribe aparte y se renombra al final: si falla a medias, la fotografía anterior sigue intacta. */
    override fun write(snapshot: CatalogSnapshot) {
        val dir = file.parentFile ?: error("sin carpeta de copias")
        dir.mkdirs()
        val tmp = File(dir, "catalog-snapshot.tmp")
        FileOutputStream(tmp).use { out ->
            // Se cierra el GZIPOutputStream (libera su Deflater nativo); el fichero se sincroniza antes, con el flujo aún abierto.
            GZIPOutputStream(out).use { gzip ->
                val writer = gzip.bufferedWriter()
                Gson().toJson(snapshot, writer)
                writer.flush()
                gzip.finish()
                out.fd.sync() // en disco antes de sustituir la fotografía anterior: un corte de luz no la deja truncada
            }
        }
        check(tmp.renameTo(file)) { "no se pudo guardar la fotografía del catálogo" }
    }
}
