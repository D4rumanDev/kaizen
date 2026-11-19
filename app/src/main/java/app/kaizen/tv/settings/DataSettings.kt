package app.kaizen.tv.settings

import app.kaizen.R
import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.appString

/**
 * Configuración › Datos y caché. Arriba, los capítulos recientes; debajo, «Cada cuánto se actualiza cada cosa» (los plazos de renovación) y «Portadas, memoria y
 * estadísticas». Lo que se ve en pantalla está en Pantalla; aquí, lo que se renueva en segundo plano y cuánto se guarda.
 */
internal class DataSettingsItems {
    private val icon = R.drawable.ic_sync

    fun items(): List<SettingItem> = listOf(
        CheckItem("Modo sin conexión", "No pide nada a las webs, no abre pausas de 2, 5 y 10 min y usa solo lo guardado (portadas aunque hayan caducado)", { KaizenSettings.offlineMode }) {
            app.kaizen.commons.NetworkState.setManual(it)
        },
        choiceItem(appString(R.string.ui_capitulos_recientes), KaizenSettings.RECENTS_OPTIONS, KaizenSettings::recentsCount) { "$it" },
        // Al cambiarlo se reprograma el trabajo periódico (si no, seguiría con el intervalo de antes).
        ChoiceItem("Actualizar los recientes cada", KaizenSettings.RECENTS_EVERY_OPTIONS.map { if (it < 60) "$it min" else "${it / 60} h" },
            { KaizenSettings.RECENTS_EVERY_OPTIONS.indexOf(KaizenSettings.recentsEveryMinutes).coerceAtLeast(0) }) { position ->
            KaizenSettings.recentsEveryMinutes = KaizenSettings.RECENTS_EVERY_OPTIONS[position]
            app.kaizen.jobscheduler.RecentsWork.schedule(app.kaizen.App.context)
        },
        submenu("Cada cuánto se actualiza cada cosa", icon, { "Fichas, episodios, horario, notas y catálogo" }, ::renewalItems),
        submenu("Portadas, memoria y estadísticas", icon, { "Calidad de portadas ${coverQualityLabel(KaizenSettings.coverQuality).substringBefore(" (").lowercase()}" }, ::storageItems)
    )

    /** Cuánto se sirve lo guardado antes de volver a pedirlo a las webs (menos = más al día y más peticiones). */
    private fun renewalItems() = listOf(
        choiceItem("Ficha de una serie en emisión: renovar cada", KaizenSettings.FICHA_AIRING_OPTIONS, KaizenSettings::fichaAiringMinutes, ::minutesLabel),
        choiceItem("Ficha de una serie finalizada: renovar cada", KaizenSettings.FICHA_SETTLED_OPTIONS, KaizenSettings::fichaSettledDays) { "$it días" },
        choiceItem("Episodios publicados: renovar cada", KaizenSettings.EPISODES_HOURS_OPTIONS, KaizenSettings::episodesHours) { "$it h" },
        choiceItem("Horario de emisión: renovar cada", KaizenSettings.SCHEDULE_HOURS_OPTIONS, KaizenSettings::scheduleHours) { "$it h" },
        choiceItem("Listados y búsquedas: renovar cada", KaizenSettings.PAGES_DAYS_OPTIONS, KaizenSettings::pagesDays) { "$it días" },
        choiceItem("Catálogo: preguntar a cada web cuántas series tiene cada", KaizenSettings.CATALOG_CHECK_OPTIONS, KaizenSettings::catalogCheckDays) { "$it días" },
        choiceItem("Catálogo: recorrerlo entero cada", KaizenSettings.CATALOG_WALK_OPTIONS, KaizenSettings::catalogWalkDays) { "$it días" },
        tunedChoice("Paciencia con fuentes y servidores", KaizenSettings.PATIENCE_OPTIONS, KaizenSettings::patience) {
            KaizenSettings.formatFactor(it) + when (it) { 0.5f -> " (menos espera)"; 2f -> " (más espera)"; else -> " (normal)" }
        },
        // Cada elemento caduca en su punto del plazo (±%), no todos el mismo día: las webs no reciben todas las peticiones de golpe.
        choiceItem("Repartir las actualizaciones en el tiempo (antes y después del plazo)", KaizenSettings.SPREAD_OPTIONS, KaizenSettings::spreadPercent) { if (it == 0) "Ninguna (plazos exactos)" else "±$it %" }
    )

    private fun storageItems() = listOf(
        tunedChoice("Portadas guardadas: no volver a pedirlas durante", KaizenSettings.COVER_DAYS_OPTIONS, KaizenSettings::coverDays) { if (it >= 3650) "Nunca (solo si falta)" else "$it días" },
        choiceItem("Calidad de las portadas (al reiniciar la app)", KaizenSettings.COVER_QUALITY_OPTIONS, KaizenSettings::coverQuality, ::coverQualityLabel),
        tunedChoice("Memoria para imágenes (al reiniciar la app)", KaizenSettings.IMAGE_MEMORY_OPTIONS, KaizenSettings::imageMemoryPercent) { "$it %" },
        tunedChoice("Días que se guardan las estadísticas (bajarlo borra lo anterior)", KaizenSettings.STATS_DAYS_OPTIONS, KaizenSettings::statsDays) { "$it días" },
        tunedChoice("Días de las gráficas de conexión", KaizenSettings.CHART_DAYS_OPTIONS, KaizenSettings::chartDays) { "$it días" }
    )

    private fun coverQualityLabel(quality: Int): String =
        when (quality) { 1 -> "Alta"; 2 -> "Equilibrada"; 3 -> "Ligera (menos datos y memoria)"; else -> "Automática (según el equipo y la red)" }
}
