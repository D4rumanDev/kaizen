package app.kaizen.tv.exoplayer

import app.kaizen.commons.logError
import app.kaizen.commons.runCatchingCoop
import app.kaizen.commons.DebugLog
import app.kaizen.source.AniSkip
import app.kaizen.source.AnilistFicha
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import app.kaizen.commons.AppScope
import app.kaizen.commons.HostGateDataSource
import app.kaizen.commons.KaizenProgress
import app.kaizen.commons.KaizenSettings
import app.kaizen.domain.Library
import app.kaizen.domain.Favorites
import app.kaizen.source.coverCandidates
import app.kaizen.source.seriesIdOf
import app.kaizen.tv.ui.favoriteKeys
import app.kaizen.tv.ui.matchesFavorite
import app.kaizen.pojos.av1.Chapter
import app.kaizen.pojos.av1.Series
import app.kaizen.tv.ServerMemory
import app.kaizen.tv.TVRepository
import app.kaizen.tv.TVServersFactory
import app.kaizen.tv.details.TVAnimesDetails
import app.kaizen.tv.settings.CheckItem
import app.kaizen.tv.settings.ChoiceItem
import app.kaizen.tv.settings.SettingsCategory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import app.kaizen.tv.details.sharpestCover
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.kaizen.tv.ui.KaizenToast
import app.kaizen.R
import app.kaizen.commons.appString
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.milliseconds
import app.kaizen.commons.postDelayed

/**
 * Kaizen: lo que hace el reproductor, sin pantalla. Crea y suelta el ExoPlayer con el ciclo de vida de [TVPlayer],
 * guarda el progreso, cambia de capítulo, ajusta la frecuencia de la tele y avisa del siguiente capítulo. Lo que la
 * pantalla ([PlayerScreen]) necesita pintar son propiedades de Compose que este objeto actualiza.
 */
@OptIn(UnstableApi::class)
class PlaybackController(private val activity: TVPlayer) {
    val video: Video? = Video(activity.intent)

    // --- Estado que pinta la pantalla
    var player by mutableStateOf<ExoPlayer?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    /** Hasta dónde hay vídeo descargado (para pintarlo en la barra de tiempo). */
    var bufferedMs by mutableLongStateOf(0L)
        private set
    var speed by mutableFloatStateOf(KaizenSettings.defaultSpeed)
        private set
    /** Cómo se encaja el vídeo en la pantalla; vuelve a «Ajustar» al abrir el reproductor. */
    var videoScale by mutableStateOf(VideoScale.FIT)
        private set
    var isFavorite by mutableStateOf(false)
        private set
    /** Segunda línea del encabezado: códecs, decodificador y resolución (vacía hasta que el vídeo los conoce). */
    var subtitle by mutableStateOf("")
        private set

    /** Nombre de la serie (arriba del todo) y del capítulo (sobre la barra de tiempo). */
    val seriesTitle: String get() = video?.title.orEmpty().ifBlank { video?.chapter.orEmpty() }
    val chapterTitle: String get() = video?.chapter.orEmpty()
    /** Lo pone la pantalla de controles: lleva el foco al botón «reproducir». */
    var focusPlay: (() -> Unit)? = null
    var controlsVisible by mutableStateOf(false)
        private set
    /** Mensaje flotante (saltos con las flechas). */
    var hint by mutableStateOf<String?>(null)
        private set
    /** Aviso «OK · Saltar opening» con su cuenta atrás. */
    var openingPrompt by mutableStateOf<String?>(null)
        private set
    /** Texto del panel de depuración; null = oculto. */
    var debugText by mutableStateOf<String?>(null)
        private set
    /** Lo que hay encima del vídeo (ficha, pistas, siguiente capítulo o pregunta de reanudar). */
    var overlay by mutableStateOf<PlayerOverlay>(PlayerOverlay.None)
        private set
    /** Sube cada vez que ExoPlayer cambia las pistas disponibles: el panel de pistas relee sus opciones. */
    var tracksVersion by mutableIntStateOf(0)
        private set

    /** Hay algo encima del vídeo que se lleva las teclas (las flechas ya no saltan). */
    val overlayOpen: Boolean get() = overlay != PlayerOverlay.None

    // --- Piezas del reproductor
    private var mediaSession: MediaSession? = null
    private var decoderManager: DecoderManager? = null
    private val debugInfo = PlaybackDebugInfo()
    private val handler = Handler(Looper.getMainLooper())
    private var frameRateChecked = false
    private var frameRatePausePending = false
    private var requestedMode: Int? = null
    private var foundNext: NextChapter? = null
    private var saveTicks = 0
    private var openingPromptEnds = 0L
    private var openingPromptEid = Int.MIN_VALUE

    /** Los tiempos reales del opening y del cierre de este capítulo (AniSkip), si los hay y cuadran con su duración; null: se usa el salto fijo de siempre. */
    private var skipTimes by mutableStateOf<AniSkip.Times?>(null)

    /** Los tramos del opening y del cierre que AniSkip conoce (ms), para marcarlos en la barra de tiempo. */
    val skipRanges: List<LongRange>
        get() = skipTimes?.let { t ->
            listOfNotNull(
                t.openingStartMs?.let { s -> t.openingEndMs?.let { e -> s..e } },
                t.endingStartMs?.let { s -> t.endingEndMs?.let { e -> s..e } }
            )
        }.orEmpty()
    private var skipTimesEid = Int.MIN_VALUE
    /** Capítulo cuya consulta a AniSkip terminó sin tiempos utilizables: ahí vale el aviso manual. */
    private var skipLookupFailedEid = Int.MIN_VALUE
    /** Pista de vídeo elegida a mano (-1 = automática). */
    private var qualityTrack = -1
    @Volatile private var favoriteBusy = false

    private val tickTask = object : Runnable {
        override fun run() {
            val current = player ?: return
            isPlaying = current.isPlaying
            if (pendingSeek == null) positionMs = current.currentPosition
            durationMs = current.duration.takeIf { it > 0 } ?: 0L
            bufferedMs = current.bufferedPosition
            if (++saveTicks % SAVE_EVERY_TICKS == 0 && current.isPlaying) save()
            if (saveTicks % TICKS_PER_SECOND == 0) {
                debugText?.let { debugText = describeDebug() }
                updateOpeningPrompt()
            }
            handler.postDelayed(this, TICK)
        }
    }
    /** Posición a la que se salta al soltar las flechas: mientras se mantienen no se pide un seek por repetición. */
    private var pendingSeek: Long? = null
    private val applySeekTask = Runnable {
        pendingSeek?.let { player?.seekTo(it) }
        pendingSeek = null
    }
    private val hideControlsTask = Runnable { if (isPlaying && !overlayOpen && !chaptersOpen) controlsVisible = false }
    private val clearHintTask = Runnable { hint = null }
    // Sin reproducir (pausa, pregunta de reanudar, búfer atascado) la pantalla no se queda encendida: evita quemar el panel.
    private val idleExitTask = Runnable { if (player != null) { save(); activity.finish() } }
    // Margen antes de soltar la pantalla: un búfer o el cambio de fps no deben dejar que el sistema la apague.
    private val releaseScreenTask = Runnable { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    private fun keepAwake(on: Boolean) {
        handler.removeCallbacks(releaseScreenTask)
        handler.removeCallbacks(idleExitTask)
        if (on) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            handler.postDelayed(releaseScreenTask, RELEASE_SCREEN)
            handler.postDelayed(idleExitTask, IDLE_EXIT)
        }
    }
    private val resumeAfterFrameRate = Runnable { finishFrameRateSwitch(resume = true) }
    private val nextUpTick = object : Runnable {
        override fun run() {
            val remaining = (overlay as? PlayerOverlay.NextUp)?.seconds ?: return
            if (remaining <= 0) {
                overlay = PlayerOverlay.None
                changeChapter(1)
                return
            }
            overlay = PlayerOverlay.NextUp(remaining - 1)
            handler.postDelayed(this, 1.seconds)
        }
    }

    // Se reanuda en cuanto la pantalla está en el modo pedido; FRAME_RATE_SWITCH_TIMEOUT
    // es solo la red de seguridad si la tele no avisa.
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (requestedMode != null && FrameRateMatcher.currentModeId(activity) == requestedMode) finishFrameRateSwitch(resume = true)
        }

        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }
    private val displayManager get() = activity.getSystemService(DisplayManager::class.java)

    // --- Ciclo de vida

    fun onStart() {
        if (player == null) initializePlayer()
    }

    fun onPause() {
        save()
        finishFrameRateSwitch(resume = false)
        player?.pause()
    }

    fun onStop() {
        releasePlayer()
    }

    private fun initializePlayer() {
        val trackSelector = DefaultTrackSelector(activity, AdaptiveTrackSelection.Factory()).apply {
            if (KaizenSettings.maxQuality) setParameters(buildUponParameters().setForceHighestSupportedBitrate(true))
        }
        // Kaizen: FFmpeg (NextLib) como respaldo. AUTO usa el hardware y pasa a FFmpeg cuando
        // el formato no está soportado (p. ej. H.264 High 10 en el tvbox); si el hardware
        // falla a mitad de reproducción, onDecoderError fuerza FFmpeg y reintenta.
        val manager = DecoderManager()
        decoderManager = manager
        val exo = ExoPlayer.Builder(activity, NextRenderersFactory(activity).setDecoderManager(manager))
            .setTrackSelector(trackSelector).setLoadControl(loadControl()).build()
        manager.attach(exo)
        if (KaizenSettings.forceFfmpeg) manager.selectVideoDecoder(DecoderMode.FFMPEG)
        exo.setPlaybackSpeed(KaizenSettings.defaultSpeed)
        speed = KaizenSettings.defaultSpeed
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = onDecoderError(error)

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onPlaybackEnded() else cancelAutoNext()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                this@PlaybackController.isPlaying = isPlaying
                keepAwake(isPlaying)
                if (isPlaying) cancelAutoNext()
                // Pausado, los controles se quedan; al reanudar vuelve a contar el plazo para ocultarlos.
                if (controlsVisible) scheduleHide()
            }

            override fun onTracksChanged(tracks: Tracks) {
                tracksVersion++
                // Los fps ya se conocen antes del primer fotograma: el cambio de modo se pide mientras el vídeo aún carga.
                matchFrameRate()
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) = updateDecoderInfo()

            override fun onRenderedFirstFrame() {
                updateDecoderInfo()
                maybeShowOpeningPrompt()
                matchFrameRate()
            }
        })
        exo.addAnalyticsListener(debugInfo)
        // Sesión de medios: el sistema (Cast, Google Home, Home Assistant...) ve qué se reproduce y puede pausar/seguir.
        mediaSession = MediaSession.Builder(activity, exo).setId("$MEDIA_SESSION_ID-${System.identityHashCode(this)}").build()
        player = exo
        keepAwake(false) // el primer onIsPlayingChanged(true) lo cancela
        handler.removeCallbacks(tickTask)
        handler.postDelayed(tickTask, TICK)
        play()
    }

    /**
     * Búfer según «Reproductor» (ExoPlayer: 50 s). El tope por tamaño de ExoPlayer se mantiene
     * (unos 128 MB de vídeo), así que un búfer alto no agota la memoria en 1080p.
     */
    private fun loadControl(): DefaultLoadControl {
        val bufferMs = KaizenSettings.bufferSeconds * 1000
        // Tope por tamaño según la RAM (equipo justo: 48 MB): el búfer de ExoPlayer vive en el heap de Java y sin tope un vídeo de bitrate alto puede agotar los 192 MB de la TV.
        val cap = app.kaizen.commons.MemoryBudget.playerBufferBytes()
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(bufferMs, bufferMs,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
            .apply { if (cap != app.kaizen.commons.MemoryBudget.NO_CAP) setTargetBufferBytes(cap) }
            .build()
    }

    private fun releasePlayer() {
        handler.removeCallbacksAndMessages(null)
        pendingSeek = null
        qualityTrack = -1
        finishFrameRateSwitch(resume = false)
        // La ficha y las pistas sobreviven a una parada; el aviso de siguiente capítulo y la pregunta de reanudar, no.
        if (overlay is PlayerOverlay.NextUp || overlay is PlayerOverlay.Resume) overlay = PlayerOverlay.None
        hint = null
        dismissOpeningPrompt()
        skipTimes = null
        skipTimesEid = Int.MIN_VALUE
        skipLookupFailedEid = Int.MIN_VALUE
        decoderManager?.detach()
        decoderManager = null
        player?.pause()
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        controlsVisible = false
    }

    private fun play() {
        val exo = player ?: return
        activity.lifecycleScope.launch {
            val uri = video?.uri ?: Uri.EMPTY
            // Una página hostil no debe poder entregar file:// ni content:// al reproductor.
            if (uri.scheme != "http" && uri.scheme != "https") {
                KaizenToast.toast(appString(R.string.ui_direccion_de_video_no_valida))
                return@launch
            }
            exo.setMediaSource(mediaSourceFor(uri, video?.headers))
            exo.prepare()
            bindFavorite()
            preloadChapters()
            val progress = video?.let { KaizenProgress.get(KaizenProgress.Key(seriesIdOf(it.source, it.slug), it.number), it.eid) }
            when {
                progress?.isResumable != true -> exo.play()
                KaizenSettings.resumeDirectly -> {
                    exo.seekTo(progress.positionMs)
                    exo.play()
                    KaizenToast.toast("Reanudando desde ${KaizenProgress.formatTime(progress.positionMs)}")
                }
                else -> overlay = PlayerOverlay.Resume(progress.positionMs)
            }
        }
    }

    /** Respuesta a «Reanudar desde... / Desde el principio». */
    fun resume(fromSaved: Boolean) {
        val position = (overlay as? PlayerOverlay.Resume)?.positionMs ?: return
        overlay = PlayerOverlay.None
        if (fromSaved) {
            player?.seekTo(position)
            dismissOpeningPrompt()
        } else {
            maybeShowOpeningPrompt()
        }
        player?.play()
    }

    private fun mediaSourceFor(uri: Uri, headers: Map<String, String>?): androidx.media3.exoplayer.source.MediaSource {
        // Con HostGate: un servidor que bloquea o cae seguido se pausa también para el vídeo, y el reproductor propone otro.
        val httpFactory = HostGateDataSource.Factory(DefaultHttpDataSource.Factory().apply {
            setUserAgent(headers?.get("User-Agent") ?: app.kaizen.commons.UserAgent.current())
            setDefaultRequestProperties(headers ?: emptyMap())
        })
        val url = uri.toString()
        val factory = if (url.contains("m3u8") || url.contains("cf-master")) HlsMediaSource.Factory(httpFactory)
        else ProgressiveMediaSource.Factory(httpFactory)
        return factory.createMediaSource(mediaItemFor(uri))
    }

    /** El título y el capítulo viajan en el MediaItem: de ahí los lee la sesión de medios. */
    private fun mediaItemFor(uri: Uri): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(video?.title)
            .setSubtitle(video?.chapter)
            .setArtist(video?.chapter)
            .build()
        return MediaItem.Builder().setUri(uri).setMediaMetadata(metadata).build()
    }

    // --- Errores y decodificadores

    /** Kaizen: si falla el decodificador (de vídeo o de audio) se reintenta con FFmpeg una vez. */
    private fun onDecoderError(error: PlaybackException) {
        logError(error)
        val manager = decoderManager ?: return KaizenToast.toastLong("Error de reproducción: " + error.errorCodeName)
        if (error.errorCode in NETWORK_ERRORS) {
            // Fallo de red pasajero: el servidor puede estar bien, no se olvida.
            KaizenToast.toastLong(appString(R.string.ui_error_de_red_comprueba_la_conexion))
            return
        }
        if (error.errorCode !in DECODER_ERRORS) {
            forgetServer()
            KaizenToast.toastLong(appString(R.string.ui_error_de_reproduccion_prueba_otro_servidor))
            return
        }
        val isAudio = (error as? ExoPlaybackException)?.let {
            it.type == ExoPlaybackException.TYPE_RENDERER && it.rendererFormat?.sampleMimeType?.startsWith("audio/") == true
        } == true
        val alreadyFfmpeg = if (isAudio) manager.audioMode == DecoderMode.FFMPEG else manager.videoMode == DecoderMode.FFMPEG
        if (alreadyFfmpeg) {
            forgetServer()
            KaizenToast.toastLong(appString(R.string.ui_no_se_puede_reproducir_este_formato_prueba_otro))
            return
        }
        if (isAudio) manager.selectAudioDecoder(DecoderMode.FFMPEG) else manager.selectVideoDecoder(DecoderMode.FFMPEG)
        player?.prepare()
        player?.play()
        KaizenToast.toast("Decodificador de ${if (isAudio) appString(R.string.ui_audio) else appString(R.string.ui_video)} falló: usando FFmpeg")
    }

    /** El servidor no sirve para este vídeo: la próxima vez se pedirá la lista. */
    private fun forgetServer() = ServerMemory.forget(video?.title, video?.server)

    /** Kaizen: «H.264 High 10 10 bits · AAC · FFmpeg 1080p» (códecs, decodificador y resolución) bajo el nombre. */
    private fun updateDecoderInfo() {
        val current = player ?: return
        val decoder = when (decoderManager?.activeVideoMode) {
            DecoderMode.FFMPEG -> "FFmpeg"
            DecoderMode.HARDWARE -> "HW"
            DecoderMode.SOFTWARE -> "SW"
            else -> null
        }
        val height = current.videoSize.height
        val info = listOfNotNull(decoder, height.takeIf { it > 0 }?.let { "${it}p" }).joinToString(" ")
        subtitle = listOfNotNull(debugInfo.codecSummary(current).ifEmpty { null }, info.ifEmpty { null }).joinToString(" · ")
    }

    private fun describeDebug() = debugInfo.describe(player, decoderManager, video)

    fun toggleDebug() {
        debugText = if (debugText == null) describeDebug() else null
    }

    // --- Frecuencia automática (AFR)

    private fun finishFrameRateSwitch(resume: Boolean) {
        val wasPending = frameRatePausePending
        frameRatePausePending = false
        requestedMode = null
        handler.removeCallbacks(resumeAfterFrameRate)
        displayManager?.unregisterDisplayListener(displayListener)
        if (resume && wasPending) player?.play()
    }

    /** Los fps del vídeo: los del decodificador o, si aún no hay, los de la pista de vídeo elegida. */
    private fun videoFps(player: ExoPlayer): Float? {
        player.videoFormat?.frameRate?.takeIf { it > 0f }?.let { return it }
        val group = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected } ?: return null
        return (0 until group.length).filter(group::isTrackSelected).firstNotNullOfOrNull { group.getTrackFormat(it).frameRate.takeIf { fps -> fps > 0f } }
    }

    /**
     * AFR: una vez por vídeo, en cuanto se conocen los fps (antes del primer fotograma si la pista los trae). Pausa mientras la
     * tele cambia de modo: si se pide al cargar, esa espera se suma a la carga en vez de dejar el vídeo mudo tras empezar.
     */
    private fun matchFrameRate() {
        if (frameRateChecked || !KaizenSettings.frameRateMatching) return
        val current = player ?: return
        val fps = videoFps(current) ?: return
        frameRateChecked = true
        val mode = FrameRateMatcher.apply(activity, fps) ?: return
        // Si el usuario lo tiene en pausa no se toca: la reanudación no debe arrancar un vídeo parado.
        if (!current.playWhenReady) return
        current.pause()
        KaizenToast.toast("Ajustando la pantalla a %.3f fps".format(fps))
        frameRatePausePending = true
        requestedMode = mode
        displayManager?.registerDisplayListener(displayListener, handler)
        handler.postDelayed(resumeAfterFrameRate, FRAME_RATE_SWITCH_TIMEOUT)
    }

    private fun onFrameRateMatchingChanged(enabled: Boolean) {
        if (enabled) {
            frameRateChecked = false
            matchFrameRate()
        } else {
            finishFrameRateSwitch(resume = false)
            FrameRateMatcher.reset(activity)
        }
    }

    // --- Controles

    fun showControls() {
        controlsVisible = true
        scheduleHide()
    }

    fun hideControls() {
        handler.removeCallbacks(hideControlsTask)
        controlsVisible = false
    }

    /** Controles a la vista pero sin nada enfocado: [ok] (la tecla OK) además pausa o reanuda; las flechas solo devuelven el foco. */
    fun recoverFocus(ok: Boolean) {
        touch()
        focusPlay?.invoke()
        if (ok) togglePlay()
    }

    /** Cualquier tecla con los controles a la vista renueva el plazo para ocultarlos. */
    fun touch() {
        if (controlsVisible) scheduleHide()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControlsTask)
        handler.postDelayed(hideControlsTask, KaizenSettings.controlsHideSeconds.seconds)
    }

    fun togglePlay() {
        val current = player ?: return
        // Pausar o reanudar a mano manda sobre la espera del cambio de modo.
        finishFrameRateSwitch(resume = false)
        if (current.isPlaying) current.pause() else current.play()
    }

    /**
     * Salta [deltaMs] (negativo = atrás) sin salirse del vídeo; devuelve la posición nueva. Con [deferred] (tecla
     * mantenida) la barra y el aviso se mueven al momento y el seek real se pide cuando se deja de repetir.
     */
    fun seekBy(deltaMs: Long, deferred: Boolean = false): Long {
        val current = player ?: return 0L
        val duration = current.duration.takeIf { it > 0 }
        val target = ((pendingSeek ?: current.currentPosition) + deltaMs).coerceIn(0L, duration ?: Long.MAX_VALUE)
        positionMs = target
        handler.removeCallbacks(applySeekTask)
        if (deferred) {
            pendingSeek = target
            handler.postDelayed(applySeekTask, SEEK_SETTLE)
        } else {
            pendingSeek = null
            current.seekTo(target)
        }
        return target
    }

    /** Retrocede el salto de «Reproductor». */
    fun rewind() {
        seekBy(-KaizenSettings.seekSeconds * 1000L)
    }

    /** Avanza el salto de «Reproductor». */
    fun fastForward() {
        seekBy(KaizenSettings.seekSeconds * 1000L)
    }

    /** Barra de tiempo con el mando: 1 % del vídeo por pulsación. */
    fun seekPercent(direction: Int) {
        val step = (durationMs / 100).coerceAtLeast(MIN_BAR_STEP_MS)
        seekBy(direction * step, deferred = true)
    }

    fun skipOpening() {
        val current = player ?: return
        val times = skipTimes
        if (times != null) {
            val position = current.currentPosition
            val endingEnd = times.endingEndMs
            val openingEnd = times.openingEndMs
            if (times.inEnding(position) && endingEnd != null) {
                // Si el cierre llega hasta el final del vídeo, saltarlo es pasar al capítulo siguiente.
                if (current.duration > 0 && endingEnd >= current.duration - ENDING_NEXT_MARGIN_MS) changeChapter(1, closeIfLast = true) else seekBy(endingEnd - position)
                dismissOpeningPrompt()
                return
            }
            if (times.inOpening(position) && openingEnd != null) {
                seekBy(openingEnd - position)
                dismissOpeningPrompt()
                return
            }
        }
        seekBy(KaizenSettings.openingSeconds * 1000L)
        dismissOpeningPrompt()
    }

    fun cycleScale() {
        videoScale = videoScale.next()
        KaizenToast.toast(videoScale.label)
    }

    fun cycleSpeed() {
        val current = player ?: return
        val now = current.playbackParameters.speed
        val next = KaizenSettings.SPEED_OPTIONS.firstOrNull { it > now + 0.01f } ?: KaizenSettings.SPEED_OPTIONS.first()
        current.setPlaybackSpeed(next)
        speed = next
    }

    /** Kaizen: el progreso se guarda por ficha y número en KaizenProgress. */
    fun save() {
        val current = player ?: return
        val video = video ?: return
        KaizenProgress.save(KaizenProgress.Key(seriesIdOf(video.source, video.slug), video.number), video.eid, current.currentPosition, current.duration)
    }

    /**
     * Flechas izquierda/derecha con los controles ocultos: saltan (más lejos si se mantiene la tecla) y
     * enseñan la posición. Con los controles visibles las flechas mueven el foco, como siempre.
     */
    private fun onArrowSeek(forward: Boolean, repeatCount: Int): Boolean {
        if (controlsVisible || overlayOpen) return false
        val seconds = SeekAcceleration.stepSeconds(KaizenSettings.seekSeconds, repeatCount, KaizenSettings.seekCapSeconds)
        val target = seekBy((if (forward) seconds else -seconds) * 1000L, deferred = repeatCount > 0)
        showHint("${if (forward) "▶▶ +" else "◀◀ −"}$seconds s · ${KaizenProgress.formatTime(target)} / ${KaizenProgress.formatTime(durationMs)}")
        return true
    }

    private fun showHint(text: String) {
        hint = text
        handler.removeCallbacks(clearHintTask)
        handler.postDelayed(clearHintTask, SEEK_HINT)
    }

    /** La última pulsación de flecha se usó para saltar: su suelta también se consume. */
    private var arrowSeeking = false

    /**
     * Única entrada de teclas del reproductor (la llama la raíz de [PlayerScreen] antes que los controles). Devuelve true si la
     * tecla se consume; si no, sigue su camino normal (foco de los controles, Atrás...).
     */
    fun handleKey(event: KeyEvent): Boolean {
        touch()
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        return when {
            // Solo al pulsar (también al mantener): la suelta se consume sin repetir la acción.
            code in MEDIA_KEYS -> {
                if (down) onMediaKey(code)
                true
            }
            code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) arrowSeeking = onArrowSeek(code == KeyEvent.KEYCODE_DPAD_RIGHT, event.repeatCount)
                val consumed = arrowSeeking
                if (!down) arrowSeeking = false
                consumed
            }
            down && code in OK_KEYS && onOpeningPromptKey() -> true
            // Con los controles ocultos, OK o arriba/abajo los enseñan.
            down && !controlsVisible && !overlayOpen && (code in OK_KEYS || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) -> {
                showControls()
                true
            }
            else -> false
        }
    }

    /** Teclas multimedia del mando (pausa, play, avance, retroceso): el reproductor las gestiona aunque no haya foco en los controles. */
    private fun onMediaKey(keyCode: Int) {
        val current = player ?: return
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> { finishFrameRateSwitch(resume = false); current.play() }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { finishFrameRateSwitch(resume = false); current.pause() }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> togglePlay()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> fastForward()
            KeyEvent.KEYCODE_MEDIA_REWIND -> rewind()
            KeyEvent.KEYCODE_MEDIA_NEXT -> changeChapter(1)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> previousOrRestart()
        }
    }

    // --- Aviso «Saltar opening»

    /** OK con el aviso «Saltar opening» a la vista (y los controles ocultos): salta el opening. */
    private fun onOpeningPromptKey(): Boolean {
        if (openingPromptEnds == 0L || controlsVisible || overlayOpen) return false
        skipOpening()
        return true
    }

    /** Al empezar un capítulo desde el principio, 10 s de aviso con cuenta atrás (no hay marcas de opening: salto manual de Ajustes). */
    private fun maybeShowOpeningPrompt() {
        val current = video ?: return
        loadSkipTimes(current)
        // Primero el automático (AniSkip): el salto manual por segundos solo sale cuando AniSkip no sirve (o está desactivado).
        val automatic = KaizenSettings.aniSkip && KaizenSettings.openingSeconds > 0
        if (automatic && skipLookupFailedEid != current.eid) return
        showManualOpeningPrompt(current)
    }

    /** El aviso de cuenta atrás con el salto fijo de Ajustes: la alternativa cuando no hay tiempos de AniSkip. */
    private fun showManualOpeningPrompt(current: Video) {
        // Con tiempos de opening de AniSkip no hace falta: sale el aviso por tiempos. Si AniSkip solo trae el cierre, la cuenta atrás manual sigue valiendo.
        if (skipTimes?.openingEndMs != null) return
        if (overlay is PlayerOverlay.Resume || KaizenSettings.openingSeconds <= 0 || !PlayerButton.SKIP_OPENING.isVisible) return
        if (openingPromptEid == current.eid || (player?.currentPosition ?: 0L) > OPENING_PROMPT_MAX_START_MS) return
        openingPromptEid = current.eid
        openingPromptEnds = SystemClock.uptimeMillis() + OPENING_PROMPT.inWholeMilliseconds
        updateOpeningPrompt()
    }

    /**
     * Pide a AniSkip los tiempos de este capítulo (una vez por capítulo); si hay y cuadran con la duración del vídeo, el aviso pasa a seguirlos.
     * Si no sirven por la razón que sea, se marca el capítulo en [skipLookupFailedEid] y entra el aviso manual.
     */
    private fun loadSkipTimes(current: Video) {
        if (!KaizenSettings.aniSkip || KaizenSettings.openingSeconds <= 0 || skipTimesEid == current.eid) return
        skipTimesEid = current.eid
        val eid = current.eid
        fun unavailable(reason: String) {
            DebugLog.info("AniSkip", "${current.slug}: $reason")
            if (video?.eid != eid) return
            skipLookupFailedEid = eid
            showManualOpeningPrompt(current)
        }
        val episode = current.number.takeIf { it > 0 && it == Math.floor(it) }?.toInt()
        if (episode == null) return unavailable("capítulo ${current.number} sin número entero, sin tiempos")
        activity.lifecycleScope.launch {
            val mal = runCatchingCoop { AnilistFicha.of(current.source, current.slug)?.idMal }.onFailure(::logError).getOrNull()
            if (mal == null) return@launch unavailable("sin id de MyAnimeList (la serie no está enlazada con AniList)")
            val times = runCatchingCoop { AniSkip.times(mal, episode) }.onFailure(::logError).getOrNull()
            if (times == null) return@launch unavailable("MAL $mal cap. $episode: AniSkip no tiene tiempos (o no respondió)")
            // La duración puede no estar lista aún (la respuesta de AniSkip llega antes que el vídeo): se espera hasta 15 s en vez de descartar los tiempos.
            var duration = 0L
            for (attempt in 0 until 30) {
                duration = player?.duration?.takeIf { it > 0 } ?: 0L
                if (duration > 0 || video?.eid != eid) break
                delay(500.milliseconds)
            }
            if (video?.eid != eid) return@launch
            if (duration <= 0) return@launch unavailable("MAL $mal cap. $episode: el vídeo no dio su duración, tiempos sin usar")
            // Cada tramo se comprueba con la duración de su propio corte: AniSkip mezcla a veces cortes distintos (49 y 24 min) y uno solo cuadra.
            val usable = times.forDuration(duration)
            if (usable != null) {
                skipTimes = usable
                // Sin opening en la respuesta (solo cierre): entra la cuenta atrás manual y el cierre se sigue saltando por tiempos.
                if (usable.openingStartMs == null || usable.openingEndMs == null) {
                    skipLookupFailedEid = eid
                    showManualOpeningPrompt(current)
                }
                DebugLog.info("AniSkip", "${current.slug} MAL $mal cap. $episode: tiempos aplicados (opening ${usable.openingStartMs}–${usable.openingEndMs} ms, cierre ${usable.endingStartMs}–${usable.endingEndMs} ms; vídeo $duration ms)")
            } else unavailable("MAL $mal cap. $episode: la duración no cuadra (AniSkip opening ${times.openingLengthMs} ms, cierre ${times.endingLengthMs} ms; vídeo $duration ms), tiempos descartados")
        }
    }

    /** Con los tiempos de AniSkip el aviso sale mientras suena el opening o el cierre (no solo al empezar) y salta hasta su final. */
    private fun updateTimedPrompt(times: AniSkip.Times) {
        val position = player?.currentPosition ?: return
        val hidden = overlay is PlayerOverlay.Tracks || overlay is PlayerOverlay.Resume || !PlayerButton.SKIP_OPENING.isVisible
        val text = when {
            hidden -> null
            times.inOpening(position) -> "OK · Saltar opening"
            times.inEnding(position) -> "OK · Saltar cierre"
            else -> null
        }
        if (text == null) {
            // También quita el aviso de cuenta atrás que pudo salir antes de que llegaran los tiempos.
            if (openingPromptEnds != 0L) dismissOpeningPrompt()
            return
        }
        openingPromptEnds = Long.MAX_VALUE
        openingPrompt = text
    }

    private fun updateOpeningPrompt() {
        skipTimes?.let { times ->
            // Con opening por tiempos, ellos mandan; sin él, solo el cierre (y fuera del cierre sigue la cuenta atrás manual).
            if (times.openingEndMs != null || times.inEnding(player?.currentPosition ?: 0L)) return updateTimedPrompt(times)
        }
        if (openingPromptEnds == 0L) return
        // Salió el aviso del cierre por tiempos y ya pasó: no se convierte en la cuenta atrás del opening.
        if (openingPromptEnds == Long.MAX_VALUE) return dismissOpeningPrompt()
        val seconds = ((openingPromptEnds - SystemClock.uptimeMillis() + 999) / 1000).toInt()
        if (seconds <= 0 || overlay is PlayerOverlay.Tracks) return dismissOpeningPrompt()
        openingPrompt = "OK · Saltar opening (+${KaizenSettings.openingSeconds} s) · $seconds"
    }

    private fun dismissOpeningPrompt() {
        openingPromptEnds = 0L
        openingPrompt = null
    }

    // --- Favorito

    /** Con la misma regla que el corazón de las tarjetas (título, aid o id de serie), para que reproductor y tarjetas no se contradigan. */
    private fun bindFavorite() {
        val current = video ?: return
        AppScope.launch(Dispatchers.IO) {
            val keys = favoriteKeys(Library.favoritesNow())
            val favorite = matchesFavorite(keys, current.title.orEmpty(), current.aid.coerceAtLeast(0), seriesIdOf(current.source, current.slug), current.slug)
            withContext(Dispatchers.Main) { isFavorite = favorite }
        }
    }

    fun toggleFavorite() {
        val current = video?.takeIf { (it.aid > 0 || it.slug.isNotBlank()) && it.animeUrl != null } ?: return KaizenToast.toast(appString(R.string.ui_no_hay_ficha_para_este_video))
        // Una pulsación a la vez: dos seguidas leerían el mismo estado y repetirían la acción.
        if (favoriteBusy) return
        favoriteBusy = true
        AppScope.launch(Dispatchers.IO) {
            try {
                val favorite = Favorites.toggle(current.title.orEmpty(), current.animeUrl.orEmpty())
                withContext(Dispatchers.Main) { isFavorite = favorite }
            } finally {
                favoriteBusy = false
            }
        }
    }

    // --- Ficha y capítulos

    /** Abre la ficha del anime; el reproductor queda en pausa debajo. */
    fun openSeries() {
        val url = video?.animeUrl ?: return KaizenToast.toast(appString(R.string.ui_no_hay_ficha_para_este_video))
        finishFrameRateSwitch(resume = false)
        player?.pause()
        TVAnimesDetails.start(activity, url)
    }

    fun openTracks() {
        hideControls()
        overlay = PlayerOverlay.Tracks
    }

    fun closeTracks() {
        overlay = PlayerOverlay.None
        showControls()
    }

    /** Portadas de la serie en reproducción (la primera que cargue), para el botón de la ficha. */
    val coverUrls: List<String> get() = video?.takeIf { it.aid > 0 && it.slug.isNotBlank() }?.let { coverCandidates(it.aid, it.slug, "").filter(String::isNotBlank) }.orEmpty()

    /** Capítulos de la serie; vacía hasta que carga la ficha. */
    var chapters by mutableStateOf<List<Chapter>>(emptyList())
        private set

    /** La fila de capítulos va debajo de los botones, dentro de los controles: estos suben para dejarle sitio. */
    var chaptersOpen by mutableStateOf(false)
        private set

    fun openChapters() {
        val url = video?.animeUrl ?: return KaizenToast.toast(appString(R.string.ui_no_hay_ficha_para_este_video))
        chaptersOpen = true
        loadChapters(url)
    }

    /** Carga los capítulos en segundo plano mientras el vídeo arranca, sin mostrar nada: al desplegar la fila ya están. */
    private fun preloadChapters() {
        video?.animeUrl?.let(::loadChapters)
    }

    private var chaptersLoading = false

    private fun loadChapters(url: String) {
        if (chapters.isNotEmpty() || chaptersLoading) return
        chaptersLoading = true
        activity.lifecycleScope.launch {
            try {
                val anime = try { series(url) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                val list = try { anime?.episodes() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                if (anime != null && !list.isNullOrEmpty()) {
                    chapters = list
                    // Con la fila cerrada (precarga) se espera a que el vídeo haya arrancado: las portadas no compiten con su buffering.
                    if (!chaptersOpen) delay(COVER_PROBE_DELAY)
                    // Un extra: si falla no debe romper la fila.
                    sharpCover = try { sharpestCover(activity, anime) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                }
                // Solo avisa si el usuario tiene la fila abierta esperando: la carga previa falla en silencio.
                else if (chaptersOpen) {
                    closeChapters()
                    KaizenToast.toast("No se pudo cargar la ficha")
                }
            } finally {
                chaptersLoading = false
            }
        }
    }

    /** La portada más nítida de la serie entre todas las fuentes: las fuentes que solo dan una imagen pobre por capítulo la usan en la fila. */
    var sharpCover by mutableStateOf<String?>(null)
        private set

    /** Si [chapter] es el que suena: por eid y, sin eid, por número. */
    fun isCurrent(chapter: Chapter): Boolean = video?.let { if (it.eid > 0) chapter.eid == it.eid else chapter.number == it.number } == true

    fun closeChapters() {
        chaptersOpen = false
    }

    /** Reproduce [chapter] desde la lista de capítulos; el actual solo cierra la lista. */
    fun playChapter(chapter: Chapter) {
        if (isCurrent(chapter)) return closeChapters()
        cancelAutoNext()
        save()
        KaizenToast.toast(chapter.episodeName)
        TVServersFactory.start(activity, chapter.link, chapter.name, chapter.episodeName, chapter.asRecord(),
            activity, TVServersFactory.ServerChoice.REMEMBERED)
    }

    /** Atrás: cierra lo que haya encima del vídeo y, si no hay nada, los controles. */
    fun onBack() {
        when (overlay) {
            is PlayerOverlay.NextUp -> cancelAutoNext()
            PlayerOverlay.Tracks -> closeTracks()
            // Un solo paso: Atrás oculta todo (la fila de capítulos incluida) y el siguiente sale; antes eran tres (capítulos, botón de pausa y controles).
            PlayerOverlay.None -> { closeChapters(); hideControls() }
            is PlayerOverlay.Resume -> hideControls()
        }
    }

    /**
     * Kaizen: capítulo anterior/siguiente desde el propio reproductor, con el servidor
     * recordado. Si sale bien, TVPlayer abre el nuevo y se cierra (onFinish).
     * Con [closeIfLast], si no hay capítulo siguiente porque este es el último, el reproductor se cierra como al terminar el vídeo (saltar el cierre del último capítulo).
     */
    fun changeChapter(direction: Int, closeIfLast: Boolean = false) {
        cancelAutoNext()
        save()
        val url = video?.animeUrl
        val number = video?.number?.takeIf { it >= 0 }
        if (url == null || number == null) return KaizenToast.toast(appString(R.string.ui_no_se_puede_cambiar_de_capitulo))
        activity.lifecycleScope.launch {
            val cached = foundNext?.takeIf { direction > 0 && it.fromEid == video.eid }?.chapter
            val chapter = cached ?: when (val lookup = findChapter(url, number, direction)) {
                is ChapterLookup.Found -> lookup.chapter
                is ChapterLookup.Missing -> {
                    KaizenToast.toast(lookup.reason)
                    if (closeIfLast && lookup.isLast) { save(); activity.finish() }
                    return@launch
                }
            }
            KaizenToast.toast(chapter.episodeName)
            TVServersFactory.start(activity, chapter.link, chapter.name, chapter.episodeName, chapter.asRecord(),
                activity, TVServersFactory.ServerChoice.REMEMBERED)
        }
    }

    /** Más allá de esta posición, «anterior» solo vuelve al inicio del capítulo; antes, salta al capítulo anterior. */
    private val restartThresholdMs = 3_000L

    /** «Capítulo anterior»: una pulsación vuelve al inicio del capítulo y una segunda (con el vídeo cerca del inicio) salta al anterior. */
    fun previousOrRestart() {
        val current = player
        if (current != null && current.currentPosition > restartThresholdMs) {
            cancelAutoNext()
            seekBy(-current.currentPosition)
        } else changeChapter(-1)
    }

    /** Capítulo anterior o siguiente de la ficha, o el motivo de que no lo haya. */
    private sealed interface ChapterLookup {
        class Found(val chapter: Chapter) : ChapterLookup
        /** [isLast]: no hay capítulo siguiente porque este es el último de la serie. */
        class Missing(val reason: String, val isLast: Boolean = false) : ChapterLookup
    }

    /** El capítulo siguiente de [fromEid], ya buscado al terminar el vídeo. */
    private class NextChapter(val fromEid: Int, val chapter: Chapter)

    private var seriesLoad: Deferred<Series?>? = null

    /** La ficha del vídeo, pedida una sola vez: el siguiente capítulo y el anterior la comparten. */
    private suspend fun series(animeUrl: String): Series? {
        val load = seriesLoad ?: activity.lifecycleScope.async { TVRepository.getAnime(animeUrl) }.also { seriesLoad = it }
        return try {
            load.await().also { if (it == null && seriesLoad === load) seriesLoad = null }
        } catch (e: Throwable) {
            if (seriesLoad === load) seriesLoad = null
            throw e
        }
    }

    private suspend fun findChapter(animeUrl: String, number: Double, direction: Int): ChapterLookup {
        val anime = series(animeUrl) ?: return ChapterLookup.Missing("No se pudo cargar la ficha")
        val target = anime.episodeNear(number, direction)
        if (target != null) return ChapterLookup.Found(target)
        // Sin siguiente porque este es el último solo si el capítulo está en la lista (si no, no se sabe).
        val list = anime.episodes()
        val index = list.indexOfFirst { it.number == number }
        return ChapterLookup.Missing(
            if (direction > 0) "No hay más capítulos" else "Es el primer capítulo",
            isLast = direction > 0 && index >= 0 && index == list.lastIndex
        )
    }

    /**
     * Al terminar: si era el último capítulo de la serie el reproductor se cierra y se vuelve a la ficha. Si no, el aviso
     * de siguiente capítulo solo sale con «Capítulo siguiente automático» activado.
     */
    private fun onPlaybackEnded() {
        val current = video ?: return
        val url = current.animeUrl
        val number = current.number.takeIf { it >= 0 }
        if (url == null || number == null) return
        activity.lifecycleScope.launch {
            val next = when (val lookup = findChapter(url, number, 1)) {
                is ChapterLookup.Found -> lookup.chapter
                is ChapterLookup.Missing -> {
                    // El usuario pudo volver atrás, reanudar o salir mientras se cargaba la ficha.
                    if (player?.playbackState != Player.STATE_ENDED) return@launch
                    if (KaizenSettings.autoNext || lookup.isLast) KaizenToast.toast(lookup.reason)
                    if (lookup.isLast) {
                        save()
                        activity.finish()
                    }
                    return@launch
                }
            }
            if (!KaizenSettings.autoNext) return@launch
            foundNext = NextChapter(current.eid, next)
            // Mientras se miraba la ficha el usuario pudo volver atrás o reanudar.
            if (player?.playbackState != Player.STATE_ENDED) return@launch
            hideControls()
            overlay = PlayerOverlay.NextUp(AUTO_NEXT_SECONDS)
            handler.removeCallbacks(nextUpTick)
            handler.post(nextUpTick)
        }
    }

    fun cancelAutoNext() {
        if (overlay !is PlayerOverlay.NextUp) return
        handler.removeCallbacks(nextUpTick)
        overlay = PlayerOverlay.None
    }

    /** «Ver ahora» del aviso de siguiente capítulo. */
    fun playNextNow() {
        cancelAutoNext()
        changeChapter(1)
    }

    // --- Ajustes del vídeo (pistas, calidad, AFR)

    /** Kaizen: panel lateral con las pistas disponibles, como el de SmartTube. */
    fun tracksCategory() = SettingsCategory(appString(R.string.ui_ajustes_del_video), app.kaizen.R.drawable.ic_quality_kaizen) {
        listOf(qualityItem(), trackItem(C.TRACK_TYPE_AUDIO, "Audio"), trackItem(C.TRACK_TYPE_TEXT, "Subtítulos"), frameRateItem())
    }

    /** AFR: ajusta la frecuencia de la pantalla a los fps del vídeo (desactivarla devuelve el modo de la tele). */
    private fun frameRateItem() = CheckItem(appString(R.string.ui_frecuencia_automatica_afr), appString(R.string.ui_ajusta_la_tele_a_los_fps_del_video),
        { KaizenSettings.frameRateMatching }) { enabled ->
        KaizenSettings.frameRateMatching = enabled
        onFrameRateMatchingChanged(enabled)
    }

    /** Automática (o máxima, según Ajustes) y después cada resolución de mayor a menor. */
    private fun qualityItem(): ChoiceItem {
        val group = player?.currentTracks?.groups?.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
        val tracks = group?.let { (0 until it.length).filter(it::isTrackSupported).sortedByDescending { index -> it.getTrackFormat(index).height } }.orEmpty()
        val options = listOf(-1) + tracks
        val labels = listOf(if (KaizenSettings.maxQuality) "Máxima" else "Automática") + group?.let { g -> tracks.map { qualityName(g.getTrackFormat(it)) } }.orEmpty()
        return ChoiceItem(appString(R.string.ui_calidad), labels, { options.indexOf(qualityTrack).coerceAtLeast(0) }) { position ->
            qualityTrack = options[position]
            val current = player ?: return@ChoiceItem
            val builder = current.trackSelectionParameters.buildUpon()
            current.trackSelectionParameters = if (qualityTrack < 0 || group == null) {
                builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
            } else {
                builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, qualityTrack)).build()
            }
        }
    }

    /** Pistas de audio o subtítulos; en subtítulos la primera opción es «Desactivados». */
    private fun trackItem(type: Int, title: String): ChoiceItem {
        val exo = player
        val options = exo?.currentTracks?.groups.orEmpty().filter { it.type == type && it.isSupported }
            .flatMap { group -> (0 until group.length).map { group to it } }
        val allowOff = type == C.TRACK_TYPE_TEXT
        val labels = (if (allowOff) listOf("Desactivados") else emptyList()) +
            options.mapIndexed { number, (group, index) -> trackName(group.getTrackFormat(index), number + 1) }
        val offset = if (allowOff) 1 else 0
        val selected = {
            val disabled = exo != null && type in exo.trackSelectionParameters.disabledTrackTypes
            if (allowOff && disabled) 0 else options.indexOfFirst { (group, index) -> group.isTrackSelected(index) } + offset
        }
        return ChoiceItem(title, labels.ifEmpty { listOf(appString(R.string.ui_no_hay_pistas)) }, { selected().coerceAtLeast(0) }) { position ->
            val current = player ?: return@ChoiceItem
            val builder = current.trackSelectionParameters.buildUpon()
            if (allowOff && position == 0) {
                current.trackSelectionParameters = builder.setTrackTypeDisabled(type, true).build()
                return@ChoiceItem
            }
            val (group, index) = options.getOrNull(position - offset) ?: return@ChoiceItem
            current.trackSelectionParameters = builder
                .setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                .build()
        }
    }

    private fun qualityName(format: Format) = listOfNotNull(
        format.height.takeIf { it > 0 }?.let { "${it}p" },
        format.frameRate.takeIf { it > 0f }?.let { "%.3g fps".format(it) },
        format.bitrate.takeIf { it > 0 }?.let { "%.1f Mbps".format(it / 1_000_000f) }
    ).joinToString(" · ").ifEmpty { "Pista" }

    /** Nombre legible de una pista: etiqueta, idioma (ignorando «und»), canales o número. */
    private fun trackName(format: Format, number: Int): String {
        val language = format.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
            ?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }
        val channels = when (format.channelCount) {
            1 -> "mono"
            2 -> "estéreo"
            6 -> "5.1"
            8 -> "7.1"
            else -> null
        }
        return listOfNotNull(format.label?.takeIf { it.isNotBlank() } ?: language, channels)
            .joinToString(" · ").ifEmpty { "Pista $number" }
    }

    private companion object {
        val COVER_PROBE_DELAY = 4.seconds
        const val AUTO_NEXT_SECONDS = 10
        const val MEDIA_SESSION_ID = "kaizen"
        val RELEASE_SCREEN = 30.seconds
        val IDLE_EXIT = 10.minutes
        val SEEK_HINT = 1200.milliseconds
        val SEEK_SETTLE = 250.milliseconds
        val OPENING_PROMPT = 10.seconds
        /** Si el cierre acaba a menos de esto del final del vídeo, saltarlo pasa al capítulo siguiente. */
        const val ENDING_NEXT_MARGIN_MS = 3_000L
        const val OPENING_PROMPT_MAX_START_MS = 15_000L
        val FRAME_RATE_SWITCH_TIMEOUT = 3.seconds
        const val MIN_BAR_STEP_MS = 5_000L

        val OK_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)
        val MEDIA_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS
        )

        val TICK = 500.milliseconds
        const val TICKS_PER_SECOND = 2
        const val SAVE_EVERY_TICKS = 30

        val NETWORK_ERRORS = setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT
        )
        val DECODER_ERRORS = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        )
    }
}
