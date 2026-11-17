package app.kaizen.tv.exoplayer

import app.kaizen.tv.exoplayer.PlayerButton.DEBUG
import app.kaizen.tv.exoplayer.PlayerButton.FAVORITE
import app.kaizen.tv.exoplayer.PlayerButton.FORWARD
import app.kaizen.tv.exoplayer.PlayerButton.NEXT
import app.kaizen.tv.exoplayer.PlayerButton.PLAY
import app.kaizen.tv.exoplayer.PlayerButton.PREVIOUS
import app.kaizen.tv.exoplayer.PlayerButton.REWIND
import app.kaizen.tv.exoplayer.PlayerButton.SPEED
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import app.kaizen.domain.ConnectionStats
import app.kaizen.domain.ConnectionStats.Cause
import org.junit.Assert.assertEquals
import org.junit.Test

// ===== VideoScaleTest.kt =====
class VideoScaleTest {
    @Test
    fun elBotonRecorreLasEscalasYVuelveAlPrincipio() {
        assertEquals(VideoScale.WIDTH, VideoScale.FIT.next())
        assertEquals(VideoScale.HEIGHT, VideoScale.WIDTH.next())
        assertEquals(VideoScale.ZOOM, VideoScale.HEIGHT.next())
        assertEquals(VideoScale.FILL, VideoScale.ZOOM.next())
        assertEquals(VideoScale.FIT, VideoScale.FILL.next())
    }

    @Test
    fun soloElBotonDeVelocidadTieneElAnchoDelTexto() {
        assertEquals(PlayerButton.TEXT_WIDTH_DP, PlayerButton.widthDp(PlayerButton.SPEED))
        assertEquals(PlayerButton.ROUND_DP, PlayerButton.widthDp(PlayerButton.SCALE))
        assertEquals(PlayerButton.ROUND_DP, PlayerButton.widthDp(PLAY))
    }
}

// ===== PlayerButtonFitTest.kt =====
class PlayerButtonFitTest {
    private val all = listOf(PREVIOUS, REWIND, PLAY, FORWARD, NEXT, FAVORITE, SPEED, DEBUG)
    private val c = PlayerButton.ROUND_DP
    private val w = PlayerButton.TEXT_WIDTH_DP
    private val g = PlayerButton.SPACING_DP

    // 8 botones: 7 redondos + el de velocidad (con texto, más ancho) + 7 huecos.
    @Test
    fun siCabenTodosSeMuestranTodos() {
        val justo = 7 * c + w + 7 * g
        assertEquals(all, PlayerButton.fitting(all, justo))
        assertEquals(all, PlayerButton.fitting(all, 2000))
        assertEquals(all.dropLast(1), PlayerButton.fitting(all, justo - 1))
    }

    @Test
    fun losQueNoCabenSeQuitanPorElFinal() {
        // Caben 4 justos; con un dp menos solo 3.
        assertEquals(listOf(PREVIOUS, REWIND, PLAY, FORWARD), PlayerButton.fitting(all, 4 * c + 3 * g))
        assertEquals(listOf(PREVIOUS, REWIND, PLAY), PlayerButton.fitting(all, 4 * c + 3 * g - 1))
    }

    @Test
    fun reproducirNuncaSeQuita() {
        val playAlFinal = listOf(PREVIOUS, REWIND, FORWARD, NEXT, PLAY)
        // Solo caben 2 por ancho: PLAY ocupa el sitio del último que cabe.
        assertEquals(listOf(PREVIOUS, PLAY), PlayerButton.fitting(playAlFinal, 2 * c + g))
    }

    @Test
    fun listaVaciaYUnSoloBoton() {
        assertEquals(emptyList<PlayerButton>(), PlayerButton.fitting(emptyList(), 500))
        assertEquals(listOf(PLAY), PlayerButton.fitting(listOf(PLAY), c))
        assertEquals(emptyList<PlayerButton>(), PlayerButton.fitting(listOf(REWIND), c - 1))
    }

    @Test
    fun sinReproducirEnLaListaNoSeInventa() {
        assertEquals(listOf(REWIND, FORWARD), PlayerButton.fitting(listOf(REWIND, FORWARD), 500))
    }

    @Test
    fun reproducirOcupaElSitioDelUltimoQueCabe() {
        // SPEED (más ancho) ocupa el último hueco y cabe justo; PLAY al final lo sustituye.
        val lista = listOf(PREVIOUS, SPEED, PLAY)
        assertEquals(listOf(PREVIOUS, PLAY), PlayerButton.fitting(lista, c + g + w))
    }

    @Test
    fun sinEspacioNoHayBotones() {
        assertEquals(emptyList<PlayerButton>(), PlayerButton.fitting(listOf(REWIND, FORWARD), c - 1))
    }
}

// ===== SeekAccelerationTest.kt =====
class SeekAccelerationTest {

    @Test
    fun `una pulsacion suelta salta lo configurado`() {
        assertEquals(30, SeekAcceleration.stepSeconds(30, 0))
        assertEquals(30, SeekAcceleration.stepSeconds(30, 2))
    }

    @Test
    fun `mantener la tecla sube 10 s cada 3 repeticiones`() {
        assertEquals(40, SeekAcceleration.stepSeconds(30, 3))
        assertEquals(50, SeekAcceleration.stepSeconds(30, 6))
        assertEquals(10, SeekAcceleration.stepSeconds(10, 0))
        assertEquals(20, SeekAcceleration.stepSeconds(10, 3))
    }

    @Test
    fun `el salto no pasa de 90 s`() {
        assertEquals(90, SeekAcceleration.stepSeconds(30, 30))
        assertEquals(90, SeekAcceleration.stepSeconds(30, 1000))
    }

    @Test
    fun `un salto base mayor que el tope se respeta`() {
        assertEquals(120, SeekAcceleration.stepSeconds(120, 0))
        assertEquals(120, SeekAcceleration.stepSeconds(120, 1000))
    }
}

// ===== PlaybackErrorsTest.kt =====
class PlaybackErrorsTest {
    private fun total(name: String, fail: Int, total: Int) = ConnectionStats.Total(name, fail, total)

    @Test
    fun `un nombre sin resolver se reconoce aunque venga envuelto`() {
        val wrapped = java.io.IOException("x", java.net.UnknownHostException("host"))
        assertEquals(Cause.DNS, ConnectionStats.causeOfPlayback(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, wrapped))
    }

    @Test
    fun `el codigo de ExoPlayer decide cuando no hay causa`() {
        assertEquals(Cause.TIMEOUT, ConnectionStats.causeOfPlayback(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, null))
        assertEquals(Cause.DECODER, ConnectionStats.causeOfPlayback(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, null))
        assertEquals(Cause.AUDIO_OUTPUT, ConnectionStats.causeOfPlayback(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, null))
        assertEquals(Cause.BAD_FILE, ConnectionStats.causeOfPlayback(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, null))
        assertEquals(Cause.OTHER, ConnectionStats.causeOfPlayback(PlaybackException.ERROR_CODE_UNSPECIFIED, null))
    }

    @Test
    fun `los codigos HTTP se agrupan igual para conexiones y reproductor`() {
        assertEquals(Cause.BLOCKED, ConnectionStats.causeOfHttp(403))
        assertEquals(Cause.NOT_FOUND, ConnectionStats.causeOfHttp(404))
        assertEquals(Cause.REJECTED, ConnectionStats.causeOfHttp(429))
        assertEquals(Cause.SERVER_ERROR, ConnectionStats.causeOfHttp(503))
        assertEquals(Cause.BLOCKED, ConnectionStats.causeOf(org.jsoup.HttpStatusException("x", 403, "u")))
    }

    @Test
    fun `los numeros y los codigos no se repiten`() {
        assertEquals(Cause.entries.size, Cause.entries.map { it.number }.toSet().size)
        assertEquals(Cause.entries.size, Cause.entries.map { it.code }.toSet().size)
        assertEquals("E03", Cause.CONNECTION.tag)
    }

    @Test
    fun `un fallo de red de un servidor que falla mucho es del servidor`() {
        val d = ConnectionStats.diagnose(Cause.CONNECTION, total("voe", 13, 31), null)
        assertEquals(ConnectionStats.Origin.SERVER, d.origin)
    }

    @Test
    fun `un fallo de red de un servidor sano apunta a la red o a la TV`() {
        val d = ConnectionStats.diagnose(Cause.CONNECTION, total("voe", 1, 40), null)
        assertEquals(ConnectionStats.Origin.NETWORK, d.origin)
        assert(d.evidence.contains("tu red o a la TV"))
    }

    @Test
    fun `sin historia suficiente se queda el origen de la causa`() {
        assertEquals(ConnectionStats.Origin.DEVICE, ConnectionStats.diagnose(Cause.DECODER, total("voe", 3, 3), null).origin)
        assertEquals(ConnectionStats.Origin.NETWORK, ConnectionStats.diagnose(Cause.DNS, total("voe", 2, 2), null).origin)
        assertEquals(ConnectionStats.Origin.SOURCE, ConnectionStats.diagnose(Cause.NOT_FOUND, null, total("animeflv", 5, 20)).origin)
    }

    @Test
    fun `el reloj del panel formatea posiciones`() {
        assertEquals("00:05", PlaybackDebugInfo.clock(5_000))
        assertEquals("12:34", PlaybackDebugInfo.clock(754_000))
        assertEquals("1:02:03", PlaybackDebugInfo.clock(3_723_000))
        assertEquals("00:00", PlaybackDebugInfo.clock(-1))
    }
}

// ===== PlaybackPhaseTest.kt =====
class PlaybackPhaseTest {
    private val buffering = Player.STATE_BUFFERING
    private val ready = Player.STATE_READY

    @Test
    fun `la carga inicial no es una recarga`() {
        assertEquals(PlaybackPhase.START, PlaybackPhase.START.onPlayerState(buffering))
        assertEquals(PlaybackPhase.READY, PlaybackPhase.START.onPlayerState(ready))
    }

    @Test
    fun `quedarse sin datos a mitad es una recarga y al volver a estar listo termina`() {
        val stalled = PlaybackPhase.READY.onPlayerState(buffering)
        assertEquals(PlaybackPhase.REBUFFER, stalled)
        assertEquals(PlaybackPhase.REBUFFER, stalled.onPlayerState(buffering))
        assertEquals(PlaybackPhase.READY, stalled.onPlayerState(ready))
    }

    @Test
    fun `cargar tras un salto no es una recarga`() {
        val seeking = PlaybackPhase.READY.onSeek()
        assertEquals(PlaybackPhase.SEEKING, seeking.onPlayerState(buffering))
        assertEquals(PlaybackPhase.READY, seeking.onPlayerState(ready))
    }

    @Test
    fun `un salto antes de la primera carga sigue siendo carga inicial`() {
        assertEquals(PlaybackPhase.START, PlaybackPhase.START.onSeek())
    }

    @Test
    fun `un salto dentro del bufer caduca y la siguiente carga ya cuenta`() {
        val seeking = PlaybackPhase.READY.onSeek()
        assertEquals(PlaybackPhase.SEEKING, seeking.expireSeek(1_000))
        val expired = seeking.expireSeek(PlaybackPhase.SEEK_WINDOW_MS + 1)
        assertEquals(PlaybackPhase.READY, expired)
        assertEquals(PlaybackPhase.REBUFFER, expired.onPlayerState(buffering))
    }

    @Test
    fun `tras terminar, volver a cargar es un salto`() {
        val ended = PlaybackPhase.READY.onPlayerState(Player.STATE_ENDED)
        assertEquals(PlaybackPhase.ENDED, ended)
        assertEquals(PlaybackPhase.SEEKING, ended.onPlayerState(buffering))
    }
}

// ===== PlaybackSessionTest.kt =====
class PlaybackSessionTest {
    private fun state(s: PlaybackSession, state: Int, at: Long) = s.on(SessionEvent.PlayerState(state, at))

    @Test
    fun `el arranque mide desde la primera carga hasta el primer fotograma`() {
        var s = state(PlaybackSession(), Player.STATE_BUFFERING, 1_000)
        s = state(s, Player.STATE_READY, 1_800)
        s = s.on(SessionEvent.FirstFrame(2_500))
        assertEquals(1_500, s.startupMs)
        assertEquals(0, s.rebuffers)
        // Un segundo primer fotograma (otro episodio en la misma sesión) no cambia el arranque.
        assertEquals(1_500, s.on(SessionEvent.FirstFrame(9_000)).startupMs)
    }

    @Test
    fun `una recarga se cuenta y mide su espera, y la en curso suma`() {
        var s = state(state(PlaybackSession(), Player.STATE_BUFFERING, 0), Player.STATE_READY, 500)
        s = state(s, Player.STATE_BUFFERING, 10_000)
        assertEquals(1, s.rebuffers)
        assert(s.rebufferStarted)
        assertEquals(2_000, s.rebufferMsAt(12_000))
        s = state(s, Player.STATE_READY, 13_000)
        assert(s.rebufferEnded)
        assertEquals(3_000, s.lastRebufferMs)
        assertEquals(3_000, s.rebufferMs)
        assertEquals(3_000, s.rebufferMsAt(99_000))
    }

    @Test
    fun `un salto en mitad de una recarga la termina y suma su espera`() {
        var s = state(state(PlaybackSession(), Player.STATE_BUFFERING, 0), Player.STATE_READY, 500)
        s = state(s, Player.STATE_BUFFERING, 10_000)
        assertEquals(1, s.rebuffers)
        s = s.on(SessionEvent.Seek(14_000))
        assertEquals(PlaybackPhase.SEEKING, s.phase)
        assertEquals(4_000, s.rebufferMs)
        assertEquals(4_000, s.lastRebufferMs)
        // Cargar tras ese salto ya no es otra recarga.
        assertEquals(1, state(s, Player.STATE_BUFFERING, 14_100).rebuffers)
    }

    @Test
    fun `cargar tras un salto no es recarga pero pasados 3 s si`() {
        var s = state(state(PlaybackSession(), Player.STATE_BUFFERING, 0), Player.STATE_READY, 500)
        s = s.on(SessionEvent.Seek(5_000))
        s = state(s, Player.STATE_BUFFERING, 5_100)
        assertEquals(0, s.rebuffers)
        s = state(s, Player.STATE_READY, 5_600)
        s = s.on(SessionEvent.Seek(20_000))
        s = state(s, Player.STATE_BUFFERING, 20_000 + PlaybackPhase.SEEK_WINDOW_MS + 1)
        assertEquals(1, s.rebuffers)
    }

    @Test
    fun `los errores son una lista y los totales se derivan`() {
        var s = PlaybackSession()
        s = s.on(SessionEvent.Failure(Cause.DNS, "a", 1))
        s = s.on(SessionEvent.Failure(Cause.TIMEOUT, "b", 2))
        s = s.on(SessionEvent.Failure(Cause.DNS, "c", 3))
        assertEquals(3, s.incidents.size)
        val summary = s.incidentSummary()
        assertEquals(listOf(Cause.DNS, Cause.TIMEOUT), summary.map { it.cause })
        assertEquals(2, summary[0].count)
        assertEquals("c", summary[0].lastDetail)
    }

    @Test
    fun `los incidentes tienen tope`() {
        var s = PlaybackSession()
        repeat(PlaybackSession.MAX_INCIDENTS + 50) { s = s.on(SessionEvent.Failure(Cause.OTHER, "x$it", it.toLong())) }
        assertEquals(PlaybackSession.MAX_INCIDENTS, s.incidents.size)
    }

    @Test
    fun `fotogramas perdidos y cortes de audio se acumulan`() {
        var s = PlaybackSession().on(SessionEvent.Dropped(7, 1)).on(SessionEvent.Dropped(5, 2)).on(SessionEvent.Underrun(3))
        assertEquals(12, s.droppedFrames)
        assertEquals(1, s.underruns)
    }
}
