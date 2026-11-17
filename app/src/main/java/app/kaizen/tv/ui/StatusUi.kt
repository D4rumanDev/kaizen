@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)

package app.kaizen.tv.ui

import androidx.compose.ui.text.style.TextOverflow
import app.kaizen.tv.ui.components.*

import app.kaizen.tv.ui.theme.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import app.kaizen.domain.SeriesStatus
import app.kaizen.domain.SeriesStatuses
import app.kaizen.pojos.av1.FavoriteAV1
import app.kaizen.source.AnimeSources
import app.kaizen.source.SeriesLedger
import app.kaizen.source.userCover

/** Etiqueta de color con el nombre del estado de una serie, sobre su tarjeta. */
@Composable
internal fun StatusBadge(status: SeriesStatus, modifier: Modifier = Modifier) {
    Text(status.label.uppercase(), color = Color.White, fontSize = BadgeText, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.clip(BadgeShape).background(Color(status.color).copy(alpha = 0.88f)).padding(horizontal = 6.dp, vertical = 2.dp))
}

/** El estado de una serie como un punto de color (sobre las tarjetas; el nombre va en el panel de datos y, para los lectores de pantalla, en su descripción). */
@Composable
internal fun StatusDot(status: SeriesStatus, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 14.dp) {
    val circle = androidx.compose.foundation.shape.CircleShape
    // El borde oscuro lo separa de las portadas claras, donde el verde o el amarillo se perdían.
    Box(modifier.size(size).clip(circle).background(Color(status.color)).border(1.5.dp, Black60, circle)
        .semantics { contentDescription = status.label })
}

/** Selector del estado de una serie: [current] sale marcado y con el foco. */
@Composable
internal fun StatusDialog(title: String, current: SeriesStatus, onPick: (SeriesStatus) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    KaizenDialog(onDismissRequest = onDismiss, title = title) {
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        SeriesStatus.entries.forEach { status ->
            KaizenButton((if (status == current) "✓  " else "") + status.label, onClick = { onPick(status); onDismiss() },
                modifier = Modifier.fillMaxWidth().then(if (status == current) Modifier.focusRequester(first) else Modifier))
        }
    }
}

/** Las series con estado, una vez cada una aunque se unieran de varias fuentes (se rehace al cargar el libro de ids). */
@Composable
private fun rememberUniqueStatuses(all: Map<String, app.kaizen.domain.StatusEntry>): List<app.kaizen.domain.StatusEntry> {
    val ledger by SeriesLedger.version.collectAsStateWithLifecycle()
    return remember(all, ledger) { SeriesStatuses.distinctSeries(all.values) }
}

/** «Mi lista»: las series con estado; el orden y el filtro por estado están en el panel de la derecha. */
@Composable
internal fun MyListPage(actions: CardActions) {
    val all by SeriesStatuses.all.collectAsStateWithLifecycle()
    val unique = rememberUniqueStatuses(all)
    val sort = currentSectionSort()
    val shown = rememberSorted(unique, sort, all)
    CardGrid {
        if (shown.isEmpty()) fullWidth { Text("Sin series en este estado. Ponles estado desde su ficha o con una pulsación larga.", color = White70, fontSize = Text16) }
        items(shown.size, key = { "${shown[it].source}:${shown[it].slug}" }) { index ->
            val entry = shown[index]
            val favorite = FavoriteAV1(entry.aid, entry.name, entry.slug, "", FavoriteAV1.CATEGORY_NONE, entry.source)
            SeriesCard(entry.aid, entry.slug, userCover(entry.slug, entry.source) ?: AnimeSources.of(entry.source).coverUrl(entry.aid, entry.slug),
                entry.name, null, entry.source, onClick = { actions.open(favorite) }, onLongClick = { actions.menu(favorite) })
        }
    }
}
