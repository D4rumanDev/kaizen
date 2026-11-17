package app.kaizen.commons

import androidx.annotation.Keep
import app.kaizen.control.AdbSetting
import app.kaizen.control.AdbSettings
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Copia de los **ajustes** del usuario en un JSON (`kaizen-ajustes-<fecha>.json`, en la carpeta de copias), aparte de la de los **estados** de las series
 * ([KaizenBackup]: favoritos, historial, progreso, estados, reglas de unión). Así se puede llevar la configuración a otra TV sin traerse el historial, y al revés.
 *
 * Qué entra: los ajustes que el control por ADB ya reconoce ([AdbSettings], cada uno con sus valores válidos) y la disposición (fuentes activas, orden del
 * menú y de los botones del reproductor, lo que se oculta). Qué NO entra, a propósito: las cookies del bypass y cualquier secreto, el estado interno
 * (lo recorrido, las pausas, las marcas de migraciones), el interruptor de «Control por ADB» y lo que activa trabajo en segundo plano.
 *
 * Al restaurar se valida todo: un ajuste desconocido o con un valor que no es una de sus opciones se rechaza (y se cuenta en el [Report]); nada se aplica en bloque
 * a ciegas. Restaurar es una acción del usuario (nunca automática) y no borra nada: solo vuelve a poner esos ajustes.
 */
object SettingsBackup {
    private const val VERSION = 1
    const val PREFIX = "kaizen-ajustes-"
    private const val KEEP = 10

    /** Ajustes que se guardan pero no se restauran: deciden por dónde salen las consultas (DNS de relevo, túnel WARP); un fichero ajeno no debe cambiarlos. Se cambian a mano. */
    private val NOT_RESTORED = setOf("dns_relevo", "warp_modo", "repo_descargas", "repo_url", "repo_clave", "sync_auto", "sync_url", "sync_usuario")
    private const val MAX_BYTES = 256 * 1024

    /** Disposición de la interfaz: lo que no cabe como «nombre → valor» de [AdbSettings]. */
    @Keep
    data class Layout(
        @SerializedName("sources") val sources: List<String> = emptyList(),
        @SerializedName("menuOrder") val menuOrder: String = "",
        @SerializedName("playerButtonOrder") val playerButtonOrder: String = "",
        @SerializedName("hiddenPlayerButtons") val hiddenPlayerButtons: List<String> = emptyList(),
        @SerializedName("hiddenRows") val hiddenRows: List<Int> = emptyList()
    )

    @Keep
    data class Data(
        @SerializedName("kind") val kind: String,
        @SerializedName("version") val version: Int,
        @SerializedName("createdAt") val createdAt: Long,
        @SerializedName("settings") val settings: Map<String, String>,
        @SerializedName("layout") val layout: Layout
    )

    /** Lo que Gson puede dejar a null en un fichero escrito a mano o de otra versión. */
    @Keep
    private class Raw(
        @SerializedName("kind") val kind: String?,
        @SerializedName("version") val version: Int?,
        @SerializedName("createdAt") val createdAt: Long?,
        @SerializedName("settings") val settings: Map<String, String?>?,
        @SerializedName("layout") val layout: LayoutRaw?
    )

    @Keep
    private class LayoutRaw(
        @SerializedName("sources") val sources: List<String?>?,
        @SerializedName("menuOrder") val menuOrder: String?,
        @SerializedName("playerButtonOrder") val playerButtonOrder: String?,
        @SerializedName("hiddenPlayerButtons") val hiddenPlayerButtons: List<String?>?,
        @SerializedName("hiddenRows") val hiddenRows: List<Int?>?
    )

    /** Qué se aplicó y qué se rechazó (con el motivo). */
    class Report(val applied: List<String>, val rejected: Map<String, String>) {
        override fun toString() = "${applied.size} ajustes aplicados" + if (rejected.isEmpty()) "" else ", ${rejected.size} rechazados (${rejected.keys.joinToString()})"
    }

    /** Dónde se escribe la disposición al restaurar (las pruebas lo cambian por uno en memoria). */
    interface LayoutSink {
        fun sources(value: Set<String>)
        fun menuOrder(value: String)
        fun playerButtonOrder(value: String)
        fun hiddenPlayerButtons(value: Set<String>)
        fun hiddenRows(value: Set<Int>)
    }

    private object RealSink : LayoutSink {
        override fun sources(value: Set<String>) { KaizenSettings.sourceIds = value }
        override fun menuOrder(value: String) { KaizenSettings.menuOrder = value }
        override fun playerButtonOrder(value: String) { KaizenSettings.playerButtonOrder = value }
        override fun hiddenPlayerButtons(value: Set<String>) { KaizenSettings.hiddenPlayerButtons = value }
        override fun hiddenRows(value: Set<Int>) { KaizenSettings.hiddenRows = value }
    }

    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun encode(data: Data): String = gson.toJson(data)

    /** null si no es un JSON válido, no es una copia de ajustes o es de una versión posterior. */
    fun decode(json: String): Data? {
        val raw = runCatching { gson.fromJson(json, Raw::class.java) }.getOrNull() ?: return null
        if (raw.kind != KIND || (raw.version ?: return null) !in 1..VERSION) return null
        val layout = raw.layout
        return Data(
            KIND, raw.version, raw.createdAt ?: 0L,
            raw.settings.orEmpty().mapNotNull { (name, value) -> value?.let { name to it } }.toMap(),
            Layout(layout?.sources.orEmpty().filterNotNull(), layout?.menuOrder.orEmpty(), layout?.playerButtonOrder.orEmpty(),
                layout?.hiddenPlayerButtons.orEmpty().filterNotNull(), layout?.hiddenRows.orEmpty().filterNotNull())
        )
    }

    private const val KIND = "kaizen-ajustes"

    /** Los ajustes de ahora como copia. */
    internal fun snapshot(settings: List<AdbSetting>, layout: Layout, now: Long): Data =
        Data(KIND, VERSION, now, settings.filterNot { it.name.startsWith("sync_") || it.name.startsWith("repo_") }.associate { it.name to it.get() }, layout)

    private fun currentLayout() = Layout(
        KaizenSettings.sourceIds.sorted(), KaizenSettings.menuOrder, KaizenSettings.playerButtonOrder,
        KaizenSettings.hiddenPlayerButtons.sorted(), KaizenSettings.hiddenRows.sorted()
    )

    /** Una lista de nombres o de ids separados por comas: solo letras, cifras y los separadores de siempre, con tope (un fichero ajeno no mete cualquier cosa). */
    private val ORDER_OK = Regex("[A-Za-z0-9_,:.-]{0,300}")

    /**
     * Aplica [data] con [settings] (cada valor se valida contra sus opciones) y la disposición en [sink]. Las fuentes activas solo si son todas conocidas ([knownSources])
     * y hay al menos una. Devuelve qué se aplicó y qué se rechazó; no lanza por un valor malo.
     */
    internal fun apply(data: Data, settings: List<AdbSetting>, knownSources: Set<String>, sink: LayoutSink): Report {
        val applied = ArrayList<String>()
        val rejected = LinkedHashMap<String, String>()
        val byName = settings.associateBy { it.name }
        for ((name, value) in data.settings) {
            if (name in NOT_RESTORED) { rejected[name] = "no se restaura desde un fichero (cambia a dónde van las consultas)"; continue }
            val setting = byName[name]
            val problem = if (setting == null) "ajuste desconocido" else setting.problem(value)
            if (setting == null || problem != null) { rejected[name] = problem.orEmpty(); continue }
            runCatching { setting.set(value) }.onSuccess { applied += name }.onFailure { rejected[name] = it.message ?: "error" }
        }
        val layout = data.layout
        if (layout.sources.isNotEmpty()) {
            if (layout.sources.all { it in knownSources }) { sink.sources(layout.sources.toSet()); applied += "fuentes" } else rejected["fuentes"] = "alguna fuente desconocida"
        }
        if (ORDER_OK.matches(layout.menuOrder)) { if (layout.menuOrder.isNotEmpty()) { sink.menuOrder(layout.menuOrder); applied += "orden_menu" } } else rejected["orden_menu"] = "texto no válido"
        if (ORDER_OK.matches(layout.playerButtonOrder)) { if (layout.playerButtonOrder.isNotEmpty()) { sink.playerButtonOrder(layout.playerButtonOrder); applied += "orden_botones" } } else rejected["orden_botones"] = "texto no válido"
        // Lo oculto solo se toca si la copia trae algo: un fichero sin disposición no borra lo que ya está oculto.
        if (layout.hiddenPlayerButtons.isNotEmpty()) {
            if (layout.hiddenPlayerButtons.all { ORDER_OK.matches(it) }) { sink.hiddenPlayerButtons(layout.hiddenPlayerButtons.toSet()); applied += "botones_ocultos" } else rejected["botones_ocultos"] = "texto no válido"
        }
        if (layout.hiddenRows.isNotEmpty()) { sink.hiddenRows(layout.hiddenRows.toSet()); applied += "filas_ocultas" }
        return Report(applied, rejected)
    }

    val directory: File get() = KaizenBackup.directory

    private fun files(): List<File> = directory.listFiles { f -> f.name.startsWith(PREFIX) && f.extension == "json" }.orEmpty().toList()

    fun latest(): File? = files().maxByOrNull { it.lastModified() }

    /** Todas las copias de ajustes, la más reciente primero (para elegir una si la última está dañada). */
    fun candidates(): List<File> = files().sortedByDescending { it.lastModified() }

    /** Las copias de ajustes que sobran: se guardan las [keep] más recientes (nunca la última). */
    internal fun expired(files: List<File>, keep: Int = KEEP): List<File> = files.sortedByDescending { it.lastModified() }.drop(keep.coerceAtLeast(1))

    /** Guarda una copia de los ajustes de ahora y borra las más antiguas (quedan [KEEP]). */
    suspend fun export(): File = withContext(Dispatchers.IO) {
        val data = snapshot(AdbSettings.all, currentLayout(), System.currentTimeMillis())
        val dir = directory.also { it.mkdirs() }
        val file = File(dir, "$PREFIX${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(data.createdAt)}.json")
        file.writeText(encode(data))
        expired(files()).forEach { it.delete() }
        file
    }

    /** Nombre de «mis ajustes predeterminados»: no empieza por [PREFIX], así que la rotación de copias (que borra las más viejas) nunca lo toca. */
    const val DEFAULTS_FILE = "kaizen-predeterminados.json"

    private val defaultsFile: File get() = File(directory, DEFAULTS_FILE)

    /** Cuándo se guardaron mis ajustes predeterminados; null si aún no hay. */
    fun defaultsSavedAt(): Long? = defaultsFile.takeIf { it.isFile }?.lastModified()

    /** Guarda los ajustes de ahora como predeterminados del usuario (sustituye a los anteriores). Escribe en un fichero temporal y lo mueve, para no dejar uno a medias. */
    suspend fun saveDefaults(): File = withContext(Dispatchers.IO) {
        val data = snapshot(AdbSettings.all, currentLayout(), System.currentTimeMillis())
        val dir = directory.also { it.mkdirs() }
        val tmp = File(dir, "$DEFAULTS_FILE.tmp")
        tmp.writeText(encode(data))
        if (!tmp.renameTo(defaultsFile)) { defaultsFile.delete(); check(tmp.renameTo(defaultsFile)) { "No se pudo guardar" } }
        DebugLog.info("Datos", "Ajustes actuales guardados como predeterminados")
        defaultsFile
    }

    /** Vuelve a poner mis ajustes predeterminados (con la misma validación que cualquier copia); null si no hay o no son válidos. No borra nada. */
    suspend fun restoreDefaults(): Report? = defaultsFile.takeIf { it.isFile }?.let { import(it) }

    /**
     * Restaura la copia más reciente que sea válida: si la última está dañada (o no es una copia de ajustes) se prueba la anterior, y así hasta dar con una.
     * Devuelve el fichero usado y lo aplicado, o null si ninguna vale.
     */
    suspend fun restoreNewestValid(): Pair<File, Report>? {
        for (file in candidates()) runCatchingCoop { import(file) }.getOrNull()?.let { return file to it }
        return null
    }

    /** Restaura [file]: null si no es una copia de ajustes válida. Acción del usuario; no borra nada. */
    suspend fun import(file: File): Report? = withContext(Dispatchers.IO) {
        if (file.length() > MAX_BYTES) return@withContext null
        val data = decode(file.readText()) ?: return@withContext null
        val report = apply(data, AdbSettings.all, app.kaizen.source.AnimeSources.all.map { it.id }.toSet(), RealSink)
        Tuning.apply()
        DebugLog.info("Datos", "Ajustes restaurados de ${file.name}: $report")
        report
    }
}
