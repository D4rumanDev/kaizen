package app.kaizen.control

import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.Tuning
import kotlin.reflect.KMutableProperty0

/**
 * Un ajuste que se puede leer y cambiar por ADB (acciones `SETTINGS`, `SETTING_GET` y `SETTING_SET` de [AdbControlReceiver]). Cada uno trae sus
 * valores válidos: lo que no es una de [options] (o `true`/`false`) se rechaza, igual que el menú de Configuración solo ofrece esas opciones.
 */
class AdbSetting(
    val name: String,
    val kind: Kind,
    /** Valores aceptados, en texto; vacío = texto libre limitado a letras, cifras, espacios y `._-`. */
    val options: List<String>,
    val get: () -> String,
    val set: (String) -> Unit,
    /** Para un texto que no es de los libres (una URL, una clave): dice por qué no vale, o null si vale. */
    val validate: ((String) -> String?)? = null
) {
    enum class Kind { BOOL, CHOICE, TEXT }

    /** Por qué [value] no vale, o null si vale. */
    fun problem(value: String): String? = when {
        kind == Kind.TEXT && validate != null -> validate.invoke(value)
        kind == Kind.TEXT -> if (TEXT_OK.matches(value)) null else "texto no válido (solo letras, cifras, espacios, punto, guion y guion bajo; hasta 40)"
        value in options -> null
        else -> "valor no válido; admite: ${options.joinToString(", ")}"
    }

    private companion object {
        val TEXT_OK = Regex("[A-Za-z0-9 ._-]{0,40}")
    }
}

/**
 * Los ajustes de usuario que el control por ADB puede tocar. Quedan fuera, a propósito: «Control por ADB» (no se abre ni se cierra la puerta
 * por la propia puerta), los que activan trabajo en segundo plano con efectos (emparejado con AniList, relleno de fichas: tienen su botón y su
 * acción), la contraseña de la carpeta de red y de la sincronización (no deben quedar en el historial de una consola), el orden de botones y secciones, y todo el estado interno (migraciones, estados de series, copia aceptada).
 */
object AdbSettings {
    private fun fmt(value: Float) = if (value % 1f == 0f) value.toInt().toString() else value.toString()

    private fun bool(name: String, property: KMutableProperty0<Boolean>) =
        AdbSetting(name, AdbSetting.Kind.BOOL, listOf("true", "false"), { property.get().toString() }, { property.set(it == "true") })

    private fun ints(name: String, options: List<Int>, property: KMutableProperty0<Int>) =
        AdbSetting(name, AdbSetting.Kind.CHOICE, options.map(Int::toString), { property.get().toString() }, { property.set(it.toInt()) })

    private fun floats(name: String, options: List<Float>, property: KMutableProperty0<Float>) =
        AdbSetting(name, AdbSetting.Kind.CHOICE, options.map(::fmt), { fmt(property.get()) }, { property.set(it.toFloat()) })

    val all: List<AdbSetting> by lazy {
        listOf(
            // Reproductor
            AdbSetting("reproductor", AdbSetting.Kind.CHOICE, listOf("interno", "externo"), { if (KaizenSettings.useInternalPlayer) "interno" else "externo" }, { KaizenSettings.useInternalPlayer = it == "interno" }),
            ints("saltar_opening", KaizenSettings.OPENING_OPTIONS, KaizenSettings::openingSeconds),
            bool("aniskip", KaizenSettings::aniSkip),
            floats("velocidad", KaizenSettings.SPEED_OPTIONS, KaizenSettings::defaultSpeed),
            ints("salto_segundos", KaizenSettings.SEEK_OPTIONS, KaizenSettings::seekSeconds),
            ints("salto_maximo_segundos", KaizenSettings.SEEK_CAP_OPTIONS, KaizenSettings::seekCapSeconds),
            ints("reanudar_tras_segundos", KaizenSettings.RESUME_AFTER_OPTIONS, KaizenSettings::resumeAfterSeconds),
            ints("ocultar_controles_segundos", KaizenSettings.CONTROLS_HIDE_OPTIONS, KaizenSettings::controlsHideSeconds),
            ints("bufer_segundos", KaizenSettings.BUFFER_OPTIONS, KaizenSettings::bufferSeconds),
            bool("calidad_maxima", KaizenSettings::maxQuality),
            bool("siguiente_automatico", KaizenSettings::autoNext),
            bool("reanudar_sin_preguntar", KaizenSettings::resumeDirectly),
            bool("frecuencia_automatica", KaizenSettings::frameRateMatching),
            bool("reloj_reproductor", KaizenSettings::playerClock),
            bool("forzar_ffmpeg", KaizenSettings::forceFfmpeg),
            // Servidores y red
            bool("ultimo_servidor", KaizenSettings::autoServer),
            bool("aviso_laliga", KaizenSettings::laLigaWarning),
            AdbSetting("servidor_preferido", AdbSetting.Kind.TEXT, emptyList(), { KaizenSettings.preferredServer ?: "" }, { KaizenSettings.preferredServer = it.ifBlank { null } }),
            AdbSetting("dns_relevo", AdbSetting.Kind.CHOICE, app.kaizen.commons.FallbackDns.Provider.entries.map { it.label }, { KaizenSettings.dnsProvider }, { KaizenSettings.dnsProvider = it }),
            AdbSetting("warp_modo", AdbSetting.Kind.CHOICE, app.kaizen.commons.warp.WarpMode.entries.map { it.label }, { KaizenSettings.warpMode }, { KaizenSettings.warpMode = it }),
            AdbSetting("agente", AdbSetting.Kind.CHOICE, app.kaizen.commons.UserAgent.MODES, { app.kaizen.commons.UserAgent.mode }, { KaizenSettings.userAgentMode = it }),
            floats("paciencia", KaizenSettings.PATIENCE_OPTIONS, KaizenSettings::patience),
            ints("graficas_dias", KaizenSettings.CHART_DAYS_OPTIONS, KaizenSettings::chartDays),
            // Repositorio de descargas: la URL y la clave son de confianza (solo por ADB o desde el menú; nunca al restaurar una copia). Vacío = el del proyecto.
            bool("repo_descargas", KaizenSettings::repoDownloads),
            AdbSetting("repo_url", AdbSetting.Kind.TEXT, emptyList(), { KaizenSettings.repoUrl.orEmpty() }, { KaizenSettings.repoUrl = it.ifBlank { null } },
                validate = { v -> if (v.isBlank() || app.kaizen.source.spec.RepoConfig.normalizeUrl(v) != null) null else "URL no válida (https, sin usuario ni ? ni #)" }),
            AdbSetting("repo_clave", AdbSetting.Kind.TEXT, emptyList(), { KaizenSettings.repoKey.orEmpty() }, { KaizenSettings.repoKey = it.ifBlank { null } },
                validate = { v -> if (v.isBlank() || app.kaizen.source.spec.RepoConfig.validKey(v)) null else "clave no válida (ECDSA P-256, X.509 en base64)" }),
            // Sincronización entre dispositivos (la contraseña solo desde el menú: no debe quedar en el historial de una consola)
            bool("sync_auto", KaizenSettings::syncAuto),
            AdbSetting("sync_url", AdbSetting.Kind.TEXT, emptyList(), { KaizenSettings.syncUrl.orEmpty() }, { app.kaizen.commons.sync.SyncManager.setUrl(it) },
                validate = { v -> if (v.isBlank() || app.kaizen.source.spec.RepoConfig.normalizeUrl(v) != null) null else "URL no válida (https, sin usuario ni ? ni #)" }),
            AdbSetting("sync_usuario", AdbSetting.Kind.TEXT, emptyList(), { KaizenSettings.syncUser.orEmpty() }, { KaizenSettings.syncUser = it.ifBlank { null } }),
            // Copias: la carpeta de red (la contraseña solo desde el menú) y cuánto se guarda. Bajar los días o el máximo borra las copias más viejas en la siguiente limpieza.
            AdbSetting("smb_url", AdbSetting.Kind.TEXT, emptyList(), { KaizenSettings.smbUrl.orEmpty() }, { KaizenSettings.smbUrl = it.ifBlank { null } },
                validate = { v -> if (v.isBlank() || (v.startsWith("smb://", ignoreCase = true) && v.length <= 200 && '@' !in v && v.none { it.isWhitespace() || it == '?' || it == '#' })) null else "dirección no válida (smb://servidor/recurso/carpeta, sin usuario ni clave)" }),
            AdbSetting("smb_usuario", AdbSetting.Kind.TEXT, emptyList(), { KaizenSettings.smbUser.orEmpty() }, { KaizenSettings.smbUser = it.ifBlank { null } }),
            bool("smb_auto", KaizenSettings::smbAuto),
            bool("smb_descubrir", KaizenSettings::smbDiscovery),
            ints("smb_copias", KaizenSettings.SMB_KEEP_OPTIONS, KaizenSettings::smbKeep),
            ints("copias_dias", KaizenSettings.BACKUP_DAYS_OPTIONS, KaizenSettings::backupDays),
            ints("copias_maximo", KaizenSettings.BACKUP_FILES_OPTIONS, KaizenSettings::backupFiles),
            ints("estadisticas_dias", KaizenSettings.STATS_DAYS_OPTIONS, KaizenSettings::statsDays),
            // Interfaz (los de tamaño se ven al reabrir la app)
            floats("tamano_interfaz", KaizenSettings.UI_SCALE_OPTIONS, KaizenSettings::uiScale),
            bool("ajustar_pantalla", KaizenSettings::autoFit),
            floats("tamano_tarjetas", KaizenSettings.CARD_SCALE_OPTIONS, KaizenSettings::cardScale),
            floats("separacion_tarjetas", KaizenSettings.SPACING_OPTIONS, KaizenSettings::cardSpacing),
            ints("animaciones", listOf(0, 1, 2, 3), KaizenSettings::animationLevel),
            bool("zoom_al_enfocar", KaizenSettings::focusZoom),
            bool("panel_al_enfocar", KaizenSettings::focusPanel),
            ints("panel_segundos", KaizenSettings.FOCUS_PANEL_DELAY_OPTIONS, KaizenSettings::focusPanelDelay),
            bool("reloj_arriba", KaizenSettings::topClock),
            bool("reloj_24h", KaizenSettings::clock24h),
            ints("recientes", KaizenSettings.RECENTS_OPTIONS, KaizenSettings::recentsCount),
            // Con el trabajo periódico reprogramado: por ADB o al restaurar una copia el intervalo nuevo vale al momento, no al siguiente arranque.
            AdbSetting("recientes_cada_min", AdbSetting.Kind.CHOICE, KaizenSettings.RECENTS_EVERY_OPTIONS.map(Int::toString), { KaizenSettings.recentsEveryMinutes.toString() }, set = {
                KaizenSettings.recentsEveryMinutes = it.toInt()
                runCatching { app.kaizen.jobscheduler.RecentsWork.schedule(app.kaizen.App.context) }
            }),
            AdbSetting("seccion_al_abrir", AdbSetting.Kind.CHOICE, app.kaizen.tv.ui.MainSections.CONFIGURABLE_ROWS.map { it.first.toString() },
                { KaizenSettings.startSection.takeIf { id -> app.kaizen.tv.ui.MainSections.CONFIGURABLE_ROWS.any { row -> row.first == id } }?.toString() ?: app.kaizen.tv.ui.MainSections.ordered().first().first.toString() },
                { KaizenSettings.startSection = it.toInt() }),
            ints("ficha_emision_min", KaizenSettings.FICHA_AIRING_OPTIONS, KaizenSettings::fichaAiringMinutes),
            ints("ficha_finalizada_dias", KaizenSettings.FICHA_SETTLED_OPTIONS, KaizenSettings::fichaSettledDays),
            ints("catalogo_comprobar_dias", KaizenSettings.CATALOG_CHECK_OPTIONS, KaizenSettings::catalogCheckDays),
            ints("catalogo_recorrer_dias", KaizenSettings.CATALOG_WALK_OPTIONS, KaizenSettings::catalogWalkDays),
            ints("horario_horas", KaizenSettings.SCHEDULE_HOURS_OPTIONS, KaizenSettings::scheduleHours),
            ints("episodios_horas", KaizenSettings.EPISODES_HOURS_OPTIONS, KaizenSettings::episodesHours),
            ints("listados_dias", KaizenSettings.PAGES_DAYS_OPTIONS, KaizenSettings::pagesDays),
            ints("dispersion_porcentaje", KaizenSettings.SPREAD_OPTIONS, KaizenSettings::spreadPercent),
            ints("calidad_portadas", KaizenSettings.COVER_QUALITY_OPTIONS, KaizenSettings::coverQuality),
            ints("portadas_dias", KaizenSettings.COVER_DAYS_OPTIONS, KaizenSettings::coverDays),
            ints("memoria_imagenes_porcentaje", KaizenSettings.IMAGE_MEMORY_OPTIONS, KaizenSettings::imageMemoryPercent),
            // Copia, depuración y catálogo
            ints("registro_nivel", listOf(0, 1, 2, 3), KaizenSettings::debugLevel),
            ints("registro_kb", KaizenSettings.LOG_KB_OPTIONS, KaizenSettings::logKb),
            bool("catalogo_automatico", KaizenSettings::catalogAuto),
            bool("anilist_en_ficha", KaizenSettings::anilistFicha),
            ints("relleno_tanda", KaizenSettings.FILL_BATCH_OPTIONS, KaizenSettings::fillBatch),
            ints("relleno_dia", KaizenSettings.FILL_DAILY_OPTIONS, KaizenSettings::fillDaily)
        )
    }

    fun find(name: String): AdbSetting? = all.firstOrNull { it.name == name }

    /** Cambia [name] a [value] si es válido, reparte los ajustes de medida ([Tuning]) y devuelve «nombre: antes → después» o el motivo del rechazo. */
    fun set(name: String, value: String): Pair<Boolean, String> {
        val setting = find(name) ?: return false to "Ajuste desconocido: $name (lista: SETTINGS)"
        setting.problem(value)?.let { return false to "$name: $it" }
        val before = setting.get()
        setting.set(value)
        Tuning.apply()
        return true to "$name: ${before.ifEmpty { "(vacío)" }} → ${setting.get().ifEmpty { "(vacío)" }}"
    }
}
