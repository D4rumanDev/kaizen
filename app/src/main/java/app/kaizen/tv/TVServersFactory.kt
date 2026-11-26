package app.kaizen.tv

import app.kaizen.domain.ConnectionStats
import app.kaizen.domain.Library
import app.kaizen.domain.SeriesStatuses
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import app.kaizen.App
import app.kaizen.commons.BypassUtil
import app.kaizen.commons.HostGate
import app.kaizen.commons.JsExtractor
import app.kaizen.commons.KaizenProxy
import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.doOnUIGlobal
import app.kaizen.commons.iterator
import app.kaizen.commons.logError
import app.kaizen.commons.logUnlessCancelled
import app.kaizen.commons.roundedString
import app.kaizen.player.openWebPlayer
import app.kaizen.pojos.av1.Record
import app.kaizen.tv.exoplayer.TVPlayer
import app.kaizen.tv.streaming.ChoiceOption
import app.kaizen.tv.streaming.ChoicePrompt
import app.kaizen.tv.streaming.LoadProgress
import app.kaizen.tv.streaming.LoadStep
import app.kaizen.tv.streaming.LoadSteps
import kotlinx.coroutines.Job
import app.kaizen.videoservers.Option
import app.kaizen.videoservers.Server
import app.kaizen.videoservers.ServerResolver
import app.kaizen.videoservers.VideoServer
import app.kaizen.videoservers.WebServer
import kotlinx.coroutines.launch
import app.kaizen.commons.background
import app.kaizen.tv.ui.KaizenToast
import app.kaizen.R
import app.kaizen.commons.appString
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration


class TVServersFactory private constructor(
    private val activity: ComponentActivity,
    private val url: String,
    private val name: String,
    private val chapter: String,
    private val record: Record,
    private val serversInterface: ServersInterface,
    private val choice: ServerChoice,
    /** Ranura de «una petición a la vez» de esta búsqueda: solo ella la libera. */
    private val slot: Any,
    /** Ventana con las fases; Atrás llama a [cancel]. */
    private val progress: LoadProgress
) {
    @Volatile private var cancelled = false
    /** Esta petición ya terminó (con éxito, fallo o cancelación): [onFinish][ServersInterface.onFinish] se avisa una sola vez. */
    private val ended = AtomicBoolean(false)
    @Volatile private var job: Job? = null

    private var servers: MutableList<Server> = ArrayList()
    private var subServes: List<Server> = emptyList()
    private var dubServers: List<Server> = emptyList()

    private var current: VideoServer? = null
    // El servidor recordado se intenta una sola vez; si falla, sale la lista.
    @Volatile private var rememberedTried = false
    /** El servidor que se intentó (recordado o elegido) falló: la lista sale de inmediato, sin volver a comprobar todos. */
    @Volatile private var attemptFailed = false
    private var usedServer: String? = null
    /** Nombre del servidor y fuente que ofreció el enlace usado, tal como los guardan las estadísticas (el panel de depuración los cruza con su historia). */
    private var usedServerName: String? = null
    private var usedLinkSource: String? = null

    /** Etiquetas mostradas por servidor cuando el mismo alojador viene de varias fuentes (progreso y avisos: llevan la fuente). */
    private val serverLabels = mutableMapOf<Server, String>()

    /** Texto del servidor en la lista, que ya va bajo el encabezado de su fuente: el nombre y, si la fuente lo etiqueta, el idioma. */
    private val listLabels = mutableMapOf<Server, String>()

    /** Fuente que ofreció cada servidor (la lista sale agrupada por ella y las estadísticas de fallos la usan). */
    private val serverSources = mutableMapOf<Server, String>()

    /** Fuente de los servidores cuando no se anota otra (animeav1 los trae todos de su página). */
    private var defaultSourceName = ""

    private fun sourceOf(server: Server): String = serverSources[server] ?: defaultSourceName

    /**
     * Las estadísticas cuentan cada extracción real una sola vez: el propio servidor avisa al terminar ([Server.onAttempt]), ya
     * sea al comprobarlo en la lista o al elegirlo (antes se contaba el fallo de la comprobación y otra vez al elegirlo, y los
     * éxitos de la comprobación no se contaban, con lo que el porcentaje de fallos salía inflado).
     */
    private fun track(server: Server) {
        if (server.onAttempt == null) server.onAttempt = { ok, cause ->
            // Si la comprobación ya lo dio por cortado por el tiempo, esa extracción ya está anotada: no se cuenta otra vez al terminar.
            if (timedOut.remove(server)) Unit else record(server, ok, cause)
        }
    }

    /**
     * Anota un intento de [server]: contra el propio servidor (todas las fuentes juntas) y contra la fuente que lo ofreció («enlaces de la fuente»):
     * si una fuente ofrece enlaces que no abren, se ve en ella aunque el servidor en sí funcione para otras.
     */
    private fun record(server: Server, ok: Boolean, cause: ConnectionStats.Cause?) {
        ConnectionStats.record(ConnectionStats.Kind.SERVER, server.name, ok, cause)
        sourceOf(server).takeIf { it.isNotBlank() }?.let { ConnectionStats.record(ConnectionStats.Kind.LINK, it, ok, cause) }
    }

    /** Servidores cuya comprobación se cortó por tiempo y ya se anotó como fallo; si la extracción acaba después, no se repite. */
    private val timedOut = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Server, Boolean>()))

    /** Servidores ya encontrados (fuente, servidor, etiqueta de la fuente) y fuentes que aún no se han consultado. */
    private val found = mutableListOf<Triple<app.kaizen.source.AnimeSource, Server, String?>>()
    @Volatile private var pendingSources: List<app.kaizen.source.AnimeSource> = emptyList()
    /** Ya se están consultando las fuentes pendientes: una segunda llamada no lanza otra consulta en paralelo. */
    private val loadingRemaining = AtomicBoolean(false)
    private val seenUrls = mutableSetOf<String>()

    /**
     * Consulta las fuentes pendientes de una en una (la del capítulo primero). Con [wanted] (el servidor recordado o favorito) se para en
     * cuanto alguna lo ofrece, sea cual sea la fuente: el resto se consulta solo si ese intento falla. Una fuente que no responde no
     * impide usar las demás; solo si ninguna da nada se propaga el error.
     */
    private suspend fun fetchSources(wanted: String?) {
        val number = record.number.roundedString()
        var lastError: Exception? = null
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            while (pendingSources.isNotEmpty() && !cancelled) {
                val src = pendingSources.first()
                pendingSources = pendingSources.drop(1)
                try {
                    src.servers(src.chapterUrl(record.slug, number))
                        .filter { seenUrls.add(it.url) }
                        .forEach { link -> Server.check(activity, link.url)?.takeIf(::hasExtractor)?.let { found += Triple(src, it, link.label) } }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                }
                if (wanted != null && found.any { it.second.name == wanted }) break
            }
        }
        if (found.isEmpty()) lastError?.let { throw it }
    }

    /** Lo que se ve en la fase «Servidores»: cuántos y de qué fuentes (y si quedan fuentes sin consultar por haber bastado el servidor recordado). */
    private fun serversSummary(): String {
        val names = found.map { it.first.name }.distinct().joinToString(" · ")
        return "${found.size} en $names" + if (pendingSources.isNotEmpty()) " · el recordado, sin esperar al resto" else ""
    }

    /** Pone las etiquetas y la lista de servidores a partir de [found]. */
    private fun applyFound() {
        val nameCount = found.groupingBy { it.second.name }.eachCount()
        serverLabels.clear()
        listLabels.clear()
        serverSources.clear()
        found.forEach { (src, server, tag) ->
            serverSources[server] = src.name
            if (!tag.isNullOrBlank()) listLabels[server] = "${server.name} · $tag"
            when {
                // Las fuentes que etiquetan sus servidores (idioma) lo muestran siempre.
                !tag.isNullOrBlank() -> serverLabels[server] = "${server.name} · $tag"
                (nameCount[server.name] ?: 0) > 1 -> serverLabels[server] = "${server.name} · ${src.name}"
            }
        }
        this.servers = found.map { it.second }.toMutableList()
    }

    /** El intento con el servidor recordado falló y quedaban fuentes sin consultar: se consultan ahora y sale la lista completa. */
    private fun loadRemainingSources() {
        if (!loadingRemaining.compareAndSet(false, true)) return
        activity.lifecycleScope.launch {
            progress.begin(LoadStep.SERVERS, "Buscando en otras fuentes")
            try {
                fetchSources(null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (cancelled) return@launch
                logUnlessCancelled(e)
            }
            if (cancelled) return@launch
            progress.done(LoadStep.SERVERS, serversSummary())
            applyFound()
            showServerList()
        }
    }

    /**
     * Servidores del capítulo en todas las fuentes activas que no son animeav1. Si el mismo
     * alojador aparece en más de una fuente, la etiqueta indica la fuente de origen.
     */
    private suspend fun loadSourceServers(source: app.kaizen.source.AnimeSource) {
        progress.begin(LoadStep.SERVERS, source.name)
        try {
            // La fuente del capítulo primero; el resto, por si el servidor recordado solo lo ofrece otra.
            val sources = app.kaizen.source.AnimeSources.enabled.filter { it.id != app.kaizen.source.AnimeAv1.ID }
                .ifEmpty { listOf(source) }
            pendingSources = sources.sortedBy { it.id != source.id }
            // Con el servidor recordado en uso no se espera a todas las fuentes: se para en cuanto una lo ofrece (el resto se carga solo si falla).
            val wanted = ServerMemory.preferredName(name).takeIf { choice.useRemembered() }
            fetchSources(wanted)
            if (cancelled) return
            if (found.isEmpty()) {
                progress.fail(LoadStep.SERVERS, "Ninguno se puede reproducir aquí")
                KaizenToast.toast("Ningún servidor de ${source.name} se puede reproducir aquí")
                finish(false, false)
                return
            }
            progress.done(LoadStep.SERVERS, serversSummary())
            applyFound()
            showServerList()
        } catch (e: Exception) {
            if (cancelled) return
            logUnlessCancelled(e)
            progress.fail(LoadStep.SERVERS, "No respondió")
            KaizenToast.toast("No se pudieron cargar los servidores de ${source.name}")
            finish(false, false)
        }
    }

    /** Fin de la petición: se libera para la siguiente y se avisa a la pantalla. */
    private fun finish(started: Boolean, success: Boolean) {
        if (!ended.compareAndSet(false, true)) return
        release(slot)
        // Un fallo deja ver unos instantes qué fase falló antes de cerrar la ventana.
        progress.close(if (success) Duration.ZERO else FAILURE_VISIBLE)
        serversInterface.onFinish(started, success)
    }

    /** El usuario pulsó Atrás en la ventana de fases: se corta todo y se libera la ranura para poder pedir otro capítulo. */
    fun cancel() {
        // Antes del CAS: así ninguna ruta de fondo que ya pasó su comprobación de [cancelled] gana la carrera con [finish].
        cancelled = true
        if (!ended.compareAndSet(false, true)) return
        job?.cancel()
        release(slot)
        progress.close()
        serversInterface.onFinish(false, false)
    }

    /** Pregunta al usuario: la ventana de fases se oculta mientras tanto. */
    private fun ask(title: String, subtitle: String, options: List<ChoiceOption>, onResult: (Int?) -> Unit) {
        progress.setVisible(false)
        ChoicePrompt.show(activity, title, subtitle, options) { position ->
            progress.setVisible(true)
            onResult(position)
        }
    }

    /**
     * Estado de la comprobación de cada lista por la cabeza (ver [ServerResolver.dropBrokenLeading]), por identidad
     * (SUB y DUB son listas distintas): false = en curso, true = hecha. Quien llega con la comprobación en curso no pinta;
     * pinta la propia comprobación al terminar.
     */
    private val listChecks = java.util.Collections.synchronizedMap(java.util.IdentityHashMap<List<Server>, Boolean>())

    /** Servidores que fallaron la comprobación: al volver a una lista ya comprobada (SUB/DUB) no se prueban otra vez. */
    private val brokenServers = java.util.Collections.synchronizedSet(HashSet<Server>())

    /**
     * Servidores de la lista actual que no se ofrecen: los que [HostGate] tiene en pausa (por familia, ver [HostGate.serverKey]) y
     * los que han fallado en esta petición. Salen al final de su grupo en «Selecciona servidor», con el icono de aviso, y se pueden elegir. Cada lista (SUB/DUB) tiene los suyos.
     */
    private val disabledServers = java.util.Collections.synchronizedList(ArrayList<Server>())

    private fun isPaused(server: Server) = HostGate.isDown(HostGate.serverKey(server.name))

    /** Falló en esta petición: no se vuelve a comprobar y sale deshabilitado. */
    private fun setBroken(server: Server) {
        brokenServers += server
        setAside(server)
    }

    private fun setAside(server: Server) {
        synchronized(disabledServers) { if (disabledServers.none { it === server }) disabledServers += server }
    }

    /** Hasta cuándo está en pausa un servidor; null si solo falló ahora (el icono de aviso ya lo dice). */
    private fun pauseNote(server: Server): String? {
        val until = HostGate.downUntil(HostGate.serverKey(server.name))
        if (until == 0L) return null
        val pattern = if (KaizenSettings.clock24h) "HH:mm" else "h:mm a"
        return "en pausa hasta ${java.text.SimpleDateFormat(pattern, Locale.getDefault()).format(java.util.Date(until))}"
    }

    fun showServerList() {
        // Falló el recordado y no se habían consultado todas las fuentes: primero se completan.
        // También al volver a la lista tras usar el recordado (Atrás en calidades, Mega, navegador): la lista debe llevar todas las fuentes.
        if (pendingSources.isNotEmpty() && (attemptFailed || rememberedTried)) return loadRemainingSources()
        // Los que fallaron antes o están en pausa no se comprueban (cada intento cuesta hasta CHECK_TIMEOUT): van al final, con el aviso.
        servers.forEach(::track)
        val aside = servers.filter { it in brokenServers || isPaused(it) }
        aside.forEach(::setAside)
        servers.removeAll(aside.toSet())
        if (servers.isEmpty()) return renderServerList()
        // «Elegir servidor» enseña la lista de inmediato: el usuario decide y no espera a que se prueben uno a uno. Igual cuando ya se intentó
        // el servidor recordado y falló: comprobar el resto antes de enseñar la lista dejaba al usuario en «Comprobando servidores» sin ver nada.
        if (choice == ServerChoice.ASK || attemptFailed) return renderServerList()
        when (listChecks[servers]) {
            true -> return renderServerList()
            false -> return
            null -> listChecks[servers] = false
        }
        // La comprobación trabaja sobre esta lista; si mientras tanto se cambia de lista (SUB/DUB), su resultado se descarta.
        val list = servers
        progress.begin(LoadStep.SERVER, "Comprobando servidores")
        // Si la comprobación revienta, la fase no puede quedarse en «Comprobando»: se marca fallida y se cierra la ventana.
        background(onError = { e ->
            logError(e)
            if (!cancelled) {
                progress.fail(LoadStep.SERVER, "Error al comprobar los servidores")
                KaizenToast.toast(appString(R.string.ui_error_en_servidor))
                finish(false, false)
            }
        }) {
            ServerMemory.sortPreferredFirst(name, list)
            if (list.size > 1 || list.first() !is WebServer) KaizenToast.toast(appString(R.string.ui_comprobando_servidores))
            // Solo se aparta de esta lista: un fallo puntual o un timeout no borra el servidor recordado.
            val total = list.size
            var tried = 0
            val anyLeft = ServerResolver.dropBrokenLeading(list, app.kaizen.commons.Tuning.patient(CHECK_TIMEOUT), onTrying = {
                // Qué servidor se está probando ahora, para ver por qué tarda.
                tried++
                progress.begin(LoadStep.SERVER, "Conectando con ${serverLabels[it] ?: it.name} ($tried/$total)")
            }) {
                brokenServers += it
                setAside(it)
                // Cortado por el tiempo de la comprobación: no llegó a terminar y nadie lo ha anotado (si terminó, ya lo hizo [Server.onAttempt]).
                if (!it.attempted) { timedOut += it; record(it, false, ConnectionStats.Cause.TIMEOUT) }
            }
            listChecks[list] = true
            if (servers !== list || cancelled) return@background
            // Aunque no quede ninguno, se enseña la lista (todos deshabilitados): cerrar con un aviso no dice cuáles ni hasta cuándo.
            if (!anyLeft) progress.fail(LoadStep.SERVER, "Ninguno funciona ahora mismo")
            renderServerList()
        }
    }

    private fun renderServerList() {
        doOnUIGlobal {
            try {
                renderOrAdvance()
            } catch (e: Exception) {
                // Antes solo se anotaba: la fase se quedaba en «Comprobando» y la ventana de carga sin cerrar.
                logUnlessCancelled(e)
                progress.fail(LoadStep.SERVER, "Error al mostrar los servidores")
                KaizenToast.toast(appString(R.string.ui_error_en_servidor))
                finish(false, false)
            }
        }

    }

    /** Qué sigue con la lista de servidores: nada si se canceló, cerrar si no hay ninguno, usar el recordado, pedir las demás fuentes o preguntar. */
    private fun renderOrAdvance() {
        if (cancelled) return
        if (servers.isEmpty() && disabledServers.isEmpty()) return noServersLeft()
        ServerMemory.sortPreferredFirst(name, servers)
        // Kaizen: se usa directamente el servidor recordado (un solo intento; si falla sale la lista), según cómo se pidió (ServerChoice).
        val preferred = if (!rememberedTried && choice.useRemembered()) ServerMemory.preferredIndex(name, servers) else -1
        rememberedTried = true
        if (preferred >= 0) return analyzeRemembered(servers[preferred])
        // Sin recordado utilizable (en pausa, roto o de otra fuente aún sin consultar): la lista lleva todas las fuentes.
        if (pendingSources.isNotEmpty()) return loadRemainingSources()
        askServer()
    }

    private fun noServersLeft() {
        progress.fail(LoadStep.SERVER, "Sin servidores")
        KaizenToast.toast(appString(R.string.ui_sin_servidores_disponibles))
        finish(false, false)
    }

    private fun analyzeRemembered(server: Server) {
        KaizenToast.toast("Servidor: ${server.name}")
        analyzeServer(server)
    }

    /**
     * Agrupados por la fuente que los ofrece. Los que venían fallando o están en pausa salen al final de su grupo con el icono de aviso, pero se pueden
     * elegir: un enlace roto no condena a los demás enlaces del mismo servidor. El id es la posición en esta lista (primero los buenos, luego los apartados).
     */
    private fun askServer() {
        val shown = servers.toList()
        val aside = disabledServers.toList()
        val enabled = shown.mapIndexed { index, server -> ChoiceOption(index, listLabel(server), group = serverSources[server]) }
        val warned = aside.mapIndexed { index, server ->
            ChoiceOption(shown.size + index, listLabel(server), note = pauseNote(server), group = serverSources[server], warning = true)
        }
        ask("Selecciona servidor", if (shown.isEmpty()) appString(R.string.ui_ningun_servidor_funciona_ahora_mismo) else "", enabled + warned) { position ->
            val chosen = position?.let { (shown + aside).getOrNull(it) }
            when {
                position == null -> cancel()
                chosen != null -> analyzeServer(chosen)
                else -> showServerList()
            }
        }
    }

    fun analyzeMulti(position: Int) {
        when (position) {
            0 -> this.servers = subServes.toMutableList()
            1 -> this.servers = dubServers.toMutableList()
        }
        disabledServers.clear()
        showServerList()
    }

    /** El texto de [server] en la lista de «Selecciona servidor». */
    private fun listLabel(server: Server): String = listLabels[server] ?: server.name

    /** [server] es el único candidato que queda en la petición (no hay otro al que volver). */
    private fun isOnlyCandidate(server: Server) = servers.none { it !== server } && disabledServers.none { it !== server }

    /** Un servidor que se ha podido abrir vuelve a la lista de los buenos: ya no se aparta ni se da por roto. */
    private fun clearBroken(server: Server) {
        brokenServers -= server
        synchronized(disabledServers) { disabledServers.removeAll { it === server } }
        // Si venía de los apartados, vuelve a la lista de los buenos: si el usuario cancela las calidades o falla la reproducción, la lista lo ofrece.
        if (servers.none { it === server }) servers.add(server)
    }

    fun analyzeServer(chosen: Server) {
        background {
            try {
                openServer(chosen)
            } catch (e: Exception) {
                // Kaizen: antes la excepción se tragaba y no volvía a salir la lista de servidores.
                logUnlessCancelled(e)
                progress.fail(LoadStep.SERVER, "Error en el servidor")
                failAttempt(R.string.ui_error_en_servidor)
            }
        }
    }

    private suspend fun openServer(chosen: Server) {
        if (cancelled) return
        val text = chosen.name
        progress.begin(LoadStep.SERVER, text)
        track(chosen)
        // Una marca de un corte anterior ya no vale: este es un intento nuevo y debe anotarse.
        timedOut.remove(chosen)
        val server = chosen.verified()
        if (cancelled) return
        // Si lo apartado ahora abre, deja de estar apartado.
        if (server != null && server.options.isNotEmpty()) clearBroken(chosen)
        when {
            // Con un solo servidor y ninguno apartado no hay lista que enseñar; si no, vuelve la lista con este con el aviso.
            server == null && isOnlyCandidate(chosen) -> {
                progress.fail(LoadStep.SERVER, "$text no responde")
                KaizenToast.toast(appString(R.string.ui_error_en_servidor_intente_mas_tarde))
                finish(false, false)
            }
            server == null -> {
                progress.fail(LoadStep.SERVER, "$text no responde")
                setBroken(chosen)
                failAttempt(R.string.ui_error_en_servidor)
            }
            server.options.isEmpty() -> {
                progress.fail(LoadStep.SERVER, "$text sin vídeo")
                setBroken(chosen)
                failAttempt(R.string.ui_error_en_servidor)
            }
            server.haveOptions() -> {
                useServer(text, chosen)
                progress.done(LoadStep.SERVER, text)
                showOptions(server)
            }
            chosen is WebServer -> openInBrowser(chosen, server, text)
            text.lowercase(Locale.getDefault()).contains("mega") -> {
                KaizenToast.toast(appString(R.string.ui_no_se_puede_usar_mega_en_tv))
                showServerList()
            }
            else -> {
                useServer(text, chosen)
                progress.done(LoadStep.SERVER, text)
                startStreaming(server.option)
            }
        }
    }

    /** El intento falló: se anota, se avisa y vuelve la lista de servidores. */
    private fun failAttempt(message: Int) {
        attemptFailed = true
        KaizenToast.toast(appString(message))
        showServerList()
    }

    private fun openInBrowser(chosen: Server, server: VideoServer, text: String) {
        try {
            if (cancelled) return
            progress.done(LoadStep.SERVER, text)
            openWebPlayer(activity, checkNotNull(server.option.url) { "Servidor web sin URL" }, name)
            progress.done(LoadStep.CHAPTER, "Abriendo el navegador")
            background(onError = { logError(it); KaizenToast.toast("No se pudo marcar el capítulo como visto") }) {
                Library.addChapter(record)
                SeriesStatuses.autoWatching(record.name, record.source, record.slug, record.aid)
            }
            finish(false, true)
        } catch (_: Exception) {
            KaizenToast.toast(appString(R.string.ui_error_al_abrir_explorador_web))
            showServerList()
        }
    }

    fun analyzeOption(position: Int) {
        current?.let { startStreaming(it.options[position]) }
    }

    /** Recuerda el servidor elegido para este anime y como último usado. */
    private fun useServer(text: String, chosen: Server) {
        ServerMemory.remember(name, text)
        usedServer = text
        usedServerName = chosen.name
        usedLinkSource = sourceOf(chosen)
    }

    private fun showOptions(server: VideoServer) {
        this.current = server
        val options = server.options.mapIndexed { index, option -> ChoiceOption(index, option.name ?: "") }
        ask(server.name, "Selecciona calidad", options) { position ->
            // Atrás en las calidades vuelve a la lista de servidores.
            if (position == null) showServerList() else analyzeOption(position)
        }
    }

    private fun startStreaming(option: Option) {
        progress.begin(LoadStep.CHAPTER, "Preparando el reproductor")
        // Kaizen: se prepara en segundo plano (arrancar el proxy abre un socket) y solo el
        // startActivity va al hilo principal.
        background(onError = { logError(it); KaizenToast.toast("No se pudo marcar el capítulo como visto") }) {
            // Cancelado antes de empezar: no se marca como visto un capítulo que no se va a reproducir.
            if (cancelled) return@background
            Library.addChapter(record)
            SeriesStatuses.autoWatching(record.name, record.source, record.slug, record.aid)
            val intent = try {
                if (KaizenSettings.useInternalPlayer) internalPlayerIntent(option) else externalPlayerIntent(option)
            } catch (e: IOException) {
                logError(e)
                KaizenToast.toastLong(appString(R.string.ui_no_se_pudo_preparar_el_video_para_el_reproductor))
                null
            } catch (e: IllegalStateException) {
                KaizenToast.toastLong(appString(R.string.ui_instala_un_reproductor_externo_o_cambia_a_reprod))
                null
            }
            doOnUIGlobal {
                if (cancelled) return@doOnUIGlobal
                if (intent != null) progress.done(LoadStep.CHAPTER, "Listo") else progress.fail(LoadStep.CHAPTER, "No se pudo preparar el vídeo")
                intent?.let { activity.startActivity(it) }
                finish(false, intent != null)
            }
        }
    }

    private fun Intent.withVideoExtras(uri: String, option: Option): Intent = apply {
        setDataAndType(Uri.parse(uri), "video/*")
        putExtra("title", name)
        putExtra("chapter", chapter)
        putExtra("eid", record.eid)
        putExtra("server", usedServer)
        putExtra("anime_url", record.animeUrl())
        putExtra("number", record.number)
        putExtra("aid", record.aid)
        putExtra("slug", record.slug)
        putExtra("source", record.source)
        putExtra("server_name", usedServerName)
        putExtra("link_source", usedLinkSource)
        putStringArrayListExtra("headers", ArrayList(option.headers?.createHeadersList() ?: emptyList()))
    }

    private fun internalPlayerIntent(option: Option): Intent =
        Intent(activity, TVPlayer::class.java).withVideoExtras(option.url ?: "", option)

    /**
     * Los reproductores externos no envían las cabeceras que exige el servidor: se les da una
     * URL del proxy local (KaizenProxy) que las añade, también a los segmentos HLS.
     */
    private fun externalPlayerIntent(option: Option): Intent {
        val headers = option.headers?.createHeadersMap() ?: hashMapOf()
        if (headers.keys.none { it.equals("User-Agent", true) })
            headers["User-Agent"] = BypassUtil.userAgent
        val proxied = KaizenProxy.urlFor(option.url ?: throw IOException("Sin URL de vídeo"), headers)
        val intent = Intent(Intent.ACTION_VIEW).withVideoExtras(proxied, option).apply {
            // MX Player y compatibles leen las cabeceras como String[] alternado.
            putExtra("headers", (option.headers?.createHeadersList() ?: emptyList()).toTypedArray())
        }
        val resolveInfo = App.context.packageManager.queryIntentActivities(intent, 0)
        val excluded = resolveInfo.filter {
            it.activityInfo.packageName == activity.packageName ||
                it.activityInfo.packageName == "com.google.android.tv.frameworkpackagestubs"
        }.map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
        if (resolveInfo.size - excluded.size <= 0) throw IllegalStateException("No player found")
        return Intent.createChooser(intent, "Selecciona un reproductor").apply {
            putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, excluded.toTypedArray())
        }
    }

    fun get() {
        job = activity.lifecycleScope.launch {
            // Kaizen: capítulos de otras fuentes traen sus servidores de su propia página.
            val source = app.kaizen.source.AnimeSources.of(record.source)
            defaultSourceName = source.name
            if (source.id != app.kaizen.source.AnimeAv1.ID) {
                progress.skip(LoadStep.CLOUDFLARE, "No aplica en ${source.name}")
                loadSourceServers(source)
                return@launch
            }
            LoadSteps.cloudflare(app.kaizen.bypass.BypassStatus.snapshot.value, System.currentTimeMillis())
                .let { (state, detail) -> progress.set(LoadStep.CLOUDFLARE, state, detail) }
            progress.begin(LoadStep.SERVERS, "animeav1")
            try {
                val response = JsExtractor.processLinkMultiple(url, listOf("embeds", "downloads"))
                val rawSub = mutableListOf<Server>()
                val rawDub = mutableListOf<Server>()
                response.forEach { (_, jSONArray) ->
                    jSONArray?.getJSONObject(0)?.let {
                        if (it.has("SUB")) addServers(it.getJSONArray("SUB"), rawSub)
                        if (it.has("DUB")) addServers(it.getJSONArray("DUB"), rawDub)
                    }
                }
                subServes = playable(rawSub)
                dubServers = playable(rawDub)
                if (cancelled) return@launch
                progress.done(LoadStep.SERVERS, if (dubServers.isNotEmpty()) "SUB ${subServes.size} · DUB ${dubServers.size}" else "${subServes.size} servidores")
                if (dubServers.isNotEmpty()) {
                    ask("Selecciona idioma", "", listOf(ChoiceOption(0, "Subtitulado"), ChoiceOption(1, "Doblado"))) { position ->
                        if (position == null) cancel() else analyzeMulti(position)
                    }
                } else {
                    // Los mismos que en la elección SUB/DUB: ordenados y solo los que se pueden reproducir.
                    this@TVServersFactory.servers = subServes.toMutableList()
                    showServerList()
                }
            } catch (e: Exception) {
                if (cancelled) return@launch
                logUnlessCancelled(e)
                this@TVServersFactory.servers = ArrayList()
                progress.fail(LoadStep.SERVERS, if (e is app.kaizen.commons.HostGate.HostDownException) "Fuente en pausa" else "No respondió")
                KaizenToast.toast(appString(R.string.ui_no_se_pudieron_cargar_los_servidores))
                finish(false, false)
            }
        }

    }

    /** Un servidor sin extractor que lo reproduzca en la TV (los que solo abrían el navegador, 1Fichier, Mega) no se ofrece: se oculta. */
    private fun hasExtractor(server: Server) = server !is WebServer && server.canStream && !server.name.contains("mega", true)

    /** Reproducibles en la TV, por nombre. */
    private fun playable(servers: List<Server>): List<Server> = servers.filter(::hasExtractor).sortedBy { it.name }

    /**
     * Servidores de una lista de animeav1 (SUB o DUB) sin repetir: el mismo vídeo de MP4Upload o PDrain puede venir
     * con dos URL; 1Fichier no se reproduce en TV. Los que no se reconocen no se ofrecen (sin extractor).
     */
    private fun addServers(entries: org.json.JSONArray, into: MutableList<Server>) {
        for (entry in entries) {
            val name = entry.getString("server")
            val url = entry.getString("url")
            if (name == "1Fichier") continue
            if (name == "MP4Upload" && into.any { it.baseLink.contains(url.substringAfterLast("/")) }) continue
            if (name == "PDrain" && into.any { it.baseLink.substringBeforeLast("?").contains(url.substringBeforeLast("?")) }) continue
            if (into.any { it.baseLink.substringAfterLast("/") == url.substringAfterLast("/") }) continue
            Server.check(activity, url)?.let(into::add)
        }
    }

    /** Cómo elegir servidor al reproducir. */
    enum class ServerChoice {
        /** Siempre la lista («Elegir servidor» del menú de la tarjeta). */
        ASK,
        /** El recordado sin preguntar («Seguir viendo», capítulo siguiente o anterior). */
        REMEMBERED,
        /** Según el ajuste «Usar último servidor». */
        SETTING;

        fun useRemembered() = this == REMEMBERED || (this == SETTING && KaizenSettings.autoServer)
    }

    interface ServersInterface {
        fun onFinish(started: Boolean, success: Boolean)
    }

    companion object {
        /**
         * Solo una petición a la vez (como en UKIKU): una segunda pulsación mientras se buscan servidores no lanza
         * otra búsqueda contra los mismos servidores externos. Si una petición queda colgada (la pantalla se cerró
         * sin terminar), deja de bloquear pasado [BUSY_TIMEOUT].
         */
        private val BUSY_TIMEOUT = 60.seconds

        /** Tiempo máximo para comprobar un servidor antes de darlo por caído. */
        private val CHECK_TIMEOUT = 10.seconds

        /** Cuánto se deja ver la ventana de fases tras un fallo. */
        private val FAILURE_VISIBLE = 1500.milliseconds

        private class Busy(val slot: Any, val since: Long)

        private val busy = AtomicReference<Busy?>(null)

        /** Ranura nueva si no hay otra búsqueda en curso (o la anterior quedó colgada); null si la hay. */
        private fun acquire(): Any? {
            val now = System.currentTimeMillis()
            val current = busy.get()
            if (current != null && now - current.since <= BUSY_TIMEOUT.inWholeMilliseconds) return null
            val slot = Any()
            return slot.takeIf { busy.compareAndSet(current, Busy(it, now)) }
        }

        private fun release(slot: Any) {
            busy.get()?.takeIf { it.slot === slot }?.let { busy.compareAndSet(it, null) }
        }

        /** Capítulo de un registro; la actividad recibe el resultado si es [ServersInterface]. */
        fun start(activity: ComponentActivity, record: Record, choice: ServerChoice = ServerChoice.SETTING) {
            start(activity, record.chapterUrl(), record.name, record.chapter(), record,
                activity as? ServersInterface, choice)
        }

        fun start(
            activity: ComponentActivity,
            url: String,
            name: String,
            chapter: String,
            record: Record,
            serversInterface: ServersInterface?,
            choice: ServerChoice = ServerChoice.SETTING
        ) {
            serversInterface ?: return
            val slot = acquire() ?: return KaizenToast.toast(appString(R.string.ui_solo_una_peticion_a_la_vez))
            // Atrás puede llegar antes de que exista la petición (el trabajo aún no ha arrancado): se recuerda y se cancela al crearla.
            val pendingCancel = AtomicBoolean(false)
            val created = AtomicReference<TVServersFactory?>(null)
            val progress = LoadProgress.show(activity, "Cargando capítulo", "$name · $chapter") {
                pendingCancel.set(true)
                created.get()?.cancel() ?: release(slot)
            }
            background {
                val factory = TVServersFactory(activity, url, name, chapter, record, serversInterface, choice, slot, progress)
                created.set(factory)
                if (pendingCancel.get()) factory.cancel() else factory.get()
            }
        }
    }
}
