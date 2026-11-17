package app.kaizen.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.kaizen.commons.AppScope
import app.kaizen.commons.DebugLog
import app.kaizen.commons.KaizenBackup
import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.logError
import app.kaizen.commons.runCatchingCoop
import app.kaizen.source.CatalogBuilder
import app.kaizen.source.FichaExport
import app.kaizen.source.FichaFill
import app.kaizen.source.SeriesLedger
import app.kaizen.source.WebFichaQuery
import kotlinx.coroutines.launch
import java.io.File
import app.kaizen.database.CacheDB
import app.kaizen.commons.background

/**
 * Control por ADB (`adb shell am broadcast -a app.kaizen.action.BACKUP -n app.kaizen/.control.AdbControlReceiver`), pensado para hacer
 * copias y comprobar el estado sin manejar la tele. Está limitado a propósito:
 *  - solo lo puede enviar quien tenga el permiso `android.permission.DUMP` (el shell de ADB; una app normal no lo tiene);
 *  - hace falta activar «Control por ADB» en Configuración › Depuración (apagado de fábrica);
 *  - solo hay veinticuatro acciones (STORE lista los ficheros de preferencias con su clase y sus claves, sin valores; CALLS cuenta las peticiones reales a cada web en un día, separando el segundo plano; ANILIST_AUTO resuelve sin pantalla las series dudosas con AniList que son claras, por defecto solo contando; WARP da el estado del túnel o, con `--ez evaluate true`, aplica el modo; las demás: las siete últimas: SOURCES lista las fuentes con su estado o, con `--es set id --ez on true|false`, activa o desactiva una; SOURCES_CHECK mide las activas; ERRORS resume la sesión del gestor de errores; BYPASS dice cómo va el bypass de Cloudflare; CACHE_CLEAR, LOG_CLEAR y SETTINGS_RESET exigen `--ez confirm true` y hacen lo que sus botones de Configuración). Antes: (las cinco últimas: SETTINGS lista los ajustes que se pueden tocar con su valor y sus opciones; SETTING_GET `--es name n` y SETTING_SET `--es name n --es value v` leen y cambian uno de esa lista, validado contra sus opciones y sin tocar «Control por ADB» ni el estado interno; LALIGA dice qué fuentes activas están bloqueadas por un partido; IMAGE_MISSES `--ez clear true` cuenta u olvida las imágenes que no existen): FILL_FICHAS (`--ei limit N` hasta 200, `--es fuente id`: abre las fichas de las webs sin enlace con AniList (por revisar o sin resultado) que aún no tienen copia, repartidas entre las fuentes, a ritmo lento y con las pausas de `HostGate`; solo escribe `WebFicha`; responde al momento y sigue en segundo plano), FICHA_GET (`--es name64 <nombre en base64 UTF-8>`, `--es path ruta`, `--ez resuelta true` para que el dato salga de AniList si la ficha está enlazada y, si no lo tiene, de la web: consulta lo guardado de las fichas de las webs con `WebFichaQuery`, el mismo código que usa la app; solo lee; devuelve en `data=` un JSON `[{fuente,slug,value}]` en base64 y, como mucho, 20 fichas), EXPORT_FICHAS (copia las fichas de AniList de `catalog-db`, `AnimeFicha`, a `fichas-anilist-<fecha>.jsonl.gz` en la carpeta de copias; solo lee la base y escribe un fichero nuevo; responde al momento con el nombre y sigue en segundo plano), BACKUP (crea una copia, como «Crear copia»), STATUS (recuentos, solo lectura), WALK (lanza el recorrido del catálogo que hace «Recorrer las fuentes que falten»; responde al momento y sigue en segundo plano) e
 *    IMPORT (`--es file nombre.json`: añade lo que falte de un fichero de la carpeta de copias, como «Restaurar»: solo añade, nunca borra) y SPLIT (`--es file nombre.json`, `--ez regroup false` para no rehacer el catálogo después: deshace una unión equivocada pasando cada grupo de fichas del fichero a una serie propia; no borra nada). No cambia ajustes; BACKUP solo borra lo que ya borra «Crear copia» (las copias caducadas).
 * El resultado vuelve en `data=` de `am broadcast` y se anota en el registro.
 */
class AdbControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ACTIONS) return
        val pending = goAsync()
        fun answer(code: Int, text: String) {
            DebugLog.info("ControlADB", "$action: ${text.take(300)}")
            pending.setResultCode(code)
            pending.setResultData(text)
            pending.finish()
        }
        if (!KaizenSettings.adbControl) return answer(CODE_DISABLED, "Control por ADB desactivado (Configuración › Depuración)")
        background(onError = { logError(it); answer(CODE_ERROR, "Error: ${it.javaClass.simpleName}") }) {
            if (action == ACTION_BACKUP) {
                val (file, summary) = KaizenBackup.export()
                answer(CODE_OK, "Copia creada: ${file.name} ($summary)")
            } else if (action == ACTION_SETTINGS) {
                // Todos los ajustes que se pueden tocar por ADB con su valor y sus opciones: JSON en base64 (como FICHA_GET).
                val json = org.json.JSONArray(AdbSettings.all.map { s ->
                    org.json.JSONObject().put("nombre", s.name).put("valor", s.get()).put("opciones", org.json.JSONArray(s.options)).put("tipo", s.kind.name)
                }).toString()
                answer(CODE_OK, android.util.Base64.encodeToString(json.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP))
            } else if (action == ACTION_SETTING_GET) {
                val name = intent.getStringExtra("name").orEmpty()
                val setting = AdbSettings.find(name) ?: return@background answer(CODE_ERROR, "Ajuste desconocido: $name (lista: SETTINGS)")
                answer(CODE_OK, "$name=${setting.get()}" + if (setting.options.isNotEmpty()) " (admite: ${setting.options.joinToString(", ")})" else "")
            } else if (action == ACTION_SETTING_SET) {
                val (ok, text) = AdbSettings.set(intent.getStringExtra("name").orEmpty(), intent.getStringExtra("value").orEmpty())
                answer(if (ok) CODE_OK else CODE_ERROR, text)
            } else if (action == ACTION_SOURCES) {
                // Sin --es set: la lista de fuentes con su estado. Con `--es set id --ez on true|false`: activa o desactiva una (siempre queda al menos una).
                val all = app.kaizen.source.AnimeSources.all
                val id = intent.getStringExtra("set")
                if (id != null) {
                    if (all.none { it.id == id }) return@background answer(CODE_ERROR, "Fuente desconocida: $id (" + all.joinToString(", ") { it.id } + ")")
                    val on = intent.getBooleanExtra("on", true)
                    if (!app.kaizen.tv.settings.setSourceActive(id, on)) return@background answer(CODE_ERROR, "Debe quedar al menos una fuente activa")
                }
                val selected = app.kaizen.source.AnimeSources.selected.map { it.id }.toSet()
                answer(CODE_OK, all.joinToString(" | ") { source ->
                    val until = app.kaizen.commons.HostGate.downUntil(source.host)
                    "${source.id}=" + (if (source.id in selected) "activa" else "desactivada") + if (until > 0) " (en pausa hasta " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(until)) + ")" else ""
                })
            } else if (action == ACTION_SOURCES_CHECK) {
                // Mide cada fuente activa sin tocar pausas (HostGate.probe), como al abrir «Fuentes».
                val results = app.kaizen.source.AnimeSources.selected.map { source -> source.id to app.kaizen.commons.HostGate.probe("https://${source.host}/") }
                answer(CODE_OK, results.joinToString(" | ") { (id, r) -> "$id=" + if (r.ok) "responde ${r.millis} ms" + (r.code?.takeIf { it >= 400 }?.let { " (HTTP $it)" } ?: "") else "no responde" + (r.error?.let { " ($it)" } ?: "") })
            } else if (action == ACTION_ERRORS) {
                // La sesión del gestor único de errores: cuántos de cada número y el último detalle (el detalle puede llevar la web, nunca la URL entera).
                val summary = app.kaizen.commons.AppErrors.summary()
                answer(CODE_OK, if (summary.isEmpty()) "Esta sesión: sin errores" else summary.sortedByDescending { it.count }.joinToString(" | ") { "${it.count}x ${it.cause.tag} ${it.cause.label}: ${it.lastDetail.take(80)}" }.replace('"', '\''))
            } else if (action == ACTION_BYPASS) {
                val b = app.kaizen.bypass.BypassStatus.snapshot.value
                answer(CODE_OK, "estado=${b.state} hace=" + (if (b.at > 0) ((System.currentTimeMillis() - b.at) / 1000).toString() + " s" else "nunca") + " tardo=${b.millis} ms" + if (b.pausedUntil > System.currentTimeMillis()) " en pausa" else "")
            } else if (action == ACTION_CACHE_CLEAR) {
                if (!intent.getBooleanExtra("confirm", false)) return@background answer(CODE_ERROR, "Borra respuestas guardadas, catálogo interno y portadas (favoritos, historial y progreso se conservan). Repite con --ez confirm true")
                app.kaizen.commons.CacheCleaner.clearAll(context.applicationContext)
                answer(CODE_OK, "Caché vaciada")
            } else if (action == ACTION_LOG_CLEAR) {
                if (!intent.getBooleanExtra("confirm", false)) return@background answer(CODE_ERROR, "Vacía el registro de depuración. Repite con --ez confirm true")
                DebugLog.clear()
                answer(CODE_OK, "Registro vaciado")
            } else if (action == ACTION_SETTINGS_RESET) {
                if (!intent.getBooleanExtra("confirm", false)) return@background answer(CODE_ERROR, "Vuelve todos los ajustes a los de fábrica (favoritos, historial y progreso se conservan). Repite con --ez confirm true")
                KaizenSettings.resetToDefaults()
                app.kaizen.commons.Tuning.apply()
                answer(CODE_OK, "Ajustes restablecidos (la interfaz se ve al reabrir la app)")
            } else if (action == ACTION_STORE) {
                // Qué ficheros de preferencias hay, de qué clase y cuántas claves (solo lectura, sin valores: ningún ajuste ni secreto sale por aquí). JSON en base64.
                val json = org.json.JSONArray(app.kaizen.commons.KaizenStore.report().map { r ->
                    org.json.JSONObject().put("fichero", r.file).put("clase", r.scope?.label ?: "SIN REGISTRAR").put("claves", r.keys).put("bytes", r.bytes).put("que", r.what ?: "")
                }).toString()
                answer(CODE_OK, android.util.Base64.encodeToString(json.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP))
            } else if (action == ACTION_CALLS) {
                // Peticiones reales a cada web en un día (hoy por defecto, o `--es day AAAA-MM-DD`): para conocimiento interno, ¿saturamos alguna? JSON en base64.
                val day = intent.getStringExtra("day")?.takeIf { Regex("\\d{4}-\\d{2}-\\d{2}").matches(it) }
                val report = app.kaizen.commons.RequestCounter.report(day)
                val sourceHosts = app.kaizen.source.AnimeSources.all.map { it.host.removePrefix("www.") }
                val json = org.json.JSONObject().put("dia", report.day)
                    .put("hosts", org.json.JSONArray(report.hosts.take(60).map { h ->
                        org.json.JSONObject().put("host", h.host).put("uso", h.foreground).put("fondo", h.background)
                            .put("fuente", sourceHosts.any { h.host == it || h.host.endsWith(".$it") })
                    }))
                    .put("urls", org.json.JSONArray(report.urls.take(25).map { org.json.JSONObject().put("url", it.url).put("n", it.count) })).toString()
                answer(CODE_OK, android.util.Base64.encodeToString(json.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP))
            } else if (action == ACTION_ANILIST_AUTO) {
                // Sin extras: cómo va. `--ez start true` empieza (por defecto solo cuenta y no escribe nada); `--ez apply true --ez confirm true` guarda de verdad los enlaces claros
                // (la app hace antes una copia); `--ei limit N` acota las series (hasta 5000). Sigue en segundo plano, una serie a la vez.
                if (intent.getBooleanExtra("start", false)) {
                    val apply = intent.getBooleanExtra("apply", false)
                    // Aplicar escribe enlaces y reagrupa el catálogo: como las demás acciones que cambian cosas, exige confirmar.
                    if (apply && !intent.getBooleanExtra("confirm", false)) return@background answer(CODE_ERROR, "Aplicar guarda los enlaces claros y reagrupa el catálogo (antes se hace una copia). Repite con --ez confirm true")
                    val limit = intent.getIntExtra("limit", 5000).coerceIn(1, 5000)
                    if (!app.kaizen.source.AnilistAutoRunner.start(apply, limit)) return@background answer(CODE_ERROR, "Ya hay una revisión en curso: " + app.kaizen.source.AnilistAutoRunner.progress.line)
                }
                answer(CODE_OK, app.kaizen.source.AnilistAutoRunner.progress.line)
            } else if (action == ACTION_WARP) {
                // Sin extras: el estado. Con `--ez evaluate true`: aplica ahora el modo (sube o baja el túnel). El aviso de VPN solo lo puede aceptar una persona en la TV.
                val note = if (intent.getBooleanExtra("evaluate", false)) app.kaizen.commons.warp.WarpTunnel.evaluate() else null
                answer(CODE_OK, app.kaizen.commons.warp.WarpTunnel.status(context) + (note?.let { " | aviso=$it" } ?: ""))
            } else if (action == ACTION_LALIGA) {
                // Solo lectura: qué fuentes activas resuelven hoy a una IP que algún operador bloquea (hayahora.futbol).
                val hosts = app.kaizen.source.AnimeSources.selected.map { it.host }
                val blocked = app.kaizen.commons.LaLigaBlock.blockedHosts(hosts)
                answer(CODE_OK, if (blocked.isEmpty()) "Ninguna fuente bloqueada ahora (o hayahora.futbol no responde)" else "Bloqueadas por LaLiga: ${blocked.joinToString(", ")}")
            } else if (action == ACTION_IMAGE_MISSES) {
                val before = app.kaizen.commons.ImageMisses.count()
                if (intent.getBooleanExtra("clear", false)) app.kaizen.commons.ImageMisses.clear()
                answer(CODE_OK, "Imágenes no disponibles anotadas: $before" + if (intent.getBooleanExtra("clear", false)) " (olvidadas)" else "")
            } else if (action == ACTION_FILL_FICHAS) {
                if (!FichaFill.tryLock()) return@background answer(CODE_ERROR, "Ya hay un relleno de fichas en curso")
                val todo = try {
                    FichaFill.planNow(intent.getIntExtra("limit", 20), intent.getStringExtra("fuente"))
                } catch (e: Throwable) { FichaFill.unlock(); throw e }
                AppScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val report = FichaFill.runNow(todo)
                        DebugLog.info("ControlADB", "Relleno de fichas: ${report.total} de ${todo.size} (${report})")
                    } catch (e: Throwable) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        logError(e)
                        DebugLog.info("ControlADB", "Relleno de fichas fallido: ${e.javaClass.simpleName}: ${e.message}")
                    } finally {
                        FichaFill.unlock()
                    }
                }
                answer(CODE_OK, "Relleno de fichas iniciado: ${todo.size} fichas")
            } else if (action == ACTION_FICHA_GET) {
                // Solo lectura. El nombre va en base64 (los nombres japoneses no pasan por la línea de órdenes) y la ruta, la de WebFichaQuery, con caracteres de ruta.
                val name = runCatching { String(android.util.Base64.decode(intent.getStringExtra("name64").orEmpty(), android.util.Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault("")
                val path = intent.getStringExtra("path").orEmpty()
                if (name.trim().length < 2 || !PATH_OK.matches(path) || (path.isEmpty() && intent.getBooleanExtra("resuelta", false))) return@background answer(CODE_ERROR, "Falta el nombre (name64, al menos 2 caracteres) o la ruta no es válida (con resuelta hace falta un campo): $path")
                val dao = app.kaizen.database.CatalogDB.INSTANCE.webFichaDao()
                // Con `--ez resuelta true` el dato sale de AniList si la ficha está enlazada y, si no lo tiene, de la web.
                val rows = if (intent.getBooleanExtra("resuelta", false)) {
                    val links = app.kaizen.database.UserDB.INSTANCE.animeFuenteDao()
                    val fichas = app.kaizen.database.CatalogDB.INSTANCE.animeFichaDao()
                    WebFichaQuery.resolved(dao, name, path, MAX_FICHA_ROWS) { web -> app.kaizen.source.FichaResuelta.anilistOf(links.get(web.fuente, web.slug), fichas::get) }
                } else WebFichaQuery.get(dao, name, path, MAX_FICHA_ROWS)
                val json = org.json.JSONArray(rows.map { org.json.JSONObject().put("fuente", it.fuente).put("slug", it.slug).put("value", it.value ?: org.json.JSONObject.NULL).put("origen", it.origen ?: org.json.JSONObject.NULL) }).toString()
                if (json.length > MAX_FICHA_BYTES) return@background answer(CODE_ERROR, "Resultado demasiado grande (${json.length} caracteres): pide un campo concreto con path")
                answer(CODE_OK, android.util.Base64.encodeToString(json.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP))
            } else if (action == ACTION_EXPORT_FICHAS) {
                // Como el recorrido: puede tardar más de lo que Android deja a un receptor, así que responde ya con el nombre del fichero y sigue en segundo plano.
                if (!exporting.compareAndSet(false, true)) return@background answer(CODE_ERROR, "Ya hay una exportación de fichas en curso")
                val name = FichaExport.nameFor(System.currentTimeMillis())
                AppScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val result = FichaExport.exportAll(KaizenBackup.directory, name)
                        DebugLog.info("ControlADB", "Fichas de AniList exportadas: ${result.rows} en ${result.file.name} (${result.file.length() / 1024} KB, comprobado)")
                    } catch (e: Throwable) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        logError(e)
                        DebugLog.info("ControlADB", "Exportación de fichas fallida: ${e.javaClass.simpleName}: ${e.message}")
                    } finally {
                        exporting.set(false)
                    }
                }
                answer(CODE_OK, "Exportación de fichas iniciada → $name")
            } else if (action == ACTION_WALK) {
                // El recorrido dura minutos: la respuesta no espera. Su avance se ve en el registro de la app.
                AppScope.launch { runCatchingCoop { CatalogBuilder.ensureFresh(force = true) }.onFailure(::logError) }
                answer(CODE_OK, "Recorrido del catálogo iniciado")
            } else if (action == ACTION_SPLIT) {
                // Deshace una unión equivocada: cada grupo de fichas del fichero `{"groups":[[["fuente","slug"],…],…]}` pasa a una serie propia.
                val name = intent.getStringExtra("file").orEmpty()
                val file = File(KaizenBackup.directory, name)
                if (name.isBlank() || '/' in name || '\\' in name || !name.endsWith(".json") || !file.isFile || file.length() > MAX_FILE_BYTES) return@background answer(CODE_ERROR, "Fichero no válido: $name")
                val groups = org.json.JSONObject(file.readText()).getJSONArray("groups")
                if (groups.length() > MAX_GROUPS) return@background answer(CODE_ERROR, "Demasiados grupos: ${groups.length()}")
                // Se lee y se valida todo el fichero antes de tocar nada: una entrada mal formada no deja el trabajo a medias.
                val parsed = (0 until groups.length()).map { g ->
                    val members = groups.getJSONArray(g)
                    (0 until members.length()).map { members.getJSONArray(it).let { f -> f.getString(0) to f.getString(1) } }
                }
                val oversized = parsed.count { it.size > MAX_GROUP_SIZE }
                var moved = 0
                for (fichas in parsed) if (fichas.size <= MAX_GROUP_SIZE && SeriesLedger.detachGroup(fichas) != null) moved++
                // Cambian los grupos de varias series: se rehace el catálogo agrupado en segundo plano (la respuesta no espera).
                if (moved > 0 && intent.getBooleanExtra("regroup", true)) AppScope.launch { runCatchingCoop { CatalogBuilder.regroup() }.onFailure(::logError) }
                answer(CODE_OK, "Series separadas: $moved de ${parsed.size} grupos" + if (oversized > 0) " ($oversized omitidos por tener más de $MAX_GROUP_SIZE fichas)" else "")
            } else if (action == ACTION_IMPORT) {
                // Solo un fichero .json de la carpeta de copias, por su nombre: ninguna ruta.
                val name = intent.getStringExtra("file").orEmpty()
                val file = File(KaizenBackup.directory, name)
                if (name.isBlank() || '/' in name || '\\' in name || !name.endsWith(".json") || !file.isFile) return@background answer(CODE_ERROR, "Fichero no válido: $name")
                val summary = KaizenBackup.import(file)
                answer(if (summary == null) CODE_ERROR else CODE_OK, if (summary == null) "La copia no es válida: $name" else "Añadido desde $name: $summary")
            } else {
                val db = CacheDB.INSTANCE
                // Abrir `catalog-db` es lo que ejecuta su migración la primera vez: si falla, el recuento lo dice y no tumba el resto.
                val catalog = runCatchingCoop {
                    val catalogDb = app.kaizen.database.CatalogDB.INSTANCE
                    "fichas=${catalogDb.animeFichaDao().count()} webfichas=${catalogDb.webFichaDao().count()} webfichas_con_idmal=${catalogDb.webFichaDao().countWithMal()} " +
                        "webfichas_por_fuente=" + catalogDb.webFichaDao().countByFuente().joinToString(",") { "${it.fuente}:${it.total}" }.ifEmpty { "-" }
                }.getOrElse { "catalog-db=error:${it.javaClass.simpleName}" }
                answer(CODE_OK, "favoritos=${db.favoriteAV1DAO().allRaw.size} vistos=${db.recordAV1DAO().count} $catalog ultima=${KaizenBackup.latest()?.name}")
            }
        }
    }

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
