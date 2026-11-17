package app.kaizen.commons

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import app.kaizen.database.CacheDB
import app.kaizen.source.SeriesLedger
import app.kaizen.source.UserTitles
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Una copia hecha en otro equipo se importa en una instalación limpia (los ids de serie son otros): favoritos y vistos entran
 * enteros y las uniones y separaciones de `titles.json` siguen relacionando las mismas fichas. Necesita la copia en
 * `/data/local/tmp/portability.json` (adb push) y la app sin datos (`pm clear`): ejecutar solo en un emulador.
 */
@RunWith(AndroidJUnit4::class)
class BackupPortabilityTest {
    private fun shellRead(path: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $path")
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    @Test
    fun laCopiaDeOtroEquipoConservaDatosYUniones() {
        val text = shellRead("/data/local/tmp/portability.json")
        val source = JSONObject(text)
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "portability.json").apply { writeText(text) }

        val summary = runBlocking { KaizenBackup.import(file) }
        assertNotNull(summary)

        val db = CacheDB.INSTANCE
        assertEquals(source.getJSONArray("favorites").length(), db.favoriteAV1DAO().allRaw.size)
        assertEquals(source.getJSONArray("records").length(), db.recordAV1DAO().all.size)

        val titles = JSONObject(source.getString("titles"))
        val fichas = titles.getJSONObject("fichas")
        fun keyOf(oldId: String): String? {
            val members = fichas.optJSONArray(oldId) ?: return null
            val ficha = members.optJSONArray(0) ?: return null
            return SeriesLedger.idOf(ficha.getString(0), ficha.getString(1)).toString()
        }
        val rules = UserTitles.rules
        var merges = 0
        var splits = 0
        val broken = ArrayList<String>()
        val mergeList = titles.getJSONArray("merges")
        for (i in 0 until mergeList.length()) {
            val group = mergeList.getJSONArray(i)
            val keys = (0 until group.length()).map { keyOf(group.get(it).toString()) }
            if (keys.any { it == null }) continue
            merges++
            if (keys.map { rules.canonical(it!!) }.toSet().size != 1) broken += "unión ${group}"
        }
        val splitList = titles.getJSONArray("splits")
        for (i in 0 until splitList.length()) {
            val pair = splitList.getJSONArray(i)
            val a = keyOf(pair.get(0).toString())
            val b = keyOf(pair.get(1).toString())
            if (a == null || b == null) continue
            splits++
            if (!rules.isSplit(a, b)) broken += "separación $pair"
        }
        println("PORTABILIDAD uniones comprobadas=$merges separaciones comprobadas=$splits rotas=${broken.size}")
        assertTrue("sin reglas que comprobar", merges + splits > 0)
        assertEquals(broken.take(5).toString(), 0, broken.size)
    }
}
