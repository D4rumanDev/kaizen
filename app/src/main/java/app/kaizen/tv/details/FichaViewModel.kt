package app.kaizen.tv.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.kaizen.commons.logError
import app.kaizen.commons.runCatchingCoop
import app.kaizen.tv.LoadLimits
import kotlinx.coroutines.withTimeoutOrNull
import app.kaizen.domain.WatchState
import app.kaizen.commons.KaizenProgress
import app.kaizen.domain.SeriesStatuses
import app.kaizen.source.seriesIdOf
import app.kaizen.pojos.av1.Chapter
import app.kaizen.pojos.av1.RELATION_PREQUEL
import app.kaizen.pojos.av1.RELATION_SEQUEL
import app.kaizen.pojos.av1.Relation
import app.kaizen.pojos.av1.Series
import app.kaizen.pojos.av1.SeriesSummary
import app.kaizen.commons.KaizenSettings
import app.kaizen.source.FichaData
import app.kaizen.source.AnilistFicha
import app.kaizen.source.AnilistSyncRunner
import app.kaizen.source.AnimeSources
import app.kaizen.source.FichaPolicy
import app.kaizen.source.SourceCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Lo que muestra la ficha: la serie con sus episodios, relaciones y similares, y qué ver ahora. */
class FichaViewModel(private val url: String) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data object Failed : State
        class Ready(
            val series: Series,
            val episodes: List<Chapter>,
            val similar: List<SeriesSummary>,
            val watch: WatchState,
            /** Se incrementa en cada refresco para que las tarjetas relean el progreso guardado. */
            val version: Int = 0,
            /** Lo que se sabe de la serie en AniList (pestaña «Datos»); null si no está enlazada o falta la ficha. */
            val anilist: FichaData? = null
        ) : State {
            /** Precuelas y secuelas juntas (en ese orden). */
            val story: List<Relation> = series.relations
                .filter { it.relation == RELATION_PREQUEL || it.relation == RELATION_SEQUEL }
                .sortedBy { it.relation }

            /** El resto de relaciones por importancia. */
            val others: List<Relation> = series.relations
                .filter { it.relation != RELATION_PREQUEL && it.relation != RELATION_SEQUEL }
                .sortedBy { RELATION_ORDER.indexOf(it.relation).let { index -> if (index < 0) RELATION_ORDER.size else index } }

            /** Posición del episodio que toca ver, donde se abre la fila. */
            val startIndex: Int get() = episodes.indexOf(watch.target).coerceAtLeast(0)
        }
    }

    private val refreshing = Mutex()

    /** Los datos de AniList con el texto en español, si llegó antes de que la ficha acabara de cargarse. */
    @Volatile private var spanish: FichaData? = null
    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    init {
        viewModelScope.launch {
            // Con plazo ([LoadLimits.OPEN_SERIES]): una web colgada deja la ficha en «Failed» (la pantalla avisa con un toast y se cierra; se vuelve a abrir) en vez de quedarse en el esqueleto sin fin.
            val raw = runCatchingCoop { withTimeoutOrNull(LoadLimits.OPEN_SERIES) { AnimeSources.openAnime(url) } }.onFailure(::logError).getOrNull()
            if (raw == null) {
                _state.value = State.Failed
                return@launch
            }
            // Los datos de AniList se leen de la base local, sin red.
            val anilist = AnilistFicha.of(raw.source, raw.slug)
            // Sin texto en español todavía: se pide a Animeoteca (solo esta serie) y, al llegar, la sinopsis de la ficha se cambia.
            if (anilist != null && anilist.es == null) viewModelScope.launch { spanishArrived(raw, anilist) }
            // Sin capítulos propios hay que esperar a las demás fuentes (de ellas salen); con ellos la ficha sale ya, sin
            // esperar a la más lenta, y la nota, las relaciones y las series parecidas se completan después.
            if (raw.chapters.isEmpty()) {
                _state.value = runCatchingCoop { withTimeoutOrNull(LoadLimits.DATA) { ready(AnimeSources.complete(raw), similar = true, anilist) } }.onFailure(::logError).getOrNull() ?: State.Failed
                return@launch
            }
            val first = runCatchingCoop {
                ready(raw, similar = false, anilist)
            }.onFailure(::logError).getOrNull()
            if (first == null) {
                _state.value = State.Failed
                return@launch
            }
            _state.value = first
            // Si las demás fuentes fallan se completa solo con la propia (calcula las series parecidas).
            val full = completed(raw, FichaPolicy.settled(raw, anilist, KaizenSettings.anilistFicha)) ?: first.series
            val enriched = runCatchingCoop { ready(full, similar = true, anilist) }.onFailure(::logError).getOrNull() ?: return@launch
            // Si el texto en español llegó mientras se completaba la ficha, no se pierde.
            val newest = spanish ?: enriched.anilist
            val enrichedSeries = if (newest != null && newest !== enriched.anilist) AnilistFicha.applied(enriched.series, newest) else enriched.series
            // Una a la vez con los refrescos: si no, una lectura vieja podría pisar el estado nuevo.
            refreshing.withLock {
                val latest = _state.value as? State.Ready ?: return@withLock
                // Qué ver ahora se lee dentro del candado: un refresco que terminara antes no se pisa con una lectura vieja.
                _state.value = State.Ready(enrichedSeries, enriched.episodes, enriched.similar, enriched.series.watchState(), latest.version + 1, newest)
            }
        }
    }

    /** Pide el texto en español y, si llega, relee los datos de AniList y rehace la ficha con él (una a la vez con los refrescos). */
    private suspend fun spanishArrived(raw: Series, before: FichaData) {
        if (!AnilistSyncRunner.fetchSpanish(before.anilistId ?: return)) return
        val now = AnilistFicha.of(raw.source, raw.slug)?.takeIf { it.es != null } ?: return
        // Se anota aunque la ficha aún no esté lista: al completarse, el estado final lo usa.
        spanish = now
        refreshing.withLock {
            val latest = _state.value as? State.Ready ?: return@withLock
            val series = AnilistFicha.applied(latest.series, now)
            _state.value = State.Ready(series, latest.episodes, latest.similar, latest.watch, latest.version + 1, now)
        }
    }

    private suspend fun ready(source: Series, similar: Boolean, anilist: FichaData?): State.Ready {
        // La nota sale solo de AniList (las de las fuentes se descartan) y la sinopsis de Animeoteca si la tiene.
        val series = AnilistFicha.applied(source, anilist)
        return State.Ready(series, series.episodes(), if (similar) series.similar() else emptyList(), series.watchState(), anilist = anilist)
    }

    /**
     * La ficha completada con las demás fuentes. Una serie finalizada y completa ya no cambia: la completada se guarda la primera vez
     * y las siguientes se sirve sin pedir nada a ninguna web. null si las demás fuentes fallan.
     */
    private suspend fun completed(raw: Series, settled: Boolean): Series? {
        // Solo si no ha salido ningún capítulo nuevo desde que se guardó.
        if (settled) SourceCache.completeFiche(url)?.takeIf { it.chapters.size == raw.chapters.size }?.let { return it }
        val full = runCatchingCoop { AnimeSources.complete(raw) }.onFailure(::logError).getOrNull() ?: return null
        if (settled) SourceCache.saveCompleteFiche(url, full.copy(name = raw.name))
        return full
    }

    /** Vuelve a calcular qué ver ahora y los textos de los botones (al volver del reproductor, por ejemplo). */
    fun refresh() {
        val current = _state.value as? State.Ready ?: return
        viewModelScope.launch {
            // Fuera del candado: puede pedir el horario a la red.
            val airing = isAiring(current.series)
            // Una a la vez: si no, una lectura vieja podría terminar la última y pisar el estado nuevo.
            refreshing.withLock {
                val watch = current.series.watchState()
                val latest = _state.value as? State.Ready ?: return@withLock
                // Serie finalizada (sin día de emisión ni en el calendario) y con todo visto: «Completada» (ver SeriesStatuses.autoCompleted).
                with(latest.series) {
                    // Un capítulo a medias (reproducción en curso de esta serie) aún no la completa: se completa al terminarlo.
                    val seriesId = seriesIdOf(source, slug)
                    val inProgress = KaizenProgress.inProgress().keys.any { it.seriesId == seriesId }
                    if (SeriesStatuses.shouldAutoComplete(watch.allSeen, airing, latest.episodes.isNotEmpty(), inProgress)) SeriesStatuses.autoCompleted(name, source, slug, aid)
                }
                _state.value = State.Ready(latest.series, latest.episodes, latest.similar, watch, latest.version + 1, latest.anilist)
            }
        }
    }

    /** En emisión según AniList (o, sin AniList, según la fuente y el calendario): ver [app.kaizen.domain.StatusChoice.isAiring]. */
    private suspend fun isAiring(series: Series) = app.kaizen.domain.StatusChoice.isAiring(series)

    fun toggleFavorite() {
        val current = _state.value as? State.Ready ?: return
        viewModelScope.launch {
            current.series.toggleFavorite()
            refresh()
        }
    }

    /** Elige el estado a mano (ver [app.kaizen.domain.StatusChoice]): «Completada» marca todos los capítulos, y en emisión queda «Viendo» con aviso. */
    fun setStatus(status: app.kaizen.domain.SeriesStatus) {
        val current = _state.value as? State.Ready ?: return
        viewModelScope.launch {
            app.kaizen.domain.StatusChoice.pick(current.series, status)
            refresh()
        }
    }

    fun setAllSeen(seen: Boolean) {
        val current = _state.value as? State.Ready ?: return
        viewModelScope.launch {
            current.series.setAllSeen(seen)
            // Marcar todos los capítulos como vistos termina la serie solo si no está en emisión (ver SeriesStatuses.afterAllSeen); una descartada sigue descartada.
            if (seen) with(current.series) {
                SeriesStatuses.afterAllSeen(SeriesStatuses.of(name, source, slug), isAiring(this))?.let { SeriesStatuses.set(name, it, source, slug, aid) }
            }
            refresh()
        }
    }

    class Factory(private val url: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = FichaViewModel(url) as T
    }

    private companion object {
        // Historia principal, completa, paralela, versión alternativa, ambientación alternativa, resumen.
        val RELATION_ORDER = listOf(8, 7, 5, 4, 3, 6)
    }
}
