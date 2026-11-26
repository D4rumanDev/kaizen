@file:OptIn(ExperimentalTvMaterial3Api::class)

package app.kaizen.tv.ui.components

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import app.kaizen.tv.ui.theme.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.kaizen.R
import app.kaizen.commons.appString
import app.kaizen.tv.ui.*
import app.kaizen.tv.ui.theme.*

/** El corazón arriba a la derecha de la imagen: relleno si la serie es favorita y solo el contorno si no. */
@Composable
internal fun FavoriteMark(modifier: Modifier = Modifier, favorite: Boolean = true) {
    Icon(painterResource(if (favorite) R.drawable.ic_favorite_kaizen else R.drawable.ic_favorite_border_kaizen),
        contentDescription = if (favorite) appString(R.string.ui_favorita) else null, tint = if (favorite) Color.Unspecified else Color.White,
        modifier = modifier.padding(6.dp).clip(CircleShape).background(Black60).padding(4.dp).size(14.dp))
}

/** El capítulo (o la nota) sobre la imagen, abajo a la izquierda, en una pastilla oscura translúcida. */
@Composable
internal fun ChapterPill(text: String, color: Color, modifier: Modifier = Modifier, reserveEnd: androidx.compose.ui.unit.Dp = 8.dp) {
    Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = reserveEnd).clip(BadgeShape).background(Black67).padding(horizontal = 6.dp, vertical = 2.dp))
}

/**
 * El «seguir viendo» sobre la imagen, abajo a la derecha: círculo azul con el triángulo de reproducir. El check queda solo para lo acabado (un check azul se leería
 * como «visto»), y la barra de debajo dice cuánto falta.
 */
@Composable
internal fun ResumeBadge(modifier: Modifier = Modifier) {
    Box(modifier.padding(8.dp).size(20.dp).clip(CircleShape).background(Accent).semantics { contentDescription = "Seguir viendo" }, contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_play_arrow_kaizen), contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
    }
}

/** La marca de un capítulo sobre su imagen, abajo a la derecha, la misma en todas las tarjetas: triángulo azul si está empezado, check verde si está acabado y nada si no. */
@Composable
internal fun WatchMark(watch: CardWatch, modifier: Modifier = Modifier) {
    when (watch) {
        CardWatch.RESUMING -> ResumeBadge(modifier)
        CardWatch.SEEN -> SeenBadge(modifier)
        CardWatch.NONE -> Unit
    }
}

/** El «visto» sobre la imagen, abajo a la derecha: círculo verde con el check. */
@Composable
internal fun SeenBadge(modifier: Modifier = Modifier) {
    Box(modifier.padding(8.dp).size(20.dp).clip(CircleShape).background(SeenGreen).semantics { contentDescription = appString(R.string.ui_visto) }, contentAlignment = Alignment.Center) {
        Text("✓", color = Color.White, fontSize = Text12, fontWeight = FontWeight.Bold)
    }
}

/** «NOVEDAD» (el capítulo 1 de una serie que aparece en recientes, solo mientras está ahí) o «FINAL» (el último capítulo), sobre la imagen, arriba a la izquierda. */
@Composable
internal fun NewBadge(label: String = "NOVEDAD", modifier: Modifier = Modifier) {
    Text(label, color = Color.White, fontSize = BadgeText, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = modifier.clip(BadgeShape).background(Accent).padding(horizontal = 6.dp, vertical = 2.dp))
}
