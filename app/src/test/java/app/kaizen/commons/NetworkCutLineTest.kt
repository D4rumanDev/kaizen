package app.kaizen.commons

import app.kaizen.commons.NetworkState.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las líneas del registro al caer y volver Internet: con qué evidencia se declaró el corte y cuánto duró. */
class NetworkCutLineTest {
    @Test fun `el corte dice que vio Android y que webs fallaron`() {
        val line = NetworkState.cutLine(Platform.UNVALIDATED, listOf("jkanime.net", "animeflv.one"))
        assertEquals("Internet cortado: Android no valida la red; fallaron por red: animeflv.one, jkanime.net", line)
        assertTrue(NetworkState.cutLine(Platform.NONE, emptyList()).contains("ninguna red con salida"))
        assertTrue(NetworkState.cutLine(Platform.NONE, emptyList()).endsWith("ninguna web falló"))
    }

    @Test fun `la vuelta dice cuanto duro el corte`() {
        assertEquals("Internet recuperado tras 45 s", NetworkState.backLine(45_000))
        assertEquals("Internet recuperado tras 2 min 5 s", NetworkState.backLine(125_000))
        assertEquals("Internet recuperado tras 0 s", NetworkState.backLine(-10))
    }
}
