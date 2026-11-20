package app.kaizen.commons

import java.io.IOException
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Aviso de versiones nuevas: comparar versiones, leer la respuesta de GitHub, el límite de 24 h y avisar una sola vez. Sin red ni preferencias reales. */
class UpdateCheckerTest {
    private class FakeState(
        override var mode: String = "notice",
        override var checkedAt: Long = 0L,
        override var attemptedAt: Long = 0L,
        override var latestTag: String? = null,
        override var notifiedTag: String? = null,
        override var skippedTag: String? = null,
    ) : UpdateChecker.State

    private val notices = mutableListOf<String>()
    private lateinit var state: FakeState
    private var requests = 0
    private var body = release("v6.3.0")

    private fun release(tag: String, draft: Boolean = false, prerelease: Boolean = false, url: String = "https://github.com/D4rumanDev/kaizen/releases/tag/$tag") =
        """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"html_url":"$url"}"""

    @Before
    fun setUp() {
        state = FakeState()
        requests = 0
        body = release("v6.3.0")
        notices.clear()
        UpdateChecker.state = state
        UpdateChecker.currentVersion = "6.2.2"
        UpdateChecker.fetcher = { requests++; body }
        UpdateChecker.refreshAvailable()
    }

    private fun check(force: Boolean = false, now: Long = 100L * 3_600_000L) = runBlocking { UpdateChecker.check(force, now) { notices += it } }

    // ---- criterio 1: comparar versiones
    @Test
    fun isNewerComparaNumericamente() {
        assertTrue(UpdateChecker.isNewer("6.2.2", "v6.3.0"))
        assertTrue(UpdateChecker.isNewer("6.9.9", "6.10.0"))
        assertTrue(UpdateChecker.isNewer("6.2.2", "6.3"))
        assertTrue(UpdateChecker.isNewer("6.2.2", "6.2.2.1"))
        assertFalse(UpdateChecker.isNewer("6.2.2", "6.2.2"))
        assertFalse(UpdateChecker.isNewer("6.2.2", "v6.2.2"))
        assertFalse(UpdateChecker.isNewer("6.3.0", "6.2.9"))
        assertFalse(UpdateChecker.isNewer("6.2", "6.2.0"))
    }

    @Test
    fun isNewerEsFalsoConVersionesNoNumericas() {
        listOf("", "latest", "6.3.0-beta", "6.3.0+build5", "v", "6..3", "6.3.x", "６.３").forEach {
            assertFalse(it, UpdateChecker.isNewer("6.2.2", it))
            assertFalse(it, UpdateChecker.isNewer(it, "6.3.0"))
        }
    }

    // ---- criterio 2: lo que no avisa
    @Test
    fun parseReleaseLeeLaEtiquetaYConstruyeLaDireccionPropia() {
        val release = UpdateChecker.parseRelease(release("v6.3.0"))!!
        assertEquals("v6.3.0", release.tag)
        assertEquals("6.3.0", release.version)
        assertEquals("https://github.com/D4rumanDev/kaizen/releases/tag/v6.3.0", release.url)
        // El html_url de la respuesta no se usa: aunque venga de otro sitio, la dirección sale de la etiqueta validada.
        assertEquals("https://github.com/D4rumanDev/kaizen/releases/tag/v6.3.0", UpdateChecker.parseRelease(release("v6.3.0", url = "https://evil.example/x"))!!.url)
    }

    @Test
    fun parseReleaseIgnoraBorradoresPrereleasesYEtiquetasRaras() {
        assertNull(UpdateChecker.parseRelease(release("v6.3.0", draft = true)))
        assertNull(UpdateChecker.parseRelease(release("v6.3.0", prerelease = true)))
        assertNull(UpdateChecker.parseRelease(release("latest")))
        assertNull(UpdateChecker.parseRelease(release("v6.3.0-beta")))
        assertNull(UpdateChecker.parseRelease(release("v6.3.0/../x")))
        assertNull(UpdateChecker.parseRelease("""{"draft":false}"""))
        assertNull(UpdateChecker.parseRelease("no es json"))
        assertNull(UpdateChecker.parseRelease(""))
        assertNull(UpdateChecker.parseRelease("[]"))
    }

    // ---- criterio 3: límite y ajuste
    @Test
    fun dueRespetaLas24Horas() {
        val interval = 24.hours.inWholeMilliseconds
        assertTrue(UpdateChecker.due(now = interval, last = 0L))
        assertFalse(UpdateChecker.due(now = interval - 1, last = 0L))
        assertTrue(UpdateChecker.due(now = 1_000L, last = 999_999L)) // el reloj se movió hacia atrás: se mira
    }

    @Test
    fun conElAjusteApagadoNoHayNingunaPeticion() {
        state.mode = "off"
        assertEquals(UpdateChecker.Outcome.Skipped, check())
        assertEquals(0, requests)
        assertTrue(notices.isEmpty())
    }

    @Test
    fun noRepiteAntesDe24HorasSalvoConComprobarAhora() {
        val now = 100L * 3_600_000L
        state.checkedAt = now - 1_000L
        assertEquals(UpdateChecker.Outcome.Skipped, check(now = now))
        assertEquals(0, requests)
        assertTrue(check(force = true, now = now) is UpdateChecker.Outcome.Available)
        assertEquals(1, requests)
    }

    @Test
    fun comprobarAhoraFuncionaConElAjusteApagado() {
        state.mode = "off"
        assertTrue(check(force = true) is UpdateChecker.Outcome.Available)
        assertEquals(1, requests)
    }

    // ---- criterio 4: avisar una vez
    @Test
    fun avisaUnaSolaVezPorVersionYGuardaLaDisponible() {
        val first = check()
        assertEquals("6.3.0", (first as UpdateChecker.Outcome.Available).release.version)
        assertEquals(1, notices.size)
        assertTrue(notices.single().contains("6.3.0"))
        assertEquals("v6.3.0", state.latestTag)
        assertEquals("v6.3.0", state.notifiedTag)
        assertEquals(100L * 3_600_000L, state.checkedAt)
        // 25 h después sigue siendo la misma: se anota como disponible pero no se vuelve a avisar.
        val second = check(now = 125L * 3_600_000L)
        assertTrue(second is UpdateChecker.Outcome.Available)
        assertEquals(1, notices.size)
        // Sale una más nueva: se avisa de esa.
        body = release("v6.4.0")
        check(now = 150L * 3_600_000L)
        assertEquals(2, notices.size)
        assertEquals("v6.4.0", state.latestTag)
    }

    @Test
    fun alDiaOLaMismaVersionLimpiaLaDisponible() {
        state.latestTag = "v6.3.0"
        body = release("v6.2.2")
        assertEquals(UpdateChecker.Outcome.UpToDate, check())
        assertNull(state.latestTag)
        assertTrue(notices.isEmpty())
        assertEquals(100L * 3_600_000L, state.checkedAt)
    }

    @Test
    fun unaRespuestaIgnorableCuentaComoAlDia() {
        body = release("v6.3.0", prerelease = true)
        assertEquals(UpdateChecker.Outcome.UpToDate, check())
        assertTrue(notices.isEmpty())
    }

    // ---- criterio 5: fallos
    @Test
    fun unFalloDeRedNoCambiaElEstadoNiAvisa() {
        state.latestTag = "v6.3.0"
        state.checkedAt = 5L
        UpdateChecker.fetcher = { throw IOException("sin red") }
        assertEquals(UpdateChecker.Outcome.Failed, check())
        assertEquals(5L, state.checkedAt)
        assertEquals("v6.3.0", state.latestTag)
        assertTrue(notices.isEmpty())
    }

    @Test
    fun laCancelacionSePropaga() {
        UpdateChecker.fetcher = { throw CancellationException("cancelado") }
        assertThrows(CancellationException::class.java) { check() }
        assertEquals(0L, state.checkedAt)
    }

    @Test
    fun unCuerpoQueNoEsUnObjetoJsonEsUnFalloYNoCambiaElEstado() {
        state.latestTag = "v6.3.0"
        listOf("<html>portal cautivo</html>", "", "[]", "null").forEach {
            body = it
            state.attemptedAt = 0L
            assertEquals(it, UpdateChecker.Outcome.Failed, check())
            assertEquals(it, 0L, state.checkedAt)
            assertEquals(it, "v6.3.0", state.latestTag)
        }
        assertTrue(notices.isEmpty())
    }

    @Test
    fun tras_un_fallo_no_se_reintenta_hasta_pasadas_6_horas() {
        val t0 = 100L * 3_600_000L
        UpdateChecker.fetcher = { requests++; throw IOException("sin red") }
        assertEquals(UpdateChecker.Outcome.Failed, check(now = t0))
        assertEquals(1, requests)
        // Se abre la app otra vez a la hora: ni petición.
        assertEquals(UpdateChecker.Outcome.Skipped, check(now = t0 + 3_600_000L))
        assertEquals(1, requests)
        // Pasadas 6 h se vuelve a intentar.
        assertEquals(UpdateChecker.Outcome.Failed, check(now = t0 + 6L * 3_600_000L))
        assertEquals(2, requests)
        // «Comprobar ahora» no espera.
        assertEquals(UpdateChecker.Outcome.Failed, check(force = true, now = t0 + 6L * 3_600_000L + 1))
        assertEquals(3, requests)
    }

    @Test
    fun retryableCubreElRelojHaciaAtras() {
        assertTrue(UpdateChecker.retryable(now = 1_000L, attemptedAt = 999_999L))
        assertFalse(UpdateChecker.retryable(now = 1_000L, attemptedAt = 900L))
    }

    // ---- etiqueta de Inicio
    @Test
    fun laEtiquetaSoloSaleConVersionNuevaAjusteEncendidoYSinOmitir() {
        state.latestTag = "v6.3.0"
        UpdateChecker.refreshAvailable()
        assertEquals("v6.3.0", UpdateChecker.available.value?.tag)
        state.mode = "off"
        UpdateChecker.refreshAvailable()
        assertNull(UpdateChecker.available.value)
        state.mode = "notice"
        state.latestTag = "v6.2.2"
        UpdateChecker.refreshAvailable()
        assertNull("la misma versión no es nueva", UpdateChecker.available.value)
        state.latestTag = null
        UpdateChecker.refreshAvailable()
        assertNull(UpdateChecker.available.value)
    }

    @Test
    fun omitirOcultaEsaVersionPeroNoUnaMayor() {
        state.latestTag = "v6.3.0"
        UpdateChecker.skip("v6.3.0")
        assertEquals("v6.3.0", state.skippedTag)
        assertNull(UpdateChecker.available.value)
        body = release("v6.4.0")
        check(force = true)
        assertEquals("v6.4.0", UpdateChecker.available.value?.tag)
    }

    @Test
    fun mirarGitHubActualizaLaEtiqueta() {
        assertNull(UpdateChecker.available.value)
        check()
        assertEquals("v6.3.0", UpdateChecker.available.value?.tag)
        body = release("v6.2.2")
        check(force = true)
        assertNull(UpdateChecker.available.value)
    }

    // ---- fila de Acerca de
    @Test
    fun elResumenDiceSiHayVersionNuevaYDondeBajarla() {
        assertEquals("Estás al día (6.2.2)", UpdateChecker.summary("6.2.2", null))
        assertEquals("Estás al día (6.2.2)", UpdateChecker.summary("6.2.2", "v6.2.2"))
        val text = UpdateChecker.summary("6.2.2", "v6.3.0")
        assertTrue(text, text.contains("6.3.0") && text.contains("github.com/D4rumanDev/kaizen/releases/tag/v6.3.0"))
    }
}
