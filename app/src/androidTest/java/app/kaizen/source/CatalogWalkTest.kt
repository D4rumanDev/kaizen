package app.kaizen.source

import androidx.test.runner.AndroidJUnit4
import app.kaizen.commons.Network
import app.kaizen.database.CacheDB
import app.kaizen.database.UserDB
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Un recorrido real del catálogo (cientos de peticiones, varios minutos): los ids se dan en `user-db` y el catálogo se escribe después en
 * `cache-db`; toda serie y todo enlace del catálogo tiene su id en el libro. Necesita red; ejecutar solo en un emulador.
 */
@RunWith(AndroidJUnit4::class)
class CatalogWalkTest {
    @Test
    fun todaSerieDelCatalogoTieneSuIdEnElLibro() = runBlocking {
        assumeTrue(Network.isConnected)
        CatalogBuilder.ensureFresh(force = true)

        val catalog = CacheDB.INSTANCE.catalogDao()
        val ledger = UserDB.INSTANCE.seriesIdDao()
        val known = ledger.allMap().map { it.seriesId }.toSet()
        val entries = catalog.allEntries()
        assertTrue("el recorrido no dejó catálogo", entries.isNotEmpty())
        assertEquals(emptyList<Long>(), entries.map { it.seriesId }.filter { it !in known })
        assertEquals(emptyList<Long>(), catalog.allLinks().map { it.seriesId }.filter { it !in known }.distinct())
    }
}
