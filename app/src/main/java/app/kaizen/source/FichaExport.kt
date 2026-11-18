package app.kaizen.source

import androidx.room.Dao
import androidx.room.Query
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.time.Duration.Companion.hours

/** Lectura por tramos de [AnimeFicha] para exportarla sin cargar la tabla entera en memoria (cada fila lleva su JSON comprimido). */
@Dao
interface FichaExportDao {
    /** Las [limit] fichas siguientes a [after] por id: paginar por clave y no por posición evita repetir o saltar filas si se guarda una ficha mientras se exporta. */
    @Query("SELECT * FROM AnimeFicha WHERE anilistId > :after ORDER BY anilistId LIMIT :limit")
    fun page(after: Long, limit: Int): List<AnimeFicha>

    /** Los anime con la ficha descargada con esta versión de la consulta (solo esas valen para compartir entre equipos). */
    @Query("SELECT anilistId FROM AnimeFicha WHERE consulta = :version")
    fun idsOf(version: Int): List<Long>

    /** Las fichas de esta versión con id en [from, to). */
    @Query("SELECT * FROM AnimeFicha WHERE anilistId >= :from AND anilistId < :to AND consulta = :version ORDER BY anilistId")
    fun range(from: Long, to: Long, version: Int): List<AnimeFicha>
}

/**
 * Copia de las fichas de AniList (`AnimeFicha`, en `catalog-db`) a un fichero que se puede sacar de la tele con ADB. Existe porque la app no es
 * depurable y su base de datos no se puede copiar de fuera; las fichas cuestan horas de peticiones a AniList.
 *
 * Formato `fichas-anilist-<fecha>.jsonl.gz`: texto JSON, una línea por registro, todo comprimido con gzip. Primera línea: cabecera
 * (`formato`, `version`, `creado`, `filas` previstas); después una línea por ficha con sus columnas y, en base64, los dos blobs tal como están en la
 * base (`json` y `es`, ya comprimidos, así que la vuelta es exacta); última línea: `{"fin": N}` con las filas escritas. Un fichero sin esa última línea,
 * o con otro número, es una copia cortada y [verify] la rechaza. Se escribe a un fichero temporal y solo se renombra si se comprueba completo.
 * No borra nada: ni de la base ni de la carpeta.
 */
internal object FichaExport {
    const val PREFIX = "fichas-anilist-"
    const val FORMAT = "kaizen-fichas-anilist"
    const val VERSION = 1
    private const val PAGE = 200
    private val STALE_PART = 1.hours

    class Result(val file: File, val rows: Int)

    /** `fichas-anilist-20261104-153000.jsonl.gz`: el momento en la hora de la tele, como las demás copias. */
    fun nameFor(at: Long) = PREFIX + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date(at)) + ".jsonl.gz"

    /** Exporta `AnimeFicha` de la base real a [dir] con el nombre [name]. Bloquea: llamar fuera del hilo principal. */
    fun exportAll(dir: File, name: String, at: Long = System.currentTimeMillis()): Result {
        val db = app.kaizen.database.CatalogDB.INSTANCE
        return export(dir, name, at, db.animeFichaDao().count()) { after, limit -> db.fichaExportDao().page(after, limit) }
    }

    /** Escribe todas las fichas que da [page] en [dir] y devuelve el fichero ya comprobado. [expected] es el recuento al empezar (solo informativo). */
    fun export(dir: File, name: String, at: Long, expected: Int, page: (after: Long, limit: Int) -> List<AnimeFicha>): Result {
        dir.mkdirs()
        // Un temporal de una exportación que murió a medias (más de una hora): no es una copia válida, así que se limpia. Solo estos; las copias completas no se tocan.
        dir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(".part") && at - f.lastModified() > STALE_PART.inWholeMilliseconds }.orEmpty().forEach { it.delete() }
        val target = File(dir, name)
        require(!target.exists()) { "ya existe ${target.name}" }
        val part = File(dir, "$name.part")
        var rows = 0
        try {
            GZIPOutputStream(FileOutputStream(part)).bufferedWriter(Charsets.UTF_8).use { out ->
                out.write(JSONObject().put("formato", FORMAT).put("version", VERSION).put("creado", at).put("filas", expected).toString())
                out.newLine()
                var after = Long.MIN_VALUE
                while (true) {
                    val batch = page(after, PAGE)
                    batch.forEach { out.write(toLine(it).toString()); out.newLine() }
                    rows += batch.size
                    if (batch.size < PAGE) break
                    after = batch.last().anilistId
                }
                out.write(JSONObject().put("fin", rows).toString())
                out.newLine()
            }
            check(verify(part) == rows) { "la copia no coincide con lo escrito" }
            check(part.renameTo(target)) { "no se pudo dejar ${target.name}" }
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
        return Result(target, rows)
    }

    /** Las fichas [rows] como bytes en el mismo formato que el fichero (para dejarlas en la carpeta de red). */
    fun encode(rows: List<AnimeFicha>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        GZIPOutputStream(out).bufferedWriter(Charsets.UTF_8).use { w ->
            w.write(JSONObject().put("formato", FORMAT).put("version", VERSION).put("creado", System.currentTimeMillis()).put("filas", rows.size).toString()); w.newLine()
            rows.forEach { w.write(toLine(it).toString()); w.newLine() }
            w.write(JSONObject().put("fin", rows.size).toString()); w.newLine()
        }
        return out.toByteArray()
    }

    /** Las fichas de [bytes]; null si no es de Kaizen, está cortado o mal formado. */
    fun decode(bytes: ByteArray): List<AnimeFicha>? = runCatching {
        BufferedReader(InputStreamReader(GZIPInputStream(bytes.inputStream()), Charsets.UTF_8)).use { input ->
            val head = JSONObject(input.readLine() ?: return null)
            if (head.optString("formato") != FORMAT || head.optInt("version") != VERSION) return null
            val rows = mutableListOf<AnimeFicha>()
            var end: Int? = null
            input.lineSequence().forEach { line ->
                check(end == null)
                val row = JSONObject(line)
                if (row.has("fin")) end = row.getInt("fin") else rows += fromLine(row)
            }
            rows.takeIf { end == it.size }
        }
    }.getOrNull()

    private fun b64(bytes: ByteArray?) = bytes?.let { Base64.getEncoder().encodeToString(it) }

    private fun toLine(f: AnimeFicha) = JSONObject()
        .put("anilistId", f.anilistId).put("malId", f.malId ?: JSONObject.NULL).put("consulta", f.consulta).put("descargada", f.descargada)
        .put("json", b64(f.json)).put("es", b64(f.es) ?: JSONObject.NULL)
        .put("tituloRomaji", f.tituloRomaji ?: JSONObject.NULL).put("tituloIngles", f.tituloIngles ?: JSONObject.NULL)
        .put("tituloNativo", f.tituloNativo ?: JSONObject.NULL).put("anio", f.anio ?: JSONObject.NULL)
        .put("formato", f.formato ?: JSONObject.NULL).put("nota", f.nota ?: JSONObject.NULL)

    private fun reader(file: File) = BufferedReader(InputStreamReader(GZIPInputStream(file.inputStream().buffered()), Charsets.UTF_8))

    /** Lee [file] entero y comprueba cabecera, cada ficha y línea final; devuelve cuántas fichas tiene. Lanza si está cortado o mal formado. */
    fun verify(file: File): Int {
        var count = 0
        var end: Int? = null
        reader(file).use { input ->
            val head = JSONObject(input.readLine() ?: error("fichero vacío"))
            check(head.optString("formato") == FORMAT && head.optInt("version") == VERSION) { "no es una copia de fichas de Kaizen" }
            input.lineSequence().forEach { line ->
                check(end == null) { "hay líneas después del final" }
                val row = JSONObject(line)
                if (row.has("fin")) end = row.getInt("fin") else { fromLine(row); count++ }
            }
        }
        check(end == count) { "copia cortada: ${end ?: "sin línea final"} frente a $count fichas" }
        return count
    }

    /** Las fichas de [file], tal como estaban (los blobs son los mismos bytes). Es lo que usaría una restauración; hoy lo usa [verify] y las pruebas. */
    fun read(file: File): List<AnimeFicha> {
        verify(file)
        return reader(file).use { input -> input.lineSequence().drop(1).map(::JSONObject).filter { !it.has("fin") }.map(::fromLine).toList() }
    }

    private fun fromLine(row: JSONObject) = AnimeFicha(
        anilistId = row.getLong("anilistId"), malId = if (row.isNull("malId")) null else row.getLong("malId"),
        consulta = row.getInt("consulta"), descargada = row.getLong("descargada"),
        json = Base64.getDecoder().decode(row.getString("json")), es = if (row.isNull("es")) null else Base64.getDecoder().decode(row.getString("es")),
        tituloRomaji = row.optString("tituloRomaji").takeIf { !row.isNull("tituloRomaji") }, tituloIngles = row.optString("tituloIngles").takeIf { !row.isNull("tituloIngles") },
        tituloNativo = row.optString("tituloNativo").takeIf { !row.isNull("tituloNativo") }, anio = if (row.isNull("anio")) null else row.getInt("anio"),
        formato = row.optString("formato").takeIf { !row.isNull("formato") }, nota = if (row.isNull("nota")) null else row.getInt("nota")
    )
}
