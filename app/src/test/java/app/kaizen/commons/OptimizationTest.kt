package app.kaizen.commons

import app.kaizen.source.foldTitle
import app.kaizen.source.longestWord
import app.kaizen.source.relativeDate
import app.kaizen.tv.ui.components.shortChapter
import com.google.gson.JsonParser
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test

/**
 * Pruebas de caracterización de la optimización del 20-nov-2026: cada función optimizada debe dar la misma salida que la versión anterior,
 * que se conserva aquí como referencia (`old…`). Las cifras de antes y después salen de [medida].
 */
class OptimizationTest {
    private val titles = listOf(
        "Magical Explorer", "  Shin   Tennis no Ouji-sama! ", "Kashita mary", "K", "Re:Zero kara Hajimeru", "Hyōken\tno Maria", "", "   ", "!!!",
        "Tōkyō ★ Ghoul 2nd Season", "Cap. 12", "Ñandú 3ra temporada", "ＦＵＬＬ ｗｉｄｔｈ", "a b c d e f",
    )

    // ---- referencias: el código tal como era antes de la optimización
    private fun oldLongestWord(text: String) =
        Regex("[\\p{L}\\p{N}\\p{Mn}]+").findAll(text).map { it.value }.fold("") { best, word -> if (word.length > best.length) word else best }
    private fun oldShortChapter(text: String): String =
        Regex("^(?:Episodio|Ep\\.?|Capítulo|Cap\\.?)\\s*(\\d+(?:[.,]\\d+)?)\\s*$", RegexOption.IGNORE_CASE).find(text.trim())?.let { "E${it.groupValues[1]}" } ?: text

    @Test
    fun longestWordDaLoMismoQueAntes() = titles.forEach { assertEquals(it, oldLongestWord(it), longestWord(it)) }

    @Test
    fun shortChapterDaLoMismoQueAntes() {
        listOf("Episodio 5", "Ep. 12", "Ep12", "Cap 3,5", "capítulo 7", "CAPÍTULO 10.5", " Episodio 5 ", "Episodio 5 final", "Película", "", "Ep.", "Episodio").forEach {
            assertEquals(it, oldShortChapter(it), shortChapter(it))
        }
        assertEquals("E5", shortChapter("Episodio 5"))
        assertEquals("Película", shortChapter("Película"))
    }

    @Test
    fun relativeDateConservaLosCasosConocidos() {
        val now = 1_700_000_000_000L
        assertEquals(now - 5 * 60_000L, relativeDate("hace 5 minutos", now))
        assertEquals(now - 2 * 3_600_000L, relativeDate("Hace 2 horas", now))
        assertEquals(0L, relativeDate("sin fecha", now))
        // «Miércoles 07»: día del mes a las 12:00 (mismo mes o el anterior, según el día actual).
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = relativeDate("Miércoles 07", now) }
        assertEquals(7, calendar.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(12, calendar.get(java.util.Calendar.HOUR_OF_DAY))
    }

    @Test
    fun debugLogEscribeLaMismaFechaQueAntes() {
        val saved = TimeZone.getDefault()
        try {
            listOf("UTC", "Europe/Madrid", "America/Los_Angeles").forEach { zone ->
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                listOf(0L, 1_700_000_000_123L, 1_732_000_000_999L).forEach { now ->
                    val expected = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)
                    val line = DebugLog.encode("I", "tag", "mensaje", null, now)
                    assertEquals("$zone $now", expected, JsonParser.parseString(line).asJsonObject.get("t").asString)
                }
            }
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    @Test
    fun conversoresDeCabecerasYRelacionesDanLoMismoQueAntes() {
        val headers = app.kaizen.videoservers.Headers { add("User-Agent" to "x"); add("Referer" to "https://a.example/") }
        val converter = app.kaizen.database.BaseConverter()
        val type = object : com.google.gson.reflect.TypeToken<app.kaizen.videoservers.Headers>() {}.type
        val oldJson = com.google.gson.Gson().toJson(headers, type)
        assertEquals(oldJson, converter.headersToString(headers))
        assertEquals(oldJson, converter.headersToString(converter.stringToHeader(oldJson)))
        assertEquals("null", converter.headersToString(null))
        val series = app.kaizen.pojos.av1.DirectoryConverter()
        assertEquals("[]", series.relationsToString(null))
        assertEquals(emptyList<Any>(), series.stringToRelations("[]"))
    }

    // ---- cifras de antes y después (no afirman nada: se leen en el informe de la prueba)
    @Ignore("Informe de medidas, no una prueba: quitar @Ignore para ejecutarlo (-i en gradle muestra las líneas MEDIDA).")
    @Test
    fun medida() {
        fun time(label: String, repeat: Int, block: () -> Unit) {
            repeat(repeat / 4) { block() } // calentar
            val start = System.nanoTime()
            repeat(repeat) { block() }
            println("MEDIDA $label: ${(System.nanoTime() - start) / repeat} ns/llamada")
        }
        val title = "  Shin   Tennis no Ouji-sama! "
        time("DebugLog.encode", 20_000) { DebugLog.encode("I", "tag", "mensaje", null, 1_700_000_000_123L) }
        time("shortChapter", 200_000) { shortChapter("Episodio 5") }
        time("longestWord", 200_000) { longestWord(title) }
        val headers = app.kaizen.videoservers.Headers { add("User-Agent" to "x"); add("Referer" to "https://a.example/") }
        val converter = app.kaizen.database.BaseConverter()
        val json = converter.headersToString(headers)
        time("Headers->JSON", 50_000) { converter.headersToString(headers) }
        time("JSON->Headers", 50_000) { converter.stringToHeader(json) }
        time("relativeDate", 100_000) { relativeDate("hace 5 minutos", 1_700_000_000_000L) }
    }
}
