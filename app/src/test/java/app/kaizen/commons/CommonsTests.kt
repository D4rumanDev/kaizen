package app.kaizen.commons

import app.kaizen.pojos.av1.FavoriteAV1
import app.kaizen.pojos.av1.Record
import app.kaizen.source.FakePreferences
import app.kaizen.tv.settings.LogLine
import app.kaizen.tv.settings.entryLines
import coil3.network.NetworkHeaders
import coil3.network.NetworkResponse
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import okhttp3.Call
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// ===== CacheFirstStrategyTest.kt =====
class CacheFirstStrategyTest {
    private val now = 10_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    // `no-store` y max-age=0: la estrategia por defecto de Coil no reutilizaría esta respuesta.
    private fun response(code: Int, at: Long, type: String? = null) = NetworkResponse(
        code = code, requestMillis = at, responseMillis = at,
        headers = NetworkHeaders.Builder().add("Cache-Control", "no-store, max-age=0").apply { type?.let { add("Content-Type", it) } }.build()
    )

    @Before
    fun setUp() {
        CacheFirstStrategy.clock = { now }
    }

    @After
    fun tearDown() {
        CacheFirstStrategy.clock = System::currentTimeMillis
    }

    @Test
    fun reutilizaLaImagenGuardadaAunqueElServidorDigaNoCache() {
        assertTrue(CacheFirstStrategy.isReusable(response(200, now - 3 * day)))
    }

    @Test
    fun pasadoElLimiteVuelveAPreguntarALaWeb() {
        assertFalse(CacheFirstStrategy.isReusable(response(200, now - 91 * day)))
    }

    @Test
    fun soloGuardaImagenes() {
        assertTrue(CacheFirstStrategy.isStorable(response(200, now, "image/webp")))
        assertFalse(CacheFirstStrategy.isStorable(response(200, now, "text/html; charset=utf-8")))
        assertFalse(CacheFirstStrategy.isReusable(response(200, now, "text/html")))
    }

    @Test
    fun noReutilizaUnaRespuestaDeError() {
        assertFalse(CacheFirstStrategy.isReusable(response(404, now)))
    }
}

// ===== CrashStreakTest.kt =====
class CrashStreakTest {
    private val prefs = FakePreferences()
    private var now = 1_000_000_000L
    private val streak = CrashStreak({ prefs }, { now })

    private fun crash(times: Int) = repeat(times) { streak.recordCrash() }

    @Test
    fun `por debajo del limite no se restablece`() {
        crash(CrashStreak.LIMIT - 1)
        assertFalse(streak.consumeResetRequest())
        assertEquals(CrashStreak.LIMIT - 1, streak.count)
    }

    @Test
    fun `al llegar al limite se pide restablecer una sola vez y se olvida la racha`() {
        crash(CrashStreak.LIMIT)
        assertTrue(streak.consumeResetRequest())
        assertEquals(0, streak.count)
        assertFalse(streak.consumeResetRequest())
    }

    @Test
    fun `funcionar con normalidad reinicia la racha`() {
        crash(CrashStreak.LIMIT - 1)
        streak.recordHealthy()
        crash(1)
        assertFalse(streak.consumeResetRequest())
        assertEquals(1, streak.count)
    }

    @Test
    fun `el aviso al usuario sale una sola vez tras restablecer`() {
        assertFalse(streak.takeNotice())
        crash(CrashStreak.LIMIT)
        streak.consumeResetRequest()
        assertTrue(streak.takeNotice())
        assertFalse(streak.takeNotice())
    }

    @Test
    fun `fallos separados por mas tiempo que la ventana no forman racha`() {
        crash(CrashStreak.LIMIT - 1)
        now += CrashStreak.WINDOW_MS + 1
        crash(1)
        assertEquals(1, streak.count)
        assertFalse(streak.consumeResetRequest())
    }
}

// ===== DebugLogParseTest.kt =====
class DebugLogParseTest {
    private val lines = listOf(
        "2026-10-29 20:05:45 I/Series: Libro de series copiado: 3 fichas, 1 uniones",
        "2026-10-29 20:06:01 E/KAIZEN: Fallo inesperado: IllegalStateException: boom",
        "java.lang.IllegalStateException: boom",
        "\tat app.kaizen.Foo.bar(Foo.kt:1)",
        "\tat app.kaizen.Foo.baz(Foo.kt:2)",
        "2026-10-30 08:00:00 D/Raro: detalle"
    )

    @Test
    fun `las lineas sin fecha son el rastro tecnico de la anotacion anterior`() {
        val entries = DebugLog.parse(lines)
        assertEquals(3, entries.size)
        assertEquals("Libro de series", entries[0].area)
        assertEquals(DebugLog.ERROR, entries[1].level)
        assertEquals("Error de la app", entries[1].area)
        assertEquals(3, entries[1].details.size)
        assertEquals("Raro", entries[2].area)
    }

    @Test
    fun `el visor pone una cabecera por dia y resume el rastro bajo el error`() {
        val text = entryLines(DebugLog.parse(lines)).map { it.text }
        assertEquals("2026-10-29", text[0])
        assertEquals("20:05:45 · info · Libro de series — Libro de series copiado: 3 fichas, 1 uniones", text[1])
        assertEquals("20:06:01 · ERROR · Error de la app — Fallo inesperado: IllegalStateException: boom", text[2])
        assertEquals("      java.lang.IllegalStateException: boom (+2 líneas técnicas; se leen enteras en el panel de abajo)", text[3])
        assertEquals("2026-10-30", text[4])
    }

    @Test
    fun `cada fila de la tabla trae sus columnas y su tipo`() {
        val rows = entryLines(DebugLog.parse(lines))
        assertEquals(listOf(LogLine.Kind.DAY, LogLine.Kind.ENTRY, LogLine.Kind.ENTRY, LogLine.Kind.DETAIL, LogLine.Kind.DAY, LogLine.Kind.ENTRY), rows.map { it.kind })
        val error = rows[2]
        assertEquals("20:06:01", error.time)
        assertEquals("ERROR", error.level)
        assertEquals("Error de la app", error.area)
        assertEquals("Fallo inesperado: IllegalStateException: boom", error.message)
        // Las anotaciones se cuentan por filas de tipo ENTRY: ni los días ni el rastro técnico suman.
        assertEquals(3, rows.count { it.kind == LogLine.Kind.ENTRY })
    }
}

// ===== EmbedUrlTest.kt =====
class EmbedUrlTest {
    @Test
    fun decodesEmbedUrls() {
        assertEquals("http://ok.ru/videoembed/5159861750431", decodeEmbedUrl("aHR0cDovL29rLnJ1L3ZpZGVvZW1iZWQvNTE1OTg2MTc1MDQzMQ=="))
        assertEquals("http://ok.ru/videoembed/5159861750431", decodeEmbedUrl(" aHR0cDovL29rLnJ1L3ZpZGVvZW1iZWQvNTE1OTg2MTc1MDQzMQ \n"))
    }

    @Test
    fun rejectsWhatIsNotAUrl() {
        assertNull(decodeEmbedUrl("bm8gZXMgdW5hIHVybA=="))
        assertNull(decodeEmbedUrl("%%%no-base64%%%"))
    }

    @Test
    fun onlyHttpSchemesAndStandardAlphabet() {
        assertNull(decodeEmbedUrl("aHR0cHg6Ly9ub2VzdXJs")) // "httpx://noesurl"
        assertNull(decodeEmbedUrl("aHR0cDovL2E_Yg")) // alfabeto URL-safe: no se acepta
    }

    @Test
    fun textDecodingDoesNotRequireAUrl() {
        assertEquals("no es una url", decodeBase64Text("bm8gZXMgdW5hIHVybA=="))
    }
}

// ===== ErrorLogTest.kt =====
class ErrorLogTest {

    @Test(expected = CancellationException::class)
    fun cancellationIsRethrownNotLogged() {
        logUnlessCancelled(CancellationException("cancelado"))
    }
}

// ===== FallbackDnsTest.kt =====
/** El DNS de relevo: solo entra cuando se le pide (tras un fallo de conexión) y deja de usarse pasado su plazo. */
class FallbackDnsTest {
    private var now = 1_000_000L
    private var queries = 0
    private fun ip(a: Int, b: Int, c: Int, d: Int) = InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))
    private val cloudflare = listOf(ip(104, 26, 2, 80))
    private val network = listOf(ip(188, 114, 96, 5))

    @Before
    fun setUp() {
        FallbackDns.reset()
        FallbackDns.clock = { now }
        FallbackDns.system = Dns { network }
        FallbackDns.resolver = { queries++; cloudflare }
    }

    @After
    fun tearDown() {
        FallbackDns.reset()
        FallbackDns.clock = System::currentTimeMillis
        FallbackDns.system = Dns.SYSTEM
    }

    @Test
    fun `sin fallo no consulta Quad9 y usa el DNS de la red`() {
        assertEquals(network, FallbackDns.lookup("animeav1.com"))
        assertEquals(0, queries)
    }

    @Test
    fun `tras preferir un servidor se resuelve por Quad9 hasta que pasa el plazo`() {
        assertTrue(FallbackDns.prefer("animeav1.com"))
        assertEquals(cloudflare, FallbackDns.lookup("animeav1.com"))
        now += 11 * 60_000L
        assertEquals(network, FallbackDns.lookup("animeav1.com"))
    }

    @Test
    fun `si Quad9 tampoco responde no merece la pena reintentar`() {
        FallbackDns.resolver = { emptyList() }
        assertFalse(FallbackDns.prefer("animeav1.com"))
    }

    @Test
    fun `si Quad9 da las mismas IP que la red no se reintenta`() {
        FallbackDns.resolver = { network }
        assertFalse(FallbackDns.prefer("animeav1.com"))
    }

    @Test
    fun `la respuesta de Quad9 se reutiliza unos minutos y la vacia solo unos segundos`() {
        FallbackDns.prefer("animeav1.com")
        FallbackDns.prefer("animeav1.com")
        assertEquals(1, queries)
        FallbackDns.resolver = { queries++; emptyList() }
        assertFalse(FallbackDns.prefer("otra.example.com"))
        assertFalse(FallbackDns.prefer("otra.example.com"))
        assertEquals(2, queries)
        now += 31_000L
        FallbackDns.prefer("otra.example.com")
        assertEquals(3, queries)
    }

    @Test
    fun `forget devuelve el servidor al DNS de la red`() {
        FallbackDns.prefer("animeav1.com")
        assertTrue(FallbackDns.isPreferred("animeav1.com"))
        FallbackDns.forget("animeav1.com")
        assertFalse(FallbackDns.isPreferred("animeav1.com"))
        assertEquals(network, FallbackDns.lookup("animeav1.com"))
    }

    @Test
    fun `no se consulta por nombres de la red local ni por IP escritas`() {
        for (name in listOf("tele", "nas.local", "router.lan", "192.0.2.1", "[::1]", "host.internal")) assertFalse(name, FallbackDns.prefer(name))
        assertEquals(0, queries)
    }

    /** Una respuesta DNS binaria a la consulta [id] de [host] con los registros dados: (tipo, datos). */
    private fun reply(host: String, id: Int, vararg records: Pair<Int, ByteArray>, flags: Int = 0x8180): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(out).apply {
            writeShort(id); writeShort(flags); writeShort(1); writeShort(records.size); writeShort(0); writeShort(0)
            host.split('.').forEach { writeByte(it.length); write(it.toByteArray()) }
            writeByte(0); writeShort(1); writeShort(1)
            records.forEach { (type, data) ->
                writeShort(0xC00C) // puntero al nombre de la pregunta
                writeShort(type); writeShort(1); writeInt(300); writeShort(data.size); write(data)
            }
        }
        return out.toByteArray()
    }

    private fun ipBytes(vararg octets: Int) = ByteArray(4) { octets[it].toByte() }

    @Test
    fun `de la respuesta solo valen las IPv4 publicas`() {
        val message = reply(
            "animeav1.com", 7,
            5 to "cdn".toByteArray(), 1 to ipBytes(104, 26, 2, 80),
            1 to ipBytes(127, 0, 0, 1), 1 to ipBytes(10, 0, 0, 5), 1 to ipBytes(192, 168, 1, 2), 1 to ipBytes(172, 16, 0, 1),
            1 to ipBytes(100, 64, 1, 1), 1 to ipBytes(169, 254, 1, 1), 1 to ipBytes(0, 0, 0, 0), 1 to ipBytes(224, 0, 0, 1),
            28 to ByteArray(16)
        )
        val valid = FallbackDns.parseAnswer("animeav1.com", message, 7)
        assertEquals(listOf("104.26.2.80"), valid.map { it.hostAddress })
        assertEquals("animeav1.com", valid.single().hostName)
    }

    @Test
    fun `una respuesta rota, de otra consulta o con error da una lista vacia y se limita a 8`() {
        assertTrue(FallbackDns.parseAnswer("a.example.com", "no es dns".toByteArray(), 7).isEmpty())
        assertTrue("otra consulta", FallbackDns.parseAnswer("a.example.com", reply("a.example.com", 8, 1 to ipBytes(104, 26, 2, 1)), 7).isEmpty())
        assertTrue("el nombre no existe", FallbackDns.parseAnswer("a.example.com", reply("a.example.com", 7, flags = 0x8183), 7).isEmpty())
        assertTrue("no es una respuesta", FallbackDns.parseAnswer("a.example.com", reply("a.example.com", 7, 1 to ipBytes(104, 26, 2, 1), flags = 0x0100), 7).isEmpty())
        val many = Array(20) { 1 to ipBytes(104, 26, 2, it + 1) }
        assertEquals(8, FallbackDns.parseAnswer("a.example.com", reply("a.example.com", 7, *many), 7).size)
    }

    @Test
    fun `diagnose distingue el DNS de la red de un nombre que no existe`() {
        val host = "ugc-cdn.cloudwindow-route.com"
        assertEquals(FallbackDns.Diagnosis.NETWORK_RESOLVES, FallbackDns.diagnose("animeav1.com"))
        FallbackDns.system = Dns { throw java.net.UnknownHostException("filtrado") }
        assertEquals(FallbackDns.Diagnosis.NETWORK_DNS_FAILS, FallbackDns.diagnose(host))
        // Diagnosticar no cambia cómo se resuelve: la red sigue fallando y no se pasa a Quad9.
        assertTrue(!FallbackDns.isPreferred(host))
        assertTrue(runCatching { FallbackDns.lookup(host) }.exceptionOrNull() is java.net.UnknownHostException)
        FallbackDns.reset()
        FallbackDns.resolver = { emptyList() }
        assertEquals(FallbackDns.Diagnosis.UNRESOLVED, FallbackDns.diagnose(host))
        assertEquals(FallbackDns.Diagnosis.UNRESOLVED, FallbackDns.diagnose("tele"))
    }

    @Test
    fun `la consulta lleva el nombre por etiquetas y rechaza nombres invalidos`() {
        val q = FallbackDns.buildQuery("a.bc.com", 0x1234)!!
        assertEquals("1234010000010000000000000161" + "026263" + "03636f6d" + "00" + "0001" + "0001", q.joinToString("") { "%02x".format(it) })
        assertEquals(null, FallbackDns.buildQuery("a..com", 1))
        assertEquals(null, FallbackDns.buildQuery("${"x".repeat(64)}.com", 1))
    }
}

// ===== HostGateInterceptorTest.kt =====
/** El adaptador de OkHttp (portadas y proxy): anota lo que responde cada servidor sin cambiar lo que ve quien pide. */
class HostGateInterceptorTest {
    private val answers = ArrayDeque<Int>()
    private var calls = 0

    private fun client(onCall: (Call) -> Unit = {}) = OkHttpClient.Builder()
        .addInterceptor(HostGateInterceptor)
        .addInterceptor(Interceptor { chain ->
            calls++
            onCall(chain.call())
            val code = answers.removeFirstOrNull() ?: 200
            if (code == -1) throw IOException("sin red")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x").body("".toResponseBody()).build()
        }).build()

    private fun get(client: OkHttpClient, url: String) = client.newCall(Request.Builder().url(url).build())

    @Before
    fun setUp() {
        HostGate.storage = null
        HostGate.reset()
        HostGate.onDown = {}
        HostGate.isNetworkUp = { true }
        HostGate.clock = System::currentTimeMillis
    }

    @Test
    fun `la respuesta llega tal cual aunque sea un fallo`() {
        answers += listOf(403, 404)
        val client = client()
        assertEquals(403, get(client, "https://a.example/x").execute().code)
        assertEquals(404, get(client, "https://a.example/y").execute().code)
    }

    @Test
    fun `dos 403 seguidos pausan el servidor y despues no sale ninguna peticion`() {
        answers += listOf(403, 403)
        val client = client()
        repeat(2) { get(client, "https://b.example/$it").execute().close() }
        assertTrue(HostGate.isDown("b.example"))
        val before = calls
        val failed = runCatching { get(client, "https://b.example/otra").execute() }.exceptionOrNull()
        assertTrue(failed is HostGate.HostDownException)
        assertEquals("un servidor en pausa no recibe nada", before, calls)
        // Otro servidor sigue funcionando.
        assertEquals(200, get(client, "https://c.example/").execute().code)
    }

    @Test
    fun `un 404 no pausa y un 200 en medio reinicia la cuenta`() {
        answers += listOf(404, 404, 404, 404, 403, 200, 403)
        val client = client()
        repeat(7) { get(client, "https://d.example/$it").execute().close() }
        assertTrue(!HostGate.isDown("d.example"))
    }

    @Test
    fun `un fallo de red cuenta pero una peticion cancelada no`() {
        val client = client(onCall = { })
        answers += listOf(-1, -1)
        repeat(2) { runCatching { get(client, "https://e.example/$it").execute() } }
        assertTrue(HostGate.isDown("e.example"))

        HostGate.reset()
        val canceling = client(onCall = { it.cancel() })
        answers += listOf(-1, -1, -1)
        repeat(3) { runCatching { get(canceling, "https://f.example/$it").execute() } }
        assertTrue("cancelar no es un fallo del servidor", !HostGate.isDown("f.example"))
    }

    @Test
    fun `una interrupcion del reproductor no es un fallo pero un timeout si`() {
        // Cortes que provoca el propio reproductor (saltar, parar): no cuentan.
        assertTrue(isInterruption(java.io.InterruptedIOException("cancelado"), threadInterrupted = false))
        assertTrue(isInterruption(IOException("cualquiera"), threadInterrupted = true))
        // SocketTimeoutException hereda de InterruptedIOException, pero es el servidor que no contesta: cuenta.
        assertTrue(!isInterruption(java.net.SocketTimeoutException("read timed out"), threadInterrupted = false))
        assertTrue(!isInterruption(java.net.ConnectException("rechazada"), threadInterrupted = false))
        assertTrue(!isInterruption(null, threadInterrupted = false))
    }

    @Test
    fun `varias peticiones a la vez al mismo servidor no hacen cola`() {
        val running = AtomicInteger()
        val maxRunning = AtomicInteger()
        val parallel = OkHttpClient.Builder()
            .addInterceptor(HostGateInterceptor)
            .addInterceptor(Interceptor { chain ->
                maxRunning.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                Thread.sleep(30)
                running.decrementAndGet()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("x").body("".toResponseBody()).build()
            }).build()
        val pool = Executors.newFixedThreadPool(4)
        val done = CountDownLatch(8)
        repeat(8) { pool.execute { get(parallel, "https://g.example/$it").execute().close(); done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        pool.shutdown()
        assertTrue("las portadas deben ir en paralelo", maxRunning.get() > 1)
    }
}

// ===== HostGateTest.kt =====
class HostGateTest {
    private val warnings = mutableListOf<String>()

    @Before
    fun setUp() {
        HostGate.storage = null
        HostGate.reset()
        HostGate.onDown = { warnings += it }
        HostGate.isNetworkUp = { true }
        HostGate.clock = System::currentTimeMillis
    }

    @Test
    fun unaPeticionALaVezPorServidor() {
        val running = AtomicInteger()
        val maxRunning = AtomicInteger()
        val pool = Executors.newFixedThreadPool(4)
        val done = CountDownLatch(8)
        repeat(8) {
            pool.execute {
                HostGate.call("https://a.example/x$it") {
                    maxRunning.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                    Thread.sleep(10)
                    running.decrementAndGet()
                }
                done.countDown()
            }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(1, maxRunning.get())
    }

    @Test
    fun lasPausasSeguidasCaducanSiPasaMuchoTiempoSinFallos() {
        val host = "caduca.example"
        repeat(2) { runCatching { HostGate.call("https://$host/") { throw IOException("timeout") } } }
        assertTrue(HostGate.downUntil(host) > 0)
        // Pasan más horas de las de caducidad desde que acabó la pausa: un fallo suelto ya no vuelve a pausar (empieza de nuevo y hacen falta dos).
        HostGate.clock = { System.currentTimeMillis() + HostGate.STRIKE_DECAY_MS + 5 * 60_000L }
        HostGate.reportFailure("https://$host/", IOException("timeout"))
        assertEquals(0L, HostGate.downUntil(host))
    }

    @Test
    fun dosFallosDeRedCortanElServidor() {
        repeat(2) { runCatching { HostGate.call("https://b.example/") { throw IOException("timeout") } } }
        assertEquals(1, warnings.size)
        val calls = AtomicInteger()
        val result = runCatching { HostGate.call("https://b.example/otra") { calls.incrementAndGet() } }
        assertTrue(result.exceptionOrNull() is HostGate.HostDownException)
        assertEquals(0, calls.get())
        // Otro servidor sigue funcionando.
        assertEquals(1, HostGate.call("https://c.example/") { 1 })
    }

    @Test
    fun cadaPausaSeguidaEsMasLargaYUnFalloBastaTrasUnaPausa() {
        val host = "e.example"
        fun fail() = runCatching { HostGate.call("https://$host/") { throw IOException("timeout") } }
        fun pauseMs() = HostGate.downUntil(host) - System.currentTimeMillis()

        repeat(2) { fail() }
        assertTrue(pauseMs() in (HostGate.PAUSES_MS[0] - 5_000)..HostGate.PAUSES_MS[0])
        for (expected in HostGate.PAUSES_MS.drop(1) + HostGate.PAUSES_MS.last()) {
            HostGate.endPause(host)
            fail() // tras una pausa, un solo fallo vuelve a pausar
            assertTrue(pauseMs() in (expected - 5_000)..expected)
        }
        assertEquals(5, warnings.size)
    }

    @Test
    fun siResponde_seOlvidanLasPausas() {
        val host = "f.example"
        repeat(2) { runCatching { HostGate.call("https://$host/") { throw IOException("timeout") } } }
        HostGate.endPause(host)
        assertEquals(1, HostGate.call("https://$host/") { 1 })
        // Vuelven a hacer falta 2 fallos para pausar, y la pausa es otra vez la primera.
        runCatching { HostGate.call("https://$host/") { throw IOException("timeout") } }
        assertEquals(0L, HostGate.downUntil(host))
        runCatching { HostGate.call("https://$host/") { throw IOException("timeout") } }
        assertTrue(HostGate.downUntil(host) - System.currentTimeMillis() <= HostGate.PAUSES_MS[0])
    }

    @Test
    fun unServidorDeVideoSeCortaComoFamiliaAunqueCambieDeDominio() {
        val key = HostGate.serverKey("Uqload")
        // Dos fallos de DNS en dominios distintos de la misma familia: pausa de 2 min.
        HostGate.reportServerFailure("Uqload", java.net.UnknownHostException("uqload.vc"))
        assertTrue(!HostGate.isDown(key))
        HostGate.reportServerFailure("Uqload", java.net.UnknownHostException("uqload.is"))
        assertTrue(HostGate.isDown(key))
        assertTrue(HostGate.downUntil(key) - System.currentTimeMillis() <= HostGate.PAUSES_MS[0])
        assertEquals(listOf("Uqload no responde: no se usará durante 2 minutos"), warnings)
        // Tras la pausa, un solo fallo la alarga (5 min); otra familia no se ve afectada.
        HostGate.endPause(key)
        HostGate.reportServerFailure("Uqload", java.net.UnknownHostException("uqload.vc"))
        assertTrue(HostGate.downUntil(key) - System.currentTimeMillis() > HostGate.PAUSES_MS[0])
        assertTrue(!HostGate.isDown(HostGate.serverKey("MixDrop")))
        // Entregar un vídeo la devuelve a la lista y olvida las pausas.
        HostGate.reportServerUp("Uqload")
        assertTrue(!HostGate.isDown(key))
    }

    @Test
    fun unaPausaDeOtroHostNoCuentaComoFalloDeLaFamilia() {
        repeat(3) { HostGate.reportServerFailure("MixDrop", HostGate.HostDownException("mixdrop.ps")) }
        assertTrue(!HostGate.isDown(HostGate.serverKey("MixDrop")))
    }

    @Test
    fun siNoAceptaLaConexionBastaUnaPeticionParaPausar() {
        runCatching { HostGate.call("https://g.example/") { throw java.net.ConnectException("refused") } }
        assertTrue(HostGate.isDown("g.example"))
    }

    @Test
    fun siNoHayRutaOUnTimeoutDeConexionBastaUnaPeticionParaPausar() {
        runCatching { HostGate.call("https://i.example/") { throw java.net.NoRouteToHostException("x") } }
        runCatching { HostGate.call("https://j.example/") { throw java.net.SocketTimeoutException("connect timed out") } }
        assertTrue(HostGate.isDown("i.example"))
        assertTrue(HostGate.isDown("j.example"))
    }

    @Test
    fun unFalloDeDnsOUnCorteDePropioNoPausanDeGolpe() {
        runCatching { HostGate.call("https://k.example/") { throw java.net.UnknownHostException("x") } }
        assertTrue(!HostGate.isDown("k.example"))
    }

    @Test
    fun dosFallosSeparadosPorMasDeTresMinutosNoPausan() {
        var now = 1_000_000L
        HostGate.clock = { now }
        fun fail() = runCatching { HostGate.call("https://w.example/") { throw IOException("timeout") } }
        fail()
        now += 3 * 60_000L + 1
        fail()
        assertTrue(!HostGate.isDown("w.example"))
        now += 60_000L
        fail() // dos seguidos dentro de la ventana
        assertTrue(HostGate.isDown("w.example"))
    }

    @Test
    fun unTimeoutDeLecturaSoloCuentaUnFallo() {
        runCatching { HostGate.call("https://h.example/") { throw java.net.SocketTimeoutException("Read timed out") } }
        assertTrue(!HostGate.isDown("h.example"))
    }

    @Test
    fun unEnlaceMalFormadoNoCuentaComoCaida() {
        repeat(3) { runCatching { HostGate.call("https://m.example/") { throw java.net.MalformedURLException("vacío") } } }
        assertTrue(!HostGate.isDown("m.example"))
    }

    @Test
    fun un404OUn429NoCuentanComoCaida() {
        repeat(5) {
            runCatching { HostGate.call("https://d.example/") { throw HttpStatusException("x", 404, "u") } }
            runCatching { HostGate.call("https://d.example/") { throw HttpStatusException("x", 429, "u") } }
        }
        assertTrue(warnings.isEmpty())
        assertEquals(2, HostGate.call("https://d.example/") { 2 })
    }

    @Test
    fun dos403DeBloqueoSeguidosCortanElServidor() {
        repeat(2) { runCatching { HostGate.call("https://k.example/api/$it") { throw HostGate.ForbiddenException("https://k.example/api/$it") } } }
        assertEquals(1, warnings.size)
        assertTrue(HostGate.isDown("k.example"))
        val result = runCatching { HostGate.call("https://k.example/otra") { 1 } }
        assertTrue(result.exceptionOrNull() is HostGate.HostDownException)
    }

    @Test
    fun unaRespuestaCorrectaEntreDos403NoCortaElServidor() {
        runCatching { HostGate.call("https://l.example/a") { throw HostGate.ForbiddenException("https://l.example/a") } }
        assertEquals(1, HostGate.call("https://l.example/b") { 1 })
        runCatching { HostGate.call("https://l.example/c") { throw HostGate.ForbiddenException("https://l.example/c") } }
        assertTrue(warnings.isEmpty())
        assertTrue(!HostGate.isDown("l.example"))
    }

    @Test
    fun un403QueNoEsDeBloqueoNoCuenta() {
        // Un HttpStatusException 403 simple (la verificación de la web, que resuelve el usuario) no corta el servidor.
        repeat(5) { runCatching { HostGate.call("https://n.example/") { throw HttpStatusException("x", 403, "u") } } }
        assertTrue(warnings.isEmpty())
        assertTrue(!HostGate.isDown("n.example"))
    }

    @Test
    fun unaVerificacionDeCloudflareNoCuentaComoBloqueo() {
        // Cloudflare marca toda página de verificación con `cf-mitigated: challenge`: la hace el usuario, no es un bloqueo.
        repeat(5) { HostGate.report("https://v.example/", 403, "challenge") }
        assertTrue(!HostGate.isDown("v.example"))
        assertEquals(null, HostGate.failureFor("https://v.example/", 403, "Challenge"))
        // Sin la cabecera (o con otro valor) el mismo 403 sí es un bloqueo.
        repeat(2) { HostGate.report("https://v.example/", 403) }
        assertTrue(HostGate.isDown("v.example"))
        assertTrue(HostGate.failureFor("https://v.example/", 403, "block") is HostGate.ForbiddenException)
    }

    @Test
    fun resumeLevantaLaPausaPorBloqueos() {
        repeat(2) { HostGate.report("https://w.example/", 403) }
        assertTrue(HostGate.isDown("w.example"))
        // Al hacer el usuario la verificación se levanta la pausa (y se olvidan las pausas seguidas).
        HostGate.resume("w.example")
        assertTrue(!HostGate.isDown("w.example"))
        assertEquals(1, HostGate.call("https://w.example/") { 1 })
    }

    @Test
    fun reportCuentaBloqueosYCaidasPeroNoUn404() {
        repeat(5) { HostGate.report("https://q.example/", 404) }
        assertTrue(!HostGate.isDown("q.example"))
        HostGate.report("https://q.example/", 403)
        HostGate.report("https://q.example/", 200) // responde: se reinicia la cuenta
        HostGate.report("https://q.example/", 403)
        assertTrue(!HostGate.isDown("q.example"))
        HostGate.report("https://q.example/", 502)
        assertTrue(HostGate.isDown("q.example"))
        assertTrue(runCatching { HostGate.allow("https://q.example/x") }.exceptionOrNull() is HostGate.HostDownException)
    }

    @Test
    fun unFalloSueltoSeMuestraHastaQueVuelvaAResponder() {
        runCatching { HostGate.call("https://f.example/") { throw IOException("jkanime.net no respondió") } }
        // Un solo fallo no pausa la fuente, pero se ve en «Fuentes».
        assertTrue(!HostGate.isDown("f.example"))
        assertEquals("jkanime.net no respondió", HostGate.recentFailure("f.example")?.second)
        HostGate.call("https://f.example/") { 1 }
        assertEquals(null, HostGate.recentFailure("f.example"))
    }

    @Test
    fun elFalloSeOlvidaPasadaUnaHora() {
        var now = 1_000_000L
        HostGate.clock = { now }
        runCatching { HostGate.call("https://g.example/") { throw IOException("timeout") } }
        assertTrue(HostGate.recentFailure("g.example") != null)
        now += 61 * 60_000L
        assertEquals(null, HostGate.recentFailure("g.example"))
    }
}

// ===== JsEngineTest.kt =====
class JsEngineTest {
    @Test
    fun devuelveElResultadoComoTexto() {
        assertEquals("3", JsEngine.evaluate("1 + 2"))
    }

    @Test
    fun sinValorDevuelveNull() {
        assertNull(JsEngine.evaluate("var x = 1;"))
    }

    @Test
    fun desempaquetaCodigoPacked() {
        val packed = """(function(p,a,c,k,e,d){e=function(c){return c.toString(a)};if(!''.replace(/^/,String)){while(c--){d[c.toString(a)]=k[c]||c.toString(a)}k=[function(e){return d[e]}];e=function(){return'\\w+'};c=1};while(c--){if(k[c]){p=p.replace(new RegExp('\\b'+e(c)+'\\b','g'),k[c])}}return p}('0 1',2,2,'hola|mundo'.split('|'),0,{}))"""
        assertEquals("hola mundo", JsEngine.evaluate("function prnt() {var txt = $packed; return txt;}prnt();"))
    }

    @Test(expected = IllegalStateException::class)
    fun unBucleInfinitoSeCorta() {
        JsEngine.evaluate("while (true) {}")
    }

    @Test(expected = Exception::class)
    fun sinAccesoAClasesJava() {
        JsEngine.evaluate("java.lang.System.exit(0)")
    }

    @Test
    fun lasVariablesNoPasanDeUnaLlamadaAOtra() {
        JsEngine.evaluate("var secreto = 1;")
        assertEquals("undefined", JsEngine.evaluate("typeof secreto"))
    }

    @Test
    fun buscaClavesEnDatosDeSvelteKit() {
        val script = """
            (function() {
                var arr = [{type: "data", data: {media: [{id: 1, title: "Año"}]}, uses: {}}, null, void 0];
                return JSON.stringify(arr[0].data.media);
            })();
        """.trimIndent()
        assertEquals("""[{"id":1,"title":"Año"}]""", JsEngine.evaluate(script))
    }

    @Test
    fun `un script que se atasca dentro de Rhino no bloquea a quien llama`() {
        val previous = JsEngine.hardTimeoutMs
        JsEngine.hardTimeoutMs = 1_500
        try {
            val started = System.currentTimeMillis()
            // Deja un hilo girando hasta que acabe la JVM de pruebas (Rhino no se puede interrumpir).
            // La expresión regular retrocede sin fin y el observador de instrucciones no llega a ejecutarse.
            val failure = runCatching { JsEngine.evaluate("var s = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'; /(a+)+b/.test(s)") }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure?.message?.contains("bloqueado") == true)
            assertTrue(System.currentTimeMillis() - started < 6_000)
        } finally {
            JsEngine.hardTimeoutMs = previous
        }
    }
}

// ===== JsoupGatedTest.kt =====
/** [getGated] y [executeGated] contra un servidor HTTP local: lo que responde cada ruta y qué hace HostGate con ello. */
class JsoupGatedTest {
    private lateinit var server: HttpServer
    private var hits = 0
    private val base get() = "http://127.0.0.1:${server.address.port}"
    private val host = "127.0.0.1"

    @Before
    fun setUp() {
        HostGate.storage = null
        HostGate.reset()
        HostGate.onDown = {}
        HostGate.isNetworkUp = { true }
        HostGate.clock = System::currentTimeMillis
        hits = 0
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            reply("/ok", 200, "<html><body><p>hola</p></body></html>")
            reply("/missing", 404, "no existe")
            reply("/block", 403, "bloqueado")
            reply("/cf", 403, "<html><title>Just a moment...</title></html>", "cf-mitigated" to "challenge")
            reply("/down", 502, "caido")
            start()
        }
    }

    @After
    fun tearDown() = server.stop(0)

    private fun HttpServer.reply(path: String, code: Int, body: String, vararg headers: Pair<String, String>) {
        createContext(path) { exchange ->
            hits++
            headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    @Test
    fun `getGated devuelve la pagina y no pausa nada`() {
        assertEquals("hola", Jsoup.connect("$base/ok").getGated().select("p").text())
        assertTrue(!HostGate.isDown(host))
    }

    @Test
    fun `un 404 sigue siendo un HttpStatusException y no pausa el servidor`() {
        repeat(5) {
            val failure = runCatching { Jsoup.connect("$base/missing").getGated() }.exceptionOrNull()
            assertTrue(failure is HttpStatusException)
            assertEquals(404, (failure as HttpStatusException).statusCode)
        }
        assertTrue(!HostGate.isDown(host))
    }

    @Test
    fun `dos 403 de bloqueo seguidos pausan el servidor y despues no sale ninguna peticion`() {
        repeat(2) {
            val failure = runCatching { Jsoup.connect("$base/block").getGated() }.exceptionOrNull()
            // Sigue siendo un HttpStatusException 403 para quien lo capture.
            assertEquals(403, (failure as HttpStatusException).statusCode)
        }
        assertTrue(HostGate.isDown(host))
        val before = hits
        assertTrue(runCatching { Jsoup.connect("$base/ok").getGated() }.exceptionOrNull() is HostGate.HostDownException)
        assertEquals("en pausa no se le pide nada", before, hits)
    }

    @Test
    fun `la verificacion de Cloudflare es un 403 que no cuenta`() {
        repeat(5) {
            val failure = runCatching { Jsoup.connect("$base/cf").getGated() }.exceptionOrNull()
            assertEquals(403, (failure as HttpStatusException).statusCode)
        }
        assertTrue(!HostGate.isDown(host))
    }

    @Test
    fun `executeGated devuelve la respuesta sea cual sea y cuenta los bloqueos`() {
        val first = Jsoup.connect("$base/block").executeGated()
        assertEquals(403, first.statusCode())
        assertTrue(!HostGate.isDown(host))
        assertEquals(403, Jsoup.connect("$base/block").executeGated().statusCode())
        assertTrue(HostGate.isDown(host))
        assertTrue(runCatching { Jsoup.connect("$base/ok").executeGated() }.exceptionOrNull() is HostGate.HostDownException)
    }

    @Test
    fun `executeGated no cuenta un 404 ni la verificacion de Cloudflare`() {
        repeat(3) { assertEquals(404, Jsoup.connect("$base/missing").executeGated().statusCode()) }
        repeat(3) { assertEquals(403, Jsoup.connect("$base/cf").executeGated().statusCode()) }
        assertTrue(!HostGate.isDown(host))
    }

    @Test
    fun `dos 5xx seguidos pausan con getGated`() {
        repeat(2) { runCatching { Jsoup.connect("$base/down").getGated() } }
        assertTrue(HostGate.isDown(host))
    }
}

// ===== KaizenBackupRetentionTest.kt =====
/** Retención de las copias automáticas: una al día y fuera las de más de 7 días. */
class KaizenBackupRetentionTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    // El contenido se escribe antes de fijar la fecha: escribir la cambia.
    private fun backup(name: String, ageDays: Double, text: String = ""): File =
        folder.newFile(name).also { it.writeText(text); it.setLastModified(now - (ageDays * day).toLong()) }

    @Test
    fun `se borran las de mas de 7 dias y se conservan las demas`() {
        val fresh = backup("kaizen-backup-1.json", 0.5)
        val sixDays = backup("kaizen-backup-2.json", 6.0)
        val old = backup("kaizen-backup-3.json", 8.0)

        assertEquals(listOf(old), KaizenBackup.expired(listOf(fresh, sixDays, old), now))
    }

    @Test
    fun `nunca se borra la unica copia aunque sea vieja`() {
        val only = backup("kaizen-backup-1.json", 30.0)

        assertTrue(KaizenBackup.expired(listOf(only), now).isEmpty())
    }

    @Test
    fun `la mas reciente se conserva aunque todas sean viejas`() {
        val newest = backup("kaizen-backup-1.json", 9.0)
        val older = backup("kaizen-backup-2.json", 12.0)

        assertEquals(listOf(older), KaizenBackup.expired(listOf(newest, older), now))
    }

    @Test
    fun `la mas completa no caduca aunque sea vieja y una copia vacia reciente no la desplaza`() {
        val good = backup("kaizen-backup-1.json", 20.0, "x".repeat(1000))
        val empty = backup("kaizen-backup-2.json", 0.1, "x")
        val oldSmall = backup("kaizen-backup-3.json", 15.0, "xx")

        assertEquals(good, KaizenBackup.best(listOf(good, empty, oldSmall)))
        assertEquals(listOf(oldSmall), KaizenBackup.expired(listOf(good, empty, oldSmall), now))
    }

    @Test
    fun `tras aceptar la referencia pasa a ser la copia aceptada y las buenas posteriores no caducan`() {
        val oldBig = backup("kaizen-backup-1.json", 20.0, "x".repeat(1000))
        val accepted = backup("kaizen-backup-2.json", 10.0, "x".repeat(10))
        val later = backup("kaizen-backup-3.json", 9.0, "x".repeat(40))
        val newest = backup("kaizen-backup-4.json", 0.1, "x")
        val files = listOf(oldBig, accepted, later, newest)
        val acceptedAt = accepted.lastModified()

        // La referencia es la mayor desde la aceptada, no la antigua: una pérdida posterior vuelve a avisar.
        assertEquals(later, KaizenBackup.best(KaizenBackup.since(files, acceptedAt)))
        assertEquals(oldBig, KaizenBackup.best(files))
        // Se conservan la más reciente, la más completa de todas y la de referencia; la aceptada, ya vieja y más pequeña, caduca.
        assertEquals(listOf(accepted), KaizenBackup.expired(files, now, acceptedAt))
    }

    @Test
    fun `una aceptacion con fecha futura cuenta como ninguna`() {
        val old = backup("kaizen-backup-1.json", 5.0, "x".repeat(100))
        val recent = backup("kaizen-backup-2.json", 0.1, "x")

        assertEquals(listOf(old, recent), KaizenBackup.since(listOf(old, recent), now + day, now))
        assertEquals(listOf(recent), KaizenBackup.since(listOf(old, recent), now - day, now))
    }

    @Test
    fun `avisa si los datos son menos de la mitad de los de la mejor copia`() {
        val best = KaizenBackup.Summary(240, 5964, 10)

        assertTrue(KaizenBackup.lossMessage(2, 3, best)!!.contains("240"))
        assertTrue(KaizenBackup.lossMessage(240, 100, best) != null)
        assertEquals(null, KaizenBackup.lossMessage(240, 5967, best))
        assertEquals(null, KaizenBackup.lossMessage(0, 0, KaizenBackup.Summary(3, 10, 0)))
    }

    @Test
    fun `sin copias o con la ultima de hace casi un dia toca hacer otra`() {
        assertTrue(KaizenBackup.isDue(null, now))
        assertTrue(KaizenBackup.isDue(now - 21 * 60 * 60 * 1000, now))
        assertTrue(KaizenBackup.isDue(now - day, now))
    }

    @Test
    fun `con una copia reciente no toca`() {
        assertFalse(KaizenBackup.isDue(now - 2 * 60 * 60 * 1000, now))
        assertFalse(KaizenBackup.isDue(now - 19 * 60 * 60 * 1000, now))
    }

    @Test
    fun `una fecha en el futuro cuenta como que toca copia`() {
        assertTrue(KaizenBackup.isDue(now + 3 * day, now))
    }

    @Test
    fun `pasadas 30 copias se borran las mas antiguas aunque sean recientes`() {
        val files = (0 until 35).map { backup("kaizen-backup-$it.json", it / 100.0) }

        val removed = KaizenBackup.expired(files, now)

        assertEquals(5, removed.size)
        assertTrue(files.first() !in removed)
    }
}

// ===== KaizenBackupTest.kt =====
class KaizenBackupTest {

    private val favorite = FavoriteAV1(7, "Frieren", "frieren", "tv-anime", FavoriteAV1.CATEGORY_NONE)
    private val record = Record(101, "Frieren", 3.0, 7, "frieren", 1_700_000_000_000L)

    @Test
    fun `la copia se codifica y se lee igual`() {
        val data = KaizenBackup.Data(2, 1234L, listOf(favorite), listOf(record), mapOf("101" to "60000/1400000"))
        val back = KaizenBackup.decode(KaizenBackup.encode(data))
        assertNotNull(back)
        assertEquals(listOf(favorite), back!!.favorites)
        assertEquals(listOf(record), back.records)
        assertEquals(mapOf("101" to "60000/1400000"), back.progress)
    }

    @Test
    fun `los grupos de fichas se leen y la version 1 ya no se acepta`() {
        val v2 = KaizenBackup.decode("""{"version":2,"groups":[["animeav1:a","jkanime:a"],null,["x:y"]]}""")
        assertEquals(listOf(listOf("animeav1:a", "jkanime:a"), emptyList(), listOf("x:y")), v2!!.groups)
        assertNull(KaizenBackup.decode("""{"version":1}"""))
    }

    @Test
    fun `un fichero sin algunos campos se lee con listas vacias`() {
        val back = KaizenBackup.decode("""{"version":2}""")
        assertNotNull(back)
        assertEquals(emptyList<FavoriteAV1>(), back!!.favorites)
        assertEquals(emptyList<Record>(), back.records)
        assertEquals(emptyMap<String, String>(), back.progress)
    }

    @Test
    fun `una version futura o un texto que no es JSON no se acepta`() {
        assertNull(KaizenBackup.decode("""{"version":99,"favorites":[]}"""))
        assertNull(KaizenBackup.decode("esto no es json"))
        assertNull(KaizenBackup.decode("{}"))
    }

    @Test
    fun `los registros sin id se descartan`() {
        val data = KaizenBackup.Data(2, 0L, listOf(FavoriteAV1()), listOf(Record()), emptyMap())
        val back = KaizenBackup.decode(KaizenBackup.encode(data))!!
        assertEquals(0, back.favorites.size)
        assertEquals(0, back.records.size)
    }

    @Test
    fun `un null dentro de las listas se descarta sin fallar`() {
        val back = KaizenBackup.decode("""{"version":2,"favorites":[null],"records":[null]}""")
        assertNotNull(back)
        assertEquals(emptyList<FavoriteAV1>(), back!!.favorites)
        assertEquals(emptyList<Record>(), back.records)
    }
}

// ===== KaizenProgressTest.kt =====
class KaizenProgressTest {

    @Test
    fun `un capitulo al 95 por ciento esta terminado y no se reanuda`() {
        val progress = KaizenProgress.Progress(9_500_000, 10_000_000)
        assertTrue(progress.isFinished)
        assertFalse(progress.isInProgress)
        assertFalse(progress.isResumable)
    }

    @Test
    fun `por debajo del umbral de terminado sigue en curso`() {
        val progress = KaizenProgress.Progress(9_400_000, 10_000_000)
        assertFalse(progress.isFinished)
        assertTrue(progress.isInProgress)
        assertTrue(progress.isResumable)
    }

    @Test
    fun `a dos minutos o menos del final el capitulo esta terminado`() {
        assertTrue(KaizenProgress.Progress(1_280_000, 1_400_000).isFinished)
        assertFalse(KaizenProgress.Progress(1_279_000, 1_400_000).isFinished)
        assertFalse(KaizenProgress.Progress(1_280_000, 1_400_000).isInProgress)
        assertFalse(KaizenProgress.Progress(1_280_000, 1_400_000).isResumable)
    }

    @Test
    fun `salir al segundo de empezar ya sale en seguir viendo pero no se ofrece reanudar hasta los 30 segundos`() {
        assertFalse(KaizenProgress.Progress(999, 1_000_000).isInProgress)
        assertTrue(KaizenProgress.Progress(1_000, 1_000_000).isInProgress)
        assertFalse(KaizenProgress.Progress(29_999, 1_000_000).isResumable)
        assertTrue(KaizenProgress.Progress(30_000, 1_000_000).isResumable)
    }

    @Test
    fun `salir pronto conserva el progreso anterior en vez de pisarlo`() {
        val duration = 24 * 60_000L
        val previous = KaizenProgress.Progress(18 * 60_000L, duration)
        assertEquals(KaizenProgress.SaveAction.KEEP, KaizenProgress.planSave(previous, KaizenProgress.Progress(1_000, duration)))
        assertEquals(KaizenProgress.SaveAction.KEEP, KaizenProgress.planSave(previous, KaizenProgress.Progress(20_000, duration)))
        assertEquals(KaizenProgress.SaveAction.STORE, KaizenProgress.planSave(previous, KaizenProgress.Progress(5 * 60_000L, duration)))
    }

    @Test
    fun `un capitulo nuevo dejado al segundo se guarda y uno sin empezar no`() {
        val duration = 24 * 60_000L
        assertEquals(KaizenProgress.SaveAction.STORE, KaizenProgress.planSave(null, KaizenProgress.Progress(1_200, duration)))
        assertEquals(KaizenProgress.SaveAction.KEEP, KaizenProgress.planSave(null, KaizenProgress.Progress(0, duration)))
        // Un progreso previo que aun no era reanudable (menos de 30 s) si se actualiza.
        assertEquals(KaizenProgress.SaveAction.STORE, KaizenProgress.planSave(KaizenProgress.Progress(2_000, duration), KaizenProgress.Progress(10_000, duration)))
    }

    @Test
    fun `una posicion sin duracion conocida no pisa el progreso guardado`() {
        val previous = KaizenProgress.Progress(18 * 60_000L, 24 * 60_000L)
        assertEquals(KaizenProgress.SaveAction.KEEP, KaizenProgress.planSave(previous, KaizenProgress.Progress(18 * 60_000L, -9_223_372_036_854_775_807L)))
    }

    @Test
    fun `un capitulo terminado se borra del progreso`() {
        val duration = 24 * 60_000L
        assertEquals(KaizenProgress.SaveAction.REMOVE, KaizenProgress.planSave(KaizenProgress.Progress(10 * 60_000L, duration), KaizenProgress.Progress(duration - 60_000L, duration)))
    }

    @Test
    fun `con duracion desconocida no esta en curso pero se puede reanudar`() {
        val progress = KaizenProgress.Progress(120_000, 0)
        assertEquals(0.0, progress.fraction, 0.0)
        assertFalse(progress.isFinished)
        assertFalse(progress.isInProgress)
        assertTrue(progress.isResumable)
    }

    @Test
    fun `formatTime muestra minutos o horas segun la duracion`() {
        assertEquals("0:00", KaizenProgress.formatTime(0))
        assertEquals("0:59", KaizenProgress.formatTime(59_999))
        assertEquals("1:00", KaizenProgress.formatTime(60_000))
        assertEquals("1:00:00", KaizenProgress.formatTime(3_600_000))
        assertEquals("1:02:03", KaizenProgress.formatTime(3_723_000))
    }

    @Test
    fun `la clave de un capitulo es serie y numero y se lee de vuelta`() {
        val key = KaizenProgress.Key(42L, 12.0)
        assertEquals("s:42#12", key.text)
        assertEquals(key, KaizenProgress.Key.parse(key.text))
        val half = KaizenProgress.Key(7L, 12.5)
        assertEquals("s:7#12.5", half.text)
        assertEquals(half, KaizenProgress.Key.parse(half.text))
    }

    @Test
    fun `lo que no es una clave de serie no se lee y una sin serie no es valida`() {
        assertEquals(null, KaizenProgress.Key.parse("123456"))
        assertEquals(null, KaizenProgress.Key.parse("p:fuente:slug#3"))
        assertEquals(null, KaizenProgress.Key.parse("s:sin-numero"))
        assertEquals(null, KaizenProgress.Key.parse("s:abc#3"))
        assertFalse(KaizenProgress.Key(0L, 1.0).isValid)
        assertFalse(KaizenProgress.Key(5L, -1.0).isValid)
        assertTrue(KaizenProgress.Key(5L, 0.0).isValid)
    }
}

// ===== RecentsOrderTest.kt =====
class RecentsOrderTest {
    // (nombre, released, addedAt, claves del episodio: serie|número y slug|número)
    private data class Item(val name: String, val released: Long, val added: Long, val keys: List<String>)

    private fun item(name: String, released: Long, added: Long, vararg keys: String) = Item(name, released, added, keys.toList())

    private fun order(vararg items: Item) = newestFirst(items.toList(), { it.released }, { it.added }, { it.keys }).map { it.name }

    @Test
    fun `con todas las fechas conocidas ordena por fecha y es estable`() {
        assertEquals(listOf("c", "a", "b"), order(item("a", 500, 0, "a|1"), item("b", 500, 0, "b|1"), item("c", 900, 0, "c|1")))
    }

    @Test
    fun `sin ninguna fecha ordena por cuando se vio y es estable`() {
        assertEquals(listOf("b", "a", "c"), order(item("a", 0, 100, "a|1"), item("b", 0, 300, "b|1"), item("c", 0, 100, "c|1")))
    }

    @Test
    fun `una serie distinta sin fecha sigue ordenando por cuando se vio`() {
        // Sin relación con el episodio con fecha no hay nada que heredar: es el comportamiento de siempre.
        assertEquals(listOf("flv", "av1"), order(item("av1", 500, 0, "a|1"), item("flv", 0, 1000, "b|1")))
    }

    @Test
    fun `el mismo episodio sin fecha hereda la de otra fuente y pierde el empate`() {
        // Mismo episodio: av1 con fecha 500, flv sin fecha pero leído ahora. Con la fecha heredada empatan y gana el que tiene fecha.
        assertEquals(listOf("av1", "flv"), order(item("flv", 0, 1000, "a|1"), item("av1", 500, 0, "a|1")))
    }

    @Test
    fun `la fecha se hereda tambien por el slug aunque el titulo de la otra fuente sea distinto`() {
        // Títulos distintos (otra clave de serie) pero mismo slug sin sufijos y número.
        assertEquals(listOf("av1", "flv"), order(item("flv", 0, 1000, "t:flv|1", "s:x|1"), item("av1", 500, 0, "t:av1|1", "s:x|1")))
    }

    @Test
    fun `con fecha heredada ordena por ella respecto a otras series`() {
        assertEquals(listOf("otra", "av1", "flv"), order(item("flv", 0, 9999, "a|1"), item("av1", 500, 0, "a|1"), item("otra", 700, 0, "b|1")))
    }

    @Test
    fun `no falla aunque las claves de identidad cambien mientras se ordena`() {
        // El id de serie puede cargarse en otro hilo en mitad de la ordenación (las claves salen de él): la fecha heredada de una entrada
        // sin fecha no puede cambiar a medias, o la ordenación lanza «Comparison method violates its general contract!».
        var calls = 0
        val items = (1..300).map { item("n$it", if (it % 3 == 0) 0L else (it * 7L) % 100 + 1, it.toLong(), "s${it % 20}|1") }
        val unstable = { item: Item -> calls++; if (calls % 2 == 0) item.keys else listOf("s${(calls * 31) % 20}|1") }

        val sorted = newestFirst(items, { it.released }, { it.added }, unstable)

        assertEquals(items.size, sorted.size)
        // Cada entrada pregunta por su identidad una sola vez: es lo que impide que cambie a mitad de la ordenación.
        assertEquals(items.size, calls)
        assertEquals(items.map { it.name }.toSet(), sorted.map { it.name }.toSet())
    }
}

// ===== UiScaleTest.kt =====
/** La interfaz se adapta a pantallas de otra densidad sin tocar las de 1080p a 320 ppp. */
class UiScaleTest {
    @Test
    fun `una tele de 1080p a 320 ppp no cambia (tvbox y TV)`() {
        assertEquals(1f, UiScale.autoFactor(1920, 320), 0f)
    }

    @Test
    fun `una de 4K a 320 ppp, que da el doble de dp, se duplica`() {
        assertEquals(2f, UiScale.autoFactor(3840, 320), 0.001f)
    }

    @Test
    fun `una de 4K a 640 ppp o de 720p a 213 ppp tampoco cambia`() {
        assertEquals(1f, UiScale.autoFactor(3840, 640), 0f)
        assertEquals(1f, UiScale.autoFactor(1280, 213), 0f)
    }

    @Test
    fun `una pantalla pequena se reduce hasta el minimo`() {
        assertEquals(0.67f, UiScale.autoFactor(800, 320), 0.001f)
        assertEquals(0.75f, UiScale.autoFactor(1440, 320), 0.01f)
    }

    @Test
    fun `se limita el aumento y los datos invalidos no cambian nada`() {
        assertEquals(2f, UiScale.autoFactor(20000, 160), 0f)
        assertEquals(1f, UiScale.autoFactor(0, 320), 0f)
        assertEquals(1f, UiScale.autoFactor(1920, 0), 0f)
    }
}
