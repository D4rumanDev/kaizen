@file:OptIn(ExperimentalTvMaterial3Api::class)

package app.kaizen.tv.settings

import app.kaizen.tv.ui.components.*

import app.kaizen.tv.ui.theme.*

import androidx.tv.material3.ExperimentalTvMaterial3Api
import app.kaizen.commons.logError
import app.kaizen.commons.runCatchingCoop
import app.kaizen.source.AnimeSources
import app.kaizen.source.CatalogOverview
import app.kaizen.source.SeriesLedger
import app.kaizen.source.UserTitles

/** Las cifras del Resumen de la Biblioteca: el catálogo, las uniones (y cuántas son tuyas). */
internal class StateFacts(val sources: Int, val walked: Int, val catalogSeries: Int, val ledgerFichas: Int, val joined: Int, val joinedFichas: Int, val userMerges: Int, val userSplits: Int)

/** Lee las cifras del catálogo y de las uniones (solo base de datos, sin red); null si falla. */
internal suspend fun loadStateFacts(): StateFacts? = runCatchingCoop {
    val sources = CatalogOverview.sources()
    // «Fuentes recorridas» cuenta solo las activas: una fuente apagada no se recorre y no debe aparecer como «falta».
    val active = AnimeSources.enabled.map { it.id }.toSet()
    val used = sources.filter { it.source.id in active }
    val series = SeriesLedger.seriesFichas().values
    StateFacts(
        sources = used.size, walked = used.count { it.walkedAt > 0 }, catalogSeries = sources.sumOf { it.series }, ledgerFichas = series.sumOf { it.size },
        joined = series.count { it.size > 1 }, joinedFichas = series.filter { it.size > 1 }.sumOf { it.size },
        userMerges = UserTitles.rules.mergeCount(), userSplits = UserTitles.rules.splitCount()
    )
}.onFailure(::logError).getOrNull()
