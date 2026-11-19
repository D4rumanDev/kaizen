package app.kaizen.commons

import app.kaizen.App
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Registro de depuración visible para el usuario (Configuración › Depuración): **JSON Lines**, un objeto JSON por línea, en la carpeta de la app
 * (`Android/data/app.kaizen/files/debug.jsonl`, se recoge con `adb pull`). Solo se escribe lo que alcanza el nivel elegido.
 *
 * Una línea: `{"t":"2026-11-12 10:12:03","l":"E","a":"Carátulas","m":"E06 No encontrado (404) (cdn.x.com): HttpException","d":["Origen: …","rastro…"],
 * "code":"E06","cause":"no-existe","origin":"La fuente","ctx":"cdn.x.com"}`. Los campos `code`, `cause`, `origin` y `ctx` solo los llevan los errores que
 * pasan por [AppErrors]. El `debug.log` de texto de versiones anteriores se sigue leyendo (y se borra con [clear]), pero ya no se escribe.
 *
 * **Si el disco falla** (sin espacio, carpeta no disponible, sin permiso) el registro no se pierde en silencio: las líneas esperan en una cola en memoria
 * ([MEMORY_LINES] como mucho), se avisa una vez ([onDiskFailure], que alimenta al gestor de errores como E20), el visor las sigue enseñando y se vuelcan
 * al disco en cuanto vuelve a poderse escribir. Si falla la memoria, no se escribe nada y nunca se rompe lo que se estaba registrando.
 */
object DebugLog {
    const val ERROR = 1
    const val INFO = 2
    const val DETAIL = 3

    /** Posición = valor de [KaizenSettings.debugLevel]. */
    val LEVELS = listOf("Apagado", "Errores", "Información", "Detalle")

    /** Tope del fichero y de la cola en memoria. Las pruebas bajan el tope. */
    @Volatile internal var maxBytes = 512 * 1024
    private const val MEMORY_LINES = 300
    private const val MIN_FREE_BYTES = 1L * 1024 * 1024
    private val lock = Any()

    /** Carpeta del registro: la externa de la app y, si no está disponible (almacenamiento desmontado), la interna. Las pruebas la cambian. */
    @Volatile internal var dirProvider: () -> File? = { App.context.getExternalFilesDir(null) ?: App.context.filesDir }

    /** Nivel elegido por el usuario. Las pruebas lo cambian. */
    @Volatile internal var levelProvider: () -> Int = { KaizenSettings.debugLevel }

    /** Se llama una vez cuando el disco deja de poderse escribir, con el motivo. Lo enchufa `App` al gestor de errores. */
    @Volatile var onDiskFailure: ((String) -> Unit)? = null

    /** Líneas que no pudieron escribirse y esperan en memoria, y el motivo del fallo (null: el disco funciona). */
    private val pending = ArrayDeque<String>()
    @Volatile private var diskProblem: String? = null

    private fun dir(): File = dirProvider() ?: throw java.io.IOException("sin carpeta para el registro")

    val file: File get() = File(dir(), "debug.jsonl")

    /** El registro de texto de versiones anteriores. */
    val legacyFile: File get() = File(dir(), "debug.log")

    /** Datos extra de una anotación que viene del gestor de errores: su número, causa, de quién es y la web o recurso afectado. */
    data class Fields(val code: String? = null, val cause: String? = null, val origin: String? = null, val context: String? = null)

    fun error(tag: String, message: String, throwable: Throwable? = null, fields: Fields? = null) =
        write(ERROR, "E", tag, fields) { message + throwable?.let { "\n" + it.stackTraceToString() }.orEmpty() }

    fun info(tag: String, message: String) = write(INFO, "I", tag, null) { message }

    /** Ejecuta [block] y anota en el registro cuánto tardó (para saber qué pesa en el arranque sin adivinar). [what] es una frase con mayúscula inicial que dice qué se hizo. Deja pasar la excepción, anotando el tiempo igualmente. */
    inline fun <T> timed(tag: String, what: String, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            info(tag, what + " (" + (System.nanoTime() - start) / 1_000_000 + " ms)")
        }
    }

    fun detail(tag: String, message: String) = write(DETAIL, "D", tag, null) { message }

    // shortcut: se escribe en el hilo que llama (una línea corta); pasar a un hilo propio si el nivel «Detalle» ralentiza algo.
    private fun write(level: Int, letter: String, tag: String, fields: Fields?, message: () -> String) {
        // Un fallo al registrar nunca debe romper lo que se estaba registrando (ni en pruebas sin contexto de Android).
        runCatching {
            if (levelProvider() < level) return
            append(encode(letter, tag, message(), fields))
        }
    }

    // ------------------------------------------------------------------------------------------------ Escribir

    /** Una línea JSON: la primera línea del mensaje es `m`; el resto del mensaje y el rastro técnico van en `d`. */
    internal fun encode(letter: String, tag: String, text: String, fields: Fields?, now: Long = System.currentTimeMillis()): String {
        // Sin direcciones completas ni en el mensaje ni en el rastro técnico (los mensajes de jsoup y OkHttp llevan la URL con sus parámetros).
        val lines = AppErrors.redact(text).lines()
        val json = JsonObject()
        json.addProperty("t", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now))
        json.addProperty("l", letter)
        json.addProperty("a", tag)
        json.addProperty("m", lines.first())
        if (lines.size > 1) json.add("d", com.google.gson.JsonArray().also { array -> lines.drop(1).filter { it.isNotBlank() }.forEach { array.add(it.trimEnd()) } })
        fields?.code?.let { json.addProperty("code", it) }
        fields?.cause?.let { json.addProperty("cause", it) }
        fields?.origin?.let { json.addProperty("origin", it) }
        fields?.context?.takeIf { it.isNotBlank() }?.let { json.addProperty("ctx", it) }
        return json.toString()
    }

    /** El fichero antiguo ya recortado (por su ruta); bajo [lock]. */
    private var legacyTrimmedIn: String? = null

    private fun append(line: String) {
        var failure: String? = null
        synchronized(lock) {
            try {
                val target = file
                trim(target)
                // El de texto de versiones anteriores ya no se escribe: solo se recorta, para que no se quede en el disco con su tamaño de entonces.
                // Aparte: que no se pueda recortar el antiguo no debe impedir escribir en el actual (ni dar un falso aviso de disco).
                // Como ya no crece, basta una vez por carpeta: antes se miraba en cada anotación (dos accesos al disco más por línea, en el hilo que anota).
                val legacy = legacyFile
                if (legacyTrimmedIn != legacy.path) { runCatching { trim(legacy) }; legacyTrimmedIn = legacy.path }
                // Lo que esperó en memoria por un fallo anterior va primero (el orden se conserva) y todo en una sola escritura: si falla, no queda a medias ni se duplica nada.
                target.appendText(pending.joinToString("") { it + "\n" } + line + "\n")
                pending.clear()
                diskProblem = null
            } catch (t: Throwable) {
                pending.addLast(line)
                while (pending.size > MEMORY_LINES) pending.removeFirst()
                if (diskProblem == null) {
                    diskProblem = reasonOf(t)
                    failure = diskProblem
                }
            }
        }
        // Fuera del cerrojo: el aviso entra por el gestor de errores, que vuelve a escribir en este registro (y ya irá a la cola).
        failure?.let { reason -> runCatching { onDiskFailure?.invoke(reason) } }
    }

    private fun reasonOf(t: Throwable): String {
        val free = runCatching { dir().usableSpace }.getOrNull()
        return when {
            t is OutOfMemoryError -> "sin memoria"
            free != null && free < MIN_FREE_BYTES -> "sin espacio libre (${free / 1024} KB)"
            else -> "${t::class.java.simpleName}${t.message?.let { ": $it" }.orEmpty()}"
        }
    }

    /** Al pasar del tope se queda con la mitad más reciente, **por líneas enteras** y escribiendo en un fichero aparte para no dejarlo a medias si se corta. */
    private fun trim(target: File) {
        if (!target.exists() || target.length() <= maxBytes) return
        var size = 0L
        val keep = target.readLines().asReversed().takeWhile { size += it.length + 1; size <= maxBytes / 2 }.asReversed()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(keep.joinToString("\n", postfix = "\n"))
        if (!tmp.renameTo(target)) { target.writeText(tmp.readText()); tmp.delete() }
    }

    // ------------------------------------------------------------------------------------------------ Leer

    /** Una anotación ya leída: cuándo, qué gravedad, de qué parte de la app y qué pasó. [details] son las líneas que siguen (origen y solución, rastro de un error). */
    data class Entry(
        val date: String, val time: String, val level: Int, val area: String, val message: String, val details: List<String>,
        val code: String? = null, val cause: String? = null, val origin: String? = null, val context: String? = null
    )

    private val ENTRY = Regex("""^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2}) ([EID])/([^:]*): (.*)$""")

    /** Nombre en claro de cada etiqueta que usa el código al anotar. */
    private val AREAS = mapOf(
        "Datos" to "Copia y datos", "Series" to "Libro de series", "Catálogo" to "Catálogo", "ControlADB" to "Control por ADB",
        "Depuración" to "Depuración", "KAIZEN" to "Error de la app"
    )

    private fun levelOf(letter: String) = when (letter) { "E" -> ERROR; "D" -> DETAIL; else -> INFO }

    /**
     * Pasa las líneas del fichero a anotaciones. Las que empiezan por `{` son JSON; una cortada a medias (la app se cerró escribiéndola) se ignora.
     * Las de texto de versiones anteriores siguen entendiéndose: lo que no empieza con fecha y gravedad es el rastro técnico de la anterior.
     */
    fun parse(lines: List<String>): List<Entry> {
        val entries = ArrayList<Entry>()
        for (line in lines) {
            if (line.startsWith("{")) {
                parseJson(line)?.let { entries += it }
                continue
            }
            val m = ENTRY.matchEntire(line)
            if (m == null) {
                val last = entries.lastOrNull() ?: continue
                entries[entries.lastIndex] = last.copy(details = last.details + line.trim())
                continue
            }
            val (date, time, letter, tag, message) = m.destructured
            entries += Entry(date, time, levelOf(letter), AREAS[tag] ?: tag, message, emptyList())
        }
        return entries
    }

    private fun parseJson(line: String): Entry? = runCatching {
        val json = JsonParser.parseString(line).asJsonObject
        fun text(key: String) = json.get(key)?.takeIf { it.isJsonPrimitive }?.asString
        val (date, time) = (text("t") ?: return null).split(' ', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val tag = text("a").orEmpty()
        Entry(date, time, levelOf(text("l").orEmpty()), AREAS[tag] ?: tag, text("m").orEmpty(),
            json.get("d")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString }.orEmpty(),
            text("code"), text("cause"), text("origin"), text("ctx"))
    }.getOrNull()

    /**
     * Las últimas [maxLines] líneas, la más reciente al final: el registro de texto antiguo, el actual y, al final, lo que espera en memoria porque el
     * disco no se pudo escribir. Si el disco no se puede ni leer, solo lo de memoria.
     */
    fun read(maxLines: Int = 500): List<String> = synchronized(lock) {
        val disk = runCatching {
            listOf(legacyFile, file).filter { it.exists() }.flatMap { it.readLines() }
        }.getOrDefault(emptyList())
        (disk + pending).takeLast(maxLines)
    }

    fun clear() {
        runCatching {
            synchronized(lock) {
                pending.clear()
                diskProblem = null
                file.delete()
                legacyFile.delete()
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ Salud

    /** El estado del registro, la memoria y el disco, para el visor: [diskProblem] es el motivo si no se puede escribir. */
    data class Health(val diskProblem: String?, val waiting: Int, val fileBytes: Long, val freeBytes: Long?, val heapUsedMb: Long, val heapMaxMb: Long)

    fun health(): Health {
        val rt = Runtime.getRuntime()
        val (size, free) = runCatching { file.takeIf { it.exists() }?.length() to dir().usableSpace }.getOrDefault(null to null)
        return Health(diskProblem, synchronized(lock) { pending.size }, size ?: 0L, free, (rt.totalMemory() - rt.freeMemory()) shr 20, rt.maxMemory() shr 20)
    }

    /** [Health] en una línea: «Disco: bien · libre 1,2 GB · registro 120 KB · memoria 80/256 MB», o el motivo y cuántas líneas esperan en memoria. */
    fun healthLine(): String = health().let { h ->
        val disk = if (h.diskProblem != null) "NO SE PUEDE ESCRIBIR (${h.diskProblem}) · ${h.waiting} líneas en memoria, se pierden al cerrar la app"
        else "bien · libre ${h.freeBytes?.let(::size) ?: "?"} · registro ${size(h.fileBytes)}"
        "Disco: $disk · memoria ${h.heapUsedMb}/${h.heapMaxMb} MB"
    }

    private fun size(bytes: Long) = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        else -> "${bytes shr 10} KB"
    }
}
