package app.kaizen.tv.details

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kaizen.tv.ui.screens.*
import app.kaizen.tv.ui.theme.*

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.addCallback
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.key
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import app.kaizen.commons.HostGate
import app.kaizen.pojos.av1.Chapter
import app.kaizen.source.AnimeSources
import app.kaizen.tv.TVBaseActivity
import app.kaizen.tv.TVServersFactory
import app.kaizen.tv.TVServersFactory.ServerChoice
import app.kaizen.tv.ui.ProvideSeriesMarks
import app.kaizen.tv.ui.TVMain
import app.kaizen.tv.ui.KaizenToast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import app.kaizen.R
import app.kaizen.commons.appString

/**
 * Kaizen: ficha del anime, en Compose ([SeriesDetailScreen]); aquí solo se arranca la reproducción y se abren otras fichas.
 * Las fichas abiertas desde otra ficha viven en esta misma pantalla (una pila de URL, con tope): el menú lateral no se
 * recrea ni parpadea al pasar de una a otra, y Atrás vuelve a la anterior.
 */
class TVAnimesDetails : TVBaseActivity(), TVServersFactory.ServersInterface {
    private val urls = mutableStateListOf<String>()

    // Un almacén de modelos por URL: al volver a una ficha anterior conserva lo cargado, y al salir de la pila se libera
    // (también una ficha que falló: al reabrirla se vuelve a pedir).
    private val stores = HashMap<String, ViewModelStore>()

    private fun modelFor(url: String): FichaViewModel =
        ViewModelProvider(stores.getOrPut(url) { ViewModelStore() }, FichaViewModel.Factory(url))[FichaViewModel::class.java]

    /** Saca la ficha de arriba de la pila (si hay otra debajo) y libera su modelo; con una sola, cierra la pantalla. */
    private fun closeTop() {
        if (urls.size > 1) release(urls.removeAt(urls.lastIndex)) else finish()
    }

    private fun release(url: String) {
        if (url !in urls) stores.remove(url)?.clear()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_STACK, ArrayList(urls))
    }

    override fun onDestroy() {
        stores.values.forEach { it.clear() }
        stores.clear()
        super.onDestroy()
    }

    private val current: FichaViewModel get() = modelFor(urls.last())

    private val actions = FichaActions(
        onPlay = ::play,
        onOpen = ::open,
        onFavorite = { current.toggleFavorite() },
        onSeen = { seen -> current.setAllSeen(seen) },
        onStatus = { status -> current.setStatus(status) }
    )

    /** Abre otra ficha encima de la actual; con más de [MAX_STACK] se olvida la más antigua. */
    fun open(url: String) {
        if (urls.lastOrNull() == url) return
        urls.add(url)
        while (urls.size > MAX_STACK) release(urls.removeAt(0))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // La pila sobrevive a que Android recree la pantalla (cambio de configuración); si no, solo la URL de entrada
        // (las notificaciones abren esta pantalla con la URL en data).
        urls.addAll(savedInstanceState?.getStringArrayList(KEY_STACK)?.takeIf { it.isNotEmpty() }
            ?: listOf(intent.getStringExtra(keyUrl) ?: intent.dataString ?: ""))
        onBackPressedDispatcher.addCallback(this) { closeTop() }
        setContent {
            KaizenTheme {
                val url = urls.last()
                key(url) {
                    val state by modelFor(url).state.collectAsStateWithLifecycle()
                    FichaMenu(onSection = { TVMain.openSection(this, it); finish() }, loading = state !is FichaViewModel.State.Ready) { when (val ficha = state) {
                        is FichaViewModel.State.Ready -> ProvideSeriesMarks { SeriesDetailScreen(ficha, actions) }
                        // Hueco con la forma de la ficha, no una pantalla negra.
                        FichaViewModel.State.Loading -> FichaSkeleton()
                        FichaViewModel.State.Failed -> LaunchedEffect(Unit) {
                            KaizenToast.toast(failureMessage(url))
                            closeTop()
                        }
                    } }
                }
            }
        }
    }

    // Al volver del reproductor cambian el progreso y los vistos.
    override fun onResume() {
        super.onResume()
        current.refresh()
    }

    private fun play(chapter: Chapter?, choice: ServerChoice) {
        chapter ?: return KaizenToast.toast(appString(R.string.ui_no_hay_episodios))
        TVServersFactory.start(this, chapter.link, chapter.name, chapter.episodeName, chapter.asRecord(), this, choice)
    }

    override fun onFinish(started: Boolean, success: Boolean) {
        if (success) current.refresh()
    }

    companion object {

        private const val keyUrl = "url"
        private const val MAX_STACK = 6
        private const val KEY_STACK = "fichas"

        fun start(context: Context, url: String?, inPlace: Boolean = true) {
            url ?: return
            // Desde una ficha se abre en la misma pantalla; desde cualquier otra, una ficha nueva.
            if (inPlace && context is TVAnimesDetails) return context.open(url)
            context.startActivity(Intent(context, TVAnimesDetails::class.java).putExtra(keyUrl, url))
        }
    }

    /** Por qué no cargó la ficha de [url]: si su web está en pausa, hasta cuándo; si no, el mensaje general. */
    private fun failureMessage(url: String): String {
        val host = AnimeSources.all.firstOrNull { url.contains(it.host) }?.host ?: return "No se pudo cargar la ficha"
        val pausedUntil = HostGate.downUntil(host).takeIf { it > 0 } ?: return "No se pudo cargar la ficha"
        return "$host no responde: en pausa hasta ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(pausedUntil))}"
    }
}
