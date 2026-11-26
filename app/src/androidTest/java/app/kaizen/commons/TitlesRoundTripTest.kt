package app.kaizen.commons

import androidx.test.runner.AndroidJUnit4
import app.kaizen.database.CacheDB
import app.kaizen.source.SeriesLedger
import app.kaizen.source.TitleRules
import app.kaizen.source.UserTitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exportar y volver a importar las reglas del usuario conserva lo que relacionan, también cuando una regla nombra un id que se
 * unió a otra serie (redirección). Escribe en los datos reales de la app: ejecutar solo en un emulador.
 */
@RunWith(AndroidJUnit4::class)
class TitlesRoundTripTest {
    @Test
    fun unaReglaConUnIdAbsorbidoSobreviveALaCopia() {
        val ledger = CacheDB.INSTANCE.seriesIdDao()
        val keep = SeriesLedger.idOf("animeav1", "rt-keep")
        val old = SeriesLedger.idOf("jkanime", "rt-old")
        val other = SeriesLedger.idOf("animeav1", "rt-other")
        val apart = SeriesLedger.idOf("animeav1", "rt-apart")
        // `old` se une a `keep`: su ficha pasa a la serie de `keep` y la regla que nombra `old` la sigue.
        ledger.moveSeries(mapOf(old to keep))
        SeriesLedger.followMoves(mapOf(old to keep))
        SeriesLedger.load()

        assertTrue(UserTitles.update(regroup = false) { it.withMerge(keep.toString(), other.toString()).withSplit(keep.toString(), apart.toString()) })
        val text = UserTitles.export()
        assertTrue(UserTitles.update(regroup = false) { TitleRules.EMPTY })
        assertTrue(UserTitles.rules.isEmpty)

        assertTrue(UserTitles.import(text))

        val rules = UserTitles.rules
        val kept = UserTitles.keyOf("animeav1", "rt-keep")
        val others = UserTitles.keyOf("animeav1", "rt-other")
        val aside = UserTitles.keyOf("animeav1", "rt-apart")
        assertEquals(rules.canonical(kept), rules.canonical(others))
        assertTrue(rules.isSplit(kept, aside))
    }
}
