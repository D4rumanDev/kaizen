package app.kaizen.commons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** El acceso único a las preferencias: todo fichero está en la lista y nada más abre preferencias por su cuenta. */
class KaizenStoreTest {
    private val sources: List<File> = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "app/src/main/java").takeIf(File::isDirectory) ?: File(it, "src/main/java").takeIf(File::isDirectory) }
        .firstOrNull { it != null }.let { it ?: error("no se encuentra app/src/main/java: ejecuta las pruebas desde el repositorio") }
        .walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `los ficheros conocidos y los de cada fuente se reconocen y los demas no`() {
        assertEquals(KaizenStore.Scope.SETTINGS, KaizenStore.entry("app.kaizen_preferences")?.scope)
        assertEquals(KaizenStore.Scope.SETTINGS, KaizenStore.entry("app.kaizen.debug_preferences")?.scope)
        assertEquals(KaizenStore.Scope.STATE, KaizenStore.entry("kaizen_progress")?.scope)
        assertEquals(KaizenStore.Scope.SECRET, KaizenStore.entry("kaizen_warp")?.scope)
        assertEquals(KaizenStore.Scope.CACHE, KaizenStore.entry("kaizen_jkanime_covers")?.scope)
        assertEquals(KaizenStore.Scope.CACHE, KaizenStore.entry("kaizen_animeav1_chapters")?.scope)
        assertEquals(KaizenStore.Scope.CACHE, KaizenStore.entry("kaizen_verani_dates_utc")?.scope)
        assertEquals(KaizenStore.Scope.LIBRARY, KaizenStore.entry("WebViewChromiumPrefs")?.scope)
        assertNull(KaizenStore.entry("kaizen_inventado"))
        assertNull(KaizenStore.entry("otra.libreria_preferences"))
        assertNull(KaizenStore.entry("app.kaizen"))
        assertNull(KaizenStore.entry("kaizen__covers"))
    }

    @Test
    fun `no hay dos entradas con el mismo nombre y todas dicen que guardan`() {
        val labels = KaizenStore.entries.map { it.label }
        assertEquals(labels.distinct(), labels)
        assertTrue(KaizenStore.entries.all { it.what.isNotBlank() })
    }

    @Test
    fun `solo KaizenStore abre preferencias`() {
        val direct = Regex("""\bgetSharedPreferences\(|\bgetDefaultSharedPreferences\(""")
        val offenders = sources.filter { it.name != "KaizenStore.kt" && direct.containsMatchIn(it.readText()) }.map { it.name }
        assertEquals("ficheros que abren preferencias sin pasar por KaizenStore: $offenders", emptyList<String>(), offenders)
    }

    @Test
    fun `todo nombre de fichero escrito en el codigo esta registrado`() {
        val named = Regex("""KaizenStore\.prefs\("([^"$]+)"\)""")
        val missing = sources.flatMap { f -> named.findAll(f.readText()).map { it.groupValues[1] }.toList() }.distinct().filter { KaizenStore.entry(it) == null }
        assertEquals("nombres sin registrar en KaizenStore.entries: $missing", emptyList<String>(), missing)
        assertNotNull(sources.firstOrNull { it.name == "KaizenStore.kt" })
    }

    @Test
    fun `las constantes de nombre de fichero tambien estan registradas`() {
        // Los ficheros que se piden con una constante (`FILE`, `PREFS`) y una cadena que empieza por «kaizen_» o «home_».
        val constant = Regex("""(?:FILE|PREFS)\s*=\s*"([a-z0-9_]+)"""")
        val missing = sources.flatMap { f -> constant.findAll(f.readText()).map { it.groupValues[1] }.toList() }.distinct().filter { KaizenStore.entry(it) == null }
        assertEquals("constantes de fichero sin registrar: $missing", emptyList<String>(), missing)
    }
}
