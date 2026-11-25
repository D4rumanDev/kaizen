package app.kaizen.tv.settings

import app.kaizen.R
import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.appString

/**
 * Configuración › Reproductor. Lo que más se toca va suelto arriba (motor, calidad, siguiente capítulo) y el resto, en cuatro submenús:
 * Reproducción, Controles, Continuidad y Saltar intro y cierre (AniSkip). «Botones del reproductor» abre su propio diálogo (el único tercer nivel).
 */
internal class PlayerSettingsItems(private val openPlayerButtons: () -> Unit) {
    private val icon = R.drawable.settings_ic_player

    fun items(): List<SettingItem> = listOf(
        ChoiceItem("Reproductor de vídeo", listOf(appString(R.string.ui_interno), appString(R.string.ui_externo)),
            { if (KaizenSettings.useInternalPlayer) 0 else 1 }, { KaizenSettings.useInternalPlayer = it == 0 }),
        ChoiceItem(appString(R.string.ui_calidad), listOf(appString(R.string.ui_automatica_segun_la_red), appString(R.string.ui_maxima_siempre)),
            { if (KaizenSettings.maxQuality) 1 else 0 }, { KaizenSettings.maxQuality = it == 1 }),
        CheckItem(appString(R.string.ui_siguiente_capitulo_automatico), appString(R.string.ui_aviso_con_cuenta_atras_de_10_s), KaizenSettings::autoNext),
        submenu("Reproducción", icon, { "Búfer ${KaizenSettings.bufferSeconds} s · velocidad ${KaizenSettings.formatSpeed(KaizenSettings.defaultSpeed)}" }, ::playbackItems),
        submenu("Controles", icon, { "Salto de ${KaizenSettings.seekSeconds} s" }, ::controlsItems),
        submenu("Continuidad", icon, { if (KaizenSettings.resumeDirectly) "Reanuda sin preguntar" else "Pregunta al reanudar" }, ::continuityItems),
        submenu("Saltar intro y cierre (AniSkip)", icon, ::skipSummary, ::skipItems)
    )

    private fun playbackItems() = listOf(
        choiceItem("Búfer de reproducción", KaizenSettings.BUFFER_OPTIONS, KaizenSettings::bufferSeconds) { if (it == 50) "$it s (normal)" else "$it s" },
        choiceItem(appString(R.string.ui_velocidad_por_defecto), KaizenSettings.SPEED_OPTIONS, KaizenSettings::defaultSpeed, KaizenSettings::formatSpeed),
        CheckItem(appString(R.string.ui_frecuencia_automatica), appString(R.string.ui_ajusta_la_tele_a_los_fps_del_video_23_976_25_50), KaizenSettings::frameRateMatching),
        CheckItem("Decodificador alternativo (ffmpeg)", appString(R.string.ui_si_el_decodificador_de_la_tele_da_tirones_o_pant), KaizenSettings::forceFfmpeg)
    )

    private fun controlsItems() = listOf(
        choiceItem(appString(R.string.ui_salto_atras_adelante), KaizenSettings.SEEK_OPTIONS, KaizenSettings::seekSeconds) { "$it s" },
        tunedChoice("Salto máximo al mantener la tecla", KaizenSettings.SEEK_CAP_OPTIONS, KaizenSettings::seekCapSeconds) { "$it s" },
        choiceItem(appString(R.string.ui_ocultar_controles_tras), KaizenSettings.CONTROLS_HIDE_OPTIONS, KaizenSettings::controlsHideSeconds) { "$it s" },
        CheckItem(appString(R.string.ui_reloj), appString(R.string.ui_arriba_a_la_derecha_de_los_controles), KaizenSettings::playerClock),
        ActionItem(appString(R.string.ui_botones_del_reproductor), { "" }, opens = true) { openPlayerButtons() }
    )

    private fun continuityItems() = listOf(
        CheckItem(appString(R.string.ui_reanudar_sin_preguntar), appString(R.string.ui_sigue_donde_lo_dejaste), KaizenSettings::resumeDirectly),
        tunedChoice("Reanudar solo pasados", KaizenSettings.RESUME_AFTER_OPTIONS, KaizenSettings::resumeAfterSeconds) { if (it == 0) "Siempre" else "$it s" }
    )

    private fun skipSummary(): String = when {
        KaizenSettings.aniSkip -> "Tiempos reales de AniSkip"
        KaizenSettings.openingSeconds == 0 -> "Desactivado"
        else -> "Saltar el opening: ${KaizenSettings.openingSeconds} s"
    }

    /** AniSkip es un interruptor y, si no hay tiempos reales, un salto fijo de respaldo para el opening. */
    private fun skipItems() = listOf(
        CheckItem("Tiempos reales de AniSkip", "Salta justo el opening y el cierre (envía a api.aniskip.com el id de MyAnimeList y el nº de capítulo)", KaizenSettings::aniSkip),
        choiceItem("Respaldo: saltar el opening", KaizenSettings.OPENING_OPTIONS, KaizenSettings::openingSeconds) { if (it == 0) appString(R.string.ui_desactivado) else "$it s" }
    )
}
