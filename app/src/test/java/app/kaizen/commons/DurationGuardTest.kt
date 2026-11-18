package app.kaizen.commons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Todo plazo de la app es una `kotlin.time.Duration` (ver [TimeBoundaries.kt]): este test falla si vuelve a aparecer un plazo propio en `Long` con la unidad
 * en el nombre, un `TimeUnit.X` fuera de la frontera, o una espera con el número suelto (`delay(300)`, `postDelayed(r, 5000)`). Lo que sigue en milisegundos
 * por ser **un dato del reproductor** (posiciones y márgenes de Media3 o de AniSkip) o la API de animaciones de Compose está en [ALLOWED], con su razón.
 */
class DurationGuardTest {
    private val sources: List<File> = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "app/src/main/java").takeIf(File::isDirectory) ?: File(it, "src/main/java").takeIf(File::isDirectory) }
        .firstOrNull { it != null }.let { it ?: error("no se encuentra app/src/main/java: ejecuta las pruebas desde el repositorio") }
        .walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** Las líneas de código de todos los ficheros (sin comentarios de línea ni KDoc), con su fichero y número. */
    private fun code(): List<Triple<String, Int, String>> = sources.flatMap { f ->
        f.readLines().mapIndexedNotNull { i, line ->
            val t = line.trim()
            if (t.startsWith("*") || t.startsWith("/*") || t.startsWith("//")) null else Triple(f.name, i + 1, line.substringBefore(" //"))
        }
    }

    @Test
    fun `no hay plazos propios en Long con la unidad en el nombre`() {
        val bad = code().filter { (file, _, line) -> NAMED_MS.containsMatchIn(line) && NAMED_MS.find(line)!!.groupValues[1].let { name -> "$file:$name" !in ALLOWED } }
        assertEquals("plazos en Long (usa Duration, o añádelo a ALLOWED si es un dato del reproductor):\n" + bad.joinToString("\n") { "${it.first}:${it.second}  ${it.third.trim()}" }, 0, bad.size)
    }

    @Test
    fun `TimeUnit solo aparece en la frontera y en el pool de hilos de scripts`() {
        val bad = code().filter { (file, _, line) -> "TimeUnit." in line && file !in setOf("TimeBoundaries.kt", "JsEngine.kt") }
        assertEquals("TimeUnit fuera de TimeBoundaries.kt:\n" + bad.joinToString("\n") { "${it.first}:${it.second}  ${it.third.trim()}" }, 0, bad.size)
    }

    @Test
    fun `las esperas no llevan el numero suelto ni milisegundos convertidos`() {
        val bad = code().filter { (file, _, line) -> file != "TimeBoundaries.kt" && LOOSE_WAIT.containsMatchIn(line) }
        assertEquals("esperas con número suelto o en ms:\n" + bad.joinToString("\n") { "${it.first}:${it.second}  ${it.third.trim()}" }, 0, bad.size)
    }

    @Test
    fun `las comprobaciones reconocen lo que deben y lo permitido existe`() {
        // Que el propio guarda no se pudra: cada patrón detecta su caso y deja pasar lo correcto.
        assertTrue(NAMED_MS.containsMatchIn("private const val POLL_MS = 500L"))
        assertTrue(NAMED_MS.containsMatchIn("internal var spacingMs = 2_000L"))
        assertFalse(NAMED_MS.containsMatchIn("private val POLL = 500.milliseconds"))
        assertFalse(NAMED_MS.containsMatchIn("val positionMs = player.currentPosition"))
        assertTrue(LOOSE_WAIT.containsMatchIn("delay(300)"))
        assertTrue(LOOSE_WAIT.containsMatchIn("withTimeoutOrNull(5_000L) {"))
        assertTrue(LOOSE_WAIT.containsMatchIn("handler.postDelayed(runnable, 5000)"))
        assertTrue(LOOSE_WAIT.containsMatchIn("delay(RETRY.inWholeMilliseconds)"))
        assertFalse(LOOSE_WAIT.containsMatchIn("delay(300.milliseconds)"))
        assertFalse(LOOSE_WAIT.containsMatchIn("handler.postDelayed(runnable, POLL)"))
        // Cada excepción apunta a una constante que sigue existiendo (si se migra, se quita de la lista).
        val text = sources.associate { it.name to it.readText() }
        ALLOWED.forEach { entry ->
            val (file, name) = entry.split(":")
            assertTrue("$entry ya no existe: quítalo de ALLOWED", text[file]?.contains(Regex("""\b$name\b""")) == true)
        }
    }

    private companion object {
        /** `val NOMBRE_MS = 123` o `var nombreMs = 123L`: un plazo con valor numérico y la unidad en el nombre. */
        val NAMED_MS = Regex("""\bva[lr]\s+([A-Za-z_]\w*(?:_MS|Ms))\b\s*(?::\s*\w+)?\s*=\s*[\d_]+L?\b""")

        /** `delay(300)`, `withTimeout…(5_000L)`, `debounce(2_000)`, `postDelayed(r, 5000)`, o cualquiera de ellos con `.inWholeMilliseconds`. */
        val LOOSE_WAIT = Regex("""\b(?:delay|withTimeoutOrNull|withTimeout|debounce)\(\s*(?:[\d_]+L?\s*[,)]|[^()]*inWholeMilliseconds)|\bpostDelayed\(\s*[\w.]+\s*,\s*(?:[\d_]+L?\s*\)|[\w.]*\.inWholeMilliseconds)""")

        /** Siguen en milisegundos porque son posiciones o márgenes del reproductor (Media3, AniSkip) o la API de animaciones de Compose; «fichero:nombre». */
        val ALLOWED = setOf(
            "KaizenProgress.kt:MIN_LISTED_MS", "KaizenProgress.kt:END_REMAINING_MS", "KaizenProgress.kt:minResumeMs",
            "AniSkip.kt:MAX_SEGMENT_MS", "AniSkip.kt:TOLERANCE_MS", "AniSkip.kt:LEAD_MS", "AniSkip.kt:TAIL_MS",
            "PlaybackController.kt:ENDING_NEXT_MARGIN_MS", "PlaybackController.kt:OPENING_PROMPT_MAX_START_MS", "PlaybackController.kt:MIN_BAR_STEP_MS", "PlaybackController.kt:restartThresholdMs",
            // Medidas y longitudes de segmento que da el reproductor o AniSkip: son datos, no plazos.
            "AniSkip.kt:openingLengthMs", "AniSkip.kt:endingLengthMs", "PlaybackSession.kt:rebufferMs", "PlaybackSession.kt:lastRebufferMs",
            "SettingsDialog.kt:PANEL_ANIMATION_MS", "KaizenUi.kt:SKELETON_PULSE_MS"
        )
    }
}
