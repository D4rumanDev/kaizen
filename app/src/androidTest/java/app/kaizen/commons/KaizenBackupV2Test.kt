package app.kaizen.commons

import androidx.test.runner.AndroidJUnit4
import app.kaizen.database.CacheDB
import app.kaizen.source.SeriesLedger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Copia v2 sobre los datos reales del equipo (solo en el emulador): sale sin ids locales de serie, con los grupos de fichas, y volver a
 * importarla no añade nada ni cambia los ids.
 */
@RunWith(AndroidJUnit4::class)
class KaizenBackupV2Test {
    @Test
    fun laCopiaSaleSinIdsLocalesConGruposYAlImportarlaNoCambiaNada() = runBlocking {
        SeriesLedger.ensureLoaded()
        val db = CacheDB.INSTANCE
        val favoritesBefore = db.favoriteAV1DAO().allRaw.map { it.seriesId to it.slug }.sortedBy { it.first }
        val recordsBefore = db.recordAV1DAO().all.size
        val progressBefore = KaizenProgress.exportAll()

        val (file, summary) = KaizenBackup.export()
        val data = KaizenBackup.decode(file.readText())!!

        assertEquals(2, data.version)
        assertTrue(data.favorites.all { it.seriesId == 0L } && data.records.all { it.seriesId == 0L })
        // Las claves «s:» que quedan son de pruebas con series que el libro no conoce (ids 920xxx), no de datos reales.
        assertTrue(data.progress.keys.none { it.startsWith("s:") && !it.startsWith("s:920") })
        assertTrue(data.groups.all { it.size > 1 })
        assertEquals(summary.favorites, data.favorites.size)

        val imported = KaizenBackup.import(file)!!

        assertEquals(0, imported.favorites)
        assertEquals(0, imported.progress)
        assertEquals(favoritesBefore, db.favoriteAV1DAO().allRaw.map { it.seriesId to it.slug }.sortedBy { it.first })
        assertEquals(recordsBefore, db.recordAV1DAO().all.size)
        assertEquals(progressBefore, KaizenProgress.exportAll())
    }
}
