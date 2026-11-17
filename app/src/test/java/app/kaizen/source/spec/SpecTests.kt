package app.kaizen.source.spec

import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// ===== SourceSpecTest.kt =====
class SourceSpecTest {
    private val text = File("src/main/assets/sources/verani.json").readText()

    /** El `verani.json` real con una modificación: ([editor] recibe el objeto raíz). */
    private fun edited(editor: (JSONObject) -> Unit): String = JSONObject(text).also(editor).toString()

    @Test
    fun shippedFileParses() {
        val spec = SourceSpec.parse(text)
        assertEquals("verani", spec.id)
        assertEquals("https://verani.me", spec.base)
        assertEquals(1, spec.schema)
    }

    @Test
    fun newerSchemaIsRejected() {
        assertThrows(SpecException::class.java) { SourceSpec.parse(edited { it.put("schema", 2) }) }
    }

    @Test
    fun baseMustBeHttpsAndMatchTheHost() {
        assertThrows(SpecException::class.java) { SourceSpec.parse(edited { it.put("base", "http://verani.me") }) }
        assertThrows(SpecException::class.java) { SourceSpec.parse(edited { it.put("host", "otro.example") }) }
    }

    @Test
    fun templatesCannotLeaveTheBase() {
        assertThrows(SpecException::class.java) {
            SourceSpec.parse(edited { it.getJSONObject("urls").put("anime", "https://evil.example/{slug}/") })
        }
        assertThrows(SpecException::class.java) {
            SourceSpec.parse(edited { it.getJSONObject("urls").put("anime", "{base}/{slug}/{secreto}") })
        }
    }

    @Test
    fun monoschinosFileParsesAndApiChaptersNeedTheChapterTemplate() {
        val monos = File("src/main/assets/sources/monoschinos.json").readText()
        assertEquals("monoschinos", SourceSpec.parse(monos).id)
        assertThrows(SpecException::class.java) {
            SourceSpec.parse(JSONObject(monos).also { it.getJSONObject("urls").remove("chapter") }.toString())
        }
    }

    @Test
    fun declaredSearchNeedsSomethingToResolveIt() {
        val monos = File("src/main/assets/sources/monoschinos.json").readText()
        // YEAR declarado sin urls.yearParam; texto sin urls.searchFirst.
        assertThrows(SpecException::class.java) { SourceSpec.parse(JSONObject(monos).also { it.getJSONObject("urls").remove("yearParam") }.toString()) }
        assertThrows(SpecException::class.java) { SourceSpec.parse(JSONObject(monos).also { it.getJSONObject("urls").remove("searchFirst") }.toString()) }
        assertThrows(SpecException::class.java) { SourceSpec.parse(JSONObject(monos).also { it.getJSONObject("search").put("filters", org.json.JSONArray().put("NOPE")) }.toString()) }
        // Una fuente jsData no tiene búsqueda de texto.
        val av1 = File("src/main/assets/sources/animeav1.json").readText()
        assertThrows(SpecException::class.java) { SourceSpec.parse(JSONObject(av1).also { it.getJSONObject("search").put("text", "alone") }.toString()) }
    }

    @Test
    fun animeflvFileParsesAndItsPostRequestStaysOnTheSite() {
        val text2 = File("src/main/assets/sources/animeflv.json").readText()
        assertEquals("animeflv", SourceSpec.parse(text2).id)
        // La ruta de una petición POST es del propio sitio: sin dominio ni «//».
        listOf("https://evil.example/x", "//evil.example/x", "flv").forEach { path ->
            assertThrows(SpecException::class.java) {
                SourceSpec.parse(JSONObject(text2).also { it.getJSONObject("servers").getJSONObject("request").put("path", path) }.toString())
            }
        }
    }

    @Test
    fun idMustBeSimple() {
        assertThrows(SpecException::class.java) { SourceSpec.parse(edited { it.put("id", "../x") }) }
    }

    @Test
    fun brokenJsonIsASpecException() {
        assertThrows(SpecException::class.java) { SourceSpec.parse("{no es json") }
        assertThrows(SpecException::class.java) { SourceSpec.parse(edited { it.remove("servers") }) }
    }
}

// ===== SpecBundleTest.kt =====
class SpecBundleTest {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)

    private fun sign(data: ByteArray, privateKey: java.security.PrivateKey = pair.private): ByteArray =
        Base64.getEncoder().encode(Signature.getInstance("SHA256withECDSA").run { initSign(privateKey); update(data); sign() })

    private val spec = File("src/main/assets/sources/verani.json").readBytes()
    private val shippedVersion = SourceSpec.parse(String(spec)).version

    private fun index(file: String = "verani.json", sha: String = SpecBundle.sha256(spec), version: Int = shippedVersion) =
        """{"schema":1,"version":1,"sources":[{"id":"verani","file":"$file","version":$version,"sha256":"$sha"}]}""".toByteArray()

    private fun files(index: ByteArray, signature: ByteArray, content: ByteArray = spec) = mapOf(
        "index.json" to index, "index.json.sig" to signature, "verani.json" to content
    )

    @Test
    fun validSetLoads() {
        val idx = index()
        val loaded = SpecBundle.load(publicKey, files(idx, sign(idx))::get)
        assertEquals(listOf("verani"), loaded.map { it.id })
    }

    @Test
    fun theShippedIndexIsSignedWithTheEmbeddedKey() {
        val dir = File("src/main/assets/sources")
        val loaded = SpecBundle.load(SpecSources.PUBLIC_KEY) { name -> File(dir, name).takeIf { it.isFile }?.readBytes() }
        assertTrue(loaded.any { it.id == "verani" })
    }

    @Test
    fun tamperedIndexIsRejected() {
        val idx = index()
        val signature = sign(idx)
        assertThrows(SpecException::class.java) { SpecBundle.load(publicKey, files(index(version = 9), signature)::get) }
    }

    @Test
    fun otherKeyIsRejected() {
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val idx = index()
        assertThrows(SpecException::class.java) { SpecBundle.load(publicKey, files(idx, sign(idx, other.private))::get) }
    }

    @Test
    fun fileThatDoesNotMatchItsHashIsSkipped() {
        val idx = index()
        val changed = String(spec).replace("\"version\": $shippedVersion", "\"version\": ${shippedVersion + 1}").toByteArray()
        assertEquals(emptyList<String>(), SpecBundle.load(publicKey, files(idx, sign(idx), changed)::get).map { it.id })
    }

    @Test
    fun missingSignatureIsRejected() {
        assertThrows(SpecException::class.java) { SpecBundle.load(publicKey, mapOf("index.json" to index())::get) }
    }

    @Test
    fun indexCannotPointOutsideTheFolder() {
        assertThrows(SpecException::class.java) { SpecBundle.parseIndex(index(file = "../../etc/passwd")) }
        assertThrows(SpecException::class.java) { SpecBundle.parseIndex(index(file = "a/b.json")) }
        assertThrows(SpecException::class.java) { SpecBundle.parseIndex(index(sha = "no-es-un-hash")) }
    }

    @Test
    fun verifyFailsOnGarbage() {
        assertFalse(SpecBundle.verify(publicKey, "x".toByteArray(), "no-es-base64-%%%".toByteArray()))
        assertFalse(SpecBundle.verify("clave rota", "x".toByteArray(), "AAAA".toByteArray()))
    }
}
