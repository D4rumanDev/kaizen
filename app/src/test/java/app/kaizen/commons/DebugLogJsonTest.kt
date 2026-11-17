package app.kaizen.commons

import app.kaizen.commons.AppErrors.Area
import app.kaizen.domain.ConnectionStats.Cause
import java.io.File
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

/** El registro en JSON Lines: ida y vuelta, compatibilidad con el texto antiguo, recorte por líneas y qué pasa si el disco falla. */
class DebugLogJsonTest {
    @get:Rule val folder = TemporaryFolder()

    private var failures = mutableListOf<String>()

    @Before
    fun setUp() {
        DebugLog.dirProvider = { folder.root }
        DebugLog.levelProvider = { DebugLog.DETAIL }
        DebugLog.onDiskFailure = { failures += it }
        DebugLog.clear()
    }

    @After
    fun tearDown() {
        DebugLog.clear()
        DebugLog.maxBytes = 512 * 1024
        DebugLog.onDiskFailure = null
        DebugLog.dirProvider = { null }
        DebugLog.levelProvider = { 0 }
    }

    @Test
    fun `un error con origen y rastro sale igual que entra`() {
        val fields = DebugLog.Fields(code = "E06", cause = "no-existe", origin = "La fuente", context = "cdn.x.com")
        DebugLog.error("Carátulas", "E06 No encontrado (404) (cdn.x.com): HttpException\nOrigen: La fuente · Qué hacer: reabrir", RuntimeException("boom"), fields)
        val entry = DebugLog.parse(DebugLog.read()).single()
        assertEquals(DebugLog.ERROR, entry.level)
        assertEquals("Carátulas", entry.area)
        assertEquals("E06 No encontrado (404) (cdn.x.com): HttpException", entry.message)
        assertEquals("Origen: La fuente · Qué hacer: reabrir", entry.details.first())
        assertTrue(entry.details.any { it.contains("java.lang.RuntimeException: boom") })
        assertEquals("E06", entry.code)
        assertEquals("no-existe", entry.cause)
        assertEquals("cdn.x.com", entry.context)
    }

    @Test
    fun `cada anotacion es una sola linea aunque el mensaje tenga saltos`() {
        DebugLog.error("Datos", "uno\ndos\ntres", null)
        DebugLog.info("Datos", "otra")
        assertEquals(2, DebugLog.read().size)
        assertTrue(DebugLog.read().all { it.startsWith("{") && it.endsWith("}") })
    }

    @Test
    fun `una linea cortada a medias se ignora y la de texto antiguo se sigue entendiendo`() {
        val lines = listOf(
            "2026-10-29 20:05:45 I/Series: Libro de series copiado",
            """{"t":"2026-11-12 10:00:00","l":"I","a":"Datos","m":"bien"}""",
            """{"t":"2026-11-12 10:00:01","l":"E","a":"Dat"""
        )
        val entries = DebugLog.parse(lines)
        assertEquals(listOf("Libro de series copiado", "bien"), entries.map { it.message })
        assertEquals("Libro de series", entries[0].area)
    }

    @Test
    fun `lo que ya habia en el debug log de texto se lee antes de lo nuevo y clear borra los dos`() {
        File(folder.root, "debug.log").writeText("2026-10-29 20:05:45 I/Series: antiguo\n")
        DebugLog.info("Datos", "nuevo")
        assertEquals(listOf("antiguo", "nuevo"), DebugLog.parse(DebugLog.read()).map { it.message })
        DebugLog.clear()
        assertTrue(DebugLog.read().isEmpty())
        assertFalse(File(folder.root, "debug.log").exists())
    }

    @Test
    fun `al pasar del tope se queda con las lineas mas recientes y todas enteras`() {
        DebugLog.maxBytes = 2_000
        repeat(60) { DebugLog.info("Datos", "linea numero $it con relleno suficiente para ocupar sitio") }
        val lines = DebugLog.read()
        assertTrue(lines.size < 60)
        assertTrue(lines.all { it.startsWith("{") && it.endsWith("}") })
        assertTrue(lines.last().contains("numero 59"))
        assertTrue(DebugLog.file.length() <= 2_000 + 200)
    }

    @Test
    fun `ni el mensaje ni el rastro llevan direcciones completas`() {
        val cause = org.jsoup.HttpStatusException("HTTP error fetching URL", 403, "https://voe.sx/e/abc?key=SECRETO")
        DebugLog.error("Fuentes y servidores", "fallo en https://cdn.x.com/v/a.m3u8?token=SECRETO", RuntimeException("envoltorio", cause))
        val raw = DebugLog.read().joinToString("\n")
        assertFalse(raw, raw.contains("SECRETO"))
        assertTrue(raw.contains("https://voe.sx") && raw.contains("https://cdn.x.com"))
    }

    @Test
    fun `un mismo fallo repetido en 30 segundos cuenta pero se escribe una vez`() {
        var now = 1_000_000L
        AppErrors.clock = { now }
        AppErrors.clear()
        AppErrors.record(Cause.IMAGE, Area.IMAGE, "no carga", "cdn.x.com")
        AppErrors.record(Cause.IMAGE, Area.IMAGE, "no carga", "cdn.x.com")
        AppErrors.record(Cause.IMAGE, Area.IMAGE, "no carga", "otro.cdn.com")
        assertEquals(3, AppErrors.recent().size)
        assertEquals(2, DebugLog.read().size)
        now += 31_000
        AppErrors.record(Cause.IMAGE, Area.IMAGE, "no carga", "cdn.x.com")
        assertEquals(4, AppErrors.recent().size)
        assertEquals(3, DebugLog.read().size)
        AppErrors.clock = System::currentTimeMillis
        AppErrors.clear()
    }

    @Test
    fun `el nivel apagado no escribe nada`() {
        DebugLog.levelProvider = { 0 }
        DebugLog.error("Datos", "x")
        assertTrue(DebugLog.read().isEmpty())
    }

    @Test
    fun `si el disco falla las lineas esperan en memoria, se avisa una vez y al volver se vuelcan en orden`() {
        val notDir = folder.newFile("no-es-carpeta")
        DebugLog.dirProvider = { File(notDir, "sub") }
        DebugLog.info("Datos", "uno")
        DebugLog.info("Datos", "dos")
        // Se sigue pudiendo leer lo que no llegó al disco, y el aviso fue uno solo.
        assertEquals(listOf("uno", "dos"), DebugLog.parse(DebugLog.read()).map { it.message })
        assertEquals(1, failures.size)
        assertNotNull(DebugLog.health().diskProblem)
        assertEquals(2, DebugLog.health().waiting)
        assertTrue(DebugLog.healthLine().contains("NO SE PUEDE ESCRIBIR"))
        // El disco vuelve: lo que esperaba se escribe antes que lo nuevo y el problema desaparece.
        DebugLog.dirProvider = { folder.root }
        DebugLog.info("Datos", "tres")
        assertEquals(listOf("uno", "dos", "tres"), DebugLog.parse(DebugLog.read()).map { it.message })
        assertNull(DebugLog.health().diskProblem)
        assertEquals(0, DebugLog.health().waiting)
        assertEquals(3, DebugLog.file.readLines().size)
    }

    @Test
    fun `el registro de texto antiguo tambien se recorta y no se escribe mas`() {
        DebugLog.maxBytes = 2_000
        val old = File(folder.root, "debug.log")
        old.writeText((1..200).joinToString("") { "2026-10-29 20:05:45 I/Series: linea antigua numero $it con relleno\n" })
        assertTrue(old.length() > 2_000)
        DebugLog.info("Datos", "nuevo")
        assertTrue("sigue con ${old.length()} bytes", old.length() <= 2_000)
        assertTrue(old.readText().lines().filter { it.isNotBlank() }.all { it.startsWith("2026-10-29") })
        assertTrue(old.readText().contains("numero 200"))
    }

    @Test
    fun `la cola que espera se vuelca en una sola escritura sin duplicar lineas`() {
        val notDir = folder.newFile("no-es-carpeta")
        DebugLog.dirProvider = { File(notDir, "sub") }
        repeat(5) { DebugLog.info("Datos", "espera$it") }
        DebugLog.dirProvider = { folder.root }
        DebugLog.info("Datos", "ahora")
        // Dos lecturas seguidas dan lo mismo y cada línea sale una sola vez.
        val messages = DebugLog.parse(DebugLog.read()).map { it.message }
        assertEquals(listOf("espera0", "espera1", "espera2", "espera3", "espera4", "ahora"), messages)
        assertEquals(messages, DebugLog.parse(DebugLog.read()).map { it.message })
        assertEquals(6, DebugLog.file.readLines().size)
    }

    @Test
    fun `la cola en memoria tiene tope`() {
        val notDir = folder.newFile("no-es-carpeta")
        DebugLog.dirProvider = { File(notDir, "sub") }
        repeat(500) { DebugLog.info("Datos", "n$it") }
        assertEquals(300, DebugLog.health().waiting)
        assertEquals("n499", DebugLog.parse(DebugLog.read()).last().message)
    }

    @Test
    fun `sin carpeta para el registro tampoco se rompe nada`() {
        DebugLog.dirProvider = { null }
        DebugLog.error("Datos", "x")
        assertEquals(1, DebugLog.read().size)
        assertEquals(1, failures.size)
    }

    @Test
    fun `el gestor de errores deduce la parte de la app de la clase donde se produjo`() {
        assertEquals(Area.CACHE, AppErrors.areaOfClass("app.kaizen.source.ApiCache"))
        assertEquals(Area.CACHE, AppErrors.areaOfClass("app.kaizen.commons.CacheCleaner"))
        assertEquals(Area.DATABASE, AppErrors.areaOfClass("app.kaizen.database.UserDB"))
        assertEquals(Area.PLAYER, AppErrors.areaOfClass("app.kaizen.tv.exoplayer.PlaybackController"))
        assertEquals(Area.SOURCE, AppErrors.areaOfClass("app.kaizen.videoservers.Server"))
        assertEquals(Area.SOURCE, AppErrors.areaOfClass("app.kaizen.commons.HostGate"))
        assertEquals(Area.STORAGE, AppErrors.areaOfClass("app.kaizen.commons.BackupManager"))
        assertEquals(Area.APP, AppErrors.areaOfClass("app.kaizen.tv.ui.StatsPage"))
    }

    @Test
    fun `placeOf dice la clase y el metodo donde se produjo`() {
        val place = AppErrors.placeOf(RuntimeException("x"))
        assertNotNull(place)
        assertTrue(place!!.frame.startsWith("DebugLogJsonTest."))
    }

    @Test
    fun `un fallo de programacion en codigo de la cache es interno y no una cache corrupta`() {
        assertEquals(Cause.INTERNAL, AppErrors.classify(NullPointerException(), Area.CACHE, online = true))
        assertEquals(Cause.CACHE, AppErrors.classify(java.io.IOException("journal"), Area.CACHE, online = true))
    }
}
