package app.kaizen.source.spec

import android.content.Context
import app.kaizen.App
import app.kaizen.commons.logError
import app.kaizen.commons.runCatchingCoop
import app.kaizen.source.AnimeSource
import app.kaizen.source.ConfigurableSource
import app.kaizen.commons.HostGate
import app.kaizen.source.SourceHttp
import java.io.File

/**
 * Fuentes descritas por fichero: las que lleva la app en `assets/sources/` y las que se descargan del repositorio de
 * fuentes ([SOURCES_URL]) a una caché. De cada id vale la versión más alta; una descarga solo se acepta si el índice
 * lo firma la clave de [PUBLIC_KEY] y cada fichero coincide con su hash, y nunca baja de versión (no hay vuelta atrás).
 * Una descarga nueva se aplica en el siguiente arranque de la app.
 */
object SpecSources {
    /** Carpeta pública con `index.json`, `index.json.sig` y un JSON por fuente (no contiene secretos: solo selectores). */
    const val SOURCES_URL = "https://raw.githubusercontent.com/D4rumanDev/kaizen-sources/main/"

    /** Clave pública ECDSA P-256 (X.509 DER en base64) con la que se firma `index.json`; la privada no está en el repositorio. */
    internal const val PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEcCEhWYl+wlltbZ6VGkXCYT6y8xfOMivl83rSLETj9yFr5EpKDB82IKYxOwd2b1Pl5Yz43633eZfu2YbbCjpOUA=="

    private const val ASSETS = "sources"
    private const val CACHE_DIR = "sources-cache"
    private const val REFRESH_INTERVAL_MS = 12 * 60 * 60 * 1000L
    private const val MAX_FILE_BYTES = 512 * 1024

    private val context: Context get() = App.context
    private val cacheDir: File get() = File(context.filesDir, CACHE_DIR)
    private val prefs get() = app.kaizen.commons.KaizenStore.prefs("kaizen_sources")

    /** Fuentes por fichero activas en esta ejecución. */
    val sources: List<AnimeSource> by lazy {
        // Cada fuente se construye aparte: un fichero roto (expresión que Android no entiende...) solo deja fuera a esa fuente.
        specs().mapNotNull { spec ->
            try {
                ConfigurableSource(spec)
            } catch (e: Throwable) {
                logError(e)
                null
            }
        }
    }

    internal fun specs(): List<SourceSpec> {
        val best = HashMap<String, SourceSpec>()
        (bundled() + cached()).forEach { spec -> if (spec.version > (best[spec.id]?.version ?: -1)) best[spec.id] = spec }
        return best.values.sortedBy { it.id }
    }

    private fun bundled(): List<SourceSpec> = runCatchingCoop {
        val names = context.assets.list(ASSETS).orEmpty().filter { it.endsWith(".json") && !it.startsWith("index") }
        names.mapNotNull { name ->
            runCatchingCoop { SourceSpec.parse(context.assets.open("$ASSETS/$name").bufferedReader().readText()) }
                .onFailure(::logError).getOrNull()
        }
    }.getOrDefault(emptyList())

    /** La caché se vuelve a verificar entera en cada arranque: si no cuadra (corrupta o manipulada), se ignora. */
    private fun cached(): List<SourceSpec> = runCatchingCoop {
        if (!File(cacheDir, "index.json").exists()) return@runCatchingCoop emptyList()
        SpecBundle.load(RepoConfig.publicKey) { name -> File(cacheDir, name).takeIf { it.isFile }?.readBytes() }
    }.onFailure(::logError).getOrDefault(emptyList())

    /**
     * Mira el repositorio de fuentes y guarda en la caché lo más nuevo (como mucho una vez cada 12 horas, salvo [force]).
     * Devuelve cuántas fuentes se han actualizado; un fallo de red o de verificación deja la caché como estaba.
     */
    fun refresh(force: Boolean = false): Int {
        // Apagado por defecto mientras el repositorio del proyecto sea privado (ver RepoConfig); las fuentes de `assets/sources/` y la caché ya descargada siguen igual.
        if (!RepoConfig.enabled) return 0
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("last_refresh", 0L) < REFRESH_INTERVAL_MS) return 0
        prefs.edit().putLong("last_refresh", now).apply()
        return runCatchingCoop { download() }.onFailure(::logError).getOrDefault(0)
    }

    private fun fetch(name: String): ByteArray = RepoConfig.baseUrl.let { base ->
        HostGate.call(base) { SourceHttp.execute(base + name) { maxBodySize(MAX_FILE_BYTES) }.bodyAsBytes() }
    }

    private fun download(): Int {
        val index = fetch("index.json")
        val signature = fetch("index.json.sig")
        val key = RepoConfig.publicKey
        if (!SpecBundle.verify(key, index, signature)) throw SpecException("firma del repositorio de fuentes no válida")
        val remote = SpecBundle.parseIndex(index)
        val current = specs().associateBy { it.id }
        // Solo las fuentes elegidas (todas si no se eligió ninguna): el resto ni se baja ni se guarda.
        val chosen = remote.entries.filter { RepoConfig.wants(it.id) }
        val wanted = chosen.filter { it.version > (current[it.id]?.version ?: -1) }
        if (wanted.isEmpty()) return 0
        // Se guarda el conjunto entero de la versión remota (índice, firma y ficheros): así la caché es siempre un conjunto firmado.
        val staging = File(context.filesDir, "$CACHE_DIR.tmp").apply { deleteRecursively(); mkdirs() }
        chosen.forEach { File(staging, it.file).writeBytes(fetch(it.file)) }
        File(staging, "index.json").writeBytes(index)
        File(staging, "index.json.sig").writeBytes(signature)
        // Antes de sustituir la caché se comprueba el conjunto: hashes, formato e ids.
        val valid = SpecBundle.load(key) { name -> File(staging, name).takeIf { it.isFile }?.readBytes() }
        // Una entrada firmada que esta app no sabe interpretar (formato nuevo...) no bloquea las demás: solo cuentan las usables.
        val usable = wanted.filter { entry -> valid.any { it.id == entry.id && it.version == entry.version } }
        if (usable.isEmpty()) {
            staging.deleteRecursively()
            throw SpecException("el conjunto descargado no trae ninguna fuente usable")
        }
        cacheDir.deleteRecursively()
        if (!staging.renameTo(cacheDir)) throw SpecException("no se pudo guardar la caché de fuentes")
        return usable.size
    }
}
