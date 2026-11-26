package app.kaizen.commons

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Las comprobaciones antes de instalar: elegir el asset, comparar el paquete y la firma, y bajar el fichero con tope. Sin red real ni Android. */
class UpdateInstallerTest {
    @get:Rule val tmp = TemporaryFolder()
    private var server: HttpServer? = null

    @After
    fun tearDown() { server?.stop(0) }

    private val hash = "a".repeat(64)
    private fun asset(
        name: String = "app-tv.apk", size: Long = 31_462_667L, digest: String? = "sha256:$hash", state: String = "uploaded",
        url: String = "https://github.com/D4rumanDev/kaizen/releases/download/v6.3.0/app-tv.apk",
    ): String = buildString {
        append("""{"name":"$name","size":$size,"state":"$state","browser_download_url":"$url"""")
        if (digest != null) append(""","digest":"$digest"""")
        append("}")
    }

    private fun release(vararg assets: String, tag: String = "v6.3.0", draft: Boolean = false, prerelease: Boolean = false) =
        """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"assets":[${assets.joinToString(",")}]}"""

    private fun pick(json: String, tag: String = "v6.3.0") = UpdateInstaller.pickAsset(json, tag)

    // ---- 1. el asset
    @Test
    fun `describe da paquete version y firmas abreviadas y distingue lo ilegible`() {
        assertEquals("app.kaizen v254, firmas: e9a4e8d5", UpdateInstaller.describe(UpdateInstaller.PackageFacts("app.kaizen", 254, setOf("e9a4e8d5f688fc18"))))
        assertEquals("app.kaizen v1, firmas: ninguna", UpdateInstaller.describe(UpdateInstaller.PackageFacts("app.kaizen", 1, emptySet())))
        assertEquals("ilegible", UpdateInstaller.describe(null))
    }

    @Test
    fun eligeElAssetCorrectoConSuHuella() {
        val ok = pick(release(asset())) as UpdateInstaller.AssetResult.Ok
        assertEquals("v6.3.0", ok.asset.tag)
        assertEquals(31_462_667L, ok.asset.size)
        assertEquals(hash, ok.asset.sha256)
        assertEquals("https://github.com/D4rumanDev/kaizen/releases/download/v6.3.0/app-tv.apk", ok.asset.url)
    }

    @Test
    fun elAssetSeBuscaPorNombreExactoEntreVarios() {
        val json = release(asset(name = "app-tv.apk.sig", digest = "sha256:${"b".repeat(64)}"), asset(), asset(name = "source.zip"))
        assertEquals(hash, (pick(json) as UpdateInstaller.AssetResult.Ok).asset.sha256)
    }

    @Test
    fun rechazaTodoLoQueNoSeaExactamenteLoEsperado() {
        val refused = listOf(
            "sin asset" to release(),
            "otro nombre" to release(asset(name = "app-tv-debug.apk")),
            "sin huella" to release(asset(digest = null)),
            "huella con otro algoritmo" to release(asset(digest = "sha1:" + "a".repeat(40))),
            "huella corta" to release(asset(digest = "sha256:abc")),
            "huella no hexadecimal" to release(asset(digest = "sha256:" + "g".repeat(64))),
            "tamaño cero" to release(asset(size = 0)),
            "demasiado grande" to release(asset(size = UpdateInstaller.MAX_BYTES + 1)),
            "no subido del todo" to release(asset(state = "starter")),
            "otro repositorio" to release(asset(url = "https://github.com/evil/kaizen/releases/download/v6.3.0/app-tv.apk")),
            "otro host" to release(asset(url = "https://evil.example/D4rumanDev/kaizen/releases/download/v6.3.0/app-tv.apk")),
            "http" to release(asset(url = "http://github.com/D4rumanDev/kaizen/releases/download/v6.3.0/app-tv.apk")),
            "otra etiqueta en la dirección" to release(asset(url = "https://github.com/D4rumanDev/kaizen/releases/download/v9.9.9/app-tv.apk")),
            "otro fichero en la dirección" to release(asset(url = "https://github.com/D4rumanDev/kaizen/releases/download/v6.3.0/otro.apk")),
            "borrador" to release(asset(), draft = true),
            "prerelease" to release(asset(), prerelease = true),
            "etiqueta distinta de la avisada" to release(asset(), tag = "v6.4.0"),
            "no es json" to "no es json",
        )
        refused.forEach { (why, json) -> assertTrue(why, pick(json) is UpdateInstaller.AssetResult.Refused) }
        assertEquals(18, refused.size)
    }

    @Test
    fun laHuellaEnMayusculasSeNormalizaAMinusculas() {
        val ok = pick(release(asset(digest = "sha256:${"AB".repeat(32)}"))) as UpdateInstaller.AssetResult.Ok
        assertEquals("ab".repeat(32), ok.asset.sha256)
    }

    @Test
    fun cadaRechazoDiceElMotivo() {
        val reason = (pick(release(asset(digest = null))) as UpdateInstaller.AssetResult.Refused).reason
        assertTrue(reason, reason.contains("huella"))
    }

    // ---- 4. el paquete descargado frente al instalado
    private val installed = UpdateInstaller.PackageFacts("app.kaizen", 245L, setOf("cert-a"))

    @Test
    fun aceptaElMismoPaqueteMasNuevoYConLaMismaFirma() {
        assertNull(UpdateInstaller.verifyPackage(installed, UpdateInstaller.PackageFacts("app.kaizen", 246L, setOf("cert-a"))))
    }

    @Test
    fun rechazaCualquierDiferenciaDePaqueteVersionOFirma() {
        listOf(
            "no se pudo leer" to null,
            "otro paquete" to UpdateInstaller.PackageFacts("app.otra", 246L, setOf("cert-a")),
            "misma versión" to UpdateInstaller.PackageFacts("app.kaizen", 245L, setOf("cert-a")),
            "versión menor" to UpdateInstaller.PackageFacts("app.kaizen", 244L, setOf("cert-a")),
            "otra firma" to UpdateInstaller.PackageFacts("app.kaizen", 246L, setOf("cert-b")),
            "una firma de más" to UpdateInstaller.PackageFacts("app.kaizen", 246L, setOf("cert-a", "cert-b")),
            "sin firma" to UpdateInstaller.PackageFacts("app.kaizen", 246L, emptySet()),
        ).forEach { (why, archive) -> assertNotNull(why, UpdateInstaller.verifyPackage(installed, archive)) }
        // La app instalada sin firma legible tampoco permite comparar: no se instala nada.
        assertNotNull(UpdateInstaller.verifyPackage(installed.copy(signatures = emptySet()), UpdateInstaller.PackageFacts("app.kaizen", 246L, emptySet())))
    }

    // ---- 2 y 3. descarga con tope y huella
    private fun serve(path: String = "/app-tv.apk", body: ByteArray, chunked: Boolean = false): String {
        val http = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        http.createContext(path) { exchange ->
            exchange.sendResponseHeaders(200, if (chunked) 0 else body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        http.createContext("/falla") { exchange -> exchange.sendResponseHeaders(404, -1); exchange.close() }
        http.start()
        server = http
        return "http://127.0.0.1:${http.address.port}"
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val client = OkHttpClient()

    private fun download(url: String, dest: File, expectedSize: Long, progress: (Long, Long) -> Unit = { _, _ -> }) =
        runBlocking { UpdateInstaller.download(client, url, dest, expectedSize, progress) }

    @Test
    fun bajaElFicheroYDaSuHuellaYElProgreso() {
        val body = ByteArray(200_000) { (it % 251).toByte() }
        val base = serve(body = body)
        val dest = File(tmp.root, "app-tv.apk")
        val seen = mutableListOf<Long>()
        val digest = download("$base/app-tv.apk", dest, body.size.toLong()) { done, _ -> seen += done }
        assertEquals(sha(body), digest)
        assertEquals(body.size.toLong(), dest.length())
        assertTrue(seen.isNotEmpty() && seen.last() == body.size.toLong())
        assertFalse("no debe quedar el fichero parcial", File(tmp.root, "app-tv.apk.part").exists())
    }

    @Test
    fun siElServidorMandaMasDeLoAnunciadoSeCortaYSeBorra() {
        val body = ByteArray(100_000) { 1 }
        val base = serve(body = body, chunked = true)
        val dest = File(tmp.root, "app-tv.apk")
        assertThrows(IOException::class.java) { download("$base/app-tv.apk", dest, expectedSize = 50_000L) }
        assertFalse(dest.exists())
        assertFalse(File(tmp.root, "app-tv.apk.part").exists())
    }

    @Test
    fun siLaRespuestaTraeMenosDeLoAnunciadoEsUnFallo() {
        val body = ByteArray(10_000) { 2 }
        val base = serve(body = body)
        val dest = File(tmp.root, "app-tv.apk")
        assertThrows(IOException::class.java) { download("$base/app-tv.apk", dest, expectedSize = 20_000L) }
        assertFalse(dest.exists())
    }

    @Test
    fun unaRespuestaDeErrorNoDejaNada() {
        val base = serve(body = ByteArray(1))
        val dest = File(tmp.root, "app-tv.apk")
        assertThrows(IOException::class.java) { download("$base/falla", dest, expectedSize = 1L) }
        assertFalse(dest.exists())
    }

    @Test
    fun cancelarBorraElParcial() {
        val body = ByteArray(2_000_000) { 3 }
        val base = serve(body = body)
        val dest = File(tmp.root, "app-tv.apk")
        assertThrows(CancellationException::class.java) {
            download("$base/app-tv.apk", dest, body.size.toLong()) { _, _ -> throw CancellationException("cancelado") }
        }
        assertFalse(dest.exists())
        assertFalse(File(tmp.root, "app-tv.apk.part").exists())
    }

    @Test
    fun alBajarSustituyeUnFicheroAnteriorSoloSiTodoVaBien() {
        val dest = File(tmp.root, "app-tv.apk").apply { writeText("viejo") }
        val base = serve(body = ByteArray(10) { 4 })
        assertThrows(IOException::class.java) { download("$base/app-tv.apk", dest, expectedSize = 99L) }
        assertFalse("un intento fallido no deja el viejo como si fuera el nuevo", dest.exists())
    }
}
