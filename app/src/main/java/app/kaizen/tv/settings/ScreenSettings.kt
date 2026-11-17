package app.kaizen.tv.settings

import android.content.Context
import app.kaizen.R
import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.appString
import app.kaizen.commons.findActivity
import app.kaizen.tv.ui.MainSections

/**
 * Configuración › Pantalla. El tamaño de la interfaz va suelto arriba; el resto, en submenús: Tamaño y tarjetas, Foco y animaciones, Reloj, Inicio y menú y, cuando
 * esté listo, Contenido sensible (apagado por [SettingsFlags.SENSITIVE]).
 */
internal class ScreenSettingsItems(private val context: Context, private val openMenuSections: () -> Unit) {
    private val icon = R.drawable.settings_ic_ui

    fun items(): List<SettingItem> = listOfNotNull(
        ChoiceItem(appString(R.string.ui_tamano_de_la_interfaz), KaizenSettings.UI_SCALE_OPTIONS.map(KaizenSettings::formatFactor),
            { KaizenSettings.UI_SCALE_OPTIONS.indexOf(KaizenSettings.effectiveUiScale) }) { position ->
            val chosen = KaizenSettings.UI_SCALE_OPTIONS[position]
            if (chosen != KaizenSettings.effectiveUiScale) {
                // A prueba: la pantalla pregunta si se mantiene y, sin respuesta, vuelve al tamaño anterior.
                KaizenSettings.trialUiScale(chosen)
                // La densidad se aplica al crear la actividad: se recrea para verlo al momento.
                context.findActivity()?.recreate()
            }
        },
        submenu("Tamaño y tarjetas", icon, { "Tarjetas ${KaizenSettings.formatFactor(KaizenSettings.cardScale)}" }, ::sizeItems),
        submenu("Foco y animaciones", icon, { if (KaizenSettings.focusPanel) "Panel de datos al enfocar" else "Sin panel de datos" }, ::focusItems),
        submenu("Reloj", icon, { if (KaizenSettings.topClock) "Hora arriba" else "Sin hora" }, ::clockItems),
        submenu("Inicio y menú", icon, { MainSections.ordered().firstOrNull { it.first == KaizenSettings.startSection }?.second ?: "Inicio" }, ::menuItems),
        if (SettingsFlags.SENSITIVE) submenu("Contenido sensible", icon, { if (KaizenSettings.sensitiveBlur) "Activado" else "Apagado" }, ::sensitiveItems) else null
    )

    private fun sizeItems() = listOf(
        CheckItem(appString(R.string.ui_ajustar_a_la_pantalla), appString(R.string.ui_mismo_tamano_en_teles_de_otra_resolucion_o_densi), KaizenSettings::autoFit) {
            KaizenSettings.autoFit = it
            context.findActivity()?.recreate()
        },
        cardChoice(context, "Tamaño de las tarjetas", KaizenSettings.CARD_SCALE_OPTIONS, KaizenSettings::cardScale),
        cardChoice(context, appString(R.string.ui_separacion_entre_tarjetas), KaizenSettings.SPACING_OPTIONS, KaizenSettings::cardSpacing)
    )

    private fun focusItems() = listOf(
        ChoiceItem("Animaciones", KaizenSettings.ANIMATION_OPTIONS, { KaizenSettings.animationLevel }, { KaizenSettings.animationLevel = it }),
        CheckItem(appString(R.string.ui_animacion_al_enfocar), appString(R.string.ui_ampliar_la_tarjeta_con_el_foco_zoom), KaizenSettings::focusZoom),
        CheckItem(appString(R.string.ui_panel_de_datos_al_enfocar), appString(R.string.ui_nota_ano_y_sinopsis_de_anilist_al_dejar_el_foco_un_momento), KaizenSettings::focusPanel),
        choiceItem(appString(R.string.ui_tiempo_panel_datos), KaizenSettings.FOCUS_PANEL_DELAY_OPTIONS, KaizenSettings::focusPanelDelay) { appString(R.string.ui_n_segundos, it) }
    )

    private fun clockItems() = listOf(
        CheckItem(appString(R.string.ui_hora_y_fecha_arriba), null, KaizenSettings::topClock),
        ChoiceItem(appString(R.string.ui_formato_de_hora), listOf(appString(R.string.ui_24_horas), appString(R.string.ui_12_horas)),
            { if (KaizenSettings.clock24h) 0 else 1 }, { KaizenSettings.clock24h = it == 0 })
    )

    private fun menuItems() = listOf(
        ChoiceItem(appString(R.string.ui_seccion_al_abrir), MainSections.ordered().map { it.second },
            { MainSections.ordered().indexOfFirst { it.first == KaizenSettings.startSection }.coerceAtLeast(0) },
            { KaizenSettings.startSection = MainSections.ordered()[it].first }),
        ActionItem(appString(R.string.ui_secciones_del_menu), { "Mostrar, ocultar y organizar" }) { openMenuSections() }
    )

    /** Modo parental: las carátulas de las series sensibles (adultas o de los géneros ecchi y hentai en AniList) salen desenfocadas en tarjetas y ficha. */
    private fun sensitiveItems() = listOf(
        CheckItem("Carátulas con blur", "Desenfoca las carátulas de las series adultas o ecchi y hentai de AniList", KaizenSettings::sensitiveBlur)
    )
}
