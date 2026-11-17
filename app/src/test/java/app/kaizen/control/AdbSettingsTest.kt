package app.kaizen.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los ajustes que se tocan por ADB solo admiten sus opciones, y la lista no deja abierta la puerta del propio control. */
class AdbSettingsTest {
    private var stored = "30"
    private val choice = AdbSetting("reanudar", AdbSetting.Kind.CHOICE, listOf("0", "30", "60"), { stored }, { stored = it })
    private val flag = AdbSetting("reloj", AdbSetting.Kind.BOOL, listOf("true", "false"), { "true" }, { })
    private val text = AdbSetting("servidor", AdbSetting.Kind.TEXT, emptyList(), { "" }, { })

    @Test
    fun `un valor que no es una opcion se rechaza y lista las validas`() {
        assertNull(choice.problem("60"))
        val problem = choice.problem("45")
        assertNotNull(problem)
        assertTrue("0, 30, 60" in problem!!)
        assertNull(flag.problem("false"))
        assertNotNull(flag.problem("yes"))
    }

    @Test
    fun `el texto libre solo admite caracteres seguros`() {
        assertNull(text.problem("Mp4Upload"))
        assertNull(text.problem(""))
        assertNotNull(text.problem("a;rm -rf"))
        assertNotNull(text.problem("x".repeat(41)))
    }

    @Test
    fun `los nombres son unicos y de un solo tipo de caracteres`() {
        val names = AdbSettings.all.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { Regex("[a-z0-9_]{1,40}").matches(it) })
    }

    @Test
    fun `la lista no incluye el propio control por ADB ni lo que arranca trabajo`() {
        val names = AdbSettings.all.map { it.name }.toSet()
        listOf("control_adb", "adb", "anilist", "relleno", "emparejado").forEach { assertTrue("no debe estar: $it", it !in names) }
    }

    @Test
    fun `los ajustes de medida nuevos se pueden tocar`() {
        val names = AdbSettings.all.map { it.name }.toSet()
        listOf("salto_maximo_segundos", "reanudar_tras_segundos", "paciencia", "graficas_dias", "portadas_dias",
            "memoria_imagenes_porcentaje", "registro_kb", "relleno_tanda", "relleno_dia").forEach { assertTrue(it, it in names) }
    }

    @Test
    fun `lo que al bajarlo borra datos antiguos no se toca por ADB`() {
        val names = AdbSettings.all.map { it.name }.toSet()
        listOf("estadisticas_dias", "copias_dias", "copias_maximo").forEach { assertTrue("no debe estar: $it", it !in names) }
    }

    @Test
    fun `un ajuste desconocido se rechaza`() {
        val (ok, text) = AdbSettings.set("no_existe", "1")
        assertEquals(false, ok)
        assertTrue("desconocido" in text)
    }
}
