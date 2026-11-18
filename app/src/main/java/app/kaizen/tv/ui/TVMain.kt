package app.kaizen.tv.ui

import app.kaizen.tv.ui.screens.LandingScreen
import app.kaizen.tv.ui.theme.*

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.kaizen.commons.AppExit
import app.kaizen.commons.BypassUtil
import app.kaizen.commons.CrashGuard
import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.Network
import app.kaizen.commons.doOnUI
import app.kaizen.commons.toast
import app.kaizen.jobscheduler.BackupWork
import app.kaizen.domain.StatusMigration
import app.kaizen.jobscheduler.RecentsWork
import app.kaizen.recents.RecentsNotReceiver
import app.kaizen.retrofit.Repository
import app.kaizen.tv.TVBaseActivity
import app.kaizen.tv.TVServersFactory
import app.kaizen.tv.settings.RecoveryPanel
import app.kaizen.bypass.BypassActivity
import app.kaizen.bypass.BypassStatus
import app.kaizen.bypass.launchBypass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.kaizen.R
import app.kaizen.tv.settings.OpenRequest
import app.kaizen.commons.appString
import kotlin.time.Duration.Companion.seconds

class TVMain : TVBaseActivity(), TVServersFactory.ServersInterface {

    private val model: MainViewModel by viewModels()
    private var lastBackAt = 0L

    /** Sección que pide otra pantalla (el menú de la ficha) y la hora del pedido, para repetir la misma. */
    private var requestedSection by mutableStateOf<Pair<Int, Long>?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readRequestedSection(intent)
    }

    /** Devuelve si el intent traía un enlace a una ficha (kaizen://tv/series) y se abrió. */
    private fun readRequestedSection(intent: Intent): Boolean {
        var linkOpened = false
        // kaizen://tv/series?...: lo abren las filas de la pantalla de inicio de Android TV (la ficha no está exportada).
        intent.data?.takeIf { it.scheme == "kaizen" && it.path == "/series" }?.let { uri ->
            // BROWSABLE: cualquier app o web puede lanzar este enlace, así que solo se abren URLs https de las fuentes de la app.
            val url = uri.getQueryParameter("url")?.takeIf { isSourceUrl(it) } ?: return@let
            app.kaizen.tv.details.TVAnimesDetails.start(this, url)
            linkOpened = true
        }
        // Pedir la sección que ya se está viendo solo cierra las fichas: no se vuelve a seleccionar (sin recarga ni salto de foco).
        intent.getIntExtra(EXTRA_SECTION, -1).takeIf { it >= 0 && it != currentSection }?.let { requestedSection = it to SystemClock.elapsedRealtime() }
        // Una pantalla de Configuración que se abre sola (adb: `node tools/tv.js series`); SettingsPage consume el pedido.
        // Solo con la sección de Configuración (si no, el pedido se quedaría esperando hasta la próxima visita a Configuración).
        if (intent.getIntExtra(EXTRA_SECTION, -1) == MainSections.TOOLS) intent.getStringExtra(EXTRA_OPEN)?.let { OpenRequest.dialog.value = it }
        return linkOpened
    }

    private fun isSourceUrl(url: String): Boolean {
        val parsed = Uri.parse(url)
        val host = parsed.host ?: return false
        return parsed.scheme == "https" && app.kaizen.source.AnimeSources.all.any { host == it.host || host.endsWith(".${it.host}") }
    }

    /** Panel de emergencia (restablecer ajustes, vaciar la caché), abierto con Atrás mantenido. */
    private var recoveryOpen by mutableStateOf(false)

    /**
     * Atrás mantenido abre el panel de emergencia desde cualquier punto del menú, aunque Configuración no se pueda
     * alcanzar (sección oculta, interfaz ilegible). Al consumirse, soltar la tecla no cuenta como «Atrás».
     */
    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_BACK) return super.onKeyLongPress(keyCode, event)
        recoveryOpen = true
        return true
    }

    /**
     * Atrás, como en SmartTube: en el contenido abre el menú; en el menú, la primera pulsación avisa y la segunda
     * (antes de [EXIT_WINDOW]) cierra la app con sus conexiones.
     */
    private fun onBackAtMenu() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackAt < EXIT_WINDOW.inWholeMilliseconds) {
            AppExit.exit(this)
        } else {
            lastBackAt = now
            KaizenToast.show(appString(R.string.ui_pulsa_atras_otra_vez_para_salir))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CrashGuard.takeResetNotice()) {
            KaizenToast.show(appString(R.string.ui_kaizen_se_cerro_varias_veces_seguidas_ajustes_y), longDuration = true)
        }
        // Con la actividad recreada el intent es el mismo: no se vuelve a abrir la ficha.
        // Un menú nuevo que llega por un enlace sobre otro que ya estaba abierto (o con una ficha encima) cede a la ficha y se cierra: no se apilan dos.
        if (savedInstanceState == null && readRequestedSection(intent) && !isTaskRoot) finish()
        KaizenSettings.migrateHiddenRows(setOf(MainSections.RECENTS, MainSections.SECTIONS_V1, MainSections.CONTINUE))
        setContent {
            KaizenTheme {
                LandingScreen(model, this, requestedSection, ::onBackAtMenu)
                if (recoveryOpen) RecoveryPanel { recoveryOpen = false }
            }
        }
        if (savedInstanceState == null) start()
    }

    private fun start() {
        RecentsNotReceiver.removeAll(this)
        checkCloudflare()
        // Túnel WARP solo para Kaizen (apagado de fábrica): al abrir la app y cada pocos minutos se mira si hay que subirlo o bajarlo.
        app.kaizen.commons.warp.WarpTunnel.watch()
        // Programar los trabajos periódicos y migrar estados no hace falta para ver Inicio: carga diferida.
        val appContext = applicationContext
        app.kaizen.commons.AppScope.launch {
            kotlinx.coroutines.delay(app.kaizen.App.LAZY_START_DELAY)
            RecentsWork.schedule(appContext)
            BackupWork.schedule(appContext)
            StatusMigration.launch()
        }
    }

    // Kaizen: sin red no se comprueba nada.
    private fun checkCloudflare() {
        if (!Network.isConnected) return
        // Estas pruebas solo atañen a animeav1 (Cloudflare y User-Agent): sin esa fuente activa no se hacen.
        if (app.kaizen.source.AnimeSources.enabled.none { it.id == app.kaizen.source.AnimeAv1.ID }) return
        lifecycleScope.launch(Dispatchers.Main) {
            if (withContext(Dispatchers.IO) { BypassUtil.isNeeded() } && launchBypass(BYPASS_REQUEST))
                KaizenToast.show("Verificando Cloudflare...")
        }
    }

    override fun onFinish(started: Boolean, success: Boolean) {

    }

    override fun onResume() {
        super.onResume()
        // onActivityResult llega antes: si el bypass volvió sin resultado, la guarda no se queda puesta.
        BypassUtil.isLoading = false
    }

    // La verificación de Cloudflare vuelve por startActivityForResult; pasarla a la Activity Result API es otro cambio.
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == BYPASS_REQUEST) {
            val cookiesUpdated = data?.let {
                BypassUtil.saveCookies(this, it.getStringExtra(BypassActivity.RESULT_COOKIES) ?: "null")
            } ?: false
            Repository.reloadRecents()
            BypassUtil.isLoading = false
            RecentsWork.run()
            doOnUI {
                when {
                    cookiesUpdated -> "Bypass actualizado"
                    data != null -> "Cloudflare no pide verificación ahora"
                    BypassStatus.snapshot.value.state == BypassStatus.State.UNREACHABLE ->
                        "Cloudflare no responde: se reintentará más tarde (estado en Fuentes)"
                    else -> "No se pudo completar el bypass de Cloudflare"
                }.toast()
            }
        }
    }


    companion object {
        private const val EXTRA_SECTION = "section"
        private const val EXTRA_OPEN = "open"

        /** Sección que muestra ahora la pantalla principal (la ficha de encima la usa para su menú). -1 si no hay ninguna. */
        @Volatile var currentSection = -1

        /** Vuelve a la pantalla principal (cerrando las fichas abiertas encima) en la sección [id]. */
        fun openSection(context: android.content.Context, id: Int) {
            context.startActivity(Intent(context, TVMain::class.java).putExtra(EXTRA_SECTION, id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }

        /** Código del resultado del bypass de Cloudflare (al arrancar y desde Configuración). */
        const val BYPASS_REQUEST = 7425
        private val EXIT_WINDOW = 2.seconds
    }
}
