@file:OptIn(ExperimentalTvMaterial3Api::class)

package app.kaizen.tv.settings

import app.kaizen.tv.ui.components.*

import app.kaizen.tv.ui.theme.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import app.kaizen.R
import app.kaizen.commons.AppScope
import app.kaizen.commons.appString
import app.kaizen.commons.logError
import app.kaizen.commons.runCatchingCoop
import app.kaizen.source.CatalogBuilder
import app.kaizen.source.CatalogOverview
import app.kaizen.source.SeriesLedger
import app.kaizen.source.UserTitles
import app.kaizen.tv.ui.DialogWidthWide
import app.kaizen.tv.ui.KaizenDialog
import app.kaizen.tv.ui.rememberAutoFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import app.kaizen.tv.ui.KaizenToast

/** Las cifras del Resumen de la Biblioteca: el catálogo, las uniones (y cuántas son tuyas). */
internal class StateFacts(val sources: Int, val walked: Int, val catalogSeries: Int, val ledgerFichas: Int, val joined: Int, val joinedFichas: Int, val userMerges: Int, val userSplits: Int)

/** Lee las cifras del catálogo y de las uniones (solo base de datos, sin red); null si falla. */
internal suspend fun loadStateFacts(): StateFacts? = runCatchingCoop {
    val sources = CatalogOverview.sources()
    val series = SeriesLedger.seriesFichas().values
    StateFacts(
        sources = sources.size, walked = sources.count { it.walkedAt > 0 }, catalogSeries = sources.sumOf { it.series }, ledgerFichas = series.sumOf { it.size },
        joined = series.count { it.size > 1 }, joinedFichas = series.filter { it.size > 1 }.sumOf { it.size },
        userMerges = UserTitles.rules.mergeCount(), userSplits = UserTitles.rules.splitCount()
    )
}.onFailure(::logError).getOrNull()
