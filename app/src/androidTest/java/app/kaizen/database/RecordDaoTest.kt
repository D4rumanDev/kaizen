package app.kaizen.database

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import app.kaizen.pojos.av1.Record
import app.kaizen.source.SeriesMap
import app.kaizen.source.SeriesRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lo visto es de la serie: `chapterIsSeen(fuente, slug, número)` resuelve la serie de la ficha en la propia consulta con el libro de ids
 * (SeriesMap), y una ficha que el libro no conoce no casa con nada.
 */
@RunWith(AndroidJUnit4::class)
class RecordDaoTest {
    private lateinit var db: UserDB
    private val dao get() = db.recordAV1DAO()

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, UserDB::class.java).allowMainThreadQueries().build()
        val ledger = db.seriesIdDao()
        val series = ledger.newSeries(SeriesRegistry())
        // «animeav1:a» y «jkanime:a» son la misma serie; «jkanime:solo» es otra.
        ledger.putMap(listOf(SeriesMap("animeav1", "a", series), SeriesMap("jkanime", "a", series), SeriesMap("jkanime", "solo", ledger.newSeries(SeriesRegistry()))))
    }

    @After
    fun close() = db.close()

    private fun record(number: Double, slug: String, source: String, seriesId: Long) =
        Record(eid = 1, name = "A", number = number, aid = 7, slug = slug, date = 0L, source = source, seriesId = seriesId)

    private fun seriesOf(source: String, slug: String) = db.seriesIdDao().idOf(source, slug)!!

    @Test
    fun elMismoNumeroDeLaMismaSerieEsVistoVengaDeLaFichaQueVenga() {
        dao.insertAll(listOf(record(4.0, "a", "animeav1", seriesOf("animeav1", "a"))))

        assertTrue(dao.chapterIsSeen("animeav1", "a", 4.0))
        assertTrue(dao.chapterIsSeen("jkanime", "a", 4.0))
        assertFalse(dao.chapterIsSeen("jkanime", "a", 5.0))
        assertFalse(dao.chapterIsSeen("jkanime", "solo", 4.0))
    }

    @Test
    fun unaFichaQueElLibroNoConoceNoCasaConNada() {
        dao.insertAll(listOf(record(4.0, "a", "animeav1", seriesOf("animeav1", "a"))))

        assertFalse(dao.chapterIsSeen("jkanime", "desconocida", 4.0))
    }

    @Test
    fun elMismoCapituloVistoEnDosFuentesEsUnSoloRegistro() {
        val series = seriesOf("animeav1", "a")
        dao.insertAllIgnore(listOf(record(4.0, "a", "animeav1", series), record(4.0, "a", "jkanime", series)))

        assertEquals(1, dao.getAllBySeries("jkanime", "a").size)
    }

    @Test
    fun desmarcarQuitaElCapituloDeLaSerieEntera() {
        dao.insertAll(listOf(record(4.0, "a", "animeav1", seriesOf("animeav1", "a"))))

        dao.deleteChapter("jkanime", "a", 4.0)

        assertFalse(dao.chapterIsSeen("animeav1", "a", 4.0))
    }
}
