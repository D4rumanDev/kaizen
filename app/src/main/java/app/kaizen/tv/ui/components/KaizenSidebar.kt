@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package app.kaizen.tv.ui.components

import app.kaizen.tv.ui.theme.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.NavigationDrawerItemDefaults
import androidx.tv.material3.Text
import app.kaizen.R
import app.kaizen.commons.KaizenSettings
import androidx.compose.ui.text.style.TextOverflow
import app.kaizen.tv.ui.*
import app.kaizen.tv.ui.theme.*
import androidx.tv.material3.NavigationDrawerScope
import androidx.compose.foundation.layout.size

/** Una cabecera del menú lateral. */
internal class Header(val id: Int, val title: String)

internal fun iconFor(id: Int): Int = when (id) {
    MainSections.HOME -> R.drawable.header_ic_home
    MainSections.FAVORITES -> R.drawable.header_ic_heart
    MainSections.STATUS -> R.drawable.header_ic_list
    MainSections.LAST_SEEN -> R.drawable.header_ic_record
    MainSections.STAFF -> R.drawable.header_ic_star_heart
    MainSections.BEST -> R.drawable.header_ic_trending
    MainSections.BESTGLOBAL -> R.drawable.header_ic_trophy
    MainSections.SEARCH -> R.drawable.header_ic_search
    MainSections.EMISSION -> R.drawable.header_ic_emision
    else -> R.drawable.header_bottom_settings
}


/** Entradas del menú lateral (id, título): las secciones visibles (también «Fuentes», que se ordena y se oculta como las demás) y «Configuración». También las usa el menú de la ficha. */
internal fun menuRows(): List<Pair<Int, String>> {
    val hidden = KaizenSettings.hiddenRows
    return MainSections.ordered().filter { it.first !in hidden } + (MainSections.TOOLS to "Configuración")
}


/**
 * El menú lateral de la pantalla principal: una entrada por sección; la elegida va en azul lleno, el foco siempre en blanco (también sobre la elegida, para que no cambie de color al desplazarse) y el resto apagado.
 * Con la interfaz grande no cabe: se desplaza y el foco lleva la lista a la entrada enfocada.
 */
@Composable
internal fun NavigationDrawerScope.KaizenSidebar(
    headers: List<Header>,
    current: Int,
    health: SourcesHealth,
    headerRequesters: Map<Int, FocusRequester>,
    onMenuFocus: (Boolean) -> Unit,
    onFocusedHeader: (Int) -> Unit,
    onSelect: (Int) -> Unit
) {
    // Con la interfaz grande el menú no cabe: se desplaza, y el foco lleva la lista a la entrada enfocada.
    Box(Modifier.fillMaxHeight().background(SurfaceDark).padding(horizontal = 8.dp)) {
    Column(
        Modifier.align(Alignment.Center).verticalScroll(rememberScrollState())
            // Al entrar desde el contenido con la flecha izquierda el foco va a la entrada de la sección en uso (no a la que quede a la
            // altura del elemento, y no a «Inicio»: eso cambiaba de sección y recargaba la página). Sin ella, a la primera.
            .focusEnter { (headerRequesters[current] ?: headers.firstOrNull()?.let { headerRequesters[it.id] }) ?: FocusRequester.Default }
            .onFocusChanged { onMenuFocus(it.hasFocus) }
            .focusGroup()
    ) {
        headers.forEach { header ->
            NavigationDrawerItem(
                selected = header.id == current,
                onClick = { onSelect(header.id) },
                // La sección en uso va en gris claro y lleno; el foco, en blanco; el resto, apagado: se distinguen a primera vista.
                colors = NavigationDrawerItemDefaults.colors(
                    selectedContainerColor = Accent, selectedContentColor = Color.White,
                    focusedContainerColor = Color.White, focusedContentColor = SurfaceDark,
                    focusedSelectedContainerColor = Color.White, focusedSelectedContentColor = SurfaceDark,
                    inactiveContentColor = Color.White.copy(alpha = 0.55f)
                ),
                // El mismo redondeo que el resto de la interfaz (antes, píldora) y tamaños de la propuesta.
                shape = NavigationDrawerItemDefaults.shape(ShapeMd),
                leadingContent = {
                    if (header.id == MainSections.SOURCES) Led(health)
                    else Icon(painterResource(iconFor(header.id)), contentDescription = null, modifier = Modifier.size(IconSm))
                },
                modifier = Modifier.focusRequester(headerRequesters.getValue(header.id))
                    .onFocusChanged { if (it.isFocused) onFocusedHeader(header.id) }
            ) { Text(header.title, fontSize = Text14, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
    }
}
