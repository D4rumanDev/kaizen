@file:OptIn(ExperimentalTvMaterial3Api::class)

package app.kaizen.tv.ui

import app.kaizen.tv.ui.components.*

import app.kaizen.tv.ui.theme.*

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import app.kaizen.R
import app.kaizen.commons.appString
import app.kaizen.domain.SeriesStatus
import app.kaizen.source.AnilistLabels
import app.kaizen.source.CardInfo
import app.kaizen.tv.cards.formatScore
import kotlinx.coroutines.delay

/*
 * Las piezas comunes de todas las tarjetas. Marcas sobre la imagen: el estado (con su nombre) arriba a la izquierda y el favorito abajo a la derecha; debajo, título y
 * capítulo o nota. Un capítulo visto lleva un check verde junto al capítulo y uno empezado, el triángulo azul y la barra de lo visto. Cada tarjeta se monta en un solo sitio y solo cambia la forma:
 *  - de serie (portada vertical): [MediaCard] (en KaizenUi.kt; la usan SeriesCard, RecordCard, RecentCard, RecommendedCard y «Más como esto»);
 *  - horizontal (portada pequeña y texto al lado): [WideCard] («Seguir viendo», relaciones y series unidas);
 *  - de capítulo (imagen ancha): [EpisodeCard].
 * Todas parten de [FocusCard] y comparten el texto ([CardTexts]) y la barra de lo visto ([CardProgressBar]).
 */

/** Lo que tarda el panel de datos en salir con el foco quieto sobre una tarjeta (Configuración, por defecto 2 s): pasar por encima mientras se navega no lo enseña. */
internal fun focusPanelDelayMs(): Long = app.kaizen.commons.KaizenSettings.focusPanelDelay * 1000L

/**
 * Título (de [titleLines] líneas, aunque ocupe menos: así la imagen mide lo mismo en todas las tarjetas) y, si hay, una línea de [detail]: el texto común de las tarjetas, dentro del
 * borde del foco. Las tarjetas de serie llevan aquí el capítulo o la nota.
 */
@Composable
internal fun ColumnScope.CardTexts(title: String, detail: String? = null, detailColor: Color = White60, titleLines: Int = 1, seen: Boolean = false) {
    Text(title, color = Color.White, fontSize = PosterTitleText, minLines = titleLines, maxLines = titleLines, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = CardTextInset, end = CardTextInset, top = 6.dp, bottom = if (detail == null && !seen) 6.dp else 0.dp))
    if (detail != null || seen) Row(
        Modifier.padding(start = CardTextInset, end = CardTextInset, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (seen) SeenCheck()
        if (detail != null) Text(detail, color = detailColor, fontSize = PosterSubtitleText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    }
}





/**
 * El panel de una tarjeta de serie con el foco quieto ([focusPanelDelayMs], ajustable en Configuración): nota, año, formato, episodios, estado, géneros y sinopsis corta de AniList, sobre la portada.
 * La sinopsis ocupa como mucho [synopsisLines] líneas (menos en las tarjetas estrechas, donde no cabe más). Solo existe mientras la tarjeta tiene el foco, así que solo la enfocada paga su coste. No se enfoca ni lleva botones: no añade paradas a la navegación con el mando.
 */
@Composable
internal fun FocusInfoPanel(info: CardInfo, score: Int?, modifier: Modifier = Modifier, synopsisLines: Int = 6, status: SeriesStatus = SeriesStatus.NONE) {
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(focusPanelDelayMs()); ready = true }
    val shown by animateFloatAsState(if (ready) 1f else 0f, Motion.spec(180), label = "infoPanel")
    if (shown <= 0f) return
    // Interlineado fijo en todas las líneas: con el del tema salían huecos desiguales entre líneas (visto en la TV).
    Column(
        modifier.graphicsLayer { alpha = shown }.background(Black90).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        if (score != null) Text(formatScore(score / 20.0), color = ScoreColor, fontSize = Text16, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
        // Tu estado de seguimiento: sobre la imagen solo va su punto de color; aquí, con su nombre.
        if (status != SeriesStatus.NONE) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusDot(status, Modifier, 10.dp)
            PanelLine("Estado: ${status.label}", Color.White, lines = 1)
        }
        // Una sola línea de datos (año, formato, episodios) y el estado de emisión solo si dice algo: «Finalizada» es lo normal y no hace falta repetirlo en cada tarjeta.
        val facts = listOfNotNull(
            info.year?.toString(), AnilistLabels.format(info.format), info.episodes?.takeIf { it > 0 }?.let { episodesLabel(it) },
            info.status?.takeIf { it != "FINISHED" }?.let { AnilistLabels.status(it) }
        ).joinToString(" · ")
        if (facts.isNotEmpty()) PanelLine(facts, Color.White)
        if (info.genres.isNotEmpty()) PanelLine(info.genres.joinToString(", "), AccentLight, lines = 1)
        // La sinopsis solo si está en español (ver [CardInfo]); sin ella, el panel queda más corto en lugar de enseñar un texto en inglés.
        info.synopsis?.let { PanelLine(it, White70, lines = synopsisLines) }
        // La pista del menú de la tarjeta, abajo; si la sinopsis ocupa todo el sitio no se ve.
        Spacer(Modifier.weight(1f))
        PanelLine(appString(R.string.ui_manten_ok_favorito_estado_y_mas), White60)
    }
}

/** Una línea de texto del panel de datos: 12 sp con interlineado fijo y, si no cabe, puntos suspensivos. */
@Composable
private fun PanelLine(text: String, color: Color, lines: Int = 2) {
    Text(text, color = color, fontSize = Text12, lineHeight = 16.sp, maxLines = lines, overflow = TextOverflow.Ellipsis)
}


