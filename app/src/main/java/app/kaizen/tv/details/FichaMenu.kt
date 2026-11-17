@file:OptIn(ExperimentalTvMaterial3Api::class)

package app.kaizen.tv.details

import app.kaizen.tv.ui.theme.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import app.kaizen.tv.ui.Led
import app.kaizen.tv.ui.SourcesHealth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Text
import app.kaizen.tv.ui.MainSections
import app.kaizen.tv.ui.TVMain
import app.kaizen.tv.ui.components.iconFor
import app.kaizen.tv.ui.components.menuRows
import app.kaizen.tv.ui.screens.FixedContentArea

/** El menú lateral de la pantalla principal también en la ficha (flecha izquierda): elegir una sección cierra las fichas y vuelve a ella. */
@Composable
fun FichaMenu(onSection: (Int) -> Unit, loading: Boolean = false, content: @Composable () -> Unit) {
    // Como en la pantalla principal, «Fuentes» lleva el piloto de estado y no un engranaje.
    val health = remember { SourcesHealth.current() }
    // Al entrar con la flecha izquierda el foco va siempre a la primera entrada, no a la que quede a la altura del elemento.
    val first = remember { FocusRequester() }
    // La entrada de la sección donde estaba la pantalla principal: elegirla solo cierra las fichas, sin recargar nada.
    val origin = remember { TVMain.currentSection }
    NavigationDrawer(drawerContent = {
        // La biblioteca da el foco a la entrada más cercana al elemento de donde viene: se mueve siempre a la primera.
        androidx.compose.runtime.LaunchedEffect(hasFocus) {
            if (hasFocus) { androidx.compose.runtime.withFrameNanos { }; runCatching { first.requestFocus() } }
        }
        Box(Modifier.fillMaxHeight().background(SurfaceDark).padding(horizontal = 12.dp)) {
            // Cargando no hay nada más enfocable: sin esto el foco cae en «Inicio» y se lo quita después la portada (parpadeo).
            Column(Modifier.align(Alignment.Center).verticalScroll(rememberScrollState()).focusProperties { canFocus = !loading }) {
                val rows = menuRows()
                val entry = rows.indexOfFirst { it.first == origin }.coerceAtLeast(0)
                rows.forEachIndexed { index, (id, title) ->
                    NavigationDrawerItem(
                        selected = false,
                        modifier = if (index == entry) Modifier.focusRequester(first) else Modifier,
                        onClick = { onSection(id) },
                        leadingContent = {
                            if (id == MainSections.SOURCES) Led(health) else Icon(painterResource(iconFor(id)), contentDescription = null)
                        }
                    ) { Text(if (id == MainSections.SOURCES) "$title · ${health.label}" else title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }) {
        // Como en la pantalla principal: al abrir el menú el contenido no se redimensiona, se desplaza hacia la derecha y se recorta.
        FixedContentArea(content)
    }
}

/** Hueco con la forma de la ficha (portada, título, texto, botones y fila de episodios) mientras llega: mismas medidas, sin foco, con un latido suave. */
@Composable
fun FichaSkeleton() {
    val pulse by rememberInfiniteTransition(label = "fichaSkeleton").animateFloat(
        initialValue = 0.45f, targetValue = 1f, label = "fichaSkeletonAlpha",
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse)
    )
    @Composable
    fun Bar(modifier: Modifier) = Box(modifier.clip(RoundedCornerShape(4.dp)).background(White10))
    Column(Modifier.fillMaxSize().background(Background).graphicsLayer { alpha = pulse }) {
        Row(Modifier.fillMaxWidth().height(DetailHeaderMinHeight + 40.dp).padding(start = ContentPaddingHorizontal, end = ContentPaddingHorizontal, top = 40.dp, bottom = 20.dp)) {
            Bar(Modifier.width(DetailPosterWidth).fillMaxHeight())
            Spacer(Modifier.width(32.dp))
            Column(Modifier.weight(1f), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                Bar(Modifier.width(120.dp).height(16.dp))
                Bar(Modifier.fillMaxWidth(0.6f).height(36.dp))
                Bar(Modifier.fillMaxWidth(0.4f).height(24.dp))
                Bar(Modifier.fillMaxWidth().weight(1f))
                Bar(Modifier.width(220.dp).height(48.dp))
            }
        }
        Bar(Modifier.padding(start = ContentPaddingHorizontal, top = DetailSectionSpacing, bottom = 8.dp).width(140.dp).height(20.dp))
        Row(Modifier.padding(start = ContentPaddingHorizontal), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(PosterHorizontalSpacing)) {
            repeat(3) { Bar(Modifier.width(EpisodeCardWidth).height(EpisodeImageHeight + 50.dp)) }
        }
    }
}
