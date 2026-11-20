package app.kaizen.commons

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * El orden de las comprobaciones de [UpdateFlow.run]: nada llega a `install` si falla una, y cada fallo borra el fichero y dice el motivo. Con un [UpdateFlow.Env] falso y un
 * servidor local que hace de GitHub (el cliente reescribe la dirección de github.com a él), sin Android.
 */
class UpdateFlowTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeState(
        override var mode: String = "notice", override var checkedAt: Long = 0L, override var attemptedAt: Long = 0L,
        override var latestTag: String? = "v6.3.0", override var notifiedTag: String? = null, override var skippedTag: String? = null,
    ) : UpdateChecker.State

    private lateinit var server: HttpServer
    private val downloads = AtomicInteger()
    private val installs = AtomicInteger()
    private val fetches = AtomicInteger()
    private var permission = true
    private val good = ByteArray(300_000) { (it % 241).toByte() }
    private var served = good
    private var digest = sha(good)
    private var size = good.size.toLong()
    private var asset = true

    private val installed = UpdateInstaller.PackageFacts("app.kaizen", 245L, setOf("cert-a"))
    private var archive: UpdateInstaller.PackageFacts? = UpdateInstaller.PackageFacts("app.kaizen", 246L, setOf("cert-a"))

    private lateinit var env: UpdateFlow.Env

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun releaseJson(): String {
        val files = if (asset) """[{"name":"app-tv.apk","size":$size,"state":"uploaded","digest":"sha256:$digest","browser_download_url":"https://github.com/D4rumanDev/kaizen/releases/download/v6.3.0/app-tv.apk"}]""" else "[]"
        return """{"tag_name":"v6.3.0","draft":false,"prerelease":false,"assets":$files}"""
    }

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/") { exchange ->
            downloads.incrementAndGet()
            exchange.sendResponseHeaders(200, served.size.toLong())
            exchange.responseBody.use { it.write(served) }
        }
        server.start()
        val local = "http://127.0.0.1:${server.address.port}"
        val http = OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(local + chain.request().url.encodedPath).build()) }.build()
        val folder = File(tmp.root, "updates")
        env = object : UpdateFlow.Env {
            override val client = http
            override val folder = folder
            override fun canInstall() = permission
            override fun installedFacts() = installed
            override fun archiveFacts(apk: File) = archive
            override fun install(apk: File) { assertTrue("se instala el fichero verificado", apk.isFile); installs.incrementAndGet() }
        }
        UpdateChecker.state = FakeState()
        UpdateChecker.currentVersion = "6.2.2"
        UpdateChecker.fetcher = { fetches.incrementAndGet(); releaseJson() }
        UpdateFlow.reset()
    }

    @After
    fun tearDown() { server.stop(0) }

    private fun run() { runBlocking { UpdateFlow.run(env) } }
    private fun failedWith(part: String) {
        val ui = UpdateFlow.ui.value
        assertTrue("$ui", ui is UpdateFlow.Ui.Failed && ui.message.contains(part, ignoreCase = true))
        assertEquals("un fallo no instala", 0, installs.get())
        assertFalse("un fallo no deja el fichero", File(env.folder, "app-tv.apk").exists())
    }

    @Test
    fun elCaminoBuenoPasaTodasLasComprobacionesYInstala() {
        run()
        assertEquals(UpdateFlow.Ui.Installing, UpdateFlow.ui.value)
        assertEquals(1, installs.get())
        assertEquals(1, fetches.get())
        assertEquals(1, downloads.get())
    }

    @Test
    fun sinPermisoNoSeMiraNiSeBajaNada() {
        permission = false
        run()
        assertEquals(UpdateFlow.Ui.NeedsPermission(canOpenSettings = true), UpdateFlow.ui.value)
        assertEquals(0, fetches.get())
        assertEquals(0, downloads.get())
        assertEquals(0, installs.get())
    }

    @Test
    fun sinVersionNuevaGuardadaNoHaceNada() {
        UpdateChecker.state = FakeState(latestTag = null)
        run()
        failedWith("ya no hay")
        assertEquals(0, fetches.get())
        UpdateChecker.state = FakeState(latestTag = "v6.2.2")
        run()
        failedWith("ya no hay")
        assertEquals(0, fetches.get())
    }

    @Test
    fun unaReleaseSinHuellaOSinAssetNoBajaNada() {
        digest = ""
        run()
        failedWith("huella")
        assertEquals(0, downloads.get())
        asset = false
        run()
        failedWith("app-tv.apk")
        assertEquals(0, downloads.get())
    }

    @Test
    fun siGitHubNoRespondeNoSeInstala() {
        UpdateChecker.fetcher = { throw IOException("sin red") }
        run()
        failedWith("GitHub")
    }

    @Test
    fun unaHuellaQueNoCoincideConLoBajadoNoSeInstala() {
        digest = sha("otro contenido".toByteArray())
        run()
        failedWith("huella")
        assertEquals(1, downloads.get())
    }

    @Test
    fun unTamanoDistintoDelAnunciadoNoSeInstala() {
        size = good.size.toLong() + 1
        var failure: Throwable? = null
        try { run() } catch (e: IOException) { failure = e }
        assertTrue("el tamaño distinto es un fallo", failure is IOException)
        assertEquals(0, installs.get())
        assertFalse(File(env.folder, "app-tv.apk").exists())
    }

    @Test
    fun unPaqueteIlegibleDeOtraFirmaOMasViejoNoSeInstala() {
        listOf(
            "leer" to null,
            "firma" to UpdateInstaller.PackageFacts("app.kaizen", 246L, setOf("cert-b")),
            "no es más nuevo" to UpdateInstaller.PackageFacts("app.kaizen", 245L, setOf("cert-a")),
            "no es Kaizen" to UpdateInstaller.PackageFacts("app.otra", 246L, setOf("cert-a")),
        ).forEach { (part, facts) ->
            archive = facts
            run()
            failedWith(part)
        }
    }

    @Test
    fun sinFirmaLegibleEnLaAppInstaladaNoSeInstala() {
        env = object : UpdateFlow.Env by env {
            override fun installedFacts() = installed.copy(signatures = emptySet())
        }
        run()
        failedWith("firma")
    }
}
