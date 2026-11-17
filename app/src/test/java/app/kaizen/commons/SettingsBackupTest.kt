package app.kaizen.commons

import app.kaizen.commons.SettingsBackup.Layout
import app.kaizen.control.AdbSetting
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** La copia de ajustes en JSON: qué guarda, y que al restaurar solo entra lo válido. */
class SettingsBackupTest {
    private val store = mutableMapOf("aniskip" to "true", "velocidad" to "1", "etiqueta" to "casa")
    private val settings = listOf(
        AdbSetting("aniskip", AdbSetting.Kind.BOOL, listOf("true", "false"), { store.getValue("aniskip") }, { store["aniskip"] = it }),
        AdbSetting("velocidad", AdbSetting.Kind.CHOICE, listOf("1", "1.25", "1.5"), { store.getValue("velocidad") }, { store["velocidad"] = it }),
        AdbSetting("etiqueta", AdbSetting.Kind.TEXT, emptyList(), { store.getValue("etiqueta") }, { store["etiqueta"] = it })
    )

    private class Sink : SettingsBackup.LayoutSink {
        var sources: Set<String>? = null; var menu: String? = null; var buttons: String? = null; var hiddenButtons: Set<String>? = null; var rows: Set<Int>? = null
        override fun sources(value: Set<String>) { sources = value }
        override fun menuOrder(value: String) { menu = value }
        override fun playerButtonOrder(value: String) { buttons = value }
        override fun hiddenPlayerButtons(value: Set<String>) { hiddenButtons = value }
        override fun hiddenRows(value: Set<Int>) { rows = value }
    }

    private val known = setOf("animeav1", "animegratis", "jkanime")

    @Test fun `la copia guarda cada ajuste y la disposicion y se lee igual`() {
        val layout = Layout(listOf("animeav1"), "1,2,3", "PLAY,NEXT", listOf("PIP"), listOf(4, 5))
        val data = SettingsBackup.snapshot(settings, layout, 1_000L)
        val back = SettingsBackup.decode(SettingsBackup.encode(data))!!
        assertEquals(mapOf("aniskip" to "true", "velocidad" to "1", "etiqueta" to "casa"), back.settings)
        assertEquals(layout, back.layout)
        assertEquals(1_000L, back.createdAt)
    }

    @Test fun `un fichero que no es una copia de ajustes o es de una version posterior no se lee`() {
        assertNull(SettingsBackup.decode("no es json"))
        assertNull(SettingsBackup.decode("""{"kind":"kaizen-backup","version":1}"""))
        assertNull(SettingsBackup.decode("""{"kind":"kaizen-ajustes","version":99,"settings":{}}"""))
        assertNull(SettingsBackup.decode("""{"kind":"kaizen-ajustes","settings":{}}"""))
    }

    @Test fun `un campo que falta o es nulo no rompe la lectura`() {
        val data = SettingsBackup.decode("""{"kind":"kaizen-ajustes","version":1,"settings":{"aniskip":null,"velocidad":"1.5"}}""")!!
        assertEquals(mapOf("velocidad" to "1.5"), data.settings)
        assertEquals(Layout(), data.layout)
    }

    @Test fun `al restaurar se aplica lo valido y se rechaza lo desconocido o fuera de sus opciones`() {
        val data = SettingsBackup.Data("kaizen-ajustes", 1, 0L, mapOf("aniskip" to "false", "velocidad" to "9", "nuevo" to "x", "etiqueta" to "../etc"), Layout())
        val sink = Sink()
        val report = SettingsBackup.apply(data, settings, known, sink)
        assertEquals("false", store["aniskip"])
        assertEquals("1", store["velocidad"])
        assertEquals("casa", store["etiqueta"])
        assertEquals(setOf("velocidad", "nuevo", "etiqueta"), report.rejected.keys)
        assertTrue("aniskip" in report.applied)
    }

    @Test fun `las fuentes solo se aplican si todas son conocidas`() {
        val ok = Sink().also { SettingsBackup.apply(SettingsBackup.Data("kaizen-ajustes", 1, 0L, emptyMap(), Layout(sources = listOf("animeav1", "jkanime"))), settings, known, it) }
        assertEquals(setOf("animeav1", "jkanime"), ok.sources)
        val bad = Sink()
        val report = SettingsBackup.apply(SettingsBackup.Data("kaizen-ajustes", 1, 0L, emptyMap(), Layout(sources = listOf("animeav1", "pirata"))), settings, known, bad)
        assertNull(bad.sources)
        assertTrue("fuentes" in report.rejected)
    }

    @Test fun `el orden de menus y botones solo admite texto razonable`() {
        val sink = Sink()
        val report = SettingsBackup.apply(SettingsBackup.Data("kaizen-ajustes", 1, 0L, emptyMap(), Layout(menuOrder = "1,2;rm -rf", playerButtonOrder = "PLAY,NEXT")), settings, known, sink)
        assertNull(sink.menu)
        assertEquals("PLAY,NEXT", sink.buttons)
        assertTrue("orden_menu" in report.rejected)
    }

    @Test fun `mis ajustes predeterminados no entran en la rotacion de copias`() {
        // La rotación solo mira los ficheros que empiezan por PREFIX: el de predeterminados no puede empezar así, o se borraría al crear la copia número once.
        assertFalse(SettingsBackup.DEFAULTS_FILE.startsWith(SettingsBackup.PREFIX))
        assertTrue(SettingsBackup.DEFAULTS_FILE.endsWith(".json"))
        val files = listOf(File(SettingsBackup.DEFAULTS_FILE)).filter { it.name.startsWith(SettingsBackup.PREFIX) }
        assertTrue(SettingsBackup.expired(files, keep = 1).isEmpty())
    }

    @Test fun `quedan las diez copias mas recientes y nunca la ultima`() {
        val files = (1..13).map { File("kaizen-ajustes-$it.json").also { f -> f.deleteOnExit() } }
        files.forEachIndexed { i, f -> f.writeText("{}"); f.setLastModified(1_000_000L + i * 1_000L) }
        val sobran = SettingsBackup.expired(files)
        assertEquals(3, sobran.size)
        assertFalse(files.last() in sobran)
        assertTrue(files.first() in sobran)
        files.forEach { it.delete() }
    }
}
