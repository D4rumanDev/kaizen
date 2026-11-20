package app.kaizen.commons

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.net.ssl.SSLException

/**
 * El cliente estricto (AniList, AniSkip) no pasa por [TlsPolicy]: aunque el usuario ponga la validación en «permisiva» para arreglar un servidor de vídeo,
 * un certificado no fiable sigue rechazado. Se prueba con un handshake TLS de verdad contra un servidor local de certificado autofirmado. Se conecta por la IP
 * literal: con «localhost» OkHttp prueba primero ::1 y devolvería esa ConnectException en lugar del fallo TLS.
 */
class StrictClientTest {
    private lateinit var server: HttpsServer
    private var wasPermissive = false

    @Before
    fun start() {
        wasPermissive = TlsPolicy.isPermissive
        val held = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(held).build()
        server = HttpsServer.create(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(serverCerts.sslContext())
            createContext("/") { exchange ->
                val body = "ok".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
    }

    @After
    fun stop() {
        server.stop(0)
        TlsPolicy.isPermissive = wasPermissive
    }

    private fun code(client: OkHttpClient): Int =
        client.newCall(Request.Builder().url("https://127.0.0.1:${server.address.port}/").build()).execute().use { it.code }

    /** El cliente de las fuentes: pasa por la política TLS de la app. */
    private fun permissiveClient() = OkHttpClient.Builder()
        .sslSocketFactory(TlsPolicy.sslContext.socketFactory, TlsPolicy.trustManager)
        .hostnameVerifier(TlsPolicy.hostnameVerifier)
        .build()

    @Test
    fun `control bueno - con la validacion permisiva el cliente de las fuentes si acepta el certificado no fiable`() {
        TlsPolicy.isPermissive = true
        assertEquals(200, code(permissiveClient()))
    }

    @Test
    fun `el cliente estricto rechaza el certificado no fiable aunque la validacion este en permisiva`() {
        TlsPolicy.isPermissive = true
        val strict = AppHttpClient.strictClient(OkHttpClient(), seconds = 5)
        assertThrows(SSLException::class.java) { code(strict) }
    }

    @Test
    fun `el cliente estricto rechaza el certificado no fiable tambien con la validacion normal`() {
        TlsPolicy.isPermissive = false
        val strict = AppHttpClient.strictClient(OkHttpClient(), seconds = 5)
        assertThrows(SSLException::class.java) { code(strict) }
    }

    @Test
    fun `el cliente estricto comparte dispatcher y pool para que shutdown() tambien lo corte`() {
        val base = OkHttpClient()
        val strict = AppHttpClient.strictClient(base, seconds = 5)
        assertSame(base.dispatcher, strict.dispatcher)
        assertSame(base.connectionPool, strict.connectionPool)
    }
}
