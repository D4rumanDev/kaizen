@file:OptIn(ExperimentalTvMaterial3Api::class)

package app.kaizen.tv.ui

import app.kaizen.tv.ui.components.*

import app.kaizen.tv.ui.theme.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.ProduceStateScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import app.kaizen.tv.details.TVAnimesDetails
import kotlinx.coroutines.launch
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import app.kaizen.commons.logError
import app.kaizen.commons.runCatchingCoop
import app.kaizen.source.AnimeSources
import app.kaizen.source.Catalog
import app.kaizen.source.seriesKeyOf
import app.kaizen.source.SeriesLedger
import app.kaizen.source.seriesIdOf
import app.kaizen.source.searchSeed
import kotlinx.coroutines.delay
import app.kaizen.R
import app.kaizen.commons.appString
import kotlin.time.Duration.Companion.milliseconds

/** Diálogo para elegir otra serie del catálogo buscando por nombre; [onPick] recibe la elegida. */
@Composable
internal fun SeriesPickerDialog(
    title: String,
    originName: String,
    originCovers: List<String>,
    /** La ficha de la serie de origen (fuente y slug): de ella salen su año, su fuente y sus episodios. */
    originFicha: Pair<String, String>? = null,
    /** Si se da, solo se puede elegir entre estas series (sin buscador). */
    choices: List<Catalog.Pick>? = null,
    onPick: (Catalog.Pick) -> Unit,
    onDismiss: () -> Unit
) {
    // Arranca con la primera palabra del nombre, cortada antes de símbolos (las demás fuentes lo escriben con otros acentos o vocales largas).
    val initialText = remember { if (choices == null) searchSeed(originName) else "" }
    var text by remember { mutableStateOf(initialText) }
    var results by remember { mutableStateOf(choices.orEmpty()) }
    // Lo que devuelven las fuentes al buscar el mismo texto: series que el catálogo aún no tiene o que no casan por nombre.
    var live by remember { mutableStateOf(emptyList<Catalog.Pick>()) }
    var searchingLive by remember { mutableStateOf(false) }
    // Pulsación larga en un resultado: abrir su ficha (en cada fuente donde esté) para comprobar que es la misma serie.
    var fichaMenu by remember { mutableStateOf<Catalog.Pick?>(null) }
    val input = remember { FocusRequester() }
    // El buscador solo lee el catálogo: se recorre en Configuración › Biblioteca › Fuentes, no desde aquí.
    val catalogReady by produceState(true) { value = runCatchingCoop { Catalog.isReady() }.getOrDefault(true) }
    val origin by produceState<Catalog.Pick?>(null, originFicha) { value = originFicha?.let { Catalog.pickOfFicha(it.first, it.second) } }
    // Las fichas que ya forman la serie de origen (la propia primero) con sus episodios, que salen según llegan.
    val originFichas by produceState(emptyList<Pair<String, Int?>>(), originFicha) {
        val own = originFicha ?: return@produceState
        val fichas = (listOf(own) + SeriesLedger.fichaKeysOf(seriesIdOf(own.first, own.second)).map { it.substringBefore(':') to it.substringAfter(':') })
            .distinct().take(PICK_SOURCE_LINES)
        publishEpisodeCounts(fichas.map { (source, slug) -> Triple(source, AnimeSources.of(source).name, slug) })
    }
    // Las series que el catálogo propone unir con la de origen (mismo slug sin sufijos) salen las primeras.
    val proposed by produceState(emptyList<Catalog.Pick>(), origin?.key) {
        val key = origin?.key
        value = if (choices != null || key.isNullOrEmpty()) emptyList() else runCatchingCoop { Catalog.mergeProposals() }
            .onFailure(::logError).getOrDefault(emptyList())
            .mapNotNull { proposal -> when (key) { proposal.first.key -> proposal.second; proposal.second.key -> proposal.first; else -> null } }
            .distinctBy { it.key }
    }
    LaunchedEffect(text) {
        if (choices != null) return@LaunchedEffect
        // El texto con el que se abre se busca ya; al escribir, tras una pausa.
        if (text != initialText) delay(300.milliseconds)
        live = emptyList()
        results = Catalog.searchEntries(text)
        if (text.isBlank()) return@LaunchedEffect
        searchingLive = true
        try {
            val keys = results.map { it.key }.toSet()
            live = Catalog.searchLive(text).filter { it.key.isEmpty() || it.key !in keys }
        } finally {
            searchingLive = false
        }
    }
    fichaMenu?.let { picked -> PickFichaMenu(picked) { fichaMenu = null } }
    Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // El campo se compone dentro de un SubcomposeLayout: se espera un fotograma para que ya exista.
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { input.requestFocus() } }
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
        // Todo el alto posible, con el mismo margen abajo que arriba: lo que quede detrás del teclado no importa.
        Column(Modifier.fillMaxWidth(0.9f).widthIn(max = 1100.dp).fillMaxHeight().background(PanelColor, ShapeXl).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Color.White, fontSize = Text18, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                // La serie de origen a un lado, para ver con cuál se está uniendo o separando.
                Column(Modifier.width(220.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Cover(originCovers, Modifier.size(width = 110.dp, height = 156.dp).clip(ShapeMd))
                    Text(originName, color = Color.White, fontSize = Text13, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    origin?.year?.let { Text(it.toString(), color = Highlight, fontSize = Text14) }
                    // Un bloque por ficha ya unida, justo debajo del año: la fuente en blanco y sus episodios debajo, para leerlas de un vistazo.
                    originFichas.forEach { (name, count) ->
                        Column(Modifier.padding(top = 6.dp)) {
                            Text(name, color = Color.White, fontSize = Text13, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(count?.let { episodesLabel(it) } ?: "…", color = Highlight, fontSize = Text14, maxLines = 1)
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (choices == null) TitleField(text, { text = it }, input, appString(R.string.ui_buscar_serie))
                    if (choices == null && !catalogReady) Text(appString(R.string.ui_catalogo_sin_recorrer), color = White70, fontSize = Text14)
                    // La serie de origen no se ofrece para unirla consigo misma (ni por su clave ni por su ficha).
                    val originKey = origin?.key.orEmpty()
                    val proposedKeys = proposed.map { it.key }.toSet()
                    val shown = if (choices == null) results.filter { (originKey.isEmpty() || it.key != originKey) && it.key !in proposedKeys } else results
                    val shownLive = live.filter { pick ->
                        (originKey.isEmpty() || pick.key != originKey) && (pick.key.isEmpty() || pick.key !in proposedKeys) && (originFicha == null || pick.sourceId to pick.slug != originFicha)
                    }
                    if (choices == null && text.isNotBlank() && proposed.isEmpty() && shown.isEmpty() && shownLive.isEmpty()) {
                        Text(if (searchingLive) appString(R.string.ui_buscando_en_las_fuentes) else appString(R.string.ui_sin_resultados), color = White70, fontSize = Text14)
                    }
                    // Tarjetas con la forma de las de «Seguir viendo» y «Series unidas»: no hace falta que ocupen todo el ancho.
                    LazyVerticalGrid(
                        GridCells.Adaptive(PickCardWidth), Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        if (proposed.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) { Text(appString(R.string.ui_propuestas), color = White70, fontSize = Text14) }
                            items(proposed, key = { "proposed:${it.key}" }) { pick -> PickCard(pick, onLongClick = { fichaMenu = pick }) { onPick(pick) } }
                        }
                        items(shown, key = { "${it.key}|${it.sourceId}|${it.slug}" }) { pick -> PickCard(pick, onLongClick = { fichaMenu = pick }) { onPick(pick) } }
                        if (shownLive.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) { Text(appString(R.string.ui_de_las_fuentes), color = White70, fontSize = Text14, modifier = Modifier.padding(top = 6.dp)) }
                            items(shownLive, key = { "live:${it.sourceId}|${it.slug}" }) { pick -> PickCard(pick, onLongClick = { fichaMenu = pick }, joined = originFicha != null && sameJoined(originFicha, pick)) { if (originFicha == null || !sameJoined(originFicha, pick)) onPick(pick) } }
                        }
                    }
                }
            }
        }
        }
    }
}

/** Los episodios de una ficha; [NO_EPISODES] (la fuente no respondió) sale como «sin datos». */
internal fun episodesLabel(count: Int) = if (count < 0) appString(R.string.ui_sin_datos) else if (count == 1) "1 episodio" else "$count episodios"

/** Valor de «episodios» cuando la fuente no respondió (null es «aún cargando»). */
internal const val NO_EPISODES = -1

/** Menú de pulsación larga de un resultado: abre su ficha en la fuente que se elija. */
@Composable
private fun PickFichaMenu(pick: Catalog.Pick, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val fichas by produceState(emptyList<Triple<String, String, String>>(), pick.key, pick.sourceId, pick.slug) { value = Catalog.fichasOf(pick) }
    val first = remember { FocusRequester() }
    KaizenDialog(onDismissRequest = onDismiss, title = pick.name) {
        LaunchedEffect(fichas.isEmpty()) { withFrameNanos { }; runCatching { first.requestFocus() } }
        fichas.forEachIndexed { index, (id, name, slug) ->
            KaizenButton(appString(R.string.ui_abrir_ficha) + " · " + name,
                onClick = { TVAnimesDetails.start(context, AnimeSources.of(id).animeUrl(slug), inPlace = false); onDismiss() },
                modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(first) else Modifier))
        }
        KaizenButton(appString(R.string.ui_cancelar), onClick = onDismiss, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Publica en el estado una línea por ficha (fuente, slug) con su nombre y, según va llegando de cada web (en paralelo), cuántos episodios tiene;
 * mientras tanto, null. [fichas]: (id de fuente, nombre a enseñar, slug).
 */
private suspend fun ProduceStateScope<List<Pair<String, Int?>>>.publishEpisodeCounts(fichas: List<Triple<String, String, String>>) {
    value = fichas.map { (_, label, _) -> label to null }
    kotlinx.coroutines.coroutineScope {
        fichas.forEachIndexed { index, (source, _, slug) ->
            launch {
                val count = Catalog.episodeCount(source, slug) ?: NO_EPISODES
                value = value.toMutableList().also { it[index] = it[index].first to count }
            }
        }
    }
}

/** Ancho mínimo de una tarjeta del buscador de series (las columnas se adaptan al ancho del diálogo). */
private const val PICK_SOURCE_LINES = 3

/** Una serie del buscador, con la forma de la tarjeta de «Seguir viendo»: portada estrecha a la izquierda y los datos al lado. */
@Composable
private fun PickCard(pick: Catalog.Pick, onLongClick: () -> Unit, joined: Boolean = false, onClick: () -> Unit) {
    // Los episodios de cada fuente donde está la serie se piden al verse la tarjeta (la lista es perezosa) y salen según llegan.
    val counts by produceState(emptyList<Pair<String, Int?>>(), pick.key, pick.sourceId, pick.slug) {
        val fichas = Catalog.fichasOf(pick).take(PICK_SOURCE_LINES)
        publishEpisodeCounts(fichas)
    }
    FocusCard(Modifier.fillMaxWidth(), onClick, onLongClick) {
        Row(Modifier.height(130.dp).clip(CardImageShape).background(White10)) {
            Cover(pick.covers, Modifier.width(90.dp).fillMaxSize())
            Column(Modifier.padding(12.dp).weight(1f), verticalArrangement = Arrangement.Center) {
                // El título entero: es lo que distingue una temporada de otra.
                Text(pick.name, color = Color.White, fontSize = Text15, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                pick.year?.let { Text(it.toString(), color = Highlight, fontSize = Text14, maxLines = 1) }
                if (joined) Text(appString(R.string.ui_ya_unida), color = Color.White, fontSize = Text14, fontWeight = FontWeight.Bold, maxLines = 1)
                // Una línea por fuente con sus episodios, para comparar con la serie de origen.
                counts.take(PICK_SOURCE_LINES).forEach { (name, count) ->
                    Text("$name · ${count?.let { episodesLabel(it) } ?: "…"}", color = Highlight, fontSize = Text14, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (counts.isEmpty() && pick.sources.isNotEmpty()) {
                    Text(pick.sources.joinToString(", "), color = Highlight, fontSize = Text14, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
internal fun TitleField(value: String, onChange: (String) -> Unit, focus: FocusRequester, hint: String, masked: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused }
            .border(BorderStroke(if (focused) 3.dp else 1.dp, if (focused) Color.White else White40), ShapeLg)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        singleLine = true,
        visualTransformation = if (masked) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = if (masked) androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password) else androidx.compose.foundation.text.KeyboardOptions.Default,
        textStyle = TextStyle(color = Color.White, fontSize = Text18),
        cursorBrush = SolidColor(Color.White),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(hint, color = White50, fontSize = Text18)
                inner()
            }
        }
    )
}

/** True si el resultado de las fuentes ya es una ficha unida a la de origen (las uniones guardadas por ficha no pasan por el catálogo): sale marcado «Ya unida». */
private fun sameJoined(origin: Pair<String, String>, pick: Catalog.Pick): Boolean {
    val source = pick.sourceId ?: return false
    val slug = pick.slug ?: return false
    val own = seriesKeyOf(origin.first, origin.second)
    return own.startsWith("i:") && own == seriesKeyOf(source, slug)
}
