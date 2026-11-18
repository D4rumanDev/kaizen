@file:OptIn(ExperimentalTvMaterial3Api::class, FlowPreview::class, ExperimentalComposeUiApi::class)

package app.kaizen.tv.search

import app.kaizen.tv.ui.theme.*
import app.kaizen.tv.ui.components.*

import app.kaizen.source.SortBy
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import app.kaizen.tv.ui.focusEnter
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.kaizen.R
import app.kaizen.commons.findActivity
import app.kaizen.pojos.av1.Genre
import app.kaizen.search.SearchGenres
import app.kaizen.tv.TVRepository
import app.kaizen.tv.ui.CardActions
import app.kaizen.tv.ui.CardGrid
import app.kaizen.tv.ui.PagedGrid
import app.kaizen.tv.ui.fullWidth
import app.kaizen.tv.ui.cardSpacing
import androidx.compose.foundation.layout.PaddingValues
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import app.kaizen.commons.appString
import kotlin.time.Duration.Companion.milliseconds

/**
 * Página «Buscar» del menú lateral: texto y géneros. Sin texto no se pide nada a las fuentes (solo salen los géneros);
 * con texto, las consultas esperan a que se deje de teclear y se piden por páginas, a medida que se baja.
 */
@Composable
internal fun SearchPage(actions: CardActions) {
    var text by rememberSaveable { mutableStateOf("") }
    val sort = SortBy.TITLE
    val query by remember { snapshotFlow { text }.debounce(QUERY_DELAY) }.collectAsState(initial = text)
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val input = remember { FocusRequester() }
    // Bajar del campo de texto lleva al primer género, no al que quede más cerca.
    val firstGenre = remember { FocusRequester() }

    // Al entrar en la página desde el menú el foco va al campo de texto, no a lo primero que quede a la derecha.
    Column(Modifier.fillMaxSize().focusEnter { input }.focusGroup()) {
        var focused by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().padding(start = ContentPaddingHorizontal, end = ContentPaddingHorizontal, top = 8.dp, bottom = 8.dp)
                .border(BorderStroke(if (focused) 3.dp else 1.dp, if (focused) Color.White else White40), ShapeLg)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().focusRequester(input).onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        // Si el primer género aún no está en pantalla, se usa el foco normal hacia abajo.
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                            if (!runCatching { firstGenre.requestFocus() }.getOrDefault(false)) focusManager.moveFocus(FocusDirection.Down)
                            true
                        } else false
                    },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = Text16),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // La tecla de buscar del teclado lo cierra y pasa el foco a los resultados (con el teclado abierto el mando
                // solo recorre sus teclas).
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    focusManager.moveFocus(FocusDirection.Down)
                }),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) Text(appString(R.string.ui_buscar_anime), color = White50, fontSize = Text16)
                        inner()
                    }
                }
            )
        }
        val onGenre: (Genre) -> Unit = { genre -> context.findActivity()?.let { TVTag.start(it, genre) } }
        Box(Modifier.weight(1f)) {
            if (query.isBlank()) {
                CardGrid {
                    genreRows(onGenre, firstGenre)
                    fullWidth { SectionTitle(appString(R.string.ui_escribe_el_nombre_de_un_anime_o_elige_un_genero)) }
                }
            } else {
                val results = remember(query, sort) { TVRepository.searchQuery(query, sort = sort) }.collectAsLazyPagingItems()
                PagedGrid(results, actions, onOpen = { actions.open(it) }, header = {
                    genreRows(onGenre, firstGenre)
                    fullWidth { SectionTitle("Resultados para '$query'") }
                })
            }
        }
    }
}

/** Fila de géneros, que ocupa todo el ancho de la rejilla. */
private fun LazyGridScope.genreRows(onGenre: (Genre) -> Unit, first: FocusRequester) {
    item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(appString(R.string.ui_generos)) }
    item(span = { GridItemSpan(maxLineSpan) }) {
        LazyRow(contentPadding = PaddingValues(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(cardSpacing)) {
            items(SearchGenres.genres, key = { it.slug }) { genre ->
                FocusCard(Modifier.size(width = 150.dp, height = 40.dp).then(if (genre == SearchGenres.genres.first()) Modifier.focusRequester(first) else Modifier), { onGenre(genre) }) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_tag), contentDescription = null, tint = Color.White, modifier = Modifier.size(IconSm))
                        Text(genre.name, color = Color.White, fontSize = Text13, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

private val QUERY_DELAY = 450.milliseconds
