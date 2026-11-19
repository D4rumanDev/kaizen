@file:OptIn(ExperimentalCoilApi::class)

package app.kaizen

import app.kaizen.commons.logError
import coil3.annotation.ExperimentalCoilApi
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate
import androidx.profileinstaller.ProfileInstaller
import androidx.work.Configuration
import androidx.work.WorkManager
import app.kaizen.commons.AppHttpClient
import app.kaizen.commons.CrashGuard
import app.kaizen.commons.HostGateInterceptor
import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.TlsPolicy
import app.kaizen.jobscheduler.RecentsWork
import app.kaizen.source.AnimeSources
import app.kaizen.source.spec.SpecSources
import app.kaizen.videoservers.Server
import coil3.ImageLoader
import coil3.request.bitmapConfig
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.memoryCacheMaxSizePercentWhileInBackground
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.Dispatcher
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import app.kaizen.commons.runCatchingCoop
import app.kaizen.commons.AppScope
import app.kaizen.commons.await
import kotlin.time.Duration.Companion.seconds

class App : Application(), Configuration.Provider {

    @TargetApi(Build.VERSION_CODES.O)
    private fun createChannels() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
        manager?.createNotificationChannel(
            NotificationChannel(
                RecentsWork.CHANNEL_RECENTS,
                "Capitulos recientes",
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    /** El sistema avisa de que va justo de memoria: queda anotado (E16) con lo que usa la app, para saber si el cierre o la lentitud vienen de ahí. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Soltar memoria cuando conviene (interfaz oculta: vaciar las imágenes; sistema justo: recortarlas a la mitad), antes de decidir si se anota el aviso.
        runCatching { app.kaizen.commons.MemoryBudget.onTrim(this, level) }.onFailure(::logError)
        // Los niveles 20, 40 y 60 saltan cada vez que se pulsa Inicio: no son falta de memoria.
        if (!app.kaizen.commons.AppErrors.isTrimPressure(level)) return
        val rt = Runtime.getRuntime()
        val usage = "la app usa ${(rt.totalMemory() - rt.freeMemory()) shr 20} de ${rt.maxMemory() shr 20} MB"
        // Escribe en el disco: fuera del hilo principal, que es donde llega este aviso.
        AppScope.launch {
            app.kaizen.commons.AppErrors.record(app.kaizen.domain.ConnectionStats.Cause.LOW_MEMORY, app.kaizen.commons.AppErrors.Area.APP, "El sistema pide liberar memoria (nivel $level); $usage")
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        context = this
        // Si el registro de depuración no se puede escribir en el disco (sin espacio, carpeta no disponible), el gestor de errores lo anota como E20.
        app.kaizen.commons.DebugLog.onDiskFailure = { reason ->
            app.kaizen.commons.AppErrors.record(app.kaizen.domain.ConnectionStats.Cause.STORAGE, app.kaizen.commons.AppErrors.Area.STORAGE, "El registro de depuración no se puede escribir: $reason")
        }
        TlsPolicy.install()
        // Mantenimiento que no hace falta para pintar la pantalla principal: espera a que ya tenga contenido (carga diferida).
        AppScope.launch {
            delay(LAZY_START_DELAY)
            runCatchingCoop { app.kaizen.commons.RecordSourceRepair.runOnce() }.onFailure(::logError)
            runCatchingCoop { app.kaizen.source.ApiCache.purgeOld() }.onFailure { logError(it, app.kaizen.commons.AppErrors.Area.CACHE) }
        }
        if (!WorkManager.isInitialized()) {
            WorkManager.initialize(this, Configuration.Builder().build())
        }
        AppScope.launch { delay(LAZY_START_DELAY); removeLeftoversOnce() }
        // Ids de serie en memoria: las pantallas los leen desde el hilo principal sin tocar la base de datos. Si la lectura falla se
        // reintenta (sin ellos los favoritos y lo visto solo se reconocen por aid y las reglas del usuario no se aplican).
        AppScope.launch(Dispatchers.IO) {
            // La base de los datos del usuario se abre aquí, fuera de cualquier transacción de la del catálogo.
            runCatchingCoop { app.kaizen.database.UserDB.INSTANCE.openHelper.writableDatabase }.onFailure(::logError)
            // El progreso guardado por eid (versiones anteriores) pasa a la clave de la ficha, con el historial ya abierto.
            runCatchingCoop { app.kaizen.commons.DebugLog.timed("Arranque", "Progreso de reproducción pasado a identificadores de capítulo") { app.kaizen.commons.KaizenProgress.migrateEidKeys(app.kaizen.database.CacheDB.INSTANCE.recordAV1DAO().all) } }.onFailure(::logError)
            repeat(LEDGER_ATTEMPTS) { attempt ->
                if (runCatchingCoop { app.kaizen.commons.DebugLog.timed("Arranque", "Libro de series cargado en memoria") { app.kaizen.source.SeriesLedger.load() } }.onFailure(::logError).isSuccess) {
                    // El progreso guardado por ficha pasa a la clave de su serie, ya con el libro cargado.
                    app.kaizen.commons.KaizenProgress.migrateFichaKeys { source, slug -> app.kaizen.source.seriesIdOf(source, slug) }
                    // El catálogo (de él dependen unir series y el selector) solo se recorre desde Configuración › Catálogo: a mano, o solo
                    // al arrancar si «Recorrido automático» está activado.
                    // Con las reglas del usuario de ahora: lo que se unió o separó antes de una corrección queda bien sin esperar.
                    // Reagrupar y recorrer el catálogo es lo más pesado del arranque y no hace falta para ver Inicio: después.
                    awaitHomeReady()
                    // Reagrupar puede llevar decenas de segundos de CPU: a prioridad de fondo, para no frenar la pantalla.
                    runCatchingCoop { kotlinx.coroutines.withContext(app.kaizen.commons.BackgroundCpu) { app.kaizen.commons.DebugLog.timed("Arranque", "Catálogo reagrupado (une las series repetidas de distintas webs)") { app.kaizen.source.CatalogBuilder.regroupIfStale() } } }.onFailure(::logError)
                    runCatchingCoop { app.kaizen.commons.DebugLog.timed("Arranque", "Nota de cada serie del catálogo sustituida por la de AniList (solo si hacía falta)") { app.kaizen.source.CatalogBuilder.rescoreIfNeeded() } }.onFailure(::logError)
                    runCatchingCoop { app.kaizen.commons.DebugLog.timed("Arranque", "Catálogo comprobado contra el repositorio (descarga lo que falte)") { app.kaizen.source.CatalogBuilder.ensureFresh() } }.onFailure(::logError)
                    // Emparejar con AniList (encendido de fábrica: da la nota de las series): arranca salvo que el usuario lo apagara. Su primera pasada son ~30 s de CPU y memoria (26.000 fichas: el
                    // GC no paraba y «Últimos vistos» tardaba en salir), así que espera a que la app lleve un rato abierta.
                    delay(ANILIST_START_DELAY)
                    app.kaizen.commons.DebugLog.timed("Arranque", "Emparejado con AniList puesto en marcha") { app.kaizen.source.AnilistSyncRunner.apply() }
                    return@launch
                }
                if (attempt < LEDGER_ATTEMPTS - 1) delay(LEDGER_RETRY * (attempt + 1))
            }
        }
        // Filas de la pantalla de inicio de Android TV (Continuar viendo y Capítulos recientes): no hacen falta para pintar Inicio.
        AppScope.launch {
            awaitHomeReady()
            // Sin `timed` aquí: keepInSync se queda escuchando para siempre y la cifra sería «hasta que se cancela»; mide solo el volcado inicial (dentro).
            runCatchingCoop { app.kaizen.tv.HomeChannels.keepInSync(this@App) }.onFailure(::logError)
        }
        // Fuentes por fichero: mira el repositorio de fuentes (como mucho cada 12 h); lo nuevo se usa en el siguiente arranque.
        AppScope.launch(Dispatchers.IO) {
            AnimeSources.all
            delay(LAZY_START_DELAY)
            SpecSources.refresh()
        }
        // El InitializationProvider de androidx.startup está quitado del manifiesto, así que el perfil de arranque
        // (assets/dexopt/baseline.prof) se instala a mano; ART lo compila en la próxima optimización en reposo.
        AppScope.launch {
            delay(PROFILE_INSTALL_DELAY)
            runCatchingCoop { ProfileInstaller.writeProfile(this@App) }.onFailure(::logError)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val processName = getProcessName()
            if (packageName != processName) {
                WebView.setDataDirectorySuffix(processName)
            }
        }
        OkHttp.initialize(this)
        // Los ajustes de medida (esperas, días, tamaños) llegan a los módulos que no leen preferencias.
        app.kaizen.commons.Tuning.apply()
        // ¿Hay Internet de verdad? Sigue los cambios de red al instante (lo enseña «Fuentes») y el interruptor «Modo sin conexión».
        app.kaizen.commons.NetworkState.start(this)
        configureImageLoader()
        // Después del cargador de imágenes: un restablecimiento automático vacía su caché.
        CrashGuard.install()
        AppCompatDelegate.setDefaultNightMode(KaizenSettings.themeOption.toInt())
        createChannels()
    }

    /**
     * Coil: las portadas salen por el cliente HTTP de la app (conexiones HTTP/2 ya abiertas con los CDN y su política
     * TLS) y la caché de memoria guarda los bitmaps ya decodificados de varias pantallas, que es lo que más se nota al
     * volver sobre una rejilla.
     */
    private fun configureImageLoader() {
        // Las portadas comparten conexiones y TLS con el resto de la app pero no la cola de peticiones: el recorrido del
        // catálogo (cientos de páginas) no las hace esperar.
        imageClient = AppHttpClient.streaming().newBuilder()
            .dispatcher(Dispatcher().apply { maxRequests = IMAGE_MAX_REQUESTS; maxRequestsPerHost = IMAGE_MAX_PER_HOST })
            // Las fuentes cuya web rechaza la firma de OkHttp (también su CDN de portadas) reciben la de un navegador.
            .addInterceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                if (browserUaHosts.any { host == it || host.endsWith(".$it") })
                    chain.proceed(request.newBuilder().header("User-Agent", Server.BROWSER_USER_AGENT).build())
                else chain.proceed(request)
            }
            // HostGate sin turno (las portadas siguen en paralelo): un servidor en pausa no recibe peticiones, y un 403 de
            // bloqueo o una caída seguidos lo pausan. Un 404 de una portada no cuenta.
            .addInterceptor(HostGateInterceptor)
            .build()
        // Los proveedores de contenido (ChannelArtProvider) se publican antes de onCreate: el launcher puede pedir una imagen mientras esto aún no ha corrido y crear el cargador
        // por defecto; `setSafe` lanzaría entonces y la app no arrancaba (visto en la TV). Con el fallo se sigue con el cargador que ya exista.
        try { SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components {
                    // Lo que se sabe que no existe (404, .ico indecodificable) no se vuelve a pedir en una semana.
                    add(app.kaizen.commons.MissingImageInterceptor)
                    add(OkHttpNetworkFetcherFactory(callFactory = { imageClient }, cacheStrategy = { app.kaizen.commons.CacheFirstStrategy }))
                }
                .eventListener(app.kaizen.commons.ImageErrorListener)
                // Calidad de las portadas (ver ImageQuality): solo la «ligera» fija 16 bits de color; las demás dejan el valor de Coil (bitmaps de hardware donde se puede).
                .apply { if (app.kaizen.commons.ImageQuality.level(context) == app.kaizen.commons.ImageQuality.Level.LIGHT) bitmapConfig(android.graphics.Bitmap.Config.RGB_565) }
                .memoryCache { MemoryCache.Builder().maxSizePercent(context, app.kaizen.commons.KaizenSettings.imageMemoryPercent.coerceIn(5, 40) / 100.0).build() }
                // En segundo plano la caché se recorta (Coil 3.3+; las imágenes aún en uso no se tocan). El valor, como el resto de límites de memoria, sale de MemoryBudget.
                .memoryCacheMaxSizePercentWhileInBackground(app.kaizen.commons.MemoryBudget.IMAGE_CACHE_BACKGROUND_PERCENT)
                .build()
        } } catch (e: IllegalStateException) {
            android.util.Log.w("App", "El cargador de imágenes ya existía: se usa el que hay", e)
        } finally { imageLoaderReady.countDown() }
    }

    /** Restos de funciones quitadas (logros, gestor de descargas, copias): una vez por instalación. */
    private fun removeLeftoversOnce() {
        val prefs = app.kaizen.commons.KaizenStore.prefs("kaizen_repairs")
        if (prefs.getBoolean(LEFTOVERS_DONE, false)) return
        LEFTOVER_DATABASES.forEach(::deleteDatabase)
        WorkManager.getInstance(this).run {
            cancelUniqueWork("backupObj-job")
            cancelAllWorkByTag("backupObj-job")
        }
        prefs.edit().putBoolean(LEFTOVERS_DONE, true).apply()
    }

    private lateinit var imageClient: OkHttpClient

    private val browserUaHosts by lazy { AnimeSources.all.filter { it.needsBrowserUa }.map { it.host } }

    /**
     * Espera a que Inicio tenga sus «Recientes»: pasado [LAZY_START_DELAY] (para dar tiempo a que la pantalla pida la carga), y mientras siga cargando, hasta
     * [HOME_READY_MAX]. Así el mantenimiento pesado (reagrupar, recorrer el catálogo, filas del launcher) no compite por la CPU con lo primero que se ve, que en una
     * tele lenta como la TV es lo que más se nota. Pasado el tope sigue igualmente: una carga que no termina no debe dejar el mantenimiento sin hacer.
     */
    private suspend fun awaitHomeReady() {
        delay(LAZY_START_DELAY)
        kotlinx.coroutines.withTimeoutOrNull(HOME_READY_MAX) { app.kaizen.retrofit.Repository.recentsLoading.first { !it } }
    }

    companion object {
        private val imageLoaderReady = java.util.concurrent.CountDownLatch(1)
        private val IMAGE_LOADER_WAIT = 5.seconds

        /** El cargador de imágenes de Coil, cuando la app ya lo ha configurado (con un tope de espera): para quien lo pide desde un hilo ajeno al arranque, como un proveedor de contenido. */
        fun awaitImageLoader(context: Context): ImageLoader {
            imageLoaderReady.await(IMAGE_LOADER_WAIT)
            return SingletonImageLoader.get(context)
        }

        private const val LEFTOVERS_DONE = "leftovers_v1"
        private val HOME_READY_MAX = 45.seconds
        private val PROFILE_INSTALL_DELAY = 5.seconds

        /** Cuánto espera el trabajo de segundo plano que no hace falta para pintar Inicio (también lo usa TVMain: una sola definición). */
        internal val LAZY_START_DELAY = 8.seconds
        /** Espera extra del emparejado con AniList tras el resto del arranque diferido. */
        private val ANILIST_START_DELAY = 60.seconds
        private const val LEDGER_ATTEMPTS = 3
        private val LEDGER_RETRY = 5.seconds
        private const val IMAGE_MAX_REQUESTS = 24
        private const val IMAGE_MAX_PER_HOST = 8
        private val LEFTOVER_DATABASES = listOf("ee-db", "LibGlobalFetchLib.db")
        @SuppressLint("StaticFieldLeak")
        lateinit var context: Context
            private set

    }
}
