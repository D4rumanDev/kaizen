@file:OptIn(ExperimentalTvMaterial3Api::class)

package app.kaizen.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import app.kaizen.tv.ui.theme.*

/** Una entrada de una leyenda: el [color] con el que se pinta en la pantalla y lo que significa. */
internal class LegendItem(val color: Color, val label: String)

/**
 * La leyenda de colores de una pantalla, igual en todas: cada color es una píldora con su punto y su texto, en vez de una frase en gris.
 * Los colores salen de la misma paleta que lo que explican (verde, ámbar y rojo del estado; gris de lo que no tiene dato).
 */
@Composable
internal fun Legend(items: List<LegendItem>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { item ->
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(White10).padding(start = 8.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(item.color))
                Text(item.label, color = White80, fontSize = Text13, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** La leyenda de las comparaciones (AniList, series unidas): lo que significa cada color de una fila. */
internal val MatchLegend = listOf(
    LegendItem(StatusOk, "Coincide"), LegendItem(StatusWarn, "Parecido"), LegendItem(StatusDown, "Distinto"), LegendItem(White50, "Sin dato")
)

/** La leyenda del registro de depuración: la gravedad de cada anotación. */
internal val LogLegend = listOf(LegendItem(FailureRed, "Error (con su número E01…)"), LegendItem(White70, "Información"))
