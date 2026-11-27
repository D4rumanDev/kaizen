@file:OptIn(ExperimentalCoroutinesApi::class)

package app.kaizen.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kaizen.commons.KaizenProgress
import app.kaizen.commons.RecentsArchive
import app.kaizen.domain.Library
import app.kaizen.home.Recommendations
import app.kaizen.pojos.av1.Record
import app.kaizen.retrofit.Repository
import app.kaizen.source.AnimeSources
import app.kaizen.source.SeriesLedger
import app.kaizen.source.seriesKeyOf
import app.kaizen.source.SourceChallenge
import app.kaizen.tv.TVRepository
import app.kaizen.tv.LoadLimits
import kotlinx.coroutines.withTimeoutOrNull
import app.kaizen.tv.anime.ContinueItem
import app.kaizen.source.BestTop
import app.kaizen.tv.ui.MainSections.BEST
import app.kaizen.tv.ui.MainSections.BESTGLOBAL
import app.kaizen.tv.ui.MainSections.FAVORITES
import app.kaizen.tv.ui.MainSections.CONTINUE
import app.kaizen.tv.ui.MainSections.LAST_SEEN
import app.kaizen.tv.ui.MainSections.RECENTS
import app.kaizen.tv.ui.MainSections.STAFF
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import app.kaizen.commons.logError
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Duration.Companion.seconds

/**
 * Kaizen: datos de las secciones de la pantalla principal, uno por página (id de
 * [MainSections]). Cada página recoge el flujo de su sección.
 */
class MainViewModel : ViewModel() {
    private val records = Library.history.distinctUntilChanged()

    // Fuentes activas: cada sección se rehace al cambiarlas, sin esperar a reabrir la app.
    private val enabled = AnimeSources.enabledFlow

    // Una sola instancia: así la sección no cambia (ni se repinta) en cada emisión.
    private val retryRecents = RetryCard("No se pudieron cargar. Reintentar") { Repository.reloadRecents() }

    // «Recomendados»: cada pulsación de la tarjeta «Reintentar» (que sale si la carga pasa de [LoadLimits.DATA]) la pide otra vez.
    private val staffRefresh = MutableStateFlow(0)
    private val retryStaff = RetryCard(app.kaizen.commons.appString(app.kaizen.R.string.ui_tarda_mas_de_lo_normal) + " " + app.kaizen.commons.appString(app.kaizen.R.string.ui_reintentar)) { staffRefresh.value += 1 }

    // «Mejores»: la tarjeta «Reintentar» (sin lista ni guardado) pide otra vez la lista que toque.
    private val bestRefresh = BestTop.Kind.entries.associateWith { MutableStateFlow(0) }
    private val retryBest = BestTop.Kind.entries.associateWith { kind -> RetryCard("No se pudo cargar la lista. Reintentar") { bestRefresh.getValue(kind).value += 1 } }

    /** La lista «Mejores» de [kind] (guardada una semana por [TVRepository.best]); se rehace al cambiar las fuentes activas o al pulsar «Reintentar». */
    private fun best(kind: BestTop.Kind): StateFlow<List<Any>> = bestRefresh.getValue(kind).combine(enabled) { _, _ -> }.transformLatest {
        emit(listOf<Any>(LoadingCard))
        emit(try { TVRepository.best(kind) } catch (e: CancellationException) { throw e } catch (e: Exception) { logError(e); null } ?: listOf(retryBest.getValue(kind)))
    }.catch { logError(it); emit(listOf(retryBest.getValue(kind))) }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Lazily, listOf<Any>(LoadingCard))

    private val sections: Map<Int, StateFlow<List<Any>>> = mapOf(
        // La web solo da 20 recientes; se suman a los guardados (RecentsArchive).
        // Sin recientes y con la última recarga fallida: tarjeta para reintentar.
        RECENTS to combine(Library.recents.distinctUntilChanged(), enabled, Repository.recentsLoaded, Repository.recentsLoading, SeriesLedger.version) { recents, _, loaded, loading, _ ->
            RecentsArchive.merge(recents).ifEmpty {
                when {
                    loading -> listOf(LoadingCard)
                    loaded -> emptyList()
                    else -> listOf(retryRecents)
                }
            }
        }.combine(SourceChallenge.pending.combine(enabled) { hosts, sources -> hosts.filter { host -> sources.any { it.host == host } } }) { items, hosts ->
            // Una web que pide verificación: tarjeta para que el usuario decida hacerla (la app no la resuelve sola). Solo si su fuente está activa.
            hosts.sorted().map { VerifyCard(it) } + items
        }.distinctUntilChanged().flowOn(Dispatchers.IO).whileVisible(),
        // Solo mientras se ve: el reproductor guarda progreso cada 15 s.
        // Historial y favoritos son del usuario: salen de cualquier fuente (cada ficha se abre en la suya).
        // Solo mientras se ve: el reproductor guarda progreso cada 15 s.
        CONTINUE to combine(records, KaizenProgress.changes, SeriesLedger.version) { history, _, _ -> traced("seguir viendo", history.size) { continueItems(history) } }
            .flowOn(Dispatchers.IO).whileVisible(),
        // El mismo episodio visto en dos fuentes (distinto eid) sale una vez: el más reciente.
        LAST_SEEN to combine(records, SeriesLedger.version) { list, _ -> traced("historial", list.size) { list.distinctBy { seriesKeyOf(it.source, it.slug) to it.number } } }
            .flowOn(Dispatchers.Default).whileVisible(),
        // Una serie favorita desde dos fuentes unidas sale una vez (el libro de ids carga después: se rehace al cargar).
        FAVORITES to combine(Library.favorites.distinctUntilChanged(), SeriesLedger.version) { favorites, _ ->
            favorites.distinctBy { seriesKeyOf(it.source, it.slug) }
        }.flowOn(Dispatchers.Default).whileVisible(),
        // A tu medida (géneros de favoritos e historial, nota de 3 a 5); sin gusto conocido, la lista general del staff.
        STAFF to staffRefresh.transformLatest { _ ->
            emit(listOf<Any>(LoadingCard))
            // Pasado el plazo sale una tarjeta para reintentar y avisar: una lista vacía parecería «sin recomendaciones» y no se recuperaba hasta reiniciar la app.
            emit(withTimeoutOrNull(LoadLimits.DATA) { Recommendations.forHome() } ?: listOf(retryStaff))
        }.catch { logError(it); emit(emptyList()) }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Lazily, listOf<Any>(LoadingCard)),

        BEST to best(BestTop.Kind.AIRING),
        BESTGLOBAL to best(BestTop.Kind.ALWAYS),
    )

    init {
        Repository.reloadRecents()
    }

    /** Anota en el registro cuánto tarda una sección en salir desde que arrancó el proceso (para ver qué retrasa la pantalla en un arranque en frío). */
    private inline fun <T> traced(name: String, size: Int, block: () -> T): T {
        val start = android.os.SystemClock.elapsedRealtime()
        return block().also {
            val now = android.os.SystemClock.elapsedRealtime()
            app.kaizen.commons.DebugLog.info("Arranque", "Sección «$name» lista: $size registros, calculada en ${now - start} ms y visible a los ${now - android.os.Process.getStartElapsedRealtime()} ms de arrancar la app")
        }
    }

    fun section(id: Int): StateFlow<List<Any>>? = sections[id]

    /** «Seguir viendo»: capítulos del historial con progreso guardado (por ficha y número) sin terminar. */
    private fun continueItems(history: List<Record>): List<ContinueItem> {
        KaizenProgress.migrateEidKeys(history)
        val inProgress = KaizenProgress.inProgress()
        // El mismo capítulo empezado desde dos fuentes unidas sale una vez (la más reciente).
        return history.mapNotNull { record -> inProgress[record.progressKey()]?.let { ContinueItem(record, it) } }
            .distinctBy { seriesKeyOf(it.record.source, it.record.slug) to it.record.number }
    }

    /** Activo mientras alguna página lo recoge (y 5 s más, para los cambios de página). Hasta la primera lectura sale el hueco de carga. */
    private fun Flow<List<Any>>.whileVisible() =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT.inWholeMilliseconds), listOf<Any>(LoadingCard))

    private companion object {
        val STOP_TIMEOUT = 5.seconds
    }
}
