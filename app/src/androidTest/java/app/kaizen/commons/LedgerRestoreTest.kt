package app.kaizen.commons

import androidx.test.runner.AndroidJUnit4
import app.kaizen.database.UserDB
import app.kaizen.source.SeriesLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Con la copia real en disco, si las tablas de ids se vacían (como al perderse `user-db`), al cargar el libro las fichas recuperan los
 * mismos ids. Escribe en la base y en las copias de la app: ejecutar solo en un emulador.
 */
@RunWith(AndroidJUnit4::class)
class LedgerRestoreTest {
    @Test
    fun alVaciarseElLibroLasFichasRecuperanSusIds() {
        val a = SeriesLedger.idOf("animeav1", "lr-a")
        val b = SeriesLedger.idOf("jkanime", "lr-b")
        SeriesLedger.saveBackup()
        SeriesLedger.saveBackup()
        // La copia anterior se conserva aparte.
        assertTrue(java.io.File(KaizenBackup.directory, "series-ledger.prev.json.gz").exists())

        val db = UserDB.INSTANCE.openHelper.writableDatabase
        db.execSQL("DELETE FROM `Ficha`")
        assertTrue(UserDB.INSTANCE.seriesIdDao().allMap().isEmpty())

        SeriesLedger.load()

        assertEquals(a, SeriesLedger.peek("animeav1", "lr-a"))
        assertEquals(b, SeriesLedger.peek("jkanime", "lr-b"))
        assertTrue(SeriesLedger.idOf("animeav1", "lr-nueva") > maxOf(a, b))
    }
}
