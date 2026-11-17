package app.kaizen.database

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import app.kaizen.pojos.av1.FavoriteAV1
import app.kaizen.pojos.av1.Record
import app.kaizen.source.SeriesMap
import app.kaizen.source.SeriesRegistry
import app.kaizen.source.UserRows
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Favoritos y vistos por serie: la consulta resuelve la ficha a su serie con el libro, y `followLedger` mantiene las filas al unir o separar series. */
@RunWith(AndroidJUnit4::class)
class FavoriteDaoTest {
    private lateinit var db: UserDB
    private val ledger get() = db.seriesIdDao()
    private val rows: UserRows get() = db.userRowsDao()

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, UserDB::class.java).allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    private fun series(vararg fichas: Pair<String, String>): Long = ledger.newSeries(SeriesRegistry()).also { id ->
        ledger.putMap(fichas.map { (source, slug) -> SeriesMap(source, slug, id) })
    }

    private fun fav(slug: String, source: String, seriesId: Long) = FavoriteAV1(7, slug, slug, "TV", FavoriteAV1.CATEGORY_NONE, source, seriesId)

    private fun record(number: Double, slug: String, source: String, seriesId: Long) =
        Record(eid = 1, name = slug, number = number, aid = 7, slug = slug, date = 0L, source = source, seriesId = seriesId)

    @Test
    fun esFavoritaLaSerieDeLaFichaAunqueSeGuardaraDesdeOtra() {
        val dao = db.favoriteAV1DAO()
        val id = series("animeav1" to "a", "jkanime" to "a")
        dao.insertAll(listOf(fav("a", "animeav1", id)))

        assertTrue(dao.isFav("jkanime", "a"))
        assertFalse(dao.isFav("jkanime", "otra"))
    }

    @Test
    fun removeFavQuitaLaSerieEnteraYSoloEsa() {
        val dao = db.favoriteAV1DAO()
        val a = series("animeav1" to "a", "jkanime" to "a")
        val b = series("jkanime" to "b")
        dao.insertAll(listOf(fav("a", "animeav1", a), fav("b", "jkanime", b)))

        dao.removeFav("jkanime", "a")

        assertFalse(dao.isFav("animeav1", "a"))
        assertTrue(dao.isFav("jkanime", "b"))
    }

    @Test
    fun alUnirseDosSeriesSusFilasSiguenALaVigenteYLoRepetidoSeQuedaEnUna() {
        val a = series("animeav1" to "a")
        val b = series("jkanime" to "a")
        val favs = db.favoriteAV1DAO()
        val recs = db.recordAV1DAO()
        favs.insertAll(listOf(fav("a", "animeav1", a), fav("a", "jkanime", b)))
        recs.insertAll(listOf(record(1.0, "a", "animeav1", a), record(1.0, "a", "jkanime", b), record(2.0, "a", "jkanime", b)))

        ledger.moveSeries(mapOf(b to a))
        rows.followLedger()

        assertEquals(1, favs.allRaw.size)
        assertEquals(setOf(1.0, 2.0), recs.all.map { it.number }.toSet())
        assertEquals(setOf(a), recs.all.map { it.seriesId }.toSet())
        assertEquals(a, ledger.idOf("jkanime", "a"))
    }

    @Test
    fun alSepararseUnaFichaSusFilasLaSiguen() {
        val joined = series("animeav1" to "a", "jkanime" to "a")
        val recs = db.recordAV1DAO()
        recs.insertAll(listOf(record(1.0, "a", "jkanime", joined)))
        val own = ledger.newSeries(SeriesRegistry())
        ledger.putMap(listOf(SeriesMap("jkanime", "a", own)))

        rows.followLedger()

        assertEquals(own, recs.all.single().seriesId)
        assertFalse(recs.chapterIsSeen("animeav1", "a", 1.0))
        assertTrue(recs.chapterIsSeen("jkanime", "a", 1.0))
    }
}
