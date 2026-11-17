package app.kaizen.source

import androidx.test.runner.AndroidJUnit4
import app.kaizen.database.CatalogDB
import app.kaizen.database.UserDB
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ensayo real con red, solo en el emulador (escribe en sus bases): empareja unas pocas series del libro con AniList de verdad y comprueba
 * que quedan filas en `AnimeFuente` y fichas en `AnimeFicha`. Necesita el libro cargado (copia `series-ledger.json.gz` en la carpeta de copias).
 */
@RunWith(AndroidJUnit4::class)
class AnilistSyncLiveTest {
    @Test
    fun empareja20SeriesConAniListDeVerdad() = runBlocking {
        SeriesLedger.load()
        val all = SeriesLedger.seriesFichas().map { (id, fichas) -> SeriesWork(id, fichas, 0) }
        assertTrue("el libro está vacío: falta la copia del libro", all.size > 1000)
        val sample = all.shuffled(java.util.Random(7)).take(20)

        val report = AnilistSync(
            AnilistClient(AnilistHttp::post), UserDB.INSTANCE.animeFuenteDao(), CatalogDB.INSTANCE.animeFichaDao(),
            { sample }, { true }
        ).runOnce()

        println("ANILIST LIVE series=${report.series} aplicadas=${report.applied} pendientes=${report.pending} sin=${report.none} fichas=${report.fichasDownloaded} parada=${report.stopped}")
        assertTrue(report.series > 0)
        assertTrue(UserDB.INSTANCE.animeFuenteDao().all().isNotEmpty())
    }
}
