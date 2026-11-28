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
    fun `las copias y la carpeta de red se tocan por ADB pero sin contrasenas`() {
        val names = AdbSettings.all.map { it.name }.toSet()
        listOf("smb_url", "smb_usuario", "smb_auto", "smb_descubrir", "smb_copias", "copias_dias", "copias_maximo", "estadisticas_dias").forEach { assertTrue(it, it in names) }
        assertTrue(names.none { "clave_smb" in it || "smb_clave" in it || "contrasena" in it || "password" in it })
    }

    @Test
    fun `la direccion de red no admite usuario ni clave dentro`() {
        val url = AdbSettings.find("smb_url")!!
        assertNull(url.problem("smb://nas/video/copias"))
        assertNull(url.problem(""))
        assertNotNull(url.problem("smb://usuario:clave@nas/video"))
        assertNotNull(url.problem("http://nas/video"))
        assertNotNull(url.problem("smb://nas/a b"))
    }

    @Test
    fun `un ajuste desconocido se rechaza`() {
        val (ok, text) = AdbSettings.set("no_existe", "1")
        assertEquals(false, ok)
        assertTrue("desconocido" in text)
    }
}
