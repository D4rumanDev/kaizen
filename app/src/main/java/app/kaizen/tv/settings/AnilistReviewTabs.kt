@file:OptIn(ExperimentalTvMaterial3Api::class)

package app.kaizen.tv.settings

import app.kaizen.tv.ui.components.*

import app.kaizen.tv.ui.theme.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import app.kaizen.commons.AppScope
import app.kaizen.commons.DebugLog
import app.kaizen.commons.logError
import app.kaizen.commons.runCatchingCoop
import app.kaizen.source.AnilistAutoReview
import app.kaizen.source.AnilistCompare
import app.kaizen.source.LocalFacts
import app.kaizen.source.FichaData
import app.kaizen.source.AnilistFichaRaw
import app.kaizen.source.AnilistLabels
import app.kaizen.source.AnilistReviewRepository
import app.kaizen.source.AnilistReview
import app.kaizen.source.AnimeSources
import app.kaizen.source.AnimeFuente
import app.kaizen.source.Catalog
import app.kaizen.source.CatalogBuilder
import app.kaizen.source.Match
import app.kaizen.source.PendingSeries
import app.kaizen.source.ReviewNav
import app.kaizen.tv.ui.CenterMessage
import app.kaizen.tv.ui.Cover
import app.kaizen.tv.ui.rememberAutoFocus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/** Lo que enseña la pantalla de dudosas, un solo valor: cargando, error de lectura, sin más series o una pregunta. */
internal sealed interface ReviewScreen {
    data object Loading : ReviewScreen
    data object Failed : ReviewScreen
    /** [empty]: no había ninguna dudosa (si no, ya se vieron todas). */
    data class Finished(val empty: Boolean) : ReviewScreen
    data class Asking(val series: PendingSeries, val index: Int, val total: Int) : ReviewScreen
}

/** El estado de la pantalla a partir de lo leído ([loaded]: null mientras carga) y de la serie por la que se va ([index]). */
internal fun reviewScreenOf(loaded: Result<List<PendingSeries>>?, index: Int): ReviewScreen {
    val list = loaded?.getOrElse { return ReviewScreen.Failed } ?: return ReviewScreen.Loading
    val series = list.getOrNull(index) ?: return ReviewScreen.Finished(empty = list.isEmpty())
    return ReviewScreen.Asking(series, index, list.size)
}

/**
 * Todo el estado de la pantalla de dudosas como flujos: lo leído ([loaded]), el avance ([nav]) y lo que se enseña ([screen], derivado de los dos).
 * Vive en la composición pero no depende de ella: lo actualizan las tareas de fondo y la interfaz solo lo observa.
 */
internal class ReviewSession(scope: CoroutineScope, load: suspend () -> Result<List<PendingSeries>>) {
    private val _loaded = MutableStateFlow<Result<List<PendingSeries>>?>(null)
    private val _nav = MutableStateFlow(ReviewNav())
    val loaded: StateFlow<Result<List<PendingSeries>>?> = _loaded.asStateFlow()
    val nav: StateFlow<ReviewNav> = _nav.asStateFlow()
    val screen: StateFlow<ReviewScreen> = combine(_loaded, _nav) { read, at -> reviewScreenOf(read, at.index) }
        .stateIn(scope, SharingStarted.Eagerly, ReviewScreen.Loading)

    init { scope.launch { _loaded.value = load() } }

    fun update(change: (ReviewNav) -> ReviewNav) = _nav.update(change)

    /** Una pulsación doble, o dos tareas a la vez, no deben decidir (y saltar) dos series: solo una toma el candado (atómico). */
    fun claim(): Boolean {
        while (true) {
            val current = _nav.value
            val taken = current.claim() ?: return false
            if (_nav.compareAndSet(current, taken)) return true
        }
    }

    /** Quita la última decisión del avance y devuelve cuál era (null si no hay); quien llama deshace lo escrito y confirma con [update] y `undone`. */
    fun takeUndo(): ReviewNav.Decision? {
        while (true) {
            val current = _nav.value
            val (taken, last) = current.undo() ?: return null
            if (_nav.compareAndSet(current, taken)) return last
        }
    }
}

/** Cuántas series por delante se adelantan en segundo plano. */
private const val PREFETCH_AHEAD = 2

/**
 * «AniList: dudosas»: una a una, los datos de la serie de tus fuentes y los del anime que AniList propone, en paralelo y coloreados según
 * coincidan (título, año, episodios); tú dices si es el mismo. Se puede deshacer la última decisión.
 */
@Composable
internal fun PendingTab(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    // Todo el avance (serie actual, hasta dónde se ha llegado, «Anterior», «Deshacer» y el candado de escritura) es un valor, ver [ReviewNav], dentro de un flujo.
    val session = remember { ReviewSession(scope) { runCatchingCoop { AnilistReviewRepository.pending() }.onFailure(::logError) } }
    val nav by session.nav.collectAsStateWithLifecycle()
    val screen by session.screen.collectAsStateWithLifecycle()
    val loaded by session.loaded.collectAsStateWithLifecycle()
    val list = loaded?.getOrNull()
    // Los enlaces nuevos pueden unir series de distintas fuentes: al salir, el catálogo se reagrupa con ellos.
    var autoApplied by remember { mutableStateOf(0) }
    DisposableEffect(Unit) { onDispose { if (session.nav.value.canUndo || autoApplied > 0) AppScope.launch(Dispatchers.IO) { runCatchingCoop { CatalogBuilder.regroup() }.onFailure(::logError) } } }

    // Mientras se mira una serie se piden ya (una a una, con el ritmo de AniList) el candidato y los datos de las dos siguientes: decidir no espera a la red.
    LaunchedEffect(list, nav.index) {
        val ahead = list?.drop(nav.index + 1)?.take(PREFETCH_AHEAD).orEmpty()
        if (ahead.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            AnilistReview.prefetch(ahead.map { it.anilistId })
            val active = AnimeSources.selected.map { it.id }.toSet()
            ahead.forEach { next -> next.fichas.filter { (source, _) -> source in active }.take(4).forEach { (source, slug) -> runCatchingCoop { Catalog.facts(source, slug) } } }
        }
    }

    fun undo() {
        val last = session.takeUndo() ?: return
        scope.launch(Dispatchers.IO) {
            runCatchingCoop { AnilistReviewRepository.undo(last.series, last.before) }.onFailure(::logError)
            session.update { it.undone(last) }
        }
    }
    when (val shown = screen) {
        ReviewScreen.Failed -> ReviewFinished("No se pudieron leer las series (mira el registro de depuración).", onClose)
        ReviewScreen.Loading -> CenterMessage("Buscando las series dudosas…")
        is ReviewScreen.Finished -> ReviewFinished(if (shown.empty) "No hay series dudosas con AniList." else "No hay más series dudosas.", onClose, canUndo = nav.canUndo, onUndo = ::undo)
        is ReviewScreen.Asking -> key(shown.index) {
            PendingView(shown.series, shown.index, shown.total, canUndo = nav.canUndo, onUndo = ::undo, onClose = onClose,
                onPrevious = if (nav.passed.isEmpty()) null else ({ session.update { it.back() ?: it } }),
                claim = session::claim,
                // Decidir o saltar una serie a la que se ha vuelto lleva de nuevo hasta donde se estaba; lo que se resuelve solo, también.
                onDone = { before -> session.update { it.done(shown.series, before) } },
                onDiscard = { session.update { it.resolved() } },
                onAutoStarted = { autoApplied++ },
                onAuto = { session.update { it.resolved() } })
        }
    }
}

@Composable
private fun PendingView(series: PendingSeries, index: Int, total: Int, canUndo: Boolean, onUndo: () -> Unit, onClose: () -> Unit, onPrevious: (() -> Unit)?, claim: () -> Boolean, onDone: (List<AnimeFuente?>?) -> Unit, onDiscard: () -> Unit, onAutoStarted: () -> Unit, onAuto: () -> Unit) {
    val focus = rememberAutoFocus()
    val scope = rememberCoroutineScope()
    val first = series.fichas.first()
    val local by produceState<Catalog.Pick?>(null, first) { value = runCatchingCoop { Catalog.pickOfFicha(first.first, first.second) }.getOrNull() }
    // Lo que dice la ficha de cada fuente (una puede ir con retraso o no responder): se juntan los datos de las que respondan.
    // Solo las webs activas: una desactivada no se consulta para esta comparación.
    val activeIds = AnimeSources.selected.map { it.id }.toSet()
    val facts by produceState<Pair<Boolean, List<Catalog.SourceFacts>>>(false to emptyList(), series.fichas) {
        val found = coroutineScope { series.fichas.filter { (source, _) -> source in activeIds }.take(4).map { (source, slug) -> async { runCatchingCoop { Catalog.facts(source, slug) }.getOrNull() } }.awaitAll() }
        value = true to found.filterNotNull()
    }
    // Lo que dicen las webs, juntado en un solo sitio (LocalFacts) que comparten esta pantalla y la revisión por ADB.
    val lf = LocalFacts.of(local, facts.first, facts.second)
    val episodes = lf.done to lf.episodes
    val localAiring = lf.airing
    val localGenres = lf.genres
    val localStudio = lf.studio
    val localFormat = lf.format
    val localMal = lf.mal
    val localDuration = lf.duration
    val localNames = lf.names
    val localYear = lf.year
    // Una petición a AniList por serie que se mira (con el ritmo de AniList): el candidato no está descargado hasta que se decide.
    val asked by produceState<Result<AnilistFichaRaw?>?>(null, series.anilistId) { value = runCatchingCoop { AnilistReview.candidate(series.anilistId) }.onFailure(::logError) }
    val raw = asked?.getOrNull()
    val candidate: FichaData? = remember(raw) { raw?.let { FichaData.ofAnilistJson(it.json) } }
    fun decide(same: Boolean) {
        if (!claim()) return
        scope.launch(Dispatchers.IO) {
            val before = runCatchingCoop {
                AnilistReviewRepository.decide(series, same, raw)
            }.onFailure(::logError).getOrNull()
            withContext(Dispatchers.Main) { onDone(before) }
        }
    }

    // Todas las comparaciones y la decisión se calculan en un solo sitio (AnilistAutoReview.judge); aquí solo se pintan.
    val judgement = AnilistAutoReview.judge(first, local, lf, candidate)
    val localName = judgement.localName
    val titleScore = judgement.titleScore
    // Un candidato que con el matcher de ahora ya no se parece al título (se guardó con uno anterior) no se pregunta: se descarta y se sigue.
    LaunchedEffect(candidate, local) {
        // Con el nombre de la serie ya cargado: antes se compararía con el del slug.
        if (judgement.discard && claim()) {
            withContext(Dispatchers.IO) { runCatchingCoop { AnilistReviewRepository.discard(series) }.onFailure(::logError) }
            onDiscard()
        }
    }
    val compared = judgement.compared
    val aired = compared.aired
    // Con todos los datos delante: si es clarísimo que es el mismo anime (o que no) se resuelve sin preguntar y se sigue.
    val verdict = judgement.verdict
    LaunchedEffect(verdict) {
        if (verdict == null || verdict == AnilistCompare.Auto.ASK) return@LaunchedEffect
        if (verdict == AnilistCompare.Auto.ACCEPT && raw == null) return@LaunchedEffect
        if (!claim()) return@LaunchedEffect
        // Antes de escribir: si el diálogo se cierra a medias, el catálogo se reagrupa igualmente.
        if (verdict == AnilistCompare.Auto.ACCEPT) onAutoStarted()
        DebugLog.info("AniList", "Dudosa resuelta sola (${verdict.name}): ${series.fichas.first().second} → #${series.anilistId} título ${"%.0f".format((titleScore ?: 0.0) * 100)} % año ${compared.year.name} episodios ${compared.episodes.name}")
        withContext(Dispatchers.IO) {
            runCatchingCoop {
                val at = System.currentTimeMillis()
                if (verdict == AnilistCompare.Auto.ACCEPT && raw != null) AnilistReviewRepository.accept(series, titleScore ?: 0.0, raw, at)
                else AnilistReviewRepository.discard(series, at)
            }.onFailure(::logError)
        }
        if (verdict == AnilistCompare.Auto.ACCEPT) onAuto() else onDiscard()
    }
    val loading = asked == null
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("¿Es el mismo anime?  ·  ${index + 1} de $total", color = Color.White, fontSize = Text20, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Legend(MatchLegend)
        }
        val overall = compared.overall
        Text("Parecido con todos los datos: ${overall?.let { "%.0f".format(it * 100) } ?: "…"} %" + (titleScore?.let { " (título ${"%.0f".format(it.coerceAtLeast(0.0) * 100)} %)" } ?: "") +
            ". Si dices que sí, la ficha usa los datos de AniList.", color = White70, fontSize = Text16)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Cover(local?.covers.orEmpty(), Modifier.size(PreviewPosterWidth, PreviewPosterHeight).clip(PreviewPosterShape))
            // Filas apretadas: con el título en dos líneas, diez filas a 8 dp de separación no caben en el alto de la TV y los botones tapaban las últimas (Estado, Estudio, Géneros).
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // La cabecera dice de qué web (o webs) es lo que se compara y con qué anime de AniList; el slug no se enseña (sale del título) ni hace falta una fila de «Fuente».
                CompareRow("", series.fichas.map { (source, _) -> AnimeSources.of(source).name }.distinct().joinToString(", ").ifEmpty { "En tus fuentes" }, "AniList #${series.anilistId}", Match.UNKNOWN, header = true)
                CompareRow("Título", localName, if (loading) "…" else (candidate?.romaji ?: candidate?.english ?: candidate?.native ?: "—"), AnilistCompare.title(localName, candidate), lines = 2)
                CompareRow("Año", localYear?.toString() ?: "—", candidate?.anio?.toString() ?: if (loading) "…" else "—", compared.year)
                CompareRow(
                    "Episodios", if (!episodes.first) "…" else episodes.second?.toString() ?: "—",
                    when {
                        loading -> "…"
                        aired != null && candidate?.episodios != null && aired != candidate.episodios -> "$aired emitidos de ${candidate.episodios}"
                        else -> aired?.toString() ?: "—"
                    },
                    compared.episodes
                )
                CompareRow("Formato", AnilistLabels.format(localFormat) ?: "—", AnilistLabels.format(candidate?.formato) ?: "—", compared.format)
                // La duración solo se enseña si se pudo comparar (fichas de un episodio y la web la da): en una serie larga no aporta y no gasta una fila.
                if (compared.duration != Match.UNKNOWN) CompareRow("Duración", "$localDuration min", "${candidate?.duracion} min", compared.duration)
                // El id de MyAnimeList solo se enseña si hay dos y no coinciden (si coinciden ya está dicho y no aporta; sin uno de los dos, no hay nada que comparar).
                if (compared.mal == Match.DIFFERENT) CompareRow("Id MAL", "#$localMal", "#${candidate?.idMal}", Match.DIFFERENT)
                CompareRow("Estado", localAiring?.let { if (it) "En emisión" else "Finalizada" } ?: "—", AnilistLabels.status(candidate?.estado) ?: "—", compared.status)
                CompareRow("Estudio", localStudio ?: "—", candidate?.estudio ?: "—", compared.studio)
                // Otros nombres de la web (inglés, sinónimos…): si alguno es uno de AniList lo confirma y cuenta como coincidencia; si no hay ninguno, no resta.
                if (localNames.isNotEmpty()) CompareRow("Otros nombres", localNames.take(2).joinToString(" · "), compared.sharedName ?: candidate?.nombres?.filter { it != candidate.romaji }?.take(2)?.joinToString(" · ")?.ifEmpty { null } ?: if (loading) "…" else "—",
                    if (compared.sharedName != null) Match.SAME else Match.UNKNOWN)
                CompareRow("Géneros", localGenres.joinToString(", ").ifEmpty { "—" }, candidate?.generos?.take(5)?.joinToString(", ")?.ifEmpty { null } ?: "—", compared.genres)
                if (asked != null && raw == null) Text("AniList no ha respondido con este anime. Puedes saltarla y volver más tarde.", color = StatusWarn, fontSize = Text14)
            }
            Cover(listOfNotNull(candidate?.portada), Modifier.size(PreviewPosterWidth, PreviewPosterHeight).clip(PreviewPosterShape))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // Saltar primero y con el foco: un OK sin querer no decide nada. Saltar no se deshace (no cambia nada).
            if (onPrevious != null) KaizenButton("Anterior", onClick = onPrevious)
            KaizenButton("Saltar", onClick = { if (claim()) onDone(null) }, modifier = Modifier.focusRequester(focus))
            KaizenButton("Coinciden", onClick = { decide(true) })
            KaizenButton("Diferentes", onClick = { decide(false) })
            if (canUndo) KaizenButton("Deshacer", onClick = onUndo)
        }
    }
}

/** Una fila de la comparación: el dato, el de tus fuentes y el de AniList, con el color de [match] (verde, ámbar, rojo; gris sin dato). */
@Composable
private fun CompareRow(label: String, local: String, remote: String, match: Match, header: Boolean = false, lines: Int = 1) {
    val color = when {
        header -> Highlight
        match == Match.SAME -> StatusOk
        match == Match.CLOSE -> StatusWarn
        match == Match.DIFFERENT -> StatusDown
        else -> White70
    }
    val weight = if (header) FontWeight.Bold else FontWeight.Normal
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        // Una sola línea por fila (solo el título puede ocupar dos): la etiqueta con ancho propio y los valores recortados con «…».
        Text(label, color = White70, fontSize = Text16, maxLines = 1, softWrap = false, modifier = Modifier.width(128.dp))
        Text(local, color = color, fontSize = Text16, fontWeight = weight, maxLines = lines, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(remote, color = color, fontSize = Text16, fontWeight = weight, maxLines = lines, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ReviewFinished(message: String, onClose: () -> Unit, canUndo: Boolean = false, onUndo: () -> Unit = {}) {
    val focus = rememberAutoFocus()
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(message, color = Color.White, fontSize = Text17)
        Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KaizenButton("Cerrar", onClick = onClose, modifier = Modifier.focusRequester(focus))
            if (canUndo) KaizenButton("Deshacer", onClick = onUndo)
        }
    }
}
