package app.kaizen.tv.exoplayer

import androidx.media3.common.PlaybackException
import app.kaizen.commons.HostGate
import app.kaizen.domain.ConnectionStats.Cause
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

/** Un servidor en pausa o sin conexión no es «E99 Otro»: tiene su propia causa en el reproductor. */
class PlaybackFailureCauseTest {
    @Test fun `un servidor en pausa es E26`() {
        val down = HostGate.HostDownException("cdn6.example.com")
        assertEquals(Cause.PAUSED, causeOfFailure(PlaybackException.ERROR_CODE_UNSPECIFIED, down))
        assertEquals(Cause.PAUSED, causeOfFailure(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, IOException("no respondió", down)))
    }

    @Test fun `sin conexion es sin red y no pausa`() {
        assertEquals(Cause.NO_NETWORK, causeOfFailure(PlaybackException.ERROR_CODE_UNSPECIFIED, HostGate.OfflineException("cdn6.example.com")))
    }

    @Test fun `lo demas sigue como antes`() {
        assertEquals(Cause.TIMEOUT, causeOfFailure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, null))
        assertEquals(Cause.TIMEOUT, causeOfFailure(PlaybackException.ERROR_CODE_UNSPECIFIED, java.net.SocketTimeoutException("t")))
    }
}
