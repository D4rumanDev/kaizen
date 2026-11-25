@file:OptIn(ExperimentalTvMaterial3Api::class)

package app.kaizen.tv.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kaizen.tv.ui.components.*

import androidx.compose.ui.input.key.type
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.kaizen.commons.logError
import app.kaizen.commons.runCatchingCoop
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.tv.material3.ExperimentalTvMaterial3Api
import app.kaizen.commons.AppScope
import app.kaizen.commons.KaizenProgress
import app.kaizen.commons.findActivity
import app.kaizen.source.AnimeSources
import app.kaizen.source.Catalog
import app.kaizen.source.UserTitles
import app.kaizen.source.slugOfUrl
import app.kaizen.source.coverCandidates
import app.kaizen.domain.Library
import app.kaizen.domain.Favorites
import app.kaizen.pojos.av1.FavoriteAV1
import app.kaizen.pojos.av1.RecentEpisode
import app.kaizen.pojos.av1.Recommended
import app.kaizen.pojos.av1.Record
import app.kaizen.pojos.av1.SeriesSummary
import app.kaizen.tv.TVServersFactory
import app.kaizen.tv.TVServersFactory.ServerChoice
import app.kaizen.tv.anime.ContinueItem
import app.kaizen.tv.details.TVAnimesDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.kaizen.domain.NotRecommended
import app.kaizen.domain.SeriesStatuses
import app.kaizen.R
import app.kaizen.commons.appString

/**
 * Kaizen: acciones de las tarjetas de la pantalla principal. Pulsación: reproducir el
 * capítulo o abrir la ficha ([open]). Pulsación larga: menú como el de SmartTube ([Menu]).
 */
object CardMenu {

    /** Datos comunes de un anime y, si la tarjeta es un capítulo, del capítulo. */
    private class Target(
        val aid: Int,
        val name: String,
        val animeUrl: String,
        val slug: String,
        val favorite: FavoriteAV1?,
        val chapter: Record? = null,
        /** La tarjeta es de «Seguir viendo»: se puede quitar y reproduce con el servidor recordado. */
        val isContinue: Boolean = false
    )

    /** [group]: el encabezado bajo el que se agrupa (en las tarjetas de capítulo: «Capítulo» y «Serie»); null = sin encabezado. */
    private class Option(val label: String, val keepOpen: Boolean = false, val group: String? = null, val run: () -> Unit)

    /** Diálogo de títulos abierto desde el menú. */
    private enum class TitleAction { MERGE, STATUS }

    /** Pulsación en una tarjeta: los capítulos se reproducen y el resto abre la ficha. */
    fun open(activity: ComponentActivity, item: Any?) {
        val target = item?.let(::targetFor) ?: return
        val chapter = target.chapter
        when {
            chapter == null || item is Record -> TVAnimesDetails.start(activity, target.animeUrl)
            else -> TVServersFactory.start(activity, chapter, if (target.isContinue) ServerChoice.REMEMBERED else ServerChoice.SETTING)
        }
    }

    /** Menú de pulsación larga de [item]; [onDismiss] lo cierra (también tras elegir una opción). */
    @Composable
    fun Menu(item: Any, onDismiss: () -> Unit) {
        val target = remember(item) { targetFor(item) }
        val activity = LocalContext.current.findActivity() as? ComponentActivity
        if (target == null || activity == null) {
            LaunchedEffect(item) { onDismiss() }
            return
        }
        var titleAction by remember(item) { mutableStateOf<TitleAction?>(null) }
        val options by produceState<List<Option>?>(null, item) {
            val (seen, favorite) = withContext(Dispatchers.IO) {
                // Mismo criterio que el ojo de la tarjeta (capítulo de la serie, aunque se viera desde otra fuente).
                (target.chapter?.let { Library.chapterSeen(it.source, it.slug, it.number) } ?: false) to
                    Library.isFavorite(AnimeSources.forUrl(target.animeUrl).id, slugOfUrl(target.animeUrl))
            }
            value = options(activity, target, seen, favorite) { titleAction = it }
        }
        val list = options ?: return
        val first = remember { FocusRequester() }
        val source = AnimeSources.forUrl(target.animeUrl).id
        val covers = remember(target) { coverCandidates(target.aid, target.slug, "") }
        when (titleAction) {
            TitleAction.STATUS -> {
                StatusDialog(target.name, SeriesStatuses.of(target.name, source, target.slug),
                    onPick = { status -> AppScope.launch { app.kaizen.domain.StatusChoice.pick(target.animeUrl, target.name, source, target.slug, target.aid, status) } }, onDismiss = onDismiss)
                return
            }
            TitleAction.MERGE -> {
                SeriesPickerDialog(appString(R.string.ui_unir_con_la_serie), target.name, covers, originFicha = source to target.slug, onPick = { entry -> mergeWith(source, target.slug, entry); onDismiss() }, onDismiss = onDismiss)
                return
            }
            null -> Unit
        }
        val dialogTitle = target.chapter?.let { "${target.name} · ${it.chapter()}" } ?: target.name
        // Con títulos largos y varias opciones el diálogo no cabe en pantalla: desplazable y con el título entero.
        KaizenDialog(onDismissRequest = onDismiss, title = dialogTitle, titleMaxLines = 5, scrollable = true) {
            LaunchedEffect(list) { runCatching { first.requestFocus() } }
            list.forEachIndexed { index, option ->
                if (option.group != null && list.getOrNull(index - 1)?.group != option.group) {
                    androidx.tv.material3.Text(option.group, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f), fontSize = 14.sp, modifier = Modifier.padding(top = if (index == 0) 0.dp else 8.dp))
                }
                KaizenButton(
                    option.label,
                    onClick = { if (!option.keepOpen) { onDismiss(); CardMenuSignal.arm() }; option.run() },
                    modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                )
            }
        }
    }

    private fun targetFor(item: Any): Target? = when (item) {
        is ContinueItem -> chapterTarget(item.record, isContinue = true)
        is Record -> chapterTarget(item)
        // Un capítulo reciente que ya se empezó se trata como uno de «Seguir viendo»: mismo servidor recordado y opción de quitarlo.
        is RecentEpisode -> chapterTarget(item.asRecord(),
            isContinue = KaizenProgress.startedOf(KaizenProgress.Key(app.kaizen.source.seriesIdOf(item.source, item.slug), item.number), item.eid) != null)
        is FavoriteAV1 -> Target(item.aid, item.name, item.animeUrl(), item.slug, item)
        is Recommended -> slugOfUrl(item.animeUrl).let { slug -> Target(item.aid, item.name, item.animeUrl, slug, favoriteOf(item.aid, item.name, slug, item.type, AnimeSources.forUrl(item.animeUrl).id)) }
        is SeriesSummary -> Target(item.aid, item.name, item.animeUrl, item.slug, favoriteOf(item.aid, item.name, item.slug, item.type, item.source))
        else -> null
    }

    private fun chapterTarget(record: Record, isContinue: Boolean = false) =
        Target(record.aid, record.name, record.animeUrl(), record.slug, null, record, isContinue)

    private fun favoriteOf(aid: Int, name: String, slug: String, type: String, source: String) =
        FavoriteAV1(aid, name, slug, type, FavoriteAV1.CATEGORY_NONE, source)

    private fun options(activity: ComponentActivity, target: Target, seen: Boolean, favorite: Boolean, onTitle: (TitleAction) -> Unit): List<Option> = buildList {
        val record = target.chapter
        // Con un capítulo, el menú se divide en lo del capítulo y lo de la serie; con una serie sola, solo lo de la serie y sin encabezados.
        val chapterGroup = if (record != null) "Capítulo ${record.chapter()}" else null
        val seriesGroup = if (record != null) "Serie · ${target.name}" else null
        if (record != null) {
            add(Option(appString(R.string.ui_reproducir), group = chapterGroup) { TVServersFactory.start(activity, record) })
            add(Option(appString(R.string.ui_elegir_servidor), group = chapterGroup) { TVServersFactory.start(activity, record, ServerChoice.ASK) })
            add(Option(if (seen) appString(R.string.ui_marcar_como_no_visto) else appString(R.string.ui_marcar_como_visto), group = chapterGroup) { io { record.markSeen(!seen) } })
            if (target.isContinue) add(Option(appString(R.string.ui_quitar_de_seguir_viendo), group = chapterGroup) { KaizenProgress.remove(record.progressKey(), record.eid) })
        }
        // De la serie, lo que más se hace arriba: abrir la ficha, favorito y estado; después lo demás.
        add(Option(appString(R.string.ui_abrir_ficha), group = seriesGroup) { TVAnimesDetails.start(activity, target.animeUrl) })
        add(Option(if (favorite) appString(R.string.ui_quitar_de_favoritos) else appString(R.string.ui_anadir_a_favoritos), group = seriesGroup) { toggleFavorite(target) })
        add(Option("Estado: ${SeriesStatuses.of(target.name, AnimeSources.forUrl(target.animeUrl).id, target.slug).label}", keepOpen = true, group = seriesGroup) { onTitle(TitleAction.STATUS) })
        val ficha = AnimeSources.forUrl(target.animeUrl).id
        val allowed = NotRecommended.allows(target.name, ficha, target.slug)
        add(Option(if (allowed) appString(R.string.ui_no_recomendar) else appString(R.string.ui_volver_a_recomendar), group = seriesGroup) {
            NotRecommended.set(target.name, excluded = allowed, source = ficha, slug = target.slug)
            KaizenToast.show(if (allowed) appString(R.string.ui_no_se_recomendara) else appString(R.string.ui_se_volvera_a_recomendar))
        })
        add(Option(appString(R.string.ui_unir_con_otra_serie), keepOpen = true, group = seriesGroup) { onTitle(TitleAction.MERGE) })
    }

    internal fun mergeWith(source: String, slug: String, other: Catalog.Pick) {
        // Una serie que el catálogo no tiene (viene de buscar en las fuentes) no trae clave: se identifica por su ficha.
        if (other.key.isEmpty()) UserTitles.mergeFichas(source, slug, other.sourceId.orEmpty(), other.slug.orEmpty())
        else UserTitles.editFor(source, slug) { rules, key -> rules.withMerge(key, other.key) }
        titlesChanged("Unidas")
    }

    /** Avisa de un cambio de uniones o títulos. Las reglas, al guardarse, reagrupan el catálogo solas ([UserTitles.update]). */
    internal fun titlesChanged(message: String) {
        KaizenToast.show(message)
    }

    private fun toggleFavorite(target: Target) = io {
        Favorites.toggle(target.name, target.animeUrl, target.favorite)
    }

    private fun io(block: suspend () -> Unit) {
        AppScope.launch(Dispatchers.IO) {
            runCatchingCoop { block() }.onFailure {
                logError(it)
                KaizenToast.show(appString(R.string.ui_no_se_pudo_completar_la_accion))
            }
        }
    }
}
