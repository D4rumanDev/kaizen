package app.kaizen.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import app.kaizen.commons.AppErrors
import app.kaizen.commons.AppScope
import app.kaizen.commons.CacheCleaner
import app.kaizen.commons.DebugLog
import app.kaizen.commons.HostGate
import app.kaizen.commons.ImageMisses
import app.kaizen.commons.KaizenBackup
import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.KaizenStore
import app.kaizen.commons.LaLigaBlock
import app.kaizen.commons.RequestCounter
import app.kaizen.commons.Tuning
import app.kaizen.commons.background
import app.kaizen.commons.logError
import app.kaizen.commons.runCatchingCoop
import app.kaizen.commons.warp.WarpTunnel
import app.kaizen.database.CacheDB
import app.kaizen.database.CatalogDB
import app.kaizen.database.UserDB
import app.kaizen.source.AnilistAutoRunner
import app.kaizen.source.AnimeSources
import app.kaizen.source.CatalogBuilder
import app.kaizen.source.FichaExport
import app.kaizen.source.FichaFill
import app.kaizen.source.FichaResuelta
import app.kaizen.source.SeriesLedger
import app.kaizen.source.WebFichaQuery
import app.kaizen.tv.settings.setSourceActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Control por ADB (`adb shell am broadcast -a app.kaizen.action.BACKUP -n app.kaizen/.control.AdbControlReceiver`), pensado para hacer
 * copias y comprobar el estado sin manejar la tele. Está limitado a propósito:
 *  - solo lo puede enviar quien tenga el permiso `android.permission.DUMP` (el shell de ADB; una app normal no lo tiene);
 *  - hace falta activar «Control por ADB» en Configuración › Depuración (apagado de fábrica);
 *  - solo hay las acciones de `ACTIONS` (abajo); la lista de lo que hace cada una está en `docs/wiki/control-por-adb.md`.
 * El resultado vuelve en `data=` de `am broadcast` y se anota en el registro.
 */
class AdbControlReceiver : BroadcastReceiver() {
    /** Lo que se devuelve a `am broadcast`: el código y el texto de `data=`. */
    private class Reply(val code: Int, val text: String)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ACTIONS) return
        val pending = goAsync()
        fun answer(reply: Reply) {
            DebugLog.info("ControlADB", "$action: ${reply.text.take(300)}")
            pending.setResultCode(reply.code)
            pending.setResultData(reply.text)
            pending.finish()
        }
        if (!KaizenSettings.adbControl) return answer(fail("Control por ADB desactivado (Configuración › Depuración)", CODE_DISABLED))
        background(onError = { logError(it); answer(fail("Error: ${it.javaClass.simpleName}")) }) {
            answer(handle(context, intent, action))
        }
    }

    private suspend fun handle(context: Context, intent: Intent, action: String): Reply = when (action) {
        ACTION_BACKUP -> backup()
        ACTION_SETTINGS -> settings()
        ACTION_SETTING_GET -> settingGet(intent)
        ACTION_SETTING_SET -> settingSet(intent)
        ACTION_SOURCES -> sources(intent)
        ACTION_SOURCES_CHECK -> sourcesCheck()
        ACTION_ERRORS -> errors()
        ACTION_BYPASS -> bypass()
        ACTION_CACHE_CLEAR -> cacheClear(context, intent)
        ACTION_LOG_CLEAR -> logClear(intent)
        ACTION_SETTINGS_RESET -> settingsReset(intent)
        ACTION_STORE -> store()
        ACTION_CALLS -> calls(intent)
        ACTION_ANILIST_AUTO -> anilistAuto(intent)
        ACTION_WARP -> warp(context, intent)
        ACTION_LALIGA -> laLiga()
        ACTION_IMAGE_MISSES -> imageMisses(intent)
        ACTION_FILL_FICHAS -> fillFichas(intent)
        ACTION_FICHA_GET -> fichaGet(intent)
        ACTION_EXPORT_FICHAS -> exportFichas()
        ACTION_WALK -> walk()
        ACTION_SPLIT -> split(intent)
        ACTION_IMPORT -> import(intent)
        else -> status()
    }

    private suspend fun backup(): Reply {
        val (file, summary) = KaizenBackup.export()
        return ok("Copia creada: ${file.name} ($summary)")
    }

    /** Todos los ajustes que se pueden tocar por ADB con su valor y sus opciones: JSON en base64 (como FICHA_GET). */
    private fun settings(): Reply = base64(JSONArray(AdbSettings.all.map { s ->
        JSONObject().put("nombre", s.name).put("valor", s.get()).put("opciones", JSONArray(s.options)).put("tipo", s.kind.name)
    }))

    private fun settingGet(intent: Intent): Reply {
        val name = intent.getStringExtra("name").orEmpty()
        val setting = AdbSettings.find(name) ?: return fail("Ajuste desconocido: $name (lista: SETTINGS)")
        return ok("$name=${setting.get()}" + if (setting.options.isNotEmpty()) " (admite: ${setting.options.joinToString(", ")})" else "")
    }

    private fun settingSet(intent: Intent): Reply {
        val (done, text) = AdbSettings.set(intent.getStringExtra("name").orEmpty(), intent.getStringExtra("value").orEmpty())
        return if (done) ok(text) else fail(text)
    }

    /** Sin --es set: la lista de fuentes con su estado. Con `--es set id --ez on true|false`: activa o desactiva una (siempre queda al menos una). */
    private fun sources(intent: Intent): Reply {
        val all = AnimeSources.all
        val id = intent.getStringExtra("set")
        if (id != null) {
            if (all.none { it.id == id }) return fail("Fuente desconocida: $id (" + all.joinToString(", ") { it.id } + ")")
            if (!setSourceActive(id, intent.getBooleanExtra("on", true))) return fail("Debe quedar al menos una fuente activa")
        }
        val selected = AnimeSources.selected.map { it.id }.toSet()
        return ok(all.joinToString(" | ") { source ->
            val until = HostGate.downUntil(source.host)
            "${source.id}=" + (if (source.id in selected) "activa" else "desactivada") + if (until > 0) " (en pausa hasta " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(until)) + ")" else ""
        })
    }

    /** Mide cada fuente activa sin tocar pausas (HostGate.probe), como al abrir «Fuentes». */
    private suspend fun sourcesCheck(): Reply {
        val results = AnimeSources.selected.map { source -> source.id to HostGate.probe("https://${source.host}/") }
        return ok(results.joinToString(" | ") { (id, r) -> "$id=" + if (r.ok) "responde ${r.millis} ms" + (r.code?.takeIf { it >= 400 }?.let { " (HTTP $it)" } ?: "") else "no responde" + (r.error?.let { " ($it)" } ?: "") })
    }

    /** La sesión del gestor único de errores: cuántos de cada número y el último detalle (el detalle puede llevar la web, nunca la URL entera). */
    private fun errors(): Reply {
        val summary = AppErrors.summary()
        return ok(if (summary.isEmpty()) "Esta sesión: sin errores" else summary.sortedByDescending { it.count }.joinToString(" | ") { "${it.count}x ${it.cause.tag} ${it.cause.label}: ${it.lastDetail.take(80)}" }.replace('"', '\''))
    }

    private fun bypass(): Reply {
        val b = app.kaizen.bypass.BypassStatus.snapshot.value
        return ok("estado=${b.state} hace=" + (if (b.at > 0) ((System.currentTimeMillis() - b.at) / 1000).toString() + " s" else "nunca") + " tardo=${b.millis} ms" + if (b.pausedUntil > System.currentTimeMillis()) " en pausa" else "")
    }

    private suspend fun cacheClear(context: Context, intent: Intent): Reply {
        if (!intent.confirmed()) return fail("Borra respuestas guardadas, catálogo interno y portadas (favoritos, historial y progreso se conservan). Repite con --ez confirm true")
        CacheCleaner.clearAll(context.applicationContext)
        return ok("Caché vaciada")
    }

    private fun logClear(intent: Intent): Reply {
        if (!intent.confirmed()) return fail("Vacía el registro de depuración. Repite con --ez confirm true")
        DebugLog.clear()
        return ok("Registro vaciado")
    }

    private fun settingsReset(intent: Intent): Reply {
        if (!intent.confirmed()) return fail("Vuelve todos los ajustes a los de fábrica (favoritos, historial y progreso se conservan). Repite con --ez confirm true")
        KaizenSettings.resetToDefaults()
        Tuning.apply()
        return ok("Ajustes restablecidos (la interfaz se ve al reabrir la app)")
    }

    /** Qué ficheros de preferencias hay, de qué clase y cuántas claves (solo lectura, sin valores: ningún ajuste ni secreto sale por aquí). JSON en base64. */
    private fun store(): Reply = base64(JSONArray(KaizenStore.report().map { r ->
        JSONObject().put("fichero", r.file).put("clase", r.scope?.label ?: "SIN REGISTRAR").put("claves", r.keys).put("bytes", r.bytes).put("que", r.what ?: "")
    }))

    /** Peticiones reales a cada web en un día (hoy por defecto, o `--es day AAAA-MM-DD`): para conocimiento interno, ¿saturamos alguna? JSON en base64. */
    private fun calls(intent: Intent): Reply {
        val day = intent.getStringExtra("day")?.takeIf { DAY_OK.matches(it) }
        val report = RequestCounter.report(day)
        val sourceHosts = AnimeSources.all.map { it.host.removePrefix("www.") }
        return base64(JSONObject().put("dia", report.day)
            .put("hosts", JSONArray(report.hosts.take(60).map { h ->
                JSONObject().put("host", h.host).put("uso", h.foreground).put("fondo", h.background)
                    .put("fuente", sourceHosts.any { h.host == it || h.host.endsWith(".$it") })
            }))
            .put("urls", JSONArray(report.urls.take(25).map { JSONObject().put("url", it.url).put("n", it.count) })))
    }

    /**
     * Sin extras: cómo va. `--ez start true` empieza (por defecto solo cuenta y no escribe nada); `--ez apply true --ez confirm true` guarda de verdad los enlaces claros
     * (la app hace antes una copia); `--ei limit N` acota las series (hasta 5000). Sigue en segundo plano, una serie a la vez.
     */
    private fun anilistAuto(intent: Intent): Reply {
        if (intent.getBooleanExtra("start", false)) {
            val apply = intent.getBooleanExtra("apply", false)
            // Aplicar escribe enlaces y reagrupa el catálogo: como las demás acciones que cambian cosas, exige confirmar.
            if (apply && !intent.confirmed()) return fail("Aplicar guarda los enlaces claros y reagrupa el catálogo (antes se hace una copia). Repite con --ez confirm true")
            val limit = intent.getIntExtra("limit", 5000).coerceIn(1, 5000)
            if (!AnilistAutoRunner.start(apply, limit)) return fail("Ya hay una revisión en curso: " + AnilistAutoRunner.progress.line)
        }
        return ok(AnilistAutoRunner.progress.line)
    }

    /** Sin extras: el estado. Con `--ez evaluate true`: aplica ahora el modo (sube o baja el túnel). El aviso de VPN solo lo puede aceptar una persona en la TV. */
    private suspend fun warp(context: Context, intent: Intent): Reply {
        val note = if (intent.getBooleanExtra("evaluate", false)) WarpTunnel.evaluate() else null
        return ok(WarpTunnel.status(context) + (note?.let { " | aviso=$it" } ?: ""))
    }

    /** Solo lectura: qué fuentes activas resuelven hoy a una IP que algún operador bloquea (hayahora.futbol). */
    private suspend fun laLiga(): Reply {
        val blocked = LaLigaBlock.blockedHosts(AnimeSources.selected.map { it.host })
        return ok(if (blocked.isEmpty()) "Ninguna fuente bloqueada ahora (o hayahora.futbol no responde)" else "Bloqueadas por LaLiga: ${blocked.joinToString(", ")}")
    }

    private fun imageMisses(intent: Intent): Reply {
        val before = ImageMisses.count()
        val clear = intent.getBooleanExtra("clear", false)
        if (clear) ImageMisses.clear()
        return ok("Imágenes no disponibles anotadas: $before" + if (clear) " (olvidadas)" else "")
    }

    private suspend fun fillFichas(intent: Intent): Reply {
        if (!FichaFill.tryLock()) return fail("Ya hay un relleno de fichas en curso")
        val todo = try {
            FichaFill.planNow(intent.getIntExtra("limit", 20), intent.getStringExtra("fuente"))
        } catch (e: Throwable) { FichaFill.unlock(); throw e }
        launchLogged("Relleno de fichas fallido", release = FichaFill::unlock) {
            "Relleno de fichas: ${FichaFill.runNow(todo).let { "${it.total} de ${todo.size} ($it)" }}"
        }
        return ok("Relleno de fichas iniciado: ${todo.size} fichas")
    }

    /**
     * Solo lectura. El nombre va en base64 (los nombres japoneses no pasan por la línea de órdenes) y la ruta, la de WebFichaQuery, con caracteres de ruta.
     * Con `--ez resuelta true` el dato sale de AniList si la ficha está enlazada y, si no lo tiene, de la web.
     */
    private suspend fun fichaGet(intent: Intent): Reply {
        val name = runCatching { String(Base64.decode(intent.getStringExtra("name64").orEmpty(), Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault("")
        val path = intent.getStringExtra("path").orEmpty()
        val resolved = intent.getBooleanExtra("resuelta", false)
        if (name.trim().length < 2 || !PATH_OK.matches(path) || (path.isEmpty() && resolved)) return fail("Falta el nombre (name64, al menos 2 caracteres) o la ruta no es válida (con resuelta hace falta un campo): $path")
        val dao = CatalogDB.INSTANCE.webFichaDao()
        val rows = if (resolved) {
            val links = UserDB.INSTANCE.animeFuenteDao()
            val fichas = CatalogDB.INSTANCE.animeFichaDao()
            WebFichaQuery.resolved(dao, name, path, MAX_FICHA_ROWS) { web -> FichaResuelta.anilistOf(links.get(web.fuente, web.slug), fichas::get) }
        } else WebFichaQuery.get(dao, name, path, MAX_FICHA_ROWS)
        val json = JSONArray(rows.map { JSONObject().put("fuente", it.fuente).put("slug", it.slug).put("value", it.value ?: JSONObject.NULL).put("origen", it.origen ?: JSONObject.NULL) }).toString()
        if (json.length > MAX_FICHA_BYTES) return fail("Resultado demasiado grande (${json.length} caracteres): pide un campo concreto con path")
        return base64(json)
    }

    /** Como el recorrido: puede tardar más de lo que Android deja a un receptor, así que responde ya con el nombre del fichero y sigue en segundo plano. */
    private fun exportFichas(): Reply {
        if (!exporting.compareAndSet(false, true)) return fail("Ya hay una exportación de fichas en curso")
        val name = FichaExport.nameFor(System.currentTimeMillis())
        launchLogged("Exportación de fichas fallida", release = { exporting.set(false) }) {
            val result = FichaExport.exportAll(KaizenBackup.directory, name)
            "Fichas de AniList exportadas: ${result.rows} en ${result.file.name} (${result.file.length() / 1024} KB, comprobado)"
        }
        return ok("Exportación de fichas iniciada → $name")
    }

    /** El recorrido dura minutos: la respuesta no espera. Su avance se ve en el registro de la app. */
    private fun walk(): Reply {
        AppScope.launch { runCatchingCoop { CatalogBuilder.ensureFresh(force = true) }.onFailure(::logError) }
        return ok("Recorrido del catálogo iniciado")
    }

    /** Deshace una unión equivocada: cada grupo de fichas del fichero `{"groups":[[["fuente","slug"],…],…]}` pasa a una serie propia. */
    private fun split(intent: Intent): Reply {
        val name = intent.getStringExtra("file").orEmpty()
        val file = backupFile(name)
        if (file == null || file.length() > MAX_FILE_BYTES) return fail("Fichero no válido: $name")
        // Se lee y se valida todo el fichero antes de tocar nada: una entrada mal formada no deja el trabajo a medias ni tira la app.
        val parsed = try {
            val groups = JSONObject(file.readText()).getJSONArray("groups")
            if (groups.length() > MAX_GROUPS) return fail("Demasiados grupos: ${groups.length()}")
            (0 until groups.length()).map { g ->
                val members = groups.getJSONArray(g)
                (0 until members.length()).map { members.getJSONArray(it).let { f -> f.getString(0) to f.getString(1) } }
            }
        } catch (e: org.json.JSONException) {
            return fail("Fichero mal formado: $name")
        }
        val oversized = parsed.count { it.size > MAX_GROUP_SIZE }
        val moved = parsed.count { it.size <= MAX_GROUP_SIZE && SeriesLedger.detachGroup(it) != null }
        // Cambian los grupos de varias series: se rehace el catálogo agrupado en segundo plano (la respuesta no espera).
        if (moved > 0 && intent.getBooleanExtra("regroup", true)) AppScope.launch { runCatchingCoop { CatalogBuilder.regroup() }.onFailure(::logError) }
        return ok("Series separadas: $moved de ${parsed.size} grupos" + if (oversized > 0) " ($oversized omitidos por tener más de $MAX_GROUP_SIZE fichas)" else "")
    }

    /** Solo un fichero .json de la carpeta de copias, por su nombre: ninguna ruta. */
    private suspend fun import(intent: Intent): Reply {
        val name = intent.getStringExtra("file").orEmpty()
        val file = backupFile(name) ?: return fail("Fichero no válido: $name")
        val summary = KaizenBackup.import(file) ?: return fail("La copia no es válida: $name")
        return ok("Añadido desde $name: $summary")
    }

    private suspend fun status(): Reply {
        val db = CacheDB.INSTANCE
        // Abrir `catalog-db` es lo que ejecuta su migración la primera vez: si falla, el recuento lo dice y no tumba el resto.
        val catalog = runCatchingCoop {
            val catalogDb = CatalogDB.INSTANCE
            "fichas=${catalogDb.animeFichaDao().count()} webfichas=${catalogDb.webFichaDao().count()} webfichas_con_idmal=${catalogDb.webFichaDao().countWithMal()} " +
                "webfichas_por_fuente=" + catalogDb.webFichaDao().countByFuente().joinToString(",") { "${it.fuente}:${it.total}" }.ifEmpty { "-" }
        }.getOrElse { "catalog-db=error:${it.javaClass.simpleName}" }
        return ok("favoritos=${db.favoriteAV1DAO().allRaw.size} vistos=${db.recordAV1DAO().count} $catalog ultima=${KaizenBackup.latest()?.name}")
    }

    /** Segundo plano de una acción larga: lo que devuelve [work] va al registro; si falla, se anota; [release] siempre se ejecuta al acabar. */
    private fun launchLogged(failure: String, release: () -> Unit, work: suspend () -> String) {
        AppScope.launch(Dispatchers.IO) {
            try {
                DebugLog.info("ControlADB", work())
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logError(e)
                DebugLog.info("ControlADB", "$failure: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                release()
            }
        }
    }

    /** El `.json` de la carpeta de copias con ese nombre, o null si el nombre lleva ruta, no acaba en .json o el fichero no existe. */
    private fun backupFile(name: String): File? {
        if (name.isBlank() || '/' in name || '\\' in name || !name.endsWith(".json")) return null
        return File(KaizenBackup.directory, name).takeIf { it.isFile }
    }

    private fun Intent.confirmed() = getBooleanExtra("confirm", false)
    private fun ok(text: String) = Reply(CODE_OK, text)
    private fun fail(text: String, code: Int = CODE_ERROR) = Reply(code, text)
    private fun base64(json: Any) = ok(Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP))

    companion object {
        const val ACTION_BACKUP = "app.kaizen.action.BACKUP"
        const val ACTION_STATUS = "app.kaizen.action.STATUS"
        const val ACTION_WALK = "app.kaizen.action.WALK"
        const val ACTION_IMPORT = "app.kaizen.action.IMPORT"
        const val ACTION_SPLIT = "app.kaizen.action.SPLIT"
        const val ACTION_EXPORT_FICHAS = "app.kaizen.action.EXPORT_FICHAS"
        const val ACTION_FICHA_GET = "app.kaizen.action.FICHA_GET"
        const val ACTION_FILL_FICHAS = "app.kaizen.action.FILL_FICHAS"
        const val ACTION_SETTINGS = "app.kaizen.action.SETTINGS"
        const val ACTION_SETTING_GET = "app.kaizen.action.SETTING_GET"
        const val ACTION_SETTING_SET = "app.kaizen.action.SETTING_SET"
        const val ACTION_LALIGA = "app.kaizen.action.LALIGA"
        const val ACTION_CALLS = "app.kaizen.action.CALLS"
        const val ACTION_STORE = "app.kaizen.action.STORE"
        const val ACTION_ANILIST_AUTO = "app.kaizen.action.ANILIST_AUTO"
        const val ACTION_WARP = "app.kaizen.action.WARP"
        const val ACTION_SOURCES = "app.kaizen.action.SOURCES"
        const val ACTION_SOURCES_CHECK = "app.kaizen.action.SOURCES_CHECK"
        const val ACTION_ERRORS = "app.kaizen.action.ERRORS"
        const val ACTION_BYPASS = "app.kaizen.action.BYPASS"
        const val ACTION_CACHE_CLEAR = "app.kaizen.action.CACHE_CLEAR"
        const val ACTION_LOG_CLEAR = "app.kaizen.action.LOG_CLEAR"
        const val ACTION_SETTINGS_RESET = "app.kaizen.action.SETTINGS_RESET"
        const val ACTION_IMAGE_MISSES = "app.kaizen.action.IMAGE_MISSES"
        private val ACTIONS = setOf(
            ACTION_BACKUP, ACTION_STATUS, ACTION_WALK, ACTION_IMPORT, ACTION_SPLIT, ACTION_EXPORT_FICHAS, ACTION_FICHA_GET, ACTION_FILL_FICHAS,
            ACTION_SETTINGS, ACTION_SETTING_GET, ACTION_SETTING_SET, ACTION_LALIGA, ACTION_IMAGE_MISSES,
            ACTION_CALLS, ACTION_STORE, ACTION_ANILIST_AUTO, ACTION_WARP, ACTION_SOURCES, ACTION_SOURCES_CHECK, ACTION_ERRORS, ACTION_BYPASS, ACTION_CACHE_CLEAR, ACTION_LOG_CLEAR, ACTION_SETTINGS_RESET
        )
        private val PATH_OK = Regex("[A-Za-z0-9_.\\[\\]]{0,80}")
        private val DAY_OK = Regex("\\d{4}-\\d{2}-\\d{2}")
        private const val MAX_FICHA_ROWS = 20
        private const val MAX_FICHA_BYTES = 200_000
        private val exporting = java.util.concurrent.atomic.AtomicBoolean(false)
        private const val MAX_FILE_BYTES = 2_000_000L
        private const val MAX_GROUPS = 500
        private const val MAX_GROUP_SIZE = 200
        private const val CODE_OK = 1
        private const val CODE_DISABLED = 2
        private const val CODE_ERROR = 3
    }
}
