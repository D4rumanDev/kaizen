package app.kaizen.commons

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

private fun addr(vararg bytes: Int): InetAddress = InetAddress.getByAddress(ByteArray(bytes.size) { bytes[it].toByte() })
private fun v6(text: String): InetAddress = InetAddress.getByName(text)

/** `::ffff:a.b.c.d` como `Inet6Address` (con `getByAddress(ByteArray)` Java devolvería un `Inet4Address` y no se probaría la rama de las mapeadas). */
private fun mapped(a: Int, b: Int, c: Int, d: Int): InetAddress = java.net.Inet6Address.getByAddress(
    null,
    ByteArray(16).also { it[10] = 0xFF.toByte(); it[11] = 0xFF.toByte(); it[12] = a.toByte(); it[13] = b.toByte(); it[14] = c.toByte(); it[15] = d.toByte() },
    0,
)

/** Qué direcciones son «privadas» para el proxy: ninguna petición del proxy debe llegar a la red de casa ni a la propia tele. */
class PrivateAddressTest {
    @Test
    fun `las direcciones de la red local, loopback y enlace local se bloquean`() {
        listOf(
            addr(127, 0, 0, 1), addr(127, 5, 5, 5), addr(10, 0, 0, 1), addr(172, 16, 0, 1), addr(172, 31, 255, 255), addr(192, 168, 1, 1),
            addr(169, 254, 1, 1), addr(100, 64, 0, 1), addr(100, 127, 255, 255), addr(0, 0, 0, 0), addr(0, 1, 2, 3), addr(198, 18, 0, 1), addr(198, 19, 255, 255),
            addr(224, 0, 0, 1), addr(255, 255, 255, 255),
            v6("::1"), v6("fe80::1"), v6("fc00::1"), v6("fd12:3456::1"),
            // IPv4 mapeada en IPv6 (un Inet6Address de verdad: InetAddress.getByAddress la convertiría en IPv4): la dirección de casa con otra forma.
            mapped(192, 168, 0, 1), mapped(127, 0, 0, 1), mapped(10, 1, 2, 3),
        ).forEach { assertTrue("debe bloquearse: ${it.hostAddress}", PrivateAddress.isPrivate(it)) }
    }

    @Test
    fun `control bueno - las direcciones publicas y las vecinas de los rangos privados no se bloquean`() {
        listOf(
            addr(8, 8, 8, 8), addr(1, 1, 1, 1), addr(11, 0, 0, 1), addr(172, 15, 255, 255), addr(172, 32, 0, 1), addr(192, 167, 1, 1),
            addr(169, 253, 1, 1), addr(100, 63, 255, 255), addr(100, 128, 0, 1), addr(198, 17, 255, 255), addr(198, 20, 0, 1), addr(1, 0, 0, 1), mapped(8, 8, 8, 8),
            v6("2606:4700:4700::1111"), v6("2001:4860:4860::8888"),
        ).forEach { assertFalse("no debe bloquearse: ${it.hostAddress}", PrivateAddress.isPrivate(it)) }
    }
}

/** El socket del proxy: se mira el destino en el momento de conectar, también tras una redirección y con IP literal (donde OkHttp no consulta el DNS). */
class GuardedSocketFactoryTest {
    private val closeables = mutableListOf<AutoCloseable>()
    private val servers = mutableListOf<HttpServer>()

    @After
    fun cleanUp() {
        closeables.forEach { runCatching { it.close() } }
        servers.forEach { runCatching { it.stop(0) } }
    }

    private fun loopback() = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    private fun httpServer(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress(loopback(), 0), 0).apply {
            createContext("/") { exchange -> handler(exchange) }
            start()
            servers += this
        }

    @Test
    fun `por defecto el socket no conecta con el propio equipo`() {
        val listener = ServerSocket(0, 1, loopback()).also { closeables += it }
        val socket = GuardedSocketFactory().createSocket().also { closeables += it }
        assertThrows(PrivateDestinationException::class.java) { socket.connect(InetSocketAddress(loopback(), listener.localPort), 1000) }
    }

    @Test
    fun `control bueno - con el filtro apagado el mismo socket conecta`() {
        val listener = ServerSocket(0, 1, loopback()).also { closeables += it }
        val socket = GuardedSocketFactory { false }.createSocket().also { closeables += it }
        socket.connect(InetSocketAddress(loopback(), listener.localPort), 1000)
        assertTrue(socket.isConnected)
    }

    @Test
    fun `una redireccion a un destino bloqueado se corta y sin bloqueo se sigue`() {
        val target = httpServer { ex -> val body = "ok".toByteArray(); ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) } }
        val targetPort = target.address.port
        val origin = httpServer { ex -> ex.responseHeaders.add("Location", "http://127.0.0.1:$targetPort/"); ex.sendResponseHeaders(302, -1); ex.close() }
        fun fetch(factory: GuardedSocketFactory): Int = OkHttpClient.Builder().socketFactory(factory).build()
            .newCall(Request.Builder().url("http://127.0.0.1:${origin.address.port}/").build()).execute().use { it.code }

        // Solo se bloquea el puerto del destino de la redirección: el primer salto (el origen) sí se permite.
        val error = assertThrows(IOException::class.java) { fetch(GuardedSocketFactory { it.port == targetPort }) }
        assertTrue("el error debe decir que el destino es privado: $error", error.message.orEmpty().contains("privado"))
        assertEquals(200, fetch(GuardedSocketFactory { false }))
    }
}
