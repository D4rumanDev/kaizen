package app.kaizen.domain

import app.kaizen.domain.ConnectionStats.Kind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.TimeZone

class ConnectionStatsTest {
    private class MemoryStorage : ConnectionStats.Storage {
        val data = linkedMapOf<String, String>()
        override fun all(): Map<String, String> = data.toMap()
        override fun put(key: String, value: String) { data[key] = value }
        override fun remove(keys: Collection<String>) { keys.forEach { data.remove(it) } }
    }

    private val memory = MemoryStorage()
    private var now = LocalDate.of(2026, 11, 11).toEpochDay() * 86_400_000L + 12 * 3_600_000L

    @Before
    fun setUp() {
        ConnectionStats.storage = memory
        ConnectionStats.zone = { TimeZone.getTimeZone("UTC") }
        ConnectionStats.clock = { now }
    }

    private val originalStorage = ConnectionStats.storage

    @After
    fun tearDown() {
        ConnectionStats.storage = originalStorage
        ConnectionStats.clock = System::currentTimeMillis
        ConnectionStats.zone = { TimeZone.getDefault() }
    }

    private fun day(date: LocalDate) = date.toEpochDay()

    @Test
    fun `el 11 de noviembre de 2026 es miercoles y el 1 de enero de 1970 jueves`() {
        assertEquals(2, ConnectionStats.weekdayOf(day(LocalDate.of(2026, 11, 11))))
        assertEquals(3, ConnectionStats.weekdayOf(0))
        assertEquals(0, ConnectionStats.weekdayOf(day(LocalDate.of(2026, 11, 9))))
    }

    @Test
    fun `cada intento suma al dia y al nombre de su tipo y separa fuentes de servidores`() {
        ConnectionStats.record(Kind.SOURCE, "AnimeFLV", true)
        ConnectionStats.record(Kind.SOURCE, "AnimeFLV", false)
        ConnectionStats.record(Kind.SERVER, "AnimeFLV", false)
        val rows = ConnectionStats.rows()
        assertEquals(2, rows.size)
        val source = rows.single { it.kind == Kind.SOURCE }
        assertEquals(1, source.ok)
        assertEquals(1, source.fail)
        assertEquals(0, rows.single { it.kind == Kind.SERVER }.ok)
    }

    @Test
    fun `los dias de hace mas de 90 se descartan y no se leen`() {
        ConnectionStats.record(Kind.SERVER, "Voe", false)
        now += 91 * 86_400_000L
        ConnectionStats.record(Kind.SERVER, "Voe", true)
        assertEquals(1, ConnectionStats.rows().size)
        assertEquals(1, memory.data.size)
    }

    @Test
    fun `perDay da todos los dias aunque no haya intentos y el ultimo es hoy`() {
        ConnectionStats.record(Kind.SOURCE, "A", false)
        val today = ConnectionStats.dayOf(now)
        val days = ConnectionStats.perDay(ConnectionStats.rows(), Kind.SOURCE, today, 7)
        assertEquals(7, days.size)
        assertEquals(today, days.last().day)
        assertEquals(1, days.last().fail)
        assertEquals(0, days.first().total)
    }

    @Test
    fun `perWeekday agrupa por dia de la semana y siempre da siete`() {
        val wednesday = day(LocalDate.of(2026, 11, 11))
        val rows = listOf(
            ConnectionStats.Row(Kind.SOURCE, wednesday, "A", 3, 1),
            ConnectionStats.Row(Kind.SOURCE, wednesday - 7, "A", 1, 1),
            ConnectionStats.Row(Kind.SERVER, wednesday, "B", 9, 9)
        )
        val byWeekday = ConnectionStats.perWeekday(rows, Kind.SOURCE)
        assertEquals(7, byWeekday.size)
        assertEquals(ConnectionStats.Total("miércoles", 2, 6), byWeekday[2])
        assertEquals(0, byWeekday[0].total)
    }

    @Test
    fun `ranked ordena por porcentaje sobre el total y no pone 3 de 3 por delante de 90 de 100`() {
        val d = day(LocalDate.of(2026, 11, 11))
        val rows = listOf(
            ConnectionStats.Row(Kind.SERVER, d, "pocos", 0, 3),
            ConnectionStats.Row(Kind.SERVER, d, "muchos", 10, 90),
            ConnectionStats.Row(Kind.SERVER, d, "bueno", 20, 0),
            ConnectionStats.Row(Kind.SERVER, d, "uno", 0, 1)
        )
        assertEquals(listOf("muchos", "pocos", "bueno", "uno"), ConnectionStats.ranked(rows, Kind.SERVER).map { it.name })
    }

    @Test
    fun `ranked dice el peor dia de la semana solo con base suficiente`() {
        val wednesday = day(LocalDate.of(2026, 11, 11))
        val rows = listOf(
            ConnectionStats.Row(Kind.SERVER, wednesday, "Voe", 1, 4),       // miércoles: 4 de 5
            ConnectionStats.Row(Kind.SERVER, wednesday + 1, "Voe", 5, 0),   // jueves: 0 de 5
            ConnectionStats.Row(Kind.SERVER, wednesday + 2, "Voe", 0, 1),   // viernes: 1 de 1, sin base
            ConnectionStats.Row(Kind.SERVER, wednesday, "Limpio", 9, 0)
        )
        val ranked = ConnectionStats.ranked(rows, Kind.SERVER)
        assertEquals(2, ranked.single { it.name == "Voe" }.worstWeekday)
        assertNull(ranked.single { it.name == "Limpio" }.worstWeekday)
    }

    @Test
    fun `un nombre con barra vertical se conserva y el dia exacto de 90 se descarta`() {
        ConnectionStats.record(Kind.SERVER, "Raro|Nombre", false)
        assertEquals("Raro|Nombre", ConnectionStats.rows().single().name)
        now += 90 * 86_400_000L
        ConnectionStats.record(Kind.SERVER, "Otro", true)
        // El día de hace exactamente 90 días ya no se guarda: solo queda el nuevo.
        assertEquals(listOf("Otro"), ConnectionStats.rows().map { it.name })
        assertEquals(1, memory.data.size)
    }

    @Test
    fun `lo guardado se lee una vez pero un almacen nuevo se vuelve a leer`() {
        ConnectionStats.record(Kind.SOURCE, "A", true)
        val other = MemoryStorage().also { it.put("S|${ConnectionStats.dayOf(now)}|B", "2;1") }
        ConnectionStats.storage = other
        assertEquals(listOf("B"), ConnectionStats.rows().map { it.name })
        ConnectionStats.record(Kind.SOURCE, "B", false)
        assertEquals("2;2", other.data.values.single())
    }

    @Test
    fun `cada excepcion de conexion se clasifica en su causa`() {
        val cause = ConnectionStats::causeOf
        assertEquals(ConnectionStats.Cause.DNS, cause(java.net.UnknownHostException("x")))
        assertEquals(ConnectionStats.Cause.TIMEOUT, cause(java.net.SocketTimeoutException("x")))
        assertEquals(ConnectionStats.Cause.CONNECTION, cause(java.net.ConnectException("x")))
        assertEquals(ConnectionStats.Cause.CONNECTION, cause(java.net.SocketException("Connection reset")))
        assertEquals(ConnectionStats.Cause.TLS, cause(javax.net.ssl.SSLHandshakeException("x")))
        assertEquals(ConnectionStats.Cause.BLOCKED, cause(org.jsoup.HttpStatusException("x", 403, "u")))
        assertEquals(ConnectionStats.Cause.NOT_FOUND, cause(org.jsoup.HttpStatusException("x", 404, "u")))
        assertEquals(ConnectionStats.Cause.REJECTED, cause(org.jsoup.HttpStatusException("x", 405, "u")))
        assertEquals(ConnectionStats.Cause.SERVER_ERROR, cause(org.jsoup.HttpStatusException("x", 502, "u")))
        assertEquals(ConnectionStats.Cause.EXTRACTOR, cause(org.json.JSONException("Value <!doctype")))
        assertEquals(ConnectionStats.Cause.EXTRACTOR, cause(IllegalStateException("No successful match so far")))
        assertEquals(ConnectionStats.Cause.OTHER, cause(java.io.IOException("otra cosa")))
        assertEquals(ConnectionStats.Cause.OTHER, cause(kotlinx.coroutines.CancellationException("cancelado")))
        assertNull(cause(null))
    }

    @Test
    fun `un fallo con causa se cuenta aparte por dia y nombre y los aciertos no llevan causa`() {
        ConnectionStats.record(Kind.SERVER, "Voe", false, ConnectionStats.Cause.DNS)
        ConnectionStats.record(Kind.SERVER, "Voe", false, ConnectionStats.Cause.DNS)
        ConnectionStats.record(Kind.SERVER, "Voe", false, ConnectionStats.Cause.TIMEOUT)
        ConnectionStats.record(Kind.SERVER, "Voe", true, ConnectionStats.Cause.DNS)
        ConnectionStats.record(Kind.SERVER, "Raro|Nombre", false, ConnectionStats.Cause.NO_VIDEO)
        val errors = ConnectionStats.errorRows()
        assertEquals(3, errors.size)
        assertEquals(listOf(ConnectionStats.Cause.DNS to 2, ConnectionStats.Cause.TIMEOUT to 1, ConnectionStats.Cause.NO_VIDEO to 1), ConnectionStats.causes(errors, Kind.SERVER))
        assertEquals(ConnectionStats.Cause.DNS, ConnectionStats.mainCause(errors, Kind.SERVER, "Voe"))
        assertEquals(ConnectionStats.Cause.NO_VIDEO, ConnectionStats.mainCause(errors, Kind.SERVER, "Raro|Nombre"))
        assertNull(ConnectionStats.mainCause(errors, Kind.SERVER, "Nadie"))
        // Los intentos siguen contando todos (4 de Voe: 3 fallos y 1 acierto), sin mezclarse con las causas.
        assertEquals(ConnectionStats.Total("Voe", 3, 4, null), ConnectionStats.ranked(ConnectionStats.rows(), Kind.SERVER).single { it.name == "Voe" }.copy(worstWeekday = null))
    }

    @Test
    fun `los enlaces de una fuente se cuentan aparte de los servidores y de las fuentes`() {
        ConnectionStats.record(Kind.LINK, "jkanime", false, ConnectionStats.Cause.NO_VIDEO)
        ConnectionStats.record(Kind.SERVER, "jkanime", true)
        ConnectionStats.record(Kind.SOURCE, "jkanime", true)
        val rows = ConnectionStats.rows()
        assertEquals(1, ConnectionStats.ranked(rows, Kind.LINK).size)
        assertEquals(1, ConnectionStats.ranked(rows, Kind.LINK).single().fail)
        assertEquals(0, ConnectionStats.ranked(rows, Kind.SERVER).single().fail)
        assertEquals(emptyList<ConnectionStats.Cause>(), ConnectionStats.causes(ConnectionStats.errorRows(), Kind.SOURCE).map { it.first })
    }

    @Test
    fun `las causas de hace mas de 90 dias se descartan`() {
        ConnectionStats.record(Kind.SERVER, "Voe", false, ConnectionStats.Cause.DNS)
        now += 91 * 86_400_000L
        ConnectionStats.record(Kind.SERVER, "Voe", false, ConnectionStats.Cause.TIMEOUT)
        assertEquals(listOf(ConnectionStats.Cause.TIMEOUT), ConnectionStats.errorRows().map { it.cause })
    }

    @Test
    fun `reset borra los contadores`() {
        ConnectionStats.record(Kind.SERVER, "Voe", false)
        ConnectionStats.reset()
        assertTrue(ConnectionStats.rows().isEmpty())
    }
}
