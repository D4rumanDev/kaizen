@file:OptIn(ExperimentalTvMaterial3Api::class)

package app.kaizen.tv.settings

import app.kaizen.tv.ui.components.*

import app.kaizen.tv.ui.theme.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import app.kaizen.R
import app.kaizen.commons.appString
import app.kaizen.commons.logError
import app.kaizen.commons.runCatchingCoop
import app.kaizen.source.AnilistReviewRepository
import app.kaizen.source.AnimeSources
import app.kaizen.source.Catalog
import app.kaizen.source.JoinedCheck
import app.kaizen.source.SeriesMatch
import app.kaizen.source.UserTitles
import app.kaizen.source.foldTitleUncached
import app.kaizen.tv.ui.CenterMessage
import app.kaizen.tv.ui.rememberLoadable
import app.kaizen.tv.ui.valueOrNull
import app.kaizen.tv.ui.Cover
import app.kaizen.tv.ui.KaizenDialog
import app.kaizen.tv.ui.NO_EPISODES
import app.kaizen.tv.ui.TitleField
import app.kaizen.tv.ui.episodesLabel
import app.kaizen.tv.ui.rememberAutoFocus
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/** Una ficha de una serie: (id de fuente, slug). */
internal typealias Ficha = Pair<String, String>

/** Una serie unida con lo que hace falta para buscarla: su texto plegado (nombre y slugs de sus fichas) y qué tan sospechosa es (menos = más). */
private class JoinedRow(val joined: Catalog.Joined, val cohesion: Double, val haystack: String)

/** «Series unidas» (Biblioteca › Por revisar): la lista con su buscador y, al elegir una, sus fichas para separarlas. */
@Composable
internal fun JoinedTab(
    selected: Catalog.Joined?, onSelect: (Catalog.Joined?) -> Unit, separated: Set<Ficha>, onSeparate: (Ficha, List<Ficha>) -> Unit, onChanged: () -> Unit
) {
    val rows = rememberLoadable {
        runCatchingCoop {
            val linked = AnilistReviewRepository.appliedLinks()
            Catalog.joinedSeries().map { joined ->
                JoinedRow(joined, JoinedCheck.cohesion(joined.fichas, linked),
                    foldTitleUncached(joined.pick.name) + " " + joined.fichas.joinToString(" ") { foldTitleUncached(it.second.replace('-', ' ')) })
            }.sortedBy { it.cohesion }
        }.onFailure(::logError).getOrDefault(emptyList())
    }.valueOrNull
    var text by remember { mutableStateOf("") }
    val list = rows
    when {
        list == null -> CenterMessage(appString(R.string.ui_buscando_series_unidas))
        selected != null -> {
            val current = selected
            JoinedView(current, separated, onSeparate,
                onSeparateAll = { UserTitles.separateAll(current.fichas.filterNot { it in separated }); onChanged(); onSelect(null) },
                onBack = { onSelect(null) })
        }
        else -> {
            val input = rememberAutoFocus()
            val shown = remember(list, text) {
                val words = foldTitleUncached(text.trim()).split(' ').filter { it.isNotEmpty() }
                if (words.isEmpty()) list else list.filter { row -> words.all { it in row.haystack } }
            }
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TitleField(text, { text = it }, input, "Buscar entre las series unidas")
                Text(
                    if (list.isEmpty()) "No hay series unidas. Si el catálogo aún no está construido, recórrelo en Biblioteca › Fuentes y vuelve."
                    else "${shown.size} de ${list.size} series unidas. Salen primero las que parecen mezcla de series distintas.",
                    color = White70, fontSize = Text14
                )
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(shown, key = { it.joined.pick.key }) { row ->
                        FocusCard(Modifier.fillMaxWidth(), onClick = { onSelect(row.joined) }) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(row.joined.pick.name, color = Color.White, fontSize = Text16, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(row.joined.fichas.joinToString(" · ") { AnimeSources.of(it.first).name }, color = White70, fontSize = Text14, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(if (row.cohesion < JoinedCheck.MIN_COHESION) "Revisar" else "${row.joined.fichas.size} fichas",
                                    color = if (row.cohesion < JoinedCheck.MIN_COHESION) StatusWarn else White70, fontSize = Text14, modifier = Modifier.padding(start = 16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JoinedView(joined: Catalog.Joined, separated: Set<Ficha>, onSeparate: (Ficha, List<Ficha>) -> Unit, onSeparateAll: () -> Unit, onBack: () -> Unit) {
    val focus = rememberAutoFocus()
    LaunchedEffect(separated.size) { if (separated.isNotEmpty()) { delay(150.milliseconds); runCatching { focus.requestFocus() } } }
    val fichas = joined.fichas.filterNot { it in separated }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Juntada con ${fichas.size} fichas", color = Color.White, fontSize = Text20, fontWeight = FontWeight.Bold)
        Text(appString(R.string.ui_si_alguna_no_es_la_misma_serie_separalas_no_se_v), color = White70, fontSize = Text16)
        SeriesColumn(joined.pick, fichas, onSeparate, Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KaizenButton("Volver a la lista", onClick = onBack, modifier = Modifier.focusRequester(focus))
            KaizenButton(appString(R.string.ui_separar_todas), onClick = onSeparateAll)
        }
    }
}

/**
 * La serie y sus fichas, cada una con su fuente, sus episodios y si su slug es el más repetido del grupo; OK en una la separa. La lista se desplaza si no caben todas.
 */
@Composable
private fun SeriesColumn(pick: Catalog.Pick, fichas: List<Ficha>, onSeparate: (Ficha, List<Ficha>) -> Unit, modifier: Modifier) {
    var choosing by remember { mutableStateOf<Ficha?>(null) }
    // El slug «de referencia» es el más repetido entre las fichas (sin «-latino»): las que no lo comparten son las sospechosas.
    val reference = remember(fichas) { fichas.groupingBy { SeriesMatch.slugBase(it.second) }.eachCount().maxByOrNull { it.value }?.key }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Cover(pick.covers, Modifier.size(PreviewPosterWidth, PreviewPosterHeight).clip(PreviewPosterShape))
        Column(Modifier.width(1.dp).weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(pick.name, color = Color.White, fontSize = Text18, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(pick.year?.let { "Año $it" } ?: "Año desconocido", color = Highlight, fontSize = Text16)
            fichas.forEach { ficha ->
                FichaLine(ficha, SeriesMatch.slugBase(ficha.second) == reference, canSeparate = fichas.size > 1) { choosing = ficha }
            }
        }
    }
    choosing?.let { ficha ->
        SeparateFichaDialog(ficha, onSeparate = { onSeparate(ficha, fichas) }, onDismiss = { choosing = null })
    }
}

@Composable
private fun FichaLine(ficha: Ficha, same: Boolean, canSeparate: Boolean, onClick: () -> Unit) {
    val episodes by produceState<Int?>(null, ficha) { value = Catalog.episodeCount(ficha.first, ficha.second) ?: NO_EPISODES }
    val content: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text("${AnimeSources.of(ficha.first).name} · ${episodes?.let { episodesLabel(it) } ?: "…"}", color = Color.White, fontSize = Text14, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(ficha.second, color = White80, fontSize = Text14, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (same) "= slug del grupo" else "≠ slug del grupo", color = if (same) StatusOk else StatusWarn, fontSize = Text14, maxLines = 1)
        }
    }
    if (canSeparate) FocusCard(Modifier.fillMaxWidth(), onClick, content = content)
    else Column(Modifier.fillMaxWidth(), content = content)
}

@Composable
private fun SeparateFichaDialog(ficha: Ficha, onSeparate: () -> Unit, onDismiss: () -> Unit) {
    val first = rememberAutoFocus()
    KaizenDialog(onDismissRequest = onDismiss, title = "${AnimeSources.of(ficha.first).name} · ${ficha.second}") {
        KaizenButton("Separar esta ficha", onClick = { onSeparate(); onDismiss() }, modifier = Modifier.fillMaxWidth().focusRequester(first))
        KaizenButton(appString(R.string.ui_cancelar), onClick = onDismiss, modifier = Modifier.fillMaxWidth())
    }
}
