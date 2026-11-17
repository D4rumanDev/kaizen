package app.kaizen.commons

import app.kaizen.commons.AppErrors.Area
import app.kaizen.domain.ConnectionStats
import app.kaizen.domain.ConnectionStats.Cause
import app.kaizen.domain.ConnectionStats.Kind
import coil3.annotation.ExperimentalCoilApi
import coil3.network.HttpException
import coil3.network.NetworkHeaders
import coil3.network.NetworkResponse
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** El gestor único de errores: una cadena de reglas para todo (red, TV, carátulas, caché…), con la red caída aparte. */
@OptIn(ExperimentalCoilApi::class)
class AppErrorsTest {
    private var now = 1_000_000L
    private var hasNetwork = true
    private val originalStorage = ConnectionStats.storage

    @Before
    fun setUp() {
        AppErrors.clear()
        AppErrors.clock = { now }
        AppErrors.online = { hasNetwork }
    }

    @After
    fun tearDown() {
        AppErrors.clear()
        AppErrors.clock = System::currentTimeMillis
        ConnectionStats.storage = originalStorage
        ConnectionStats.zone = { java.util.TimeZone.getDefault() }
        ConnectionStats.clock = System::currentTimeMillis
    }

    @Test
    fun `con red un nombre sin resolver es DNS y sin red es la red caida`() {
        assertEquals(Cause.DNS, AppErrors.classify(UnknownHostException("x"), Area.APP))
        hasNetwork = false
        assertEquals(Cause.NO_NETWORK, AppErrors.classify(UnknownHostException("x"), Area.APP))
        assertEquals(Cause.NO_NETWORK, AppErrors.classify(SocketTimeoutException(), Area.SOURCE))
    }

    @Test
    fun `una cancelacion no es un fallo`() {
        assertNull(AppErrors.classify(CancellationException("x")))
    }

    @Test
    fun `lo de la TV se reconoce antes que lo de la red`() {
        assertEquals(Cause.LOW_MEMORY, AppErrors.classify(RuntimeException("x", OutOfMemoryError())))
        assertEquals(Cause.STORAGE, AppErrors.classify(IOException("write failed: ENOSPC (No space left on device)")))
    }

    @Test
    fun `una portada que falla se clasifica por su causa y si no hay, como imagen`() {
        assertEquals(Cause.NOT_FOUND, AppErrors.classify(HttpException(NetworkResponse(code = 404, headers = NetworkHeaders.EMPTY)), Area.IMAGE))
        assertEquals(Cause.BLOCKED, AppErrors.classify(HttpException(NetworkResponse(code = 403, headers = NetworkHeaders.EMPTY)), Area.IMAGE))
        assertEquals(Cause.IMAGE, AppErrors.classify(IOException("bitmap corrupto"), Area.IMAGE))
        assertEquals(Cause.CACHE, AppErrors.classify(IOException("journal"), Area.CACHE))
    }

    @Test
    fun `un error de programacion es de la app salvo en una fuente, donde es del extractor`() {
        assertEquals(Cause.INTERNAL, AppErrors.classify(NullPointerException(), Area.APP))
        assertEquals(Cause.EXTRACTOR, AppErrors.classify(NullPointerException(), Area.SOURCE))
        assertEquals(Cause.OTHER, AppErrors.classify(RuntimeException("x"), Area.APP))
    }

    @Test
    fun `los fallos de la sesion se agrupan por causa sin guardar contadores`() {
        AppErrors.report(UnknownHostException("a"), Area.SOURCE, "voe")
        AppErrors.report(IOException("bitmap"), Area.IMAGE, "cdn")
        AppErrors.report(UnknownHostException("b"), Area.SOURCE, "mega")
        assertEquals(3, AppErrors.recent().size)
        val summary = AppErrors.summary()
        assertEquals(listOf(Cause.DNS, Cause.IMAGE), summary.map { it.cause })
        assertEquals(2, summary[0].count)
        assertTrue(AppErrors.sessionLine().startsWith("Esta sesión: 2× E01"))
    }

    @Test
    fun `el detalle del registro no lleva direcciones completas, solo el servidor`() {
        assertEquals("HTTP error fetching URL. Status=403, URL=[https://cdn.x.com]", AppErrors.redact("HTTP error fetching URL. Status=403, URL=[https://cdn.x.com/v/a.m3u8?token=SECRETO&u=1]"))
        assertEquals("sin direcciones", AppErrors.redact("sin direcciones"))
        // Ni el usuario ni la contraseña de una dirección con credenciales.
        assertEquals("falló https://cdn.x.com", AppErrors.redact("falló https://usuario:clave@cdn.x.com/a?b=1"))
        AppErrors.report(org.jsoup.HttpStatusException("HTTP error fetching URL", 403, "https://voe.sx/e/abc?key=SECRETO"), Area.SOURCE, "voe")
        val detail = AppErrors.recent().single().incident.detail
        assertTrue(detail, !detail.contains("SECRETO") && detail.contains("https://voe.sx"))
    }

    @Test
    fun `solo los avisos de memoria de verdad cuentan, no los de pulsar Inicio`() {
        // 5 y 10 y 15 son con la app en primer plano (solo 10 y 15 son falta de memoria), 20/40/60 saltan al pulsar Inicio y 80 es el cierre inminente.
        assertTrue(AppErrors.isTrimPressure(10))
        assertTrue(AppErrors.isTrimPressure(15))
        assertTrue(AppErrors.isTrimPressure(80))
        listOf(0, 5, 20, 40, 60).forEach { assertFalse("nivel $it", AppErrors.isTrimPressure(it)) }
    }

    @Test
    fun `una cancelacion no se anota`() {
        AppErrors.report(CancellationException("x"))
        assertTrue(AppErrors.recent().isEmpty())
        assertEquals("Esta sesión: sin errores", AppErrors.sessionLine())
    }

    @Test
    fun `la causa de conexion para las estadisticas respeta la red caida y la cancelacion`() {
        assertNull(AppErrors.connectionCause(null))
        assertEquals(Cause.DNS, AppErrors.connectionCause(UnknownHostException("x")))
        assertEquals(Cause.OTHER, AppErrors.connectionCause(CancellationException("x")))
        hasNetwork = false
        assertEquals(Cause.NO_NETWORK, AppErrors.connectionCause(UnknownHostException("x")))
    }

    @Test
    fun `la leyenda de Conexion solo lleva causas de conexion y causeOf nunca devuelve otra`() {
        val playbackOnly = listOf(Cause.BAD_FILE, Cause.DECODER, Cause.AUDIO_OUTPUT, Cause.SLOW, Cause.DROPPED, Cause.LOW_MEMORY, Cause.IMAGE, Cause.CACHE, Cause.STORAGE, Cause.DATABASE, Cause.INTERNAL, Cause.NO_NETWORK)
        assertTrue(ConnectionStats.CONNECTION_CAUSES.none { it in playbackOnly })
        // Cada excepción de conexión, con su causa exacta (no solo «alguna de la lista»: OTHER también está en ella y todo lo desconocido caería ahí).
        val expected = listOf(
            UnknownHostException("x") to Cause.DNS, SocketTimeoutException() to Cause.TIMEOUT, java.net.ConnectException() to Cause.CONNECTION,
            javax.net.ssl.SSLException("x") to Cause.TLS, org.jsoup.HttpStatusException("x", 403, "u") to Cause.BLOCKED,
            org.jsoup.HttpStatusException("x", 404, "u") to Cause.NOT_FOUND, org.jsoup.HttpStatusException("x", 429, "u") to Cause.REJECTED,
            org.jsoup.HttpStatusException("x", 503, "u") to Cause.SERVER_ERROR, IllegalStateException("x") to Cause.EXTRACTOR,
            IOException("x") to Cause.OTHER, CancellationException("x") to Cause.OTHER
        )
        expected.forEach { (error, cause) ->
            assertEquals(error::class.simpleName, cause, ConnectionStats.causeOf(error))
            assertTrue(cause in ConnectionStats.CONNECTION_CAUSES)
        }
    }

    @Test
    fun `sin red el fallo no se carga al servidor en las estadisticas`() {
        val data = linkedMapOf<String, String>()
        ConnectionStats.storage = object : ConnectionStats.Storage {
            override fun all(): Map<String, String> = data.toMap()
            override fun put(key: String, value: String) { data[key] = value }
            override fun remove(keys: Collection<String>) { keys.forEach { data.remove(it) } }
        }
        ConnectionStats.zone = { java.util.TimeZone.getTimeZone("UTC") }
        ConnectionStats.clock = { now }
        ConnectionStats.record(Kind.SERVER, "voe", false, Cause.NO_NETWORK)
        assertTrue(data.isEmpty())
        ConnectionStats.record(Kind.SERVER, "voe", false, Cause.DNS)
        assertTrue(data.isNotEmpty())
    }
}
