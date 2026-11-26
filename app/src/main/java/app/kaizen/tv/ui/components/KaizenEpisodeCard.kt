@file:OptIn(ExperimentalTvMaterial3Api::class)

package app.kaizen.tv.ui.components

import app.kaizen.tv.ui.theme.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import app.kaizen.pojos.av1.Chapter
import app.kaizen.tv.ui.*
import app.kaizen.tv.ui.theme.*

/**
 * La tarjeta horizontal: portada pequeña a la izquierda y el texto a la derecha ([label] en el color de acento, [title] en negrita y las [details], cada una con su color).
 * [height] es el alto del contenido (sin el margen del foco). Con [progress] lleva la barra de lo visto abajo. «Seguir viendo», las relaciones y las series unidas de la ficha pasan por aquí.
 */
@Composable
internal fun WideCard(
    covers: List<String>,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    label: String? = null,
    details: List<Pair<String, Color>> = emptyList(),
    titleLines: Int = 2,
    detailLines: Int = 1,
    coverWidth: Dp = 90.dp,
    height: Dp = 130.dp,
    progress: Float? = null
) {
    // [height] es el alto de la imagen y el texto; el margen del borde del foco ([CardInset]) va por fuera, como en las tarjetas de serie y de capítulo.
    FocusCard(modifier.height(height + CardInset * 2), onClick, onLongClick) {
        Row(Modifier.fillMaxSize().clip(CardImageShape).background(White10)) {
            Cover(covers, Modifier.width(coverWidth).fillMaxHeight())
            Column(
                Modifier.weight(1f).fillMaxHeight().padding(horizontal = 12.dp, vertical = if (progress != null) 8.dp else 12.dp),
                verticalArrangement = if (progress != null) Arrangement.SpaceBetween else Arrangement.Center
            ) {
                Column {
                    if (label != null) Text(label, color = AccentLight, fontSize = Text14, maxLines = 1)
                    Text(title, color = Color.White, fontSize = Text15, fontWeight = FontWeight.Bold, maxLines = titleLines, overflow = TextOverflow.Ellipsis)
                    details.forEach { (text, color) -> Text(text, color = color, fontSize = Text14, maxLines = detailLines, overflow = TextOverflow.Ellipsis) }
                }
                if (progress != null) CardProgressBar(progress, Modifier.clip(RoundedCornerShape(2.dp)))
            }
        }
    }
}

/**
 * La tarjeta de un capítulo (imagen ancha con su miniatura): la marca (triángulo azul si está empezado, check verde si acabado) y la barra de lo visto salen de [rememberChapterWatch],
 * igual que en «Recientes», y se actualizan al ver o marcar desde cualquier pantalla. [onClick] dice si el capítulo está empezado, para reanudarlo con el servidor recordado.
 */
@Composable
internal fun EpisodeCard(chapter: Chapter, onLongClick: () -> Unit, onClick: (resuming: Boolean) -> Unit) {
    val watch = rememberChapterWatch(chapter.source, chapter.slug, chapter.number, chapter.eid)
    FocusCard(Modifier.width(EpisodeCardWidth), { onClick(watch.state == CardWatch.RESUMING) }, onLongClick) {
        Box(Modifier.fillMaxWidth().height(EpisodeImageHeight).clip(CardImageShape).background(White10)) {
            Cover(listOf(chapter.thumbnail), Modifier.fillMaxSize(), under = remember(chapter) { chapter.coverUnderThumbnail })
            watch.started?.let { CardProgressBar(it.fraction.toFloat(), Modifier.align(Alignment.BottomStart)) }
            WatchMark(watch.state, Modifier.align(Alignment.BottomEnd))
        }
        CardTexts(chapter.name, chapter.episodeName, titleLines = 1)
    }
}
