package app.kaizen.source

import app.kaizen.commons.DebugLog
import app.kaizen.commons.runCatchingCoop
import app.kaizen.commons.sync.RemoteStore
import com.google.gson.Gson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Las uniones con AniList viajan con el catálogo: cada equipo deja en la carpeta compartida (la de la sincronización, SMB o WebDAV) lo que ya emparejó y lee lo que dejaron los demás,
 * para que una tele nueva no repita horas de emparejado ni de descarga de fichas.
 *  - **Enlaces** (`kaizen-anilist-enlaces.json.gz`): qué ficha de qué web es qué anime de AniList. Solo los aplicados. Lo ajeno nunca pisa un enlace que ya existe (ni el del usuario); solo
 *    rellena fichas sin enlace o con uno automático que aún no valía.
 *  - **Fichas de AniList** (`kaizen-anilist-fichas-<tramo>-<n>.jsonl.gz`): los datos de cada anime (portada, títulos, año…), en tramos de [BUCKET] ids para que ningún fichero sea enorme.
 *    El número del nombre es cuántas fichas lleva: sin abrir nada se sabe si el del servidor tiene más que lo local (se baja lo que falta) o menos (se sube el tramo entero).
 * Solo datos: nada se interpreta ni se ejecuta, y los ficheros ilegibles se omiten.
 */
internal object AnilistShare {
    const val LINKS = "kaizen-anilist-enlaces.json.gz"
    const val BUCKET = 5000L
    private const val PREFIX = "kaizen-anilist-fichas-"
    private val FICHAS = Regex("kaizen-anilist-fichas-(\\d{1,6})-(\\d{1,6})[.]jsonl[.]gz")

    /** Qué se hizo: enlaces nuevos aquí, fichas nuevas aquí y lo subido para los demás. */
    class Result(val linksIn: Int, val fichasIn: Int, val linksOut: Int, val fichasOut: Int) {
        override fun toString() = "AniList compartido: $linksIn enlaces y $fichasIn fichas recibidos, $linksOut enlaces y $fichasOut fichas enviados"
    }

    fun bucketName(bucket: Long, count: Int) = "$PREFIX$bucket-$count.jsonl.gz"

    /** (tramo, nº de fichas, nombre) de un fichero de fichas de la carpeta; el nombre puede venir con ruta. */
    fun parse(name: String): Triple<Long, Int, String>? {
        val short = name.substringAfterLast('/')
        val m = FICHAS.matchEntire(short) ?: return null
        return Triple(m.groupValues[1].toLong(), m.groupValues[2].toInt(), short)
    }

    internal fun encodeLinks(rows: List<AnimeFuente>, at: Long): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).bufferedWriter(Charsets.UTF_8).use { Gson().toJson(AnilistLinksBackup.of(rows, at), it) }
        return out.toByteArray()
    }

    internal fun decodeLinks(bytes: ByteArray): List<AnimeFuente>? = runCatching {
        GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader(Charsets.UTF_8).use { Gson().fromJson(it, AnilistLinksBackup::class.java) }
    }.getOrNull()?.takeIf { it.usable() }?.rows()

    /**
     * Los enlaces ajenos que valen aquí: aplicados y con anime, y solo de fichas sin enlace o con uno automático que no estaba aplicado. Nunca sustituye uno aplicado ni uno del usuario.
     */
    internal fun linksToTake(remote: List<AnimeFuente>, local: Map<Pair<String, String>, AnimeFuente>): List<AnimeFuente> = remote.filter { r ->
        if (r.estado != AnimeFuente.APLICADO || r.anilistId == null) return@filter false
        val mine = local[r.fuente to r.slug]
        mine == null || (mine.origen == AnimeFuente.AUTO && mine.estado != AnimeFuente.APLICADO)
    }.distinctBy { it.fuente to it.slug }

    /** Sincroniza enlaces y fichas con [store]; las dos partes son independientes (si una falla, la otra se hace). */
    suspend fun run(store: RemoteStore, links: AnimeFuenteDao, fichas: AnimeFichaDao, export: FichaExportDao, now: Long = System.currentTimeMillis()): Result {
        val names = store.list()
        var linksIn = 0
        var linksOut = 0
        runCatchingCoop {
            val local = links.all()
            val remote = if (names.any { it.substringAfterLast('/') == LINKS }) store.read(LINKS)?.let(::decodeLinks).orEmpty() else emptyList()
            val take = linksToTake(remote, local.associateBy { it.fuente to it.slug })
            if (take.isNotEmpty()) { links.put(take.map { it.copy(fecha = now) }); linksIn = take.size }
            val mine = links.all().filter { it.estado == AnimeFuente.APLICADO && it.anilistId != null }
            // Se sube solo si lo local trae algo que el servidor no tiene (otro tramo de enlaces o uno distinto), para no reescribirlo en cada sincronización.
            val have = remote.filter { it.estado == AnimeFuente.APLICADO }.associate { (it.fuente to it.slug) to it.anilistId }
            if (mine.any { have[it.fuente to it.slug] != it.anilistId }) { store.write(LINKS, encodeLinks(mine, now)); linksOut = mine.size }
        }.onFailure { DebugLog.error("Datos", "AniList compartido (enlaces): ${it.message}") }
        var fichasIn = 0
        var fichasOut = 0
        runCatchingCoop {
            val version = AnilistClient.FICHA_VERSION
            val remoteBuckets = names.mapNotNull(::parse).groupBy { it.first }.mapValues { (_, files) -> files.maxBy { it.second } }
            val localIds = export.idsOf(version).groupBy { it / BUCKET }.mapValues { (_, ids) -> ids.toMutableSet() }
            for (bucket in (remoteBuckets.keys + localIds.keys).sorted()) {
                val remote = remoteBuckets[bucket]
                val mine = localIds[bucket] ?: mutableSetOf()
                if (remote != null && remote.second > mine.size) {
                    val rows = store.read(remote.third)?.let(FichaExport::decode).orEmpty()
                        .filter { it.consulta == version && it.anilistId / BUCKET == bucket && it.anilistId !in mine }
                    rows.chunked(200).forEach { fichas.put(it) }
                    mine += rows.map { it.anilistId }
                    fichasIn += rows.size
                }
                if (mine.size > (remote?.second ?: 0)) {
                    val rows = export.range(bucket * BUCKET, (bucket + 1) * BUCKET, version)
                    val name = bucketName(bucket, rows.size)
                    store.write(name, FichaExport.encode(rows))
                    names.mapNotNull(::parse).filter { it.first == bucket && it.third != name }.forEach { runCatching { store.delete(it.third) } }
                    fichasOut += rows.size
                }
            }
        }.onFailure { DebugLog.error("Datos", "AniList compartido (fichas): ${it.message}") }
        return Result(linksIn, fichasIn, linksOut, fichasOut)
    }
}
