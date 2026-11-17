package app.kaizen.commons

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import app.kaizen.App
import java.io.File

/**
 * **El único sitio por el que la app llega a sus preferencias guardadas** (ficheros `shared_prefs`) y la única lista de qué ficheros hay, de qué son y qué
 * se puede hacer con cada uno. Nada más llama a `getSharedPreferences` ni a `PreferenceManager`: una prueba lo comprueba recorriendo el código.
 *
 * Cada fichero tiene un [Scope] que dice cómo tratarlo: [Scope.SETTINGS] lo que el usuario elige (vuelve a fábrica al restablecer; hoy la copia de KaizenBackup no incluye las
 * preferencias), [Scope.STATE] el estado interno que conserva trabajo (lo visto, lo recorrido: no se borra solo), [Scope.CACHE] lo que se vuelve a calcular (se puede vaciar sin perder
 * nada) y [Scope.SECRET] cuentas y cookies (regla de diseño: no deben ir a una copia ni a un registro; el código aún no lo hace cumplir).
 *
 * Los nombres de fichero y de claves son los de siempre: unificar el acceso no mueve ni renombra ningún dato guardado.
 */
object KaizenStore {
    enum class Scope(val label: String) {
        SETTINGS("ajustes"), STATE("estado"), CACHE("caché"), SECRET("secreto"),

        /** Ficheros que crea una librería o el sistema (el WebView), no la app: se listan para no confundirlos con uno olvidado. */
        LIBRARY("librería")
    }

    /** Un fichero (o una familia de ficheros con el mismo prefijo y sufijo: uno por fuente) con lo que guarda. */
    class Entry(val name: String, val scope: Scope, val what: String, val suffix: String? = null, private val middle: Int = 1) {
        /** Un fichero de [name] a [suffix] con al menos [middle] caracteres en medio (`kaizen_<fuente>_covers`) o el nombre exacto. */
        fun matches(file: String): Boolean =
            if (suffix == null) file == name else file.startsWith(name) && file.endsWith(suffix) && file.length >= name.length + suffix.length + middle

        val label: String get() = if (suffix == null) name else "$name*$suffix"
    }

    /** Todos los ficheros que la app usa. Para añadir uno: una línea aquí y nada más. */
    val entries: List<Entry> = listOf(
        // El fichero por defecto de Android se llama «<paquete>_preferences»; la variante de depuración añade «.debug» al paquete, y por eso se reconoce por el principio y el final.
        Entry("app.kaizen", Scope.SETTINGS, "Ajustes del usuario y marcas de la app (KaizenSettings); incluye las cookies del bypass, que son secretas", suffix = "_preferences", middle = 0),
        Entry("kaizen_progress", Scope.STATE, "Capítulos vistos y posición de cada uno"),
        Entry("kaizen_recents", Scope.STATE, "Archivo de capítulos recientes"),
        Entry("kaizen_taste", Scope.CACHE, "Gustos aprendidos para recomendar (se recalculan)"),
        Entry("kaizen_servers", Scope.STATE, "Servidor recordado de cada serie"),
        Entry("kaizen_connection_stats", Scope.STATE, "Intentos de conexión de las estadísticas"),
        Entry("kaizen_cleanup", Scope.CACHE, "Fichero antiguo de una versión anterior: ya no lo usa ningún código"),
        Entry("WebViewChromiumPrefs", Scope.LIBRARY, "Lo crea el WebView de Android"),
        Entry("AwOriginVisitLoggerPrefs", Scope.LIBRARY, "Lo crea el WebView de Android"),
        Entry("kaizen_server_stats", Scope.CACHE, "Contadores antiguos de estadísticas (ConnectionStats los borra al migrar)"),
        Entry("kaizen_request_counts", Scope.STATE, "Recuento interno de peticiones por web y día"),
        Entry("kaizen_catalog", Scope.CACHE, "Estado del recorrido del catálogo por fuente (el botón «Vaciar caché» lo borra)"),
        Entry("kaizen_regroup", Scope.CACHE, "Cuándo se reagrupó el catálogo"),
        Entry("kaizen_repairs", Scope.STATE, "Reparaciones de datos ya hechas"),
        Entry("kaizen_crash_guard", Scope.STATE, "Racha de cierres en el arranque"),
        Entry("kaizen_hostgate", Scope.STATE, "Pausas y turnos de HostGate por web"),
        Entry("kaizen_sources", Scope.STATE, "Fuentes descargadas y su comprobación"),
        Entry("kaizen_source_challenge", Scope.STATE, "Verificaciones de las webs que las piden"),
        Entry("kaizen_bypass", Scope.STATE, "Estado del bypass de Cloudflare"),
        Entry("home_channels", Scope.STATE, "Filas de la pantalla de inicio de Android TV"),
        Entry("kaizen_warp", Scope.SECRET, "Cuenta del túnel WARP"),
        Entry("kaizen_image_misses", Scope.CACHE, "Imágenes que no existen (404) para no pedirlas otra vez"),
        Entry("kaizen_api_cache", Scope.CACHE, "Respuestas guardadas de las APIs"),
        Entry("kaizen_source_icons", Scope.CACHE, "Logo de cada fuente, leído de su portada"),
        Entry("kaizen_best_covers", Scope.CACHE, "Mejor portada conocida de cada serie"),
        Entry("kaizen_", Scope.CACHE, "Portadas recordadas de una fuente", suffix = "_covers"),
        Entry("kaizen_", Scope.CACHE, "Enlaces de capítulos de una fuente", suffix = "_chapters"),
        Entry("kaizen_", Scope.CACHE, "Fechas de capítulos de una fuente", suffix = "_dates_utc")
    )

    /** Los ajustes del usuario (el fichero por defecto). Todo lo demás pide su fichero por nombre con [prefs]. */
    val settings: SharedPreferences get() = PreferenceManager.getDefaultSharedPreferences(App.context)

    /** El fichero [name]; si no está en [entries] se anota como error (E99) y se abre igualmente, para no perder un dato por un olvido. */
    fun prefs(name: String): SharedPreferences {
        if (entry(name) == null) logError(IllegalStateException("fichero de preferencias sin registrar en KaizenStore: $name"))
        return open(name)
    }

    private fun open(name: String): SharedPreferences = App.context.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun entry(file: String): Entry? = entries.firstOrNull { it.matches(file) }

    /** Una fila del informe: un fichero que existe en el disco, su clase y cuántas claves tiene. */
    class Row(val file: String, val scope: Scope?, val what: String?, val keys: Int, val bytes: Long)

    /**
     * Qué ficheros hay ahora (los que existen en `shared_prefs`) con su clase; los que no están en [entries] salen con `scope` null: o falta registrarlos
     * o sobran. Es solo lectura.
     */
    fun report(): List<Row> {
        val dir = File(App.context.applicationInfo.dataDir, "shared_prefs")
        return dir.listFiles { f -> f.name.endsWith(".xml") }.orEmpty().map { f ->
            val name = f.name.removeSuffix(".xml")
            val entry = entry(name)
            Row(name, entry?.scope, entry?.what, open(name).all.size, f.length())
        }.sortedWith(compareBy({ it.scope?.ordinal ?: Int.MAX_VALUE }, { it.file }))
    }

    /** El informe como texto, una línea por fichero (lo que enseña `tv.js store`). */
    fun describe(): String = report().joinToString("\n") { r ->
        "${r.scope?.label ?: "SIN REGISTRAR"}\t${r.file}\t${r.keys} claves\t${r.bytes} B\t${r.what ?: "-"}"
    }
}
