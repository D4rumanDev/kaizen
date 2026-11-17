package app.kaizen.tv.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.kaizen.R
import app.kaizen.commons.AppScope
import app.kaizen.commons.DebugLog
import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.appString
import app.kaizen.commons.logError
import app.kaizen.commons.runCatchingCoop
import app.kaizen.source.AnilistReviewRepository
import app.kaizen.source.AnilistSyncRunner
import app.kaizen.source.CatalogBuilder
import app.kaizen.source.UserTitles
import app.kaizen.tv.ui.KaizenToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Las cifras de la Biblioteca: se leen al abrir el panel y al volver de una pantalla (decidir una duda las cambia). Son estado de Compose: el panel las repinta al llegar. */
internal class LibraryStats {
    var facts by mutableStateOf<StateFacts?>(null)
    var pending by mutableStateOf<Int?>(null)

    suspend fun load() {
        facts = withContext(Dispatchers.IO) { loadStateFacts() }
        pending = withContext(Dispatchers.IO) { runCatchingCoop { AnilistReviewRepository.pending(adopt = false).size }.onFailure(::logError).getOrNull() }
        AnilistSyncRunner.refreshStatus()
    }
}

/**
 * Configuración › Biblioteca (panel lateral, como el resto de ajustes). Cada cifra sale una sola vez: arriba el resumen, y el contador de cada cosa en su fila.
 * Las pantallas que necesitan toda la altura se abren con [open] ([LibraryDialog]); el emparejado con AniList y el mantenimiento son submenús.
 */
internal class LibrarySettingsItems(private val stats: LibraryStats, private val open: (LibraryScreen) -> Unit) {
    private val icon = R.drawable.header_ic_directory

    fun items(): List<SettingItem> = listOf(
        ActionItem("Resumen", {
            stats.facts?.let { "${it.catalogSeries} series · ${it.walked}/${it.sources} fuentes · ${it.userMerges + it.userSplits} reglas tuyas" } ?: "Calculando…"
        }) { },
        ActionItem("Dudas de AniList", { stats.pending?.let { if (it == 0) "Ninguna por decidir" else "$it por decidir" } ?: "Calculando…" }) { open(LibraryScreen.REVIEW) },
        ActionItem("Series unidas", { stats.facts?.let { "${it.joined} (reúnen ${it.joinedFichas} fichas)" } ?: "Calculando…" }) { open(LibraryScreen.JOINED) },
        ActionItem("Fuentes", {
            val facts = stats.facts
            when {
                facts == null -> "Calculando…"
                facts.catalogSeries == 0 -> "Catálogo vacío: ninguna recorrida"
                facts.walked < facts.sources -> "Faltan ${facts.sources - facts.walked} por recorrer"
                else -> "Todas recorridas"
            }
        }) { open(LibraryScreen.SOURCES) },
        submenu("Emparejado con AniList", icon, { if (KaizenSettings.anilistSync) "Activado" else "Apagado" }, ::anilistItems),
        submenu("Mantenimiento", icon, { "Volver a agrupar o rehacer las uniones" }, ::maintenanceItems)
    )

    /** El emparejado: las casillas, el ritmo del relleno y, al final, cómo va (una cifra por fila). */
    private fun anilistItems(): List<SettingItem> = buildList {
        add(CheckItem("Emparejar con AniList", "Une las fichas de distintas fuentes que AniList da como el mismo anime", { KaizenSettings.anilistSync }) { AnilistSyncRunner.setEnabled(it) })
        add(CheckItem("Datos de AniList en la ficha", "Nota, estado, estudio y géneros de AniList donde la fuente no los da", { KaizenSettings.anilistFicha }) {
            KaizenSettings.anilistFicha = it
            DebugLog.info("AniList", "Datos de AniList en la ficha: ${if (it) "activado" else "apagado"}")
        })
        add(CheckItem("Rellenar fichas con AniList", "Pide a AniList, poco a poco, los datos de las fichas que no los tienen", { KaizenSettings.fillFichas }) { AnilistSyncRunner.setFillEnabled(it) })
        add(choiceItem("Fichas por tanda", KaizenSettings.FILL_BATCH_OPTIONS, KaizenSettings::fillBatch) { "$it" })
        add(choiceItem("Tope de fichas al día", KaizenSettings.FILL_DAILY_OPTIONS, KaizenSettings::fillDaily) { "$it" })
        // Lo que hace ahora y las cifras de cómo va: se leen al abrir el submenú (la pantalla las pidió al abrirse el panel).
        AnilistSyncRunner.activity.value.takeIf { it.isNotEmpty() }?.let { add(ActionItem("Ahora", { it }) { }) }
        (AnilistSyncRunner.status.value.split(" · ") + AnilistSyncRunner.conflicts.value.split(" · ")).filter { it.isNotBlank() }.forEach { line ->
            add(ActionItem(line.replaceFirstChar(Char::uppercase), { "" }) { })
        }
    }

    /** Siempre con confirmación (foco inicial en «Cancelar»): las dos cambian cosas. */
    private fun maintenanceItems(): List<SettingItem> = listOf(
        ConfirmItem("Volver a agrupar las series", "Usa los enlaces de AniList y tus reglas de ahora, sin pedir nada a las fuentes",
            "Se vuelven a agrupar las series con los enlaces de AniList y tus reglas. No se pide nada a las fuentes ni se pierde nada.", "Volver a agrupar") {
            AppScope.launch(Dispatchers.IO) {
                val done = runCatchingCoop { CatalogBuilder.regroup() }.onFailure(::logError).isSuccess
                KaizenToast.show(if (done) "Series reagrupadas" else "No se pudo reagrupar", longDuration = true)
            }
        },
        ConfirmItem("Rehacer todas las uniones", "Separa todas las series unidas y vacía el catálogo; se reconstruye solo",
            "Se borran las uniones automáticas y las que hiciste tú. Después el catálogo se reconstruye (varios minutos, con peticiones " +
                "a las fuentes) y solo se juntan las fichas que AniList da como el mismo anime. Se conservan tus títulos, portadas y «No son la misma»; " +
                "favoritos y capítulos vistos siguen a su ficha.", "Rehacer uniones") {
            UserTitles.resetJoins { done ->
                KaizenToast.show(if (done) appString(R.string.ui_uniones_deshechas_el_catalogo_se_reconstruira) else appString(R.string.ui_no_se_pudieron_deshacer_las_uniones), longDuration = true)
            }
        }
    )
}
