@file:OptIn(ExperimentalTvMaterial3Api::class)

package app.kaizen.tv.settings

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kaizen.tv.ui.components.*

import app.kaizen.tv.ui.theme.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import app.kaizen.R
import app.kaizen.tv.ui.rememberLoadable
import app.kaizen.tv.ui.valueOrNull
import app.kaizen.commons.AppScope
import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.appString
import app.kaizen.commons.logError
import app.kaizen.commons.runCatchingCoop
import app.kaizen.source.AnimeSources
import app.kaizen.source.CatalogBuilder
import app.kaizen.source.CatalogOverview
import app.kaizen.source.SourceCatalog
import app.kaizen.source.SourceChallenge
import app.kaizen.tv.ui.VerifySourceDialog
import app.kaizen.tv.ui.TitleField
import app.kaizen.tv.ui.rememberAutoFocus
import kotlinx.coroutines.launch
import app.kaizen.tv.ui.KaizenToast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

/**
 * Configuración › Series › Fuentes: lo que el catálogo interno tiene de cada fuente (series y fecha del último recorrido), sus series
 * con el slug y el botón para recorrerla ahora. Aquí también se decide si el catálogo se recorre solo; «Mejores en general»
 * únicamente lo lee.
 */
@Composable
internal fun SourcesTab(browsing: SourceCatalog?, onBrowse: (SourceCatalog?) -> Unit) {
    // Sube al terminar un recorrido: las cifras se leen otra vez.
    var version by remember { mutableIntStateOf(0) }
    var walking by remember { mutableStateOf<String?>(null) }
    var auto by remember { mutableStateOf(KaizenSettings.catalogAuto) }
    val sources = rememberLoadable(version) { runCatchingCoop { CatalogOverview.sources() }.onFailure(::logError).getOrDefault(emptyList()) }.valueOrNull

    fun walk(item: SourceCatalog) {
        if (walking != null) return
        walking = item.source.id
        // En AppScope: cerrar la ventana no corta a medias un recorrido de cientos de peticiones.
        AppScope.launch {
            val walked = try {
                runCatchingCoop { CatalogOverview.walkNow(item.source.id) }.onFailure(::logError).getOrDefault(false)
            } finally {
                walking = null
                version++
            }
            KaizenToast.toast(appString(if (walked) R.string.ui_cat_actualizado else R.string.ui_cat_fallo, item.source.name))
        }
    }

    /** Recorre lo que falte de todas las fuentes activas de una vez (si el catálogo está vacío, todas), sin pasar por el reinicio de la app. */
    fun walkMissing() {
        if (walking != null) return
        walking = "*"
        AppScope.launch {
            try {
                runCatchingCoop { CatalogBuilder.ensureFresh(force = true) }.onFailure(::logError)
            } finally {
                walking = null
                version++
            }
            KaizenToast.toast("Catálogo actualizado")
        }
    }

    val current = browsing
    if (current != null) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) { SeriesView(current) { onBrowse(null) } }
        return
    }
    val first = rememberAutoFocus()
    // Webs que han pedido una verificación de seguridad (la API de animegratis, por ejemplo): se hace aquí mismo, sin ir a Recientes.
    val challenged by SourceChallenge.pending.collectAsStateWithLifecycle()
    var verifyHost by remember { mutableStateOf<String?>(null) }
    verifyHost?.let { host -> VerifySourceDialog(host) { verifyHost = null } }
    val busy = walking != null || CatalogBuilder.isWalking
    val enabledIds = AnimeSources.enabled.map { it.id }
    // No cabe todo: se desplaza (el foco la sigue) en vez de quedar cortado.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(appString(R.string.ui_cat_nota), color = White70, fontSize = Text14)
        KaizenButton(
            if (auto) appString(R.string.ui_cat_auto_si) else appString(R.string.ui_cat_auto_no),
            onClick = { auto = !auto; KaizenSettings.catalogAuto = auto },
            modifier = Modifier.focusRequester(first)
        )
        SectionHeader(appString(R.string.ui_cat_sec_fuentes))
        Text(appString(R.string.ui_cat_fuentes_nota), color = White70, fontSize = Text14)
        KaizenButton(
            if (walking == "*") "Recorriendo las fuentes…" else "Recorrer las fuentes que falten",
            onClick = { if (busy) KaizenToast.toast(appString(R.string.ui_cat_aviso_ocupado)) else walkMissing() }
        )
        Text(appString(R.string.ui_cat_recorrer_nota), color = White70, fontSize = Text14)
        AnimeSources.enabled.filter { it.host in challenged }.forEach { source ->
            KaizenButton(appString(R.string.ui_verificar_fuente, source.host), onClick = { verifyHost = source.host })
        }
        sources?.forEach { item ->
            SourceRow(item, active = item.source.id in enabledIds, busy = busy, walkingThis = walking == item.source.id,
                onSeries = { onBrowse(item) }, onWalk = { walk(item) })
        }
    }
}

@Composable
internal fun SectionHeader(title: String) {
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Color.White, fontSize = Text17, fontWeight = FontWeight.Bold)
        Box(Modifier.fillMaxWidth().height(1.dp).background(White60))
    }
}

@Composable
private fun SourceRow(item: SourceCatalog, active: Boolean, busy: Boolean, walkingThis: Boolean, onSeries: () -> Unit, onWalk: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(item.source.name, color = Color.White, fontSize = Text18, fontWeight = FontWeight.Bold)
            val walked = if (item.walkedAt > 0) dateFormat.format(Date(item.walkedAt)) else appString(R.string.ui_cat_nunca)
            Text(appString(if (active) R.string.ui_cat_detalle else R.string.ui_cat_detalle_inactiva, item.series, walked), color = White70, fontSize = Text14)
        }
        KaizenButton(appString(R.string.ui_cat_ver_series), onClick = onSeries)
        KaizenButton(
            if (walkingThis) appString(R.string.ui_cat_recorriendo) else appString(R.string.ui_cat_recorrer),
            onClick = {
                if (!active) KaizenToast.toast(appString(R.string.ui_cat_aviso_inactiva))
                else if (busy) KaizenToast.toast(appString(R.string.ui_cat_aviso_ocupado))
                else onWalk()
            }
        )
    }
}

/** Las series de una fuente con su slug, con un buscador por nombre o slug. */
@Composable
private fun ColumnScope.SeriesView(item: SourceCatalog, onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val rows = rememberLoadable(item.source.id) { runCatchingCoop { CatalogOverview.series(item.source.id) }.onFailure(::logError).getOrDefault(emptyList()) }.valueOrNull
    val input = rememberAutoFocus()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        KaizenButton(appString(R.string.ui_cat_volver), onClick = onBack)
        Text(appString(R.string.ui_cat_cabecera, item.source.name, rows?.size ?: item.series), color = Color.White, fontSize = Text17, fontWeight = FontWeight.Bold)
    }
    TitleField(query, { query = it }, input, appString(R.string.ui_cat_buscar))
    val all = rows
    if (all == null) {
        Text(appString(R.string.ui_cat_cargando), color = White70, fontSize = Text16)
        return
    }
    val shown = remember(all, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) all else all.filter { q in it.name.lowercase() || q in it.slug.lowercase() }
    }
    val keys = remember(shown) { app.kaizen.tv.ui.uniqueKeys(shown.map { it.slug }) }
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(shown.size, key = { keys[it] }, contentType = { "row" }) { index ->
            val row = shown[index]
            FocusCard(Modifier.fillMaxWidth(), onClick = {}) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.name, color = Color.White, fontSize = Text16, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(row.slug, color = White60, fontSize = Text14, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 16.dp).weight(1f))
                }
            }
        }
    }
}
