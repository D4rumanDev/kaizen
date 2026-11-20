@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package app.kaizen.tv.ui.screens

import app.kaizen.tv.ui.*
import app.kaizen.tv.ui.components.*
import app.kaizen.tv.ui.theme.*

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.flow.map
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import app.kaizen.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.NavigationDrawer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Constraints
import app.kaizen.commons.HostGate
import app.kaizen.domain.SeriesStatuses
import app.kaizen.commons.KaizenSettings
import app.kaizen.pojos.av1.AiringSeries
import app.kaizen.tv.details.TVAnimesDetails
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlin.time.Duration.Companion.milliseconds

/**
 * Kaizen: pantalla principal al estilo SmartTube: menú lateral con las secciones (se abre al enfocarlo) y, al lado, la
 * página elegida. Arriba, la búsqueda y la hora y fecha. Atrás desde el contenido abre el menú; desde el menú avisa de
 * que otra pulsación cierra ([onBackAtMenu]).
 */
@Composable
fun LandingScreen(model: MainViewModel, activity: ComponentActivity, requested: Pair<Int, Long>?, onBackAtMenu: () -> Unit) {
    // Los ajustes de «Herramientas» (secciones visibles, reloj...) se aplican en cuanto cambian.
    val settingsChanges by produceState(0) { KaizenSettings.changes.collect { value += 1 } }
    val health by remember { KaizenSettings.sourceIdsFlow.combine(HostGate.down) { _, _ -> SourcesHealth.current() }.combine(app.kaizen.commons.NetworkState.mode) { _, _ -> SourcesHealth.current() } }
        .collectAsStateWithLifecycle(SourcesHealth.current())
    // «Buscar» y «Configuración» son acciones, no secciones de contenido: van en la barra de arriba, no en el menú lateral.
    val headers = remember(settingsChanges) {
        menuRows().filter { it.first != MainSections.SEARCH && it.first != MainSections.TOOLS }.map { Header(it.first, it.second) }
    }
    val searchVisible = remember(settingsChanges) { MainSections.SEARCH !in KaizenSettings.hiddenRows }

    var selected by rememberSaveable { mutableIntStateOf(KaizenSettings.startSection) }
    var focusedHeader by remember { mutableIntStateOf(selected) }
    // Si la sección elegida se oculta, se pasa a la primera visible.
    val current = if (headers.any { it.id == selected } || selected == MainSections.TOOLS || (selected == MainSections.SEARCH && searchVisible)) selected else headers.first().id

    androidx.compose.runtime.SideEffect { app.kaizen.tv.ui.TVMain.currentSection = current }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { app.kaizen.tv.ui.TVMain.currentSection = -1 } }

    // Las series favoritas marcan sus capítulos con un corazón; Room avisa al añadir o quitar una.
    val favorites by remember {
        favoriteKeysFlow()
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }
        .collectAsStateWithLifecycle(emptySet<String>())
    val statuses by SeriesStatuses.all.collectAsStateWithLifecycle()
    // Un orden por sección ordenable: se conserva al cambiar de sección y al girar la pantalla.
    val sortIds = remember { MainSections.CONFIGURABLE_ROWS.map { it.first } - MainSections.SOURCES }
    val sortStates = sortIds.associateWith { rememberListSort() }
    // Ligados a la sección: al cambiar de sección empiezan cerrado y sin armar, sin carreras con el foco de la sección nueva.
    var sidePanelOpen by remember(current) { mutableStateOf(false) }
    var stripFocused by remember { mutableStateOf(false) }
    // El panel de la derecha solo se alcanza cuando ya hubo una tarjeta con foco en la sección.
    var panelArmed by remember(current) { mutableStateOf(false) }
    val armPanel = remember(current) { { panelArmed = true } }
    var stripClosing by remember { mutableStateOf(false) }
    val content = remember { FocusRequester() }
    // Sección pedida desde fuera (el menú de una ficha): se muestra y el foco entra en ella.
    LaunchedEffect(requested) {
        requested?.let { selected = it.first; focusedHeader = it.first }
    }
    val headerRequesters = remember(headers.map { it.id }) { headers.associate { it.id to FocusRequester() } }
    var contentFocused by remember { mutableStateOf(false) }
    var menuFocused by remember { mutableStateOf(false) }
    // La entrada del menú a la que vuelve el foco: la de la sección en uso o, si esta está en la barra de arriba (Buscar, Configuración), la primera.
    val menuTarget = { headerRequesters[current] ?: headers.firstOrNull()?.let { headerRequesters[it.id] } }
    // Al bajar por el menú no se monta cada página que se cruza: la sección cambia tras un instante de foco quieto,
    // y solo si el foco sigue en el menú (si ya entró en la página, cambiarla le quitaría el foco).
    LaunchedEffect(focusedHeader) {
        delay(FOCUS_SELECT_DELAY)
        if (!contentFocused) selected = focusedHeader
    }
    var menuItem by remember { mutableStateOf<Any?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var verifyHost by remember { mutableStateOf<String?>(null) }
    var updateRelease by remember { mutableStateOf<app.kaizen.commons.UpdateChecker.Release?>(null) }

    // Se abre con el foco en el menú lateral, en la sección de inicio: el contenido (que tarda en cargar) no lo reclama ni abre paneles.
    LaunchedEffect(Unit) {
        // Se reintenta hasta que el foco esté en el menú o el usuario ya haya entrado en el contenido (entonces no se le quita).
        repeat(INITIAL_FOCUS_TRIES) {
            withFrameNanos { }
            if (contentFocused || menuFocused) return@LaunchedEffect
            runCatching { menuTarget()?.requestFocus() }
            delay(INITIAL_FOCUS_DELAY)
        }
    }
    // Al cerrar el panel de la derecha el foco vuelve a las tarjetas.
    // Si la sección no tiene tarjetas el foco caería en la tira del borde y reabriría el panel: entonces pasa al menú lateral.
    val closeSidePanel = {
        // Primero el foco a las tarjetas y luego se quita el panel: así no pasa por el menú lateral al desaparecer el panel.
        runCatching { content.requestFocus() }
        sidePanelOpen = false
        stripClosing = true
        scope.launch {
            delay(MENU_REFOCUS)
            // Sin tarjetas (p. ej. un filtro que no deja ninguna) el foco quedaría en el panel que se quita o en la tira: al menú.
            if (stripFocused || !contentFocused) runCatching { menuTarget()?.requestFocus() }
            delay(MENU_REFOCUS)
            stripClosing = false
        }
        Unit
    }
    // Un solo manejador de Atrás (el último registrado gana): primero cierra el panel de la derecha si está abierto.
    BackHandler {
        when {
            sidePanelOpen -> closeSidePanel()
            contentFocused -> runCatching { menuTarget()?.requestFocus() }
            else -> onBackAtMenu()
        }
    }

    val actions = remember {
        CardActions(
            open = { item ->
                when (item) {
                    is RetryCard -> item.retry()
                    is VerifyCard -> verifyHost = item.host
                    is AiringSeries -> TVAnimesDetails.start(activity, item.animeUrl)
                    else -> CardMenu.open(activity, item)
                }
            },
            menu = { menuItem = it }
        )
    }

    CompositionLocalProvider(LocalFavorites provides favorites, LocalStatuses provides statuses, LocalSectionSort provides sortStates[current], LocalOnCardFocus provides armPanel) {
        NavigationDrawer(
            drawerContent = {
                KaizenSidebar(headers, current, health, headerRequesters, onMenuFocus = { menuFocused = it },
                    onFocusedHeader = { focusedHeader = it }, onSelect = { selected = it; runCatching { content.requestFocus() } })
            }
        ) {
            // La biblioteca empuja el contenido al abrir y cerrar el menú (su ancho cambia en cada fotograma de la animación): aquí se mide siempre con el ancho de
            // «menú cerrado» y solo se recorta, así que al pasar del menú al contenido nada se redimensiona ni se mueve.
            FixedContentArea {
            Column(Modifier.fillMaxSize().background(Background)) {
                TopBar(current, searchVisible, settingsChanges, onUpdate = { updateRelease = it }) {
                    selected = it
                    // La página nueva tarda un instante en componerse: pedir el foco ya, sin nada enfocable dentro, lo dejaba sin dueño y caía en el menú lateral (que se abría en «Inicio»).
                    scope.launch {
                        delay(TOP_SELECT_FOCUS)
                        if (!menuFocused) runCatching { content.requestFocus() }
                    }
                }
                Box(
                    Modifier.weight(1f).fillMaxWidth()
                        .onFocusChanged { contentFocused = it.hasFocus }
                        .focusRequester(content)
                        .focusGroup()
                ) {
                    MainPage(current, model, actions)
                    // Panel de orden (y filtro) oculto a la derecha, solo en las secciones con tarjetas que se pueden ordenar.
                    sortStates[current]?.let { sort ->
                        Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight()) {
                            SidePanel(sort, sidePanelOpen, enabled = panelArmed, onOpen = { if (!stripClosing) sidePanelOpen = true }, onClose = closeSidePanel, onStripFocus = { stripFocused = it }, showSeen = current == MainSections.HOME)
                        }
                    }
                }
            }
            }
        }
    }
    // Al cerrar el menú de una tarjeta que ya no existe (p. ej. «Quitar de Seguir viendo») el foco caería en el menú lateral: vuelve al contenido.
    menuItem?.let { item ->
        CardMenu.Menu(item) {
            menuItem = null
            scope.launch {
                delay(MENU_REFOCUS)
                if (!contentFocused) runCatching { content.requestFocus() }
                // «Quitar de Seguir viendo»: la tarjeta desaparece cuando el modelo se recarga, un poco después de cerrar el menú, y el foco
                // cae en el menú lateral, que tras FOCUS_SELECT_DELAY cambiaría de sección (a Recientes). Se vigila un segundo y el foco
                // vuelve al contenido en cuanto sale, antes de que eso pase.
                if (item is app.kaizen.tv.anime.ContinueItem) repeat(REFOCUS_WATCH_TICKS) {
                    delay(REFOCUS_WATCH)
                    if (!contentFocused) runCatching { content.requestFocus() }
                }
            }
        }
    }
    verifyHost?.let { host -> VerifySourceDialog(host) { verifyHost = null } }
    updateRelease?.let { release -> UpdateDialog(release) { updateRelease = null } }
    UiScaleConfirm(activity)
}


/**
 * El contenido de la pantalla principal con el ancho del menú cerrado (el mayor que le da la biblioteca): el menú, al abrirse, pasa por encima y tapa lo que quede
 * debajo (se recorta a lo visible) en lugar de empujar y redimensionar el contenido. El ancho de «cerrado» se mide, no se supone: la biblioteca no usa un valor redondo.
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun FixedContentArea(content: @Composable () -> Unit) {
    // Se vuelve a medir si cambia la densidad (otra escala de interfaz): el ancho de «menú cerrado» ya no sería el mismo.
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val widest = remember(density) { intArrayOf(0) }
    // Todas las listas y rejillas de la pantalla se desplazan con suavidad al mover el foco (ver [smoothScroll]).
    val scroll = remember(density) { smoothScroll(12f * density) }
    CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides scroll) {
        Layout(content = content, modifier = Modifier.fillMaxSize().clipToBounds()) { measurables, constraints ->
            if (constraints.maxWidth > widest[0]) widest[0] = constraints.maxWidth
            val fixedWidth = widest[0]
            val placeables = measurables.map { it.measure(Constraints.fixed(fixedWidth, constraints.maxHeight)) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                // Con el menú abierto el hueco es menor que el ancho fijo: se desplaza a la izquierda lo que falta y el contenido no se mueve en pantalla.
                val x = constraints.maxWidth - fixedWidth
                placeables.forEach { it.place(x, 0) }
            }
        }
    }
}

private const val INITIAL_FOCUS_TRIES = 30
private val INITIAL_FOCUS_DELAY = 250.milliseconds

/** Un botón redondo con solo el icono (azul si es la sección en uso, blanco con el foco), como las entradas del menú lateral. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TopIconButton(@androidx.annotation.DrawableRes icon: Int, description: String, selected: Boolean, onClick: () -> Unit) {
    androidx.tv.material3.IconButton(
        onClick = onClick, modifier = Modifier.size(40.dp),
        colors = androidx.tv.material3.IconButtonDefaults.colors(
            containerColor = if (selected) Accent else SurfaceMuted, contentColor = androidx.compose.ui.graphics.Color.White,
            focusedContainerColor = androidx.compose.ui.graphics.Color.White, focusedContentColor = SurfaceDark
        )
    ) { androidx.tv.material3.Icon(androidx.compose.ui.res.painterResource(icon), contentDescription = description, modifier = Modifier.size(22.dp)) }
}

/** Arriba: a la derecha, «Buscar» y «Configuración» (iconos; el de la sección en uso, en azul) y la hora y la fecha, según «Pantalla». */
@Composable
private fun TopBar(current: Int, searchVisible: Boolean, @Suppress("UNUSED_PARAMETER") settingsChanges: Int, onUpdate: (app.kaizen.commons.UpdateChecker.Release) -> Unit, onSelect: (Int) -> Unit) {
    val update by app.kaizen.commons.UpdateChecker.available.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(start = ContentPaddingHorizontal, end = ContentPaddingHorizontal, top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            // Solo si hay una versión nueva (y el ajuste está en «Avisar»): pulsarla abre el diálogo de actualizar.
            update?.let { release -> UpdateBadge(release, onClick = { onUpdate(release) }) }
            if (searchVisible) TopIconButton(R.drawable.header_ic_search, "Buscar", current == MainSections.SEARCH) { onSelect(MainSections.SEARCH) }
            TopIconButton(R.drawable.header_bottom_settings, "Configuración", current == MainSections.TOOLS) { onSelect(MainSections.TOOLS) }
            Spacer(Modifier.width(8.dp))
            if (KaizenSettings.topClock) Clock(showDate = true, fontSize = Text16)
        }
        // La línea fina bajo la hora separa la cabecera del contenido.
        Spacer(Modifier.fillMaxWidth().padding(top = 6.dp).height(1.dp).background(Border))
    }
}

private val FOCUS_SELECT_DELAY = 250.milliseconds
private val TOP_SELECT_FOCUS = 200.milliseconds
private val MENU_REFOCUS = 100.milliseconds
private val REFOCUS_WATCH = 50.milliseconds
private const val REFOCUS_WATCH_TICKS = 20
