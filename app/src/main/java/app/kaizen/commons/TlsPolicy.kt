package app.kaizen.commons

import android.util.Log
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * Kaizen: validación TLS normal por defecto. Si un servidor de vídeo tiene un certificado
 * roto, la conexión falla y el host queda anotado; el modo permisivo (herramienta de la
 * pantalla principal, hasta cerrar la app) acepta cualquier certificado.
 */
object TlsPolicy {
    private const val TAG = "KaizenTLS"

    /**
     * Solo en memoria: el modo permisivo dura hasta que se cierra la app, para que no quede
     * activado sin querer.
     */
    @Volatile
    var isPermissive: Boolean = false

    /** Hosts cuyo certificado no ha pasado la validación en esta sesión. */
    val rejectedHosts: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    internal val systemTrustManager: X509TrustManager by lazy {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    // Se captura al cargar el objeto, antes de que install() lo sustituya (evita recursión).
    private val defaultHostnameVerifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()

    val trustManager: X509TrustManager = object : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            systemTrustManager.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
            checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
            checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
            verifyServer(chain, null) { systemTrustManager.checkServerTrusted(chain, authType) }

        // El TrustManager de Android exige las variantes con host (network security config).
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
            verifyServer(chain, socket?.inetAddress?.hostName) {
                (systemTrustManager as? X509ExtendedTrustManager)?.checkServerTrusted(chain, authType, socket)
                    ?: systemTrustManager.checkServerTrusted(chain, authType)
            }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
            verifyServer(chain, engine?.peerHost) {
                (systemTrustManager as? X509ExtendedTrustManager)?.checkServerTrusted(chain, authType, engine)
                    ?: systemTrustManager.checkServerTrusted(chain, authType)
            }

        override fun getAcceptedIssuers(): Array<X509Certificate> = systemTrustManager.acceptedIssuers
    }

    val hostnameVerifier = HostnameVerifier { host, session ->
        defaultHostnameVerifier.verify(host, session) || isPermissive.also { allowed ->
            if (!allowed) reject(host, "nombre de host no coincide")
        }
    }

    val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), SecureRandom()) }
    }

    @Volatile
    private var installed = false

    /** Aplica la política a HttpsURLConnection (jsoup, ExoPlayer y demás clientes por defecto). */
    @Synchronized
    fun install() {
        if (installed) return
        installed = true
        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
        HttpsURLConnection.setDefaultHostnameVerifier(hostnameVerifier)
    }

    private inline fun verifyServer(chain: Array<X509Certificate>, host: String?, systemCheck: () -> Unit) {
        try {
            systemCheck()
        } catch (e: CertificateException) {
            if (isPermissive) return
            reject(host ?: chain.firstOrNull()?.subjectX500Principal?.name ?: "?", e.message ?: "certificado no válido")
            throw e
        }
    }

    private fun reject(host: String, reason: String) {
        rejectedHosts.add(host)
        Log.w(TAG, "TLS rechazado para $host: $reason")
    }
}
