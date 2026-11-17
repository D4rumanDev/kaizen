package app.kaizen.tv.search

import app.kaizen.tv.ui.components.*

import app.kaizen.tv.ui.theme.*

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import app.kaizen.pojos.av1.Genre
import app.kaizen.tv.TVBaseActivity
import app.kaizen.tv.TVRepository
import app.kaizen.tv.details.FichaMenu
import app.kaizen.tv.details.TVAnimesDetails
import app.kaizen.tv.ui.TVMain
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import app.kaizen.tv.ui.CardActions
import app.kaizen.tv.ui.CardMenu
import app.kaizen.tv.ui.LocalOnCardFocus
import app.kaizen.tv.ui.LocalSectionSort
import app.kaizen.tv.ui.SidePanel
import app.kaizen.tv.ui.rememberListSort
import app.kaizen.tv.ui.PagedGrid
import app.kaizen.tv.ui.ProvideSeriesMarks

/** Series de un género, en rejilla. */
class TVTag : TVBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra(keyName) ?: ""
        val slug = intent.getStringExtra(keySlug) ?: ""
        setContent {
            KaizenTheme {
                val scope = rememberCoroutineScope()
                val items = remember { TVRepository.searchQuery("", slug).cachedIn(scope) }.collectAsLazyPagingItems()
                // Lo mismo que en las secciones de la pantalla principal: orden y filtro en el panel de la derecha, menú de tarjeta con
                // pulsación larga y marcas (corazón y estado).
                val sort = rememberListSort()
                var panelOpen by remember { mutableStateOf(false) }
                var panelArmed by remember { mutableStateOf(false) }
                var menuItem by remember { mutableStateOf<Any?>(null) }
                val content = remember { FocusRequester() }
                val actions = remember { CardActions(open = { CardMenu.open(this@TVTag, it) }, menu = { menuItem = it }) }
                val armPanel = remember { { panelArmed = true } }
                val closePanel = { runCatching { content.requestFocus() }; panelOpen = false; Unit }
                BackHandler(panelOpen) { closePanel() }
                // Al llegar los resultados el foco entra en la rejilla (si no, el primero que lo pide es el menú lateral y se despliega).
                val hasItems = items.itemCount > 0
                LaunchedEffect(hasItems) { if (hasItems) { withFrameNanos { }; runCatching { content.requestFocus() } } }
                // El menú lateral de la pantalla principal (flecha izquierda), como en la ficha: elegir una sección vuelve a ella.
                FichaMenu(onSection = { TVMain.openSection(this@TVTag, it); finish() }, loading = items.itemCount == 0 && items.loadState.refresh is androidx.paging.LoadState.Loading) {
                    ProvideSeriesMarks {
                        CompositionLocalProvider(LocalSectionSort provides sort, LocalOnCardFocus provides armPanel) {
                            Box(Modifier.fillMaxSize().background(Background)) {
                                Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
                                    SectionTitle(name, Modifier.padding(horizontal = ContentPaddingHorizontal))
                                    Box(Modifier.weight(1f).focusRequester(content).focusGroup()) {
                                        PagedGrid(items, actions, onOpen = { TVAnimesDetails.start(this@TVTag, it.animeUrl) })
                                    }
                                }
                                Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight()) {
                                    SidePanel(sort, panelOpen, enabled = panelArmed, onOpen = { if (items.itemCount > 0) panelOpen = true }, onClose = closePanel)
                                }
                            }
                        }
                    }
                }
                menuItem?.let { item -> CardMenu.Menu(item) { menuItem = null } }
            }
        }
    }

    companion object {

        private const val keyName = "name"
        private const val keySlug = "slug"

        fun start(context: Context, genre: Genre) {
            context.startActivity(Intent(context, TVTag::class.java)
                .putExtra(keyName, genre.name)
                .putExtra(keySlug, genre.slug)
            )
        }
    }
}
