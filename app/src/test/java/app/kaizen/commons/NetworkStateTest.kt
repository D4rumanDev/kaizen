package app.kaizen.commons

import app.kaizen.commons.NetworkState.Mode
import app.kaizen.commons.NetworkState.Platform
import app.kaizen.domain.ConnectionStats.Cause
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ConnectException
import java.net.UnknownHostException

/** «Sin Internet»: la decisión, la prueba con varias webs, la limpieza de pausas y que las peticiones no salgan sin conexión. */
class NetworkStateTest {
    private var manual = false
    private var platform = Platform.VALIDATED
    private var recovered = 0
    private var now = 1_000_000L

    @Before
    fun setUp() {
        NetworkState.reset()
        NetworkState.clock = { now }
        NetworkState.manual = { manual }
        NetworkState.platform = { platform }
        NetworkState.onRecovered = { recovered++ }
        HostGate.reset()
    }

    @After
    fun tearDown() {
        NetworkState.reset()
        NetworkState.clock = System::currentTimeMillis
        NetworkState.manual = { false }
        NetworkState.platform = { Platform.VALIDATED }
        NetworkState.onRecovered = { HostGate.reset() }
        HostGate.reset()
    }

    @Test
    fun `el interruptor manda y sin ninguna red es seguro`() {
        assertEquals(Mode.OFFLINE_MANUAL, NetworkState.decide(true, Platform.VALIDATED, 0))
        assertEquals(Mode.NO_INTERNET, NetworkState.decide(false, Platform.NONE, 0))
        assertEquals(Mode.ONLINE, NetworkState.decide(false, Platform.VALIDATED, 5))
    }

    @Test
    fun `una red sin validar necesita fallos de varias webs distintas`() {
        assertEquals(Mode.ONLINE, NetworkState.decide(false, Platform.UNVALIDATED, 0))
        assertEquals(Mode.ONLINE, NetworkState.decide(false, Platform.UNVALIDATED, NetworkState.HOSTS_TO_DECLARE - 1))
        assertEquals(Mode.NO_INTERNET, NetworkState.decide(false, Platform.UNVALIDATED, NetworkState.HOSTS_TO_DECLARE))
    }

    @Test
    fun `dos fallos de la misma web no declaran la caida pero dos webs distintas si`() {
        platform = Platform.UNVALIDATED
        repeat(3) { NetworkState.noteFailure("a.example", ConnectException()) }
        assertEquals(Mode.ONLINE, NetworkState.mode.value)
        NetworkState.noteFailure("b.example", UnknownHostException("b.example"))
        assertEquals(Mode.NO_INTERNET, NetworkState.mode.value)
        // Al entrar se limpian las pausas que abrieron los primeros fallos.
        assertEquals(1, recovered)
    }

    @Test
    fun `un acierto borra las pruebas y una web viva con error HTTP no cuenta como fallo de red`() {
        platform = Platform.UNVALIDATED
        NetworkState.noteFailure("a.example", ConnectException())
        NetworkState.noteSuccess("c.example")
        NetworkState.noteFailure("b.example", ConnectException())
        assertEquals(Mode.ONLINE, NetworkState.mode.value)
        NetworkState.noteFailure("d.example", java.io.IOException("500"))
        assertEquals(Mode.ONLINE, NetworkState.mode.value)
    }

    @Test
    fun `las pruebas caducan con la ventana`() {
        platform = Platform.UNVALIDATED
        NetworkState.noteFailure("a.example", ConnectException())
        now += NetworkState.EVIDENCE_WINDOW.inWholeMilliseconds + 1
        NetworkState.noteFailure("b.example", ConnectException())
        assertEquals(Mode.ONLINE, NetworkState.mode.value)
    }

    @Test
    fun `al volver la red sale del modo y limpia las pausas`() {
        platform = Platform.NONE
        NetworkState.recompute()
        assertEquals(Mode.NO_INTERNET, NetworkState.mode.value)
        platform = Platform.VALIDATED
        NetworkState.recompute()
        assertEquals(Mode.ONLINE, NetworkState.mode.value)
        assertEquals(2, recovered)
    }

    @Test
    fun `sin conexion no se pide nada, no se abre ninguna pausa y se ignoran las guardadas`() {
        HostGate.isNetworkUp = { true }
        manual = true
        NetworkState.recompute()
        assertTrue(NetworkState.isOffline)
        var called = false
        val failed = runCatching { HostGate.call("https://x.example/a") { called = true } }.exceptionOrNull()
        assertTrue(failed is HostGate.OfflineException)
        // Es un HostDownException: todo lo que ya trata una web en pausa lo trata igual.
        assertTrue(failed is HostGate.HostDownException)
        assertFalse(called)
        assertTrue(runCatching { HostGate.allow("https://x.example/a") }.exceptionOrNull() is HostGate.OfflineException)
        assertFalse(HostGate.isDown("x.example"))
        assertEquals(0L, HostGate.downUntil("x.example"))
        // Ni comprobar ni medir salen a la red.
        assertEquals("sin conexión", HostGate.check("https://x.example/").error)
        assertEquals("sin conexión", HostGate.probe("https://x.example/").error)
        manual = false
        NetworkState.recompute()
    }

    @Test
    fun `una portada guardada caducada vale sin conexion`() {
        val old = NetworkState.clock() - CacheFirstStrategy.MAX_AGE.inWholeMilliseconds * 2
        CacheFirstStrategy.clock = { NetworkState.clock() }
        try {
            CacheFirstStrategy.offline = { false }
            assertFalse(CacheFirstStrategy.isReusable(imageResponse(old)))
            CacheFirstStrategy.offline = { true }
            assertTrue(CacheFirstStrategy.isReusable(imageResponse(old)))
        } finally {
            CacheFirstStrategy.offline = { NetworkState.isOffline }
            CacheFirstStrategy.clock = System::currentTimeMillis
        }
    }

    @Test
    fun `una pausa de una web anota E26 y una del bypass anota E27`() {
        AppErrors.clear()
        HostGate.isNetworkUp = { true }
        val previousOnDown = HostGate.onDown
        HostGate.onDown = {}
        try {
            // Un fallo de conexión pesa doble: basta uno para pausar.
            HostGate.reportFailure("https://pausa.example/x", ConnectException("rechazada"))
            assertTrue(AppErrors.recent().any { it.incident.cause == Cause.PAUSED && it.context == "pausa.example" })
        } finally {
            HostGate.onDown = previousOnDown
        }
        val previousStorage = app.kaizen.bypass.BypassStatus.storage
        val previousPause = app.kaizen.bypass.BypassStatus.onPause
        app.kaizen.bypass.BypassStatus.storage = null
        app.kaizen.bypass.BypassStatus.onPause = {}
        try {
            app.kaizen.bypass.BypassStatus.reset()
            repeat(2) { app.kaizen.bypass.BypassStatus.start(); app.kaizen.bypass.BypassStatus.finish(app.kaizen.bypass.BypassStatus.State.FAILED) }
            assertTrue(AppErrors.recent().any { it.incident.cause == Cause.CLOUDFLARE })
        } finally {
            app.kaizen.bypass.BypassStatus.reset()
            app.kaizen.bypass.BypassStatus.storage = previousStorage
            app.kaizen.bypass.BypassStatus.onPause = previousPause
        }
    }

    @Test
    fun `al publicar una fila solo se bajan las imagenes https que faltan y sin repetir`() {
        val urls = listOf("https://cdn.example/a.jpg", "https://cdn.example/b.jpg", "https://cdn.example/a.jpg", "http://cdn.example/c.jpg", "")
        assertEquals(listOf("https://cdn.example/b.jpg"), app.kaizen.tv.ChannelArt.missing(urls) { it.endsWith("a.jpg") })
        assertTrue(app.kaizen.tv.ChannelArt.missing(emptyList()) { false }.isEmpty())
    }

    @Test
    fun `solo se sirven al launcher las imagenes publicadas y por https`() {
        val published = setOf("https://cdn.example/a.jpg")
        assertTrue(app.kaizen.tv.ChannelArt.isServable("https://cdn.example/a.jpg", published))
        assertFalse(app.kaizen.tv.ChannelArt.isServable("https://cdn.example/otra.jpg", published))
        assertFalse(app.kaizen.tv.ChannelArt.isServable("http://cdn.example/a.jpg", setOf("http://cdn.example/a.jpg")))
        assertFalse(app.kaizen.tv.ChannelArt.isServable("file:///data/data/app.kaizen/databases/x", setOf("file:///data/data/app.kaizen/databases/x")))
    }

    @Test
    fun `el catalogo de errores trae los de sin conexion con numeros y codigos unicos`() {
        assertEquals(24, Cause.NO_INTERNET.number)
        assertEquals(25, Cause.OFFLINE.number)
        assertEquals(26, Cause.PAUSED.number)
        assertEquals(27, Cause.CLOUDFLARE.number)
        assertEquals(28, Cause.HOME_CHANNEL.number)
        assertEquals(Cause.entries.size, Cause.entries.map { it.number }.toSet().size)
        assertEquals(Cause.entries.size, Cause.entries.map { it.code }.toSet().size)
        assertEquals("E24", Cause.NO_INTERNET.tag)
    }

    private fun imageResponse(at: Long) = coil3.network.NetworkResponse(
        code = 200, requestMillis = at, responseMillis = at,
        headers = coil3.network.NetworkHeaders.Builder().add("Content-Type", "image/jpeg").build(), body = null
    )
}
