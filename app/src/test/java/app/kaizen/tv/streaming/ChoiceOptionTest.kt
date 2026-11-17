package app.kaizen.tv.streaming

import org.junit.Assert.assertEquals
import org.junit.Test

class ChoiceOptionTest {
    @Test
    fun losGruposConservanSuOrdenYLosAvisosVanAlFinalDeCadaUno() {
        val options = listOf(
            ChoiceOption(0, "Voe", group = "jkanime"),
            ChoiceOption(1, "Uqload", group = "animeflv", warning = true),
            ChoiceOption(2, "Mp4", group = "jkanime"),
            ChoiceOption(3, "Filemoon", group = "animeflv")
        )
        assertEquals(listOf("Voe", "Mp4", "Filemoon", "Uqload"), ChoiceOption.arranged(options).map { it.label })
    }

    @Test
    fun elAvisoPoneUnIconoDelante() {
        assertEquals("⚠ B · en pausa hasta 22:15", ChoiceOption(1, "B", note = "en pausa hasta 22:15", warning = true).text)
        assertEquals("⚠ B", ChoiceOption(1, "B", warning = true).text)
    }

    @Test
    fun laNotaSeAñadeAlTexto() {
        assertEquals("B · falló", ChoiceOption(-1, "B", enabled = false, note = "falló").text)
        assertEquals("A", ChoiceOption(0, "A").text)
    }
}
