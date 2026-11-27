package app.kaizen.source

import androidx.annotation.Keep
import app.kaizen.commons.HostGate
import app.kaizen.source.spec.SpecBundle
import app.kaizen.source.spec.RepoConfig
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * Enlaces de partida con AniList para una instalación limpia: el repositorio de fuentes (`enlaces-anilist.json.gz` y su firma `enlaces-anilist.sig`, con la misma clave que las
 * fuentes por fichero, [SpecSources.PUBLIC_KEY]) lleva los enlaces automáticos y aplicados que ya se emparejaron, y la app los guarda al empezar en lugar de buscar cada ficha
 * en AniList. Cuidados:
 *  - **Firmado**: sin la firma de la clave privada no se usa nada (unos enlaces falsos mezclarían series sin avisar).
 *  - **Solo con la tabla de enlaces vacía**: no hace nada en una instalación que ya empareja, y lo guarda con `insertIfAbsent` (nunca pisa un enlace, ni uno del usuario).
 *  - **Solo del mismo criterio**: el fichero dice con qué versión de [AnilistMatcher.VERSION] se hizo; con otra, no se usa.
 *  - **Solo lo automático y aplicado**: nunca trae decisiones del usuario, propuestas ni «sin resultado».
 *  Solo con «Descargar del repositorio» activado (ver [RepoConfig]).
 */
internal object AnilistSeed {
    const val VERSION = 1
    const val FILE = "enlaces-anilist.json.gz"
    const val SIGNATURE = "enlaces-anilist.sig"

    private const val MAX_COMPRESSED = 4 * 1024 * 1024
    private const val MAX_UNCOMPRESSED = 24 * 1024 * 1024
    private const val MAX_ROWS = 200_000
    private val LEVELS = setOf("seguro", "probable", AnimeFuente.NIVEL_MAL, AnimeFuente.NIVEL_WEB, "datos", "mismo-id")
    private val NAME = Regex("[a-z0-9]{2,20}")

    @Keep
    class SeedFile(
        @SerializedName("version") val version: Int,
        @SerializedName("matcherVersion") val matcherVersion: Int,
        @SerializedName("links") val links: List<Link>?
    ) {
        @Keep
        class Link(
            @SerializedName("fuente") val fuente: String?,
            @SerializedName("slug") val slug: String?,
            @SerializedName("anilistId") val anilistId: Long?,
            @SerializedName("nivel") val nivel: String?,
            @SerializedName("puntuacion") val puntuacion: Double?
        )
    }

    /**
     * Las filas del fichero [data] (gzip) si su [signature] vale con [publicKey] y todo cuadra: versión del formato, [matcherVersion] y cada fila (fuente, slug, id y nivel conocidos).
     * Cualquier fallo devuelve null y no se usa nada. Las filas salen como automáticas y aplicadas, con la fecha [now].
     */
    fun rows(data: ByteArray, signature: ByteArray, publicKey: String, matcherVersion: Int, now: Long): List<AnimeFuente>? {
        if (data.size > MAX_COMPRESSED || !SpecBundle.verify(publicKey, data, signature)) return null
        val file = runCatching { Gson().fromJson(String(unzip(data) ?: return null, Charsets.UTF_8), SeedFile::class.java) }.getOrNull() ?: return null
        if (file.version != VERSION || file.matcherVersion != matcherVersion) return null
        val links = file.links?.takeIf { it.isNotEmpty() && it.size <= MAX_ROWS } ?: return null
        return links.map { l ->
            val fuente = l.fuente?.takeIf { NAME.matches(it) } ?: return null
            val slug = l.slug?.takeIf { it.isNotBlank() && it.length <= 300 } ?: return null
            val id = l.anilistId?.takeIf { it > 0 } ?: return null
            val level = l.nivel?.takeIf { it in LEVELS } ?: return null
            AnimeFuente(fuente, slug, id, AnimeFuente.APLICADO, AnimeFuente.AUTO, level, (l.puntuacion ?: 1.0).coerceIn(0.0, 2.0), now)
        }
    }

    /** Descomprime con tope: un fichero que crece más allá de [MAX_UNCOMPRESSED] se rechaza. */
    private fun unzip(data: ByteArray): ByteArray? = runCatching {
        GZIPInputStream(ByteArrayInputStream(data)).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > MAX_UNCOMPRESSED) return null
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
    }.getOrNull()

    /**
     * Si la tabla de enlaces [dao] está vacía, descarga el fichero ([fetch] da los bytes de una dirección) y guarda sus enlaces. Devuelve cuántos añadió (0 si no estaba vacía, si no
     * hay descarga o si el fichero no vale).
     */
    fun seedIfEmpty(dao: AnimeFuenteDao, matcherVersion: Int, now: Long, publicKey: String = RepoConfig.publicKey, fetch: (String) -> ByteArray): Int {
        if (dao.all().isNotEmpty()) return 0
        val data = runCatching { fetch(RepoConfig.baseUrl + FILE) }.getOrNull() ?: return 0
        val signature = runCatching { fetch(RepoConfig.baseUrl + SIGNATURE) }.getOrNull() ?: return 0
        val rows = rows(data, signature, publicKey, matcherVersion, now) ?: return 0
        dao.insertIfAbsent(rows)
        return rows.size
    }

    /** La descarga de verdad: por el turno de `HostGate` y con tope de tamaño. */
    fun download(url: String): ByteArray = HostGate.call(url) { SourceHttp.execute(url) { maxBodySize(MAX_COMPRESSED + 1) }.bodyAsBytes() }
}
