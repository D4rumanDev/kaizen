package app.kaizen.commons

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import app.kaizen.database.CacheDB
import app.kaizen.source.SeriesLedger
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Una copia v2 hecha en otro equipo se importa en una instalación limpia: las fichas que la copia agrupa quedan en una sola serie y
 * favoritos y vistos entran enteros. Necesita la copia en `/data/local/tmp/v2.json` (adb push) y la app sin datos (`pm clear`): solo en un emulador.
 */
@RunWith(AndroidJUnit4::class)
class BackupV2ImportTest {
    private fun shellRead(path: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $path")
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    @Test
    fun lasFichasAgrupadasEntranComoUnaSerieYNoSePierdeNada() {
        val text = shellRead("/data/local/tmp/v2.json")
        val source = JSONObject(text)
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "v2.json").apply { writeText(text) }

        val summary = runBlocking { KaizenBackup.import(file) }
        assertNotNull(summary)

        val db = CacheDB.INSTANCE
        val groups = source.getJSONArray("groups")
        assertTrue("la copia no trae grupos", groups.length() > 0)
        var checked = 0
        for (i in 0 until groups.length()) {
            val group = groups.getJSONArray(i)
            val ids = (0 until group.length()).map { group.getString(it).substringBefore(':') to group.getString(it).substringAfter(':') }
                .map { (src, slug) -> SeriesLedger.peek(src, slug) }.toSet()
            assertEquals("grupo $i sin unir: $ids", 1, ids.size)
            checked++
        }
        println("IMPORT V2 grupos comprobados=$checked")
        val favorites = source.getJSONArray("favorites").length()
        assertEquals(favorites, db.favoriteAV1DAO().allRaw.size)
        // Cada capítulo de la copia entra una vez: la copia ya traía uno por serie y número.
        val records = db.recordAV1DAO().all
        assertEquals(source.getJSONArray("records").length(), records.size)
        assertEquals(records.size, records.map { it.seriesId to it.number }.toSet().size)
        assertTrue(records.all { it.seriesId > 0 })
    }
}
