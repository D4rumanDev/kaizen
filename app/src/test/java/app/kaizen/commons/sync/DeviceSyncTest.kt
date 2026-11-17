package app.kaizen.commons.sync

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sincronizar varios dispositivos: cada uno escribe solo su fichero, funde los demás antes de subir el suyo y no se rompe con copias ajenas malas. */
class DeviceSyncTest {
    private class FakeStore(val files: MutableMap<String, ByteArray> = mutableMapOf()) : RemoteStore {
        val writes = mutableListOf<String>()
        val events = mutableListOf<String>()
        override suspend fun list() = files.keys.toList()
        override suspend fun read(name: String): ByteArray? = files[name]
        override suspend fun write(name: String, bytes: ByteArray) { events += "write:$name"; writes += name; files[name] = bytes }
        override suspend fun delete(name: String) { events += "delete:$name"; files.remove(name) }
    }

    private val me = "aaaaaaaa"
    private val other = "bbbbbbbb"

    @Test fun `solo escribe su propio fichero y funde el de los demas antes de subirlo`() = runTest {
        val store = FakeStore(mutableMapOf(DeviceSync.fileName(other) to "{otro}".toByteArray(), "otra-cosa.txt" to "x".toByteArray()))
        val merged = mutableListOf<String>()
        val report = DeviceSync.run(store, me, { merged += String(it); store.events += "merge"; true }, { store.events += "export"; "{propio}".toByteArray() })
        assertEquals(listOf("{otro}"), merged)
        assertEquals(listOf(DeviceSync.fileName(me)), store.writes)
        // Primero se funde, luego se exporta lo propio (que así ya lleva lo del otro) y por fin se sube.
        assertEquals(listOf("merge", "export", "write:${DeviceSync.fileName(me)}"), store.events)
        assertEquals(1, report.merged)
        assertEquals(0, report.skipped)
    }

    @Test fun `no se lee a si mismo ni ficheros que no son copias de dispositivos`() = runTest {
        val store = FakeStore(mutableMapOf(DeviceSync.fileName(me) to "{viejo propio}".toByteArray(), "kaizen-estados-XYZ.json" to "x".toByteArray(), "kaizen-estados-../../x.json" to "x".toByteArray()))
        var calls = 0
        DeviceSync.run(store, me, { calls++; true }, { "{}".toByteArray() })
        assertEquals(0, calls)
    }

    @Test fun `una copia ajena no valida, enorme o ilegible se omite y no impide las demas`() = runTest {
        val third = "cccccccc"
        val fourth = "dddddddd"
        val store = FakeStore(mutableMapOf(
            DeviceSync.fileName(other) to "mala".toByteArray(), DeviceSync.fileName(third) to ByteArray(2_000), DeviceSync.fileName(fourth) to "buena".toByteArray()
        ))
        val report = DeviceSync.run(store, me, { String(it) == "buena" }, { "{}".toByteArray() }, maxBytes = 1_000)
        assertEquals(1, report.merged)
        assertEquals(2, report.skipped)
        assertEquals(listOf(DeviceSync.fileName(me)), store.writes)
    }

    @Test fun `un fallo al fundir una copia ajena se cuenta como omitida y un fallo al subir se propaga`() = runTest {
        val store = FakeStore(mutableMapOf(DeviceSync.fileName(other) to "x".toByteArray()))
        val report = DeviceSync.run(store, me, { error("boom") }, { "{}".toByteArray() })
        assertEquals(1, report.skipped)
        val failing = object : RemoteStore {
            override suspend fun list() = emptyList<String>()
            override suspend fun read(name: String): ByteArray? = null
            override suspend fun write(name: String, bytes: ByteArray) = throw java.io.IOException("sin espacio")
        }
        val failure = runCatching { DeviceSync.run(failing, me, { true }, { "{}".toByteArray() }) }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
    }

    @Test fun `una copia de un dispositivo abandonado se funde, se sube lo propio y solo entonces se borra`() = runTest {
        val store = FakeStore(mutableMapOf(DeviceSync.fileName(other) to "vieja".toByteArray(), DeviceSync.fileName("cccccccc") to "reciente".toByteArray()))
        val report = DeviceSync.run(store, me, { true }, { "{}".toByteArray() }, isAbandoned = { String(it) == "vieja" })
        assertEquals(1, report.deleted)
        assertEquals(listOf("write:${DeviceSync.fileName(me)}", "delete:${DeviceSync.fileName(other)}"), store.events)
        assertTrue(DeviceSync.fileName("cccccccc") in store.files)
    }

    @Test fun `una copia abandonada que no se pudo fundir no se borra`() = runTest {
        val store = FakeStore(mutableMapOf(DeviceSync.fileName(other) to "mala".toByteArray()))
        DeviceSync.run(store, me, { false }, { "{}".toByteArray() }, isAbandoned = { true })
        assertTrue(DeviceSync.fileName(other) in store.files)
    }

    @Test fun `los ids de dispositivo y sus nombres de fichero`() {
        val id = DeviceSync.newDeviceId()
        assertEquals(8, id.length)
        assertEquals(id, DeviceSync.deviceOf(DeviceSync.fileName(id)))
        assertEquals(id, DeviceSync.deviceOf("/remote.php/dav/files/u/Kaizen/${DeviceSync.fileName(id)}"))
        assertNull(DeviceSync.deviceOf("kaizen-estados-1234.json"))
        assertNull(DeviceSync.deviceOf("kaizen-estados-ABCDEF12.json"))
    }

    // ----- WebDAV con un servidor simulado

    private fun client(handler: (okhttp3.Request) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val request = chain.request()
        val (code, body) = handler(request)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("x").body(body.toResponseBody("application/xml".toMediaType())).build()
    }).build()

    @Test fun `webdav lista, lee y escribe con las cabeceras y metodos esperados`() = runTest {
        val seen = mutableListOf<String>()
        val store = WebDavStore("https://nas.example/dav/Kaizen/", "ana", "secreta", client { request ->
            seen += "${request.method} ${request.url.encodedPath} depth=${request.header("Depth")} auth=${request.header("Authorization")?.startsWith("Basic ")}"
            when (request.method) {
                "PROPFIND" -> 207 to """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/Kaizen/</d:href></d:response>
                    <d:response><d:href>/dav/Kaizen/kaizen-estados-bbbbbbbb.json</d:href></d:response></d:multistatus>"""
                "GET" -> 200 to "{datos}"
                else -> 201 to ""
            }
        })
        assertEquals(listOf("kaizen-estados-bbbbbbbb.json"), store.list().filter { DeviceSync.deviceOf(it) != null })
        assertEquals("{datos}", String(checkNotNull(store.read("kaizen-estados-bbbbbbbb.json"))))
        store.write("kaizen-estados-aaaaaaaa.json", "{}".toByteArray())
        assertEquals("PROPFIND /dav/Kaizen/ depth=1 auth=true", seen[0])
        assertEquals("GET /dav/Kaizen/kaizen-estados-bbbbbbbb.json depth=null auth=true", seen[1])
        assertEquals("PUT /dav/Kaizen/kaizen-estados-aaaaaaaa.json depth=null auth=true", seen[2])
    }

    @Test fun `webdav crea la carpeta si no existe y reintenta una vez`() = runTest {
        val seen = mutableListOf<String>()
        var created = false
        val store = WebDavStore("https://nas.example/dav/Kaizen/", "", "", client { request ->
            seen += request.method
            when {
                request.method == "MKCOL" -> { created = true; 201 to "" }
                request.method == "PUT" && !created -> 409 to ""
                else -> 201 to ""
            }
        })
        store.write("kaizen-estados-aaaaaaaa.json", "{}".toByteArray())
        assertEquals(listOf("PUT", "MKCOL", "PUT"), seen)
    }

    @Test fun `webdav sin usuario no manda Authorization, y un fallo del servidor es un error`() = runTest {
        var auth: String? = "x"
        val store = WebDavStore("https://nas.example/dav/", "", "", client { request -> auth = request.header("Authorization"); 500 to "" })
        val failure = runCatching { store.write("kaizen-estados-aaaaaaaa.json", "{}".toByteArray()) }.exceptionOrNull()
        assertNull(auth)
        assertTrue(failure is java.io.IOException)
    }

    @Test fun `webdav corta una respuesta mas grande que el tope sin cargarla entera`() = runTest {
        val big = "x".repeat(20 * 1024 * 1024)
        val store = WebDavStore("https://nas.example/dav/", "", "", client { 200 to big })
        val failure = runCatching { store.read("kaizen-estados-aaaaaaaa.json") }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
        val list = WebDavStore("https://nas.example/dav/", "", "", client { 207 to big })
        assertTrue(runCatching { list.list() }.exceptionOrNull() is java.io.IOException)
    }

    @Test fun `webdav interpreta los href con prefijos y escapes`() {
        val xml = "<D:multistatus><D:response><D:href>/dav/a%20b/kaizen-estados-aaaaaaaa.json</D:href></D:response><D:response><D:href>/dav/a%20b/</D:href></D:response></D:multistatus>"
        assertEquals(listOf("kaizen-estados-aaaaaaaa.json", "a b"), WebDavStore.parseNames(xml))
    }
}
