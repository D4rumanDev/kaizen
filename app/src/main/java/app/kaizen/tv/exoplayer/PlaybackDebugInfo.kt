package app.kaizen.tv.exoplayer

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import app.kaizen.commons.AppErrors
import app.kaizen.commons.AppScope
import kotlinx.coroutines.launch
import app.kaizen.domain.ConnectionStats
import app.kaizen.domain.ConnectionStats.Cause
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * Kaizen: datos para el panel de depuración y la ventana de información del capítulo.
 *
 * Escucha al reproductor y apunta **qué ha pasado, cuándo y por qué** (registro de sucesos con la hora y el punto del vídeo) y
 * cuenta los problemas habituales con su número (ver [Cause], el catálogo único de errores de la app), para que el panel diga qué falla y cómo arreglarlo.
 */
@OptIn(UnstableApi::class)
class PlaybackDebugInfo : AnalyticsListener {
    /** Un suceso del reproductor: [wallMs] (hora real), [positionMs] (punto del vídeo), [kind] (tipo) y [text] (qué pasó y por qué). */
    data class Event(val wallMs: Long, val positionMs: Long, val kind: String, val text: String, val issue: Cause? = null)

    private var videoDecoder: String? = null
    private var audioDecoder: String? = null
    private var bandwidthBps = 0L

    /** El estado de la reproducción: lo único que cambia es `session = session.on(evento)` (ver [PlaybackSession]). */
    private var session = PlaybackSession()

    private val events = ArrayDeque<Event>()

    private fun now() = SystemClock.elapsedRealtime()

    private fun record(eventTime: AnalyticsListener.EventTime, kind: String, text: String, issue: Cause? = null) {
        events.addLast(Event(System.currentTimeMillis(), eventTime.currentPlaybackPositionMs, kind, text, issue))
        while (events.size > MAX_EVENTS) events.removeFirst()
    }

    /** Un fallo: entra en la sesión como incidente, sale en los sucesos y se guarda en el registro de depuración. */
    private fun fail(eventTime: AnalyticsListener.EventTime, issue: Cause, text: String, detail: String) {
        session = session.on(SessionEvent.Failure(issue, detail, now()))
        record(eventTime, "ERROR", text, issue)
        AppErrors.record(issue, AppErrors.Area.PLAYER, detail)
    }

    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        videoDecoder = decoderName
        record(eventTime, "VÍDEO", "Decodificador de vídeo listo: $decoderName (tardó $initializationDurationMs ms)")
    }

    override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        audioDecoder = decoderName
        record(eventTime, "AUDIO", "Decodificador de audio listo: $decoderName (tardó $initializationDurationMs ms)")
    }

    override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
        record(eventTime, "VÍDEO", "Formato de vídeo: ${shortCodec(format) ?: "?"} ${if (format.width > 0) "${format.width}x${format.height}" else ""}".trim())
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        session = session.on(SessionEvent.Dropped(droppedFrames, now()))
        if (droppedFrames >= BIG_DROP) record(eventTime, "VÍDEO", "Perdió $droppedFrames fotogramas en ${elapsedMs} ms: el decodificador no llegó a tiempo", Cause.DROPPED)
    }

    override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
        bandwidthBps = bitrateEstimate
    }

    override fun onPositionDiscontinuity(eventTime: AnalyticsListener.EventTime, oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
            session = session.on(SessionEvent.Seek(now()))
            record(eventTime, "SALTO", "Salto de ${clock(oldPosition.positionMs)} a ${clock(newPosition.positionMs)} (lo pidió el usuario o el omitir de intro/cierre)")
        }
    }

    override fun onPlayWhenReadyChanged(eventTime: AnalyticsListener.EventTime, playWhenReady: Boolean, reason: Int) {
        val why = when (reason) {
            Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "por el usuario"
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "otra app tomó el audio"
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "se desconectó la salida de audio"
            Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "fin del vídeo"
            Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "orden externa (mando o ADB)"
            Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "llevaba demasiado en suspenso"
            else -> "motivo $reason"
        }
        record(eventTime, "ESTADO", "${if (playWhenReady) "Reanudado" else "En pausa"}: $why")
    }

    /** Manda el cambio de estado a la máquina y apunta lo que la transición significa (recarga que empieza o termina, carga inicial, final). */
    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        session = session.on(SessionEvent.PlayerState(state, now()))
        val phase = session.phase
        when {
            session.rebufferStarted -> record(eventTime, "CARGA", "Se detuvo a cargar: ${phase.bufferingReason(mbps(bandwidthBps))}", Cause.SLOW)
            state == Player.STATE_BUFFERING -> record(eventTime, "CARGA", "Cargando: ${phase.bufferingReason(mbps(bandwidthBps))}")
            session.rebufferEnded -> record(eventTime, "CARGA", "Retomó tras esperar ${"%.1f".format(session.lastRebufferMs / 1000.0)} s")
        }
        if (state == Player.STATE_READY && session.previous == PlaybackPhase.START) record(eventTime, "ESTADO", "Listo para reproducir")
        if (state == Player.STATE_ENDED) record(eventTime, "ESTADO", "El vídeo terminó")
    }

    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
        val before = session.startupMs
        session = session.on(SessionEvent.FirstFrame(now()))
        if (before < 0 && session.startupMs >= 0) record(eventTime, "ESTADO", "Primer fotograma en ${session.startupMs} ms desde que empezó a cargar")
    }

    override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
        session = session.on(SessionEvent.Underrun(now()))
        record(eventTime, "AUDIO", "Corte de audio: llevaba ${elapsedSinceLastFeedMs} ms sin recibir datos", Cause.SLOW)
    }

    override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData, error: IOException, wasCanceled: Boolean) {
        // Una descarga cancelada (el reproductor cambió de calidad o saltó) no es un fallo.
        if (wasCanceled) return
        val issue = ConnectionStats.causeOfPlayback(PlaybackException.ERROR_CODE_UNSPECIFIED, error)
        fail(eventTime, issue, "Falló una descarga (${loadEventInfo.uri.host ?: "?"}): ${issue.label}",
            "${error.javaClass.simpleName}: ${error.message ?: ""}".trim())
    }

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
        val issue = ConnectionStats.causeOfPlayback(error.errorCode, error.cause)
        fail(eventTime, issue, "El reproductor se detuvo: ${issue.label}", "${error.errorCodeName}${error.message?.let { ": $it" } ?: ""}")
    }

    /** La historia del servidor y de los enlaces de la fuente del vídeo, con cuándo se leyó. */
    private class History(val at: Long, val serverName: String, val linkName: String, val server: ConnectionStats.Total?, val link: ConnectionStats.Total?)

    @Volatile private var cachedHistory: History? = null
    @Volatile private var refreshingHistory = false

    /**
     * La historia de [video] en las estadísticas de conexión. Leerla toca las preferencias en disco y el panel se redibuja cada segundo en el hilo principal: se lee
     * en segundo plano como mucho cada [HISTORY_TTL] y mientras tanto se devuelve la última (vacía la primera vez; en un segundo ya está).
     */
    private fun history(video: Video?): Pair<ConnectionStats.Total?, ConnectionStats.Total?> {
        val current = cachedHistory
        val serverName = video?.serverName.orEmpty()
        val linkName = video?.linkSource.orEmpty()
        // Caduca por tiempo y también si el vídeo es otro (otro servidor o fuente): no se enseña la historia de uno bajo el nombre de otro.
        val stale = current == null || current.serverName != serverName || current.linkName != linkName || now() - current.at > HISTORY_TTL.inWholeMilliseconds
        if (!refreshingHistory && stale) {
            refreshingHistory = true
            AppScope.launch {
                try {
                    val stats = runCatching { ConnectionStats.rows() }.getOrDefault(emptyList())
                    val server = serverName.takeIf { it.isNotBlank() }?.let { name -> ConnectionStats.ranked(stats, ConnectionStats.Kind.SERVER).firstOrNull { it.name == name } }
                    val link = linkName.takeIf { it.isNotBlank() }?.let { name -> ConnectionStats.ranked(stats, ConnectionStats.Kind.LINK).firstOrNull { it.name == name } }
                    cachedHistory = History(now(), serverName, linkName, server, link)
                } finally {
                    refreshingHistory = false
                }
            }
        }
        // Solo la historia de ESTE vídeo; mientras llega la nueva, ninguna.
        return cachedHistory?.takeIf { it.serverName == serverName && it.linkName == linkName }.let { it?.server to it?.link }
    }

    /** El panel de depuración: estado actual, avisos y errores numerados con causa y solución, y los últimos sucesos. */
    fun describe(player: ExoPlayer?, manager: DecoderManager?, video: Video?): String {
        player ?: return "Sin reproductor"
        val videoFormat = player.videoFormat
        val audioFormat = player.audioFormat
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) shr 20
        val maxMb = rt.maxMemory() shr 20
        val s = session
        val waitedMs = s.rebufferMsAt(now())
        val minutes = player.currentPosition / 60_000.0
        val dropsPerMin = if (player.currentPosition > 10_000) s.droppedFrames / minutes else 0.0

        // Avisos que se deducen de las cifras de la sesión: el vídeo va, pero mal.
        val warnings = buildList {
            if (s.rebuffers > 0) add(Cause.SLOW to "${s.rebuffers} cortes, ${"%.1f".format(waitedMs / 1000.0)} s esperando; red ≈ ${mbps(bandwidthBps)}")
            if (dropsPerMin > DROPS_PER_MIN_WARN) add(Cause.DROPPED to "${s.droppedFrames} perdidos (${"%.1f".format(dropsPerMin)}/min)")
            if (s.underruns > 0) add(Cause.SLOW to "${s.underruns} cortes de audio")
            if (maxMb > 0 && usedMb * 100 / maxMb >= LOW_MEMORY_PERCENT) add(Cause.LOW_MEMORY to "$usedMb de $maxMb MB en uso")
        }
        val problems = s.incidentSummary().map { it.cause to "${it.count}× ${it.lastDetail}".trim() } + warnings

        return buildString {
            appendLine("── AHORA ──")
            appendLine("${stateName(player.playbackState)}${if (player.isPlaying) " · reproduciendo" else if (!player.playWhenReady) " · en pausa" else ""} · ${"%.2f".format(player.playbackParameters.speed)}x · ${clock(player.currentPosition)} / ${clock(player.duration.coerceAtLeast(0))}")
            appendLine("Búfer: ${player.totalBufferedDuration / 1000} s · red ≈ ${mbps(bandwidthBps)} · arranque ${if (s.startupMs >= 0) "${s.startupMs} ms" else "?"} · fase ${s.phase.label}")
            appendLine("VÍDEO ${formatLine(videoFormat)}")
            appendLine("  ${codecName(videoFormat)}")
            appendLine("  decodificador ${videoDecoder ?: "?"} (${manager?.activeVideoMode ?: "?"})${videoFormat?.frameRate?.takeIf { it > 0 }?.let { " · ${"%.2f".format(it)} fps" } ?: ""}")
            appendLine("AUDIO ${formatLine(audioFormat)}")
            appendLine("  ${codecName(audioFormat)}")
            appendLine("  decodificador ${audioDecoder ?: "?"} (${manager?.activeAudioMode ?: "?"})${audioFormat?.let { " · ${it.sampleRate} Hz · ${it.channelCount} canales" } ?: ""}")
            appendLine("Cadena: fuente ${video?.linkSource?.ifBlank { null } ?: "?"} → servidor ${video?.serverName?.ifBlank { null } ?: video?.server ?: "?"} → ${video?.uri?.host ?: "?"} · memoria $usedMb/$maxMb MB")

            appendLine()
            if (problems.isEmpty()) {
                appendLine("── PROBLEMAS ──  ninguno detectado")
            } else {
                appendLine("── PROBLEMAS (${problems.size}) ──")
                val (serverTotal, linkTotal) = history(video)
                for ((issue, detail) in problems) {
                    val diagnosis = ConnectionStats.diagnose(issue, serverTotal, linkTotal)
                    appendLine("${issue.tag} ${issue.label}  [$detail]")
                    appendLine("   Origen: ${diagnosis.origin.label} · ${diagnosis.evidence}")
                    appendLine("   Por qué: ${issue.meaning}")
                    appendLine("   Qué hacer: ${issue.fix}")
                }
            }

            appendLine()
            appendLine("── SUCESOS (hora · punto del vídeo) ──")
            if (events.isEmpty()) append("(ninguno todavía)") else {
                val format = SimpleDateFormat("HH:mm:ss", Locale.US)
                val shown = events.takeLast(SHOWN_EVENTS)
                shown.forEachIndexed { index, e ->
                    append("${format.format(e.wallMs)} · ${clock(e.positionMs)}  ${e.kind}${e.issue?.let { " ${it.tag}" } ?: ""}: ${e.text}")
                    if (index < shown.lastIndex) appendLine()
                }
            }
        }
    }

    /** Códecs de vídeo y audio en corto, para el encabezado del reproductor: «H.264 High 10 10 bits · AAC». */
    fun codecSummary(player: ExoPlayer?): String {
        return listOfNotNull(shortCodec(player?.videoFormat), shortCodec(player?.audioFormat)).joinToString(" · ")
    }

    /** «H.264 High 10 10 bits»: nombre, perfil H.264 y profundidad de color si se conocen; null sin formato. */
    private fun shortCodec(format: Format?): String? {
        format ?: return null
        val mime = format.sampleMimeType
        val name = CODEC_NAMES[mime] ?: mime ?: return null
        val profile = if (mime == MimeTypes.VIDEO_H264) h264Profile(format.codecs) else null
        val bitDepth = format.colorInfo?.lumaBitdepth?.takeIf { it != Format.NO_VALUE }?.let { "$it bits" }
        return listOfNotNull(name, profile, bitDepth).joinToString(" ")
    }

    private fun formatLine(format: Format?): String {
        format ?: return "—"
        val size = if (format.width > 0) "${format.width}x${format.height} " else ""
        val bitrate = format.bitrate.takeIf { it > 0 }?.let { " · ${it / 1000} kbps" } ?: ""
        return "$size${format.sampleMimeType ?: "?"} ${format.codecs ?: ""}$bitrate".trim()
    }

    /** Nombre del códec con sus extras (cadena de códecs y contenedor), para el panel de depuración. */
    private fun codecName(format: Format?): String {
        format ?: return "—"
        val container = format.containerMimeType?.substringAfter('/')
        return listOfNotNull(shortCodec(format) ?: "?", format.codecs, container?.let { "contenedor $it" }).joinToString(" · ")
    }

    /** avc1.PPCCLL: PP = profile_idc en hexadecimal. */
    private fun h264Profile(codecs: String?): String? {
        val idc = codecs?.substringAfter("avc1.", "")?.take(2)?.toIntOrNull(16) ?: return null
        return when (idc) {
            66 -> "Baseline"
            77 -> "Main"
            88 -> "Extended"
            100 -> "High"
            110 -> "High 10"
            122 -> "High 4:2:2"
            244 -> "High 4:4:4"
            else -> "perfil $idc"
        }
    }

    private fun stateName(state: Int) = when (state) {
        Player.STATE_IDLE -> "inactivo"
        Player.STATE_BUFFERING -> "cargando"
        Player.STATE_READY -> "listo"
        Player.STATE_ENDED -> "terminado"
        else -> state.toString()
    }

    companion object {
        private val HISTORY_TTL = 10.seconds
        private const val MAX_EVENTS = 60
        private const val SHOWN_EVENTS = 10
        private const val BIG_DROP = 10
        private const val DROPS_PER_MIN_WARN = 5.0
        private const val LOW_MEMORY_PERCENT = 85

        /** «mm:ss» (o «h:mm:ss») de una posición en ms. */
        fun clock(ms: Long): String {
            val total = (ms.coerceAtLeast(0)) / 1000
            val h = total / 3600
            val m = total % 3600 / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
        }

        private fun mbps(bps: Long) = if (bps > 0) "%.1f Mbps".format(bps / 1_000_000.0) else "?"

        private val CODEC_NAMES = mapOf(
            MimeTypes.VIDEO_H264 to "H.264",
            MimeTypes.VIDEO_H265 to "H.265 (HEVC)",
            MimeTypes.VIDEO_AV1 to "AV1",
            MimeTypes.VIDEO_VP9 to "VP9",
            MimeTypes.VIDEO_VP8 to "VP8",
            MimeTypes.VIDEO_MPEG2 to "MPEG-2",
            MimeTypes.AUDIO_AAC to "AAC",
            MimeTypes.AUDIO_OPUS to "Opus",
            MimeTypes.AUDIO_VORBIS to "Vorbis",
            MimeTypes.AUDIO_MPEG to "MP3",
            MimeTypes.AUDIO_AC3 to "Dolby Digital (AC-3)",
            MimeTypes.AUDIO_E_AC3 to "Dolby Digital Plus (E-AC-3)",
            MimeTypes.AUDIO_FLAC to "FLAC"
        )
    }
}
