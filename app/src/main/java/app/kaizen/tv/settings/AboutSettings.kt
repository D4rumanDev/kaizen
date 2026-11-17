package app.kaizen.tv.settings

import app.kaizen.BuildConfig
import app.kaizen.R
import app.kaizen.commons.AppScope
import app.kaizen.commons.DebugLog
import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.appString
import app.kaizen.commons.logError
import app.kaizen.commons.runCatchingCoop
import app.kaizen.source.AnimeSources
import app.kaizen.source.spec.RepoConfig
import app.kaizen.tv.ui.KaizenToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Configuración › Acerca de. La versión arriba; «Créditos y licencias» en un submenú (antes eran ocho filas mezcladas con la versión); el repositorio de descargas (de dónde
 * se bajan fuentes, enlaces y catálogo) y, cuando esté listo ([SettingsFlags.UPDATES]), las actualizaciones.
 */
internal class AboutSettingsItems {
    private val icon = R.drawable.settings_ic_about

    fun items(): List<SettingItem> = listOfNotNull<SettingItem>(
        ActionItem(appString(R.string.ui_kaizen), { "Versión ${BuildConfig.VERSION_NAME} · para TV, sin anuncios ni telemetría" }) { },
        submenu("Repositorio de descargas", icon, { if (KaizenSettings.repoDownloads) "Activado" else "Apagado" }, ::repoItems),
        if (SettingsFlags.UPDATES) submenu("Actualizaciones", icon, { updateModeLabels[updateModes.indexOf(KaizenSettings.updateMode).coerceAtLeast(0)] }, ::updateItems) else null,
        submenu("Créditos y licencias", icon, { "Bibliotecas y proyectos de los que depende" }, ::creditItems),
        ActionItem(appString(R.string.ui_fuentes_de_anime), { AnimeSources.all.joinToString { it.name } }) { }
    )

    /** La app y las bibliotecas y proyectos de los que depende, con su licencia. */
    private fun creditItems() = listOf(
        ActionItem(appString(R.string.ui_ukiku_base_del_proyecto), { appString(R.string.ui_jordy_alexis_mendoza_caballero_mit_github_com_jo) }) { },
        ActionItem(appString(R.string.ui_nextlib_decodificadores_ffmpeg), { appString(R.string.ui_github_com_anilbeesetti_nextlib_gpl_3_0) }) { },
        ActionItem(appString(R.string.ui_rhino_javascript), { appString(R.string.ui_mozilla_mpl_2_0_github_com_mozilla_rhino) }) { },
        ActionItem(appString(R.string.ui_jsoup_lectura_de_html), { appString(R.string.ui_jsoup_org_mit) }) { },
        ActionItem(appString(R.string.ui_androidx_compose_room_media3_okhttp_gson_y_commo), { appString(R.string.ui_apache_2_0) }) { },
        ActionItem(appString(R.string.ui_coil_imagenes), { appString(R.string.ui_apache_2_0_github_com_coil_kt_coil) }) { },
        ActionItem(appString(R.string.ui_nanohttpd_proxy_local), { appString(R.string.ui_bsd_de_3_clausulas_github_com_nanohttpd_nanohttp) }) { }
    )

    private val updateModes = listOf("off", "notice", "dialog")
    private val updateModeLabels = listOf("No mirar", "Aviso discreto", "Diálogo")

    /** Preparado, sin lógica: solo se enseña con [SettingsFlags.UPDATES] encendido. */
    private fun updateItems() = listOf(
        ChoiceItem("Avisar de versiones nuevas", updateModeLabels,
            { updateModes.indexOf(KaizenSettings.updateMode).coerceAtLeast(0) }, { KaizenSettings.updateMode = updateModes[it] }),
        ActionItem("Comprobar ahora", { "Mira si hay versiones nuevas de la app y de las fuentes" }) { KaizenToast.toast("Aún no disponible") }
    )

    /**
     * Repositorio: de dónde se descargan fuentes, enlaces y catálogo (el del proyecto por defecto, o uno propio firmado con tu clave).
     */
    private fun repoItems(): List<SettingItem> {
        val sources = AnimeSources.all
        val repoProblem = { v: String -> if (v.isBlank() || RepoConfig.normalizeUrl(v) != null) null else "Debe ser una dirección https, sin usuario ni ? ni #" }
        return listOf(
            CheckItem("Descargar del repositorio", "Fuentes, enlaces y catálogo. Apagado por defecto mientras el repositorio del proyecto sea privado", KaizenSettings::repoDownloads),
            TextItem("URL del repositorio", { RepoConfig.baseUrl + if (RepoConfig.isCustom) "" else " (el del proyecto)" },
                { KaizenSettings.repoUrl.orEmpty() }, { KaizenSettings.repoUrl = it.ifBlank { null } }, repoProblem, "https://…/",
                "Vacío usa el repositorio del proyecto. Todo lo que se baje debe ir firmado con la clave de abajo: sin firma válida no se usa nada."),
            TextItem("Clave pública del repositorio", {
                val key = KaizenSettings.repoKey
                if (key.isNullOrBlank()) "La del proyecto" else "Propia (termina en …${key.takeLast(8)})"
            }, { KaizenSettings.repoKey.orEmpty() }, { KaizenSettings.repoKey = it.ifBlank { null } },
                { v -> if (v.isBlank() || RepoConfig.validKey(v)) null else "No es una clave ECDSA P-256 (X.509 en base64)" }, "MFkwEwYHKoZIzj0C…",
                "Si publicas en tu propio repositorio, firma con tu clave privada y pon aquí la pública. Vacío = la del proyecto."),
            MultiChoiceItem("Fuentes a descargar", sources.map { it.name },
                { sources.indices.filter { RepoConfig.wants(sources[it].id) }.toSet() },
                { chosen -> KaizenSettings.repoSources = if (chosen.size == sources.size) emptySet() else chosen.map { sources[it].id }.toSet() },
                minSelected = 1, minMessage = "Elige al menos una fuente"),
            ActionItem("Descargar ahora", { "Mira el repositorio: fuentes, enlaces y catálogo" }) {
                if (!RepoConfig.enabled) return@ActionItem KaizenToast.toast("Activa antes «Descargar del repositorio»")
                KaizenToast.toast("Mirando el repositorio…")
                AppScope.launch(Dispatchers.IO) {
                    runCatchingCoop {
                        val updated = app.kaizen.source.spec.SpecSources.refresh(force = true)
                        KaizenSettings.catalogSeedCheckedAt = 0L
                        app.kaizen.source.CatalogBuilder.ensureFresh()
                        updated
                    }.onSuccess { KaizenToast.toastLong(if (it > 0) "$it fuente(s) nueva(s): se usarán al reabrir la app" else "Repositorio revisado") }
                        .onFailure { logError(it); KaizenToast.toastLong("No se pudo revisar el repositorio") }
                }
            },
            ConfirmItem("Volver al repositorio del proyecto", "Borra la URL y la clave propias", warning = "Se vuelve a usar el repositorio y la clave del proyecto.", confirmLabel = "Volver") {
                KaizenSettings.repoUrl = null
                KaizenSettings.repoKey = null
                KaizenToast.toast("Repositorio del proyecto")
            }
        )
    }
}

/** Configuración › Depuración: qué se anota en el registro (ver [DebugLog]), verlo y vaciarlo. */
internal class DebugSettingsItems(private val openDebugLog: () -> Unit) {
    fun items(): List<SettingItem> = listOf(
        ChoiceItem("Nivel del registro", DebugLog.LEVELS, { KaizenSettings.debugLevel.coerceIn(0, DebugLog.LEVELS.lastIndex) }) {
            KaizenSettings.debugLevel = it
            DebugLog.info("Depuración", "Nivel: ${DebugLog.LEVELS[it]}")
        },
        tunedChoice("Tamaño máximo del registro", KaizenSettings.LOG_KB_OPTIONS, KaizenSettings::logKb) { if (it >= 1024) "${it / 1024} MB" else "$it KB" },
        CheckItem("Control por ADB", "Permite crear copias y ver recuentos desde ADB (solo el shell, solo esas dos acciones)", KaizenSettings::adbControl),
        ActionItem("Ver el registro", { "Lo escrito hasta ahora (${DebugLog.file.path})" }) { openDebugLog() },
        ConfirmItem("Borrar el registro", "Vacía el fichero del registro", warning = "Se borra todo lo escrito en el registro de depuración.",
            confirmLabel = "Borrar") { DebugLog.clear(); app.kaizen.tv.ui.KaizenToast.toast("Registro borrado") }
    )
}
