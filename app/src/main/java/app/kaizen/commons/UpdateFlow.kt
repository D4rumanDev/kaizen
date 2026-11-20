package app.kaizen.commons

import android.content.Context
import app.kaizen.App
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * De «Descargar e instalar» al instalador del sistema, paso a paso y dejando cada paso a la vista ([ui]): permiso, buscar el APK de la release, espacio, bajar, huella,
 * paquete y firma, y la sesión de instalación. Cualquier fallo borra el fichero y deja el motivo; nada se instala si no pasa todo (ver [UpdateInstaller]).
 *
 * El orden de las comprobaciones está en [run], que no toca Android directamente sino a través de [Env]: así se prueba que ninguna se salta.
 */
internal object UpdateFlow {
    sealed class Ui {
        object Idle : Ui()
        object Preparing : Ui()
        data class Downloading(val done: Long, val total: Long) : Ui()
        object Verifying : Ui()
        /** Falta conceder «instalar apps desconocidas» a Kaizen. [canOpenSettings] es falso si esa TV no tiene la pantalla. */
        data class NeedsPermission(val canOpenSettings: Boolean = true) : Ui()
        /** El sistema enseña su confirmación: al aceptarla se instala y la app se reinicia. */
        object Installing : Ui()
        data class Failed(val message: String) : Ui()
    }

    /** Lo que el flujo necesita del sistema; las pruebas ponen uno falso. */
    internal interface Env {
        val client: OkHttpClient
        /** Carpeta de trabajo (privada de la app); se borra entera al limpiar. */
        val folder: File
        fun canInstall(): Boolean
        fun installedFacts(): UpdateInstaller.PackageFacts
        fun archiveFacts(apk: File): UpdateInstaller.PackageFacts?
        fun install(apk: File)
    }

    private class AndroidEnv(private val app: Context) : Env {
        // Sin redirecciones entre https y http; la dirección de partida ya es https de nuestra release.
        override val client: OkHttpClient by lazy {
            AppHttpClient.strict().newBuilder().followSslRedirects(false).connectTimeout(10.seconds).readTimeout(30.seconds).callTimeout(10.minutes).dns(FallbackDns).build()
        }
        override val folder: File get() = File(app.cacheDir, "updates")
        override fun canInstall() = UpdateInstall.canInstall(app)
        override fun installedFacts() = UpdateInstall.installedFacts(app)
        override fun archiveFacts(apk: File) = UpdateInstall.archiveFacts(app, apk)
        override fun install(apk: File) = UpdateInstall.install(app, apk)
    }

    private val _ui = MutableStateFlow<Ui>(Ui.Idle)
    val ui: StateFlow<Ui> get() = _ui

    private var job: Job? = null

    /** Borra lo que quedara de un intento anterior (al terminar o fallar). */
    private fun cleanup(env: Env) {
        runCatching { env.folder.deleteRecursively() }
    }

    /** Borra lo que quedara de un intento anterior al arrancar la app, salvo que haya una descarga en marcha. */
    fun cleanup(context: Context) {
        if (!isRunning()) cleanup(AndroidEnv(context.applicationContext))
    }

    fun isRunning(): Boolean = job?.isActive == true

    /** Empieza (o repite tras conceder el permiso). Si ya hay una descarga en marcha no hace nada. */
    @Synchronized
    fun start(context: Context) {
        if (isRunning()) return
        val env = AndroidEnv(context.applicationContext)
        job = AppScope.launch(Dispatchers.IO) {
            try {
                run(env)
            } catch (e: CancellationException) {
                cleanup(env)
                _ui.value = Ui.Idle
                throw e
            } catch (e: Exception) {
                cleanup(env)
                logError(e)
                _ui.value = Ui.Failed(if (e is IOException) "No se pudo bajar la actualización: ${e.message ?: "fallo de red"}" else "La actualización falló")
            }
        }
    }

    /** Cancela la descarga en marcha. */
    @Synchronized
    fun cancel() {
        job?.cancel()
    }

    /** El ajuste de permisos no existe en esta TV: el diálogo lo dice y ofrece la dirección. */
    fun noSettings() {
        if (!isRunning()) _ui.value = Ui.NeedsPermission(canOpenSettings = false)
    }

    /** Cierra el estado final (fallo, permiso) para volver al diálogo de partida. */
    fun reset() {
        if (!isRunning()) _ui.value = Ui.Idle
    }

    private fun fail(env: Env, message: String) {
        cleanup(env)
        _ui.value = Ui.Failed(message)
    }

    /** Todo el recorrido; el estado final queda en [ui]. Lanza `CancellationException` si se cancela. */
    internal suspend fun run(env: Env) {
        val tag = UpdateChecker.state.latestTag
        if (tag == null || !UpdateChecker.isNewer(UpdateChecker.currentVersion, tag.removePrefix("v"))) return fail(env, "Ya no hay una versión nueva")
        if (!env.canInstall()) {
            _ui.value = Ui.NeedsPermission(canOpenSettings = true)
            return
        }
        _ui.value = Ui.Preparing
        cleanup(env)
        val json = try { UpdateChecker.fetcher() } catch (e: IOException) { return fail(env, "No se pudo mirar GitHub") }
        val asset = when (val picked = UpdateInstaller.pickAsset(json, tag)) {
            is UpdateInstaller.AssetResult.Refused -> return fail(env, picked.reason)
            is UpdateInstaller.AssetResult.Ok -> picked.asset
        }
        val folder = env.folder.apply { mkdirs() }
        if (folder.usableSpace < asset.size * 2) return fail(env, "No hay espacio libre para bajar la actualización")
        val apk = File(folder, UpdateInstaller.ASSET)
        var last = 0L
        val hash = UpdateInstaller.download(env.client, asset.url, apk, asset.size) { done, total ->
            // Sin inundar la pantalla: un cambio como mucho cada 256 KB.
            if (done - last >= 256 * 1024 || done == total) { last = done; _ui.value = Ui.Downloading(done, total) }
        }
        _ui.value = Ui.Verifying
        if (hash != asset.sha256) return fail(env, "La huella del fichero no coincide con la de GitHub: no se instala")
        UpdateInstaller.verifyPackage(env.installedFacts(), env.archiveFacts(apk))?.let { return fail(env, "$it: no se instala") }
        DebugLog.info("Actualizaciones", "Paquete verificado (${asset.tag}); se pasa al instalador del sistema")
        env.install(apk)
        _ui.value = Ui.Installing
    }

    /** El resultado de la sesión cuando no fue bien (cancelada por el usuario, sin espacio, firma rechazada…). */
    fun onInstallResult(status: Int, message: String?) {
        cleanup(AndroidEnv(App.context))
        DebugLog.info("Actualizaciones", "Instalación sin completar (estado $status)")
        _ui.value = Ui.Failed(if (status == android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED) "Instalación cancelada" else "El sistema no instaló la actualización${message?.let { ": $it" }.orEmpty()}")
    }
}
