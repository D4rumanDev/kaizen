package app.kaizen.source

import androidx.annotation.Keep
import app.kaizen.commons.HostGate
import app.kaizen.source.spec.SpecBundle
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.time.Duration.Companion.days

/**
 * **Catálogo unificado descargable**, para que una instalación limpia no recorra las fuentes (cientos de peticiones) para tener el catálogo. Se publica junto a las fuentes por
 * fichero: `catalogo-unificado.json.gz` y su firma `catalogo-unificado.sig` en el repositorio `kaizen-sources`, con la misma clave ([SpecSources.PUBLIC_KEY]).
 *
 * El fichero es **portable**: no lleva ningún id local (los ids de serie de una instalación no valen en otra). Cada serie va con su nombre, año, nota y las fichas
 * (fuente + slug) que son la misma serie; quien lo lee asigna sus propios ids al aplicarlo. Lo genera `tools/make-catalog-seed.py` a partir de la fotografía del catálogo
 * (`catalog-snapshot.json.gz`) que saca `tv.js backup`.
 *
 * **Solo datos, nunca código**: el fichero se lee con Gson a clases de datos fijas (texto, números y listas; ningún tipo lo elige el fichero), se valida cada campo con
 * listas cerradas o expresiones simples (fuentes conocidas, slug `[A-Za-z0-9._~-]`, nombre sin caracteres de control) y nada se interpreta, ejecuta ni carga: ni en local
 * (no hay `eval`, reflexión por nombre, WebView ni descarga de clases) ni en remoto (solo se usan para pedir páginas a las webs de las fuentes ya instaladas, con su host fijo).
 *
 * **Neutro**: cada serie es un anime de AniList (`anilistId`) con las fichas de las webs que lo son, o una ficha sin emparejar sola; no lleva las uniones ni separaciones de
 * nadie. Al aplicarlo, los `anilistId` se guardan como enlaces automáticos («mismo-id») sin pisar ninguno que la instalación ya tenga (ni los del usuario), y las reglas locales
 * agrupan con ellos como siempre.
 *
 * Cuidados, los mismos que [AnilistSeed]:
 *  - **Firmado**: sin la firma de la clave privada no se usa nada (un catálogo falso mezclaría series sin avisar).
 *  - **Con topes** de tamaño comprimido, descomprimido, series y fichas por serie; cada fila se valida (fuente conocida, slug razonable) y una mala descarta el fichero entero.
 *
 * Se aplica en [CatalogBuilder.ensureFresh] solo con el catálogo vacío (y sin fotografía propia): [CatalogBuildCore.seedIfEmpty] lo escribe con los ids locales de la instalación.
 */
internal object CatalogSeed {
    /** 2: las series se agrupan por el id de AniList (neutro, sin las reglas de nadie) y llevan su `anilistId`; ya no depende de las reglas de unión locales. */
    const val VERSION = 2
    const val FILE = "catalogo-unificado.json.gz"
    const val SIGNATURE = "catalogo-unificado.sig"

    private const val MAX_COMPRESSED = 6 * 1024 * 1024
    private const val MAX_UNCOMPRESSED = 40 * 1024 * 1024
    private const val MAX_SERIES = 100_000
    private const val MAX_FICHAS = 30
    private const val MAX_ANILIST_ID = 100_000_000L
    private val NAME = Regex("[a-z0-9]{2,20}")
    private val SLUG = Regex("[A-Za-z0-9._~-]{1,400}")
    private val MAX_FUTURE = 1.days

    @Keep
    class SeedFile(
        @SerializedName("version") val version: Int,
        /** Cuándo se generó (milisegundos): una app no aplica un fichero más antiguo que el que ya aplicó (un fichero viejo, aunque esté firmado, no la hace retroceder). */
        @SerializedName("createdAt") val createdAt: Long?,
        @SerializedName("series") val series: List<Series>?
    ) {
        @Keep
        class Series(
            @SerializedName("name") val name: String?,
            @SerializedName("year") val year: Int?,
            @SerializedName("score") val score: Double?,
            @SerializedName("votes") val votes: Int?,
            /** El anime de AniList que es la serie (todas sus fichas lo son); sin él, una serie de ficha única sin emparejar. */
            @SerializedName("anilistId") val anilistId: Long?,
            @SerializedName("fichas") val fichas: List<Ficha?>?
        )

        @Keep
        class Ficha(
            @SerializedName("fuente") val fuente: String?,
            @SerializedName("slug") val slug: String?
        )
    }

    /** Una serie ya validada: su nombre y las fichas que la forman. */
    class Entry(val name: String, val year: Int?, val score: Double?, val votes: Int, val fichas: List<Pair<String, String>>, val anilistId: Long? = null)

    /** El catálogo ya validado y cuándo se generó. */
    class Seed(val createdAt: Long, val entries: List<Entry>)

    /** Solo las series (para las pruebas y quien no necesite la fecha). */
    fun entries(data: ByteArray, signature: ByteArray, publicKey: String, knownSources: Set<String>, now: Long = System.currentTimeMillis()): List<Entry>? =
        read(data, signature, publicKey, knownSources, now)?.entries

    /**
     * Las series de [data] (gzip) si su [signature] vale con [publicKey] y todo cuadra: versión del formato y cada fila. Las fuentes deben estar en [knownSources].
     * Cualquier fallo devuelve null y no se usa nada.
     */
    fun read(data: ByteArray, signature: ByteArray, publicKey: String, knownSources: Set<String>, now: Long = System.currentTimeMillis()): Seed? {
        if (data.size > MAX_COMPRESSED || !SpecBundle.verify(publicKey, data, signature)) return null
        val file = runCatching { Gson().fromJson(String(unzip(data) ?: return null, Charsets.UTF_8), SeedFile::class.java) }.getOrNull() ?: return null
        if (file.version != VERSION) return null
        val createdAt = file.createdAt?.takeIf { it > 0 && it <= now + MAX_FUTURE.inWholeMilliseconds } ?: return null
        val series = file.series?.takeIf { it.isNotEmpty() && it.size <= MAX_SERIES } ?: return null
        val entries = series.map { s ->
            val name = s.name?.takeIf { it.isNotBlank() && it.length <= 300 && it.none { c -> c.isISOControl() || Character.getType(c) == Character.FORMAT.toInt() } } ?: return null
            val fichas = (s.fichas ?: return null).takeIf { it.isNotEmpty() && it.size <= MAX_FICHAS }?.map { f ->
                val fuente = f?.fuente?.takeIf { NAME.matches(it) && it in knownSources } ?: return null
                val slug = f.slug?.takeIf { SLUG.matches(it) && it != "." && it != ".." } ?: return null
                fuente to slug
            } ?: return null
            Entry(name, s.year?.takeIf { it in 1900..2100 }, s.score?.takeIf { it.isFinite() && it in 0.0..10.0 }, (s.votes ?: 0).coerceAtLeast(0), fichas.distinct(),
                s.anilistId?.also { id -> if (id !in 1..MAX_ANILIST_ID) return null })
        }
        return Seed(createdAt, entries)
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

    /** La descarga de verdad: por el turno de `HostGate` y con tope de tamaño. */
    fun download(url: String): ByteArray = HostGate.call(url) { SourceHttp.execute(url) { maxBodySize(MAX_COMPRESSED + 1) }.bodyAsBytes() }
}
