package app.kaizen.commons

import android.content.SharedPreferences
import androidx.core.content.edit
import app.kaizen.App
import app.kaizen.R
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Kaizen: ajustes propios (panel «Herramientas» del menú lateral). */
object KaizenSettings {
    val OPENING_OPTIONS = listOf(0, 60, 85, 90, 100)
    val SPEED_OPTIONS = listOf(0.75f, 1f, 1.25f, 1.5f)
    const val UI_SCALE_CONFIRM_MS = 15_000L
    val UI_SCALE_OPTIONS = listOf(0.75f, 0.9f, 1f, 1.1f, 1.25f)
    val SPACING_OPTIONS = listOf(0.5f, 0.75f, 1f, 1.5f, 2f, 3f)
    val CARD_SCALE_OPTIONS = listOf(0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f)
    val RECENTS_OPTIONS = listOf(20, 40, 60, 100, 150)
    /** Cada cuántos minutos se renuevan los recientes en segundo plano (el mínimo de Android para un trabajo periódico es 15). */
    val RECENTS_EVERY_OPTIONS = listOf(15, 30, 60, 120, 180)
    // Plazos de lo que se guarda y se renueva (ver [Timings]); lo de antes eran constantes sin ajuste.
    val FICHA_AIRING_OPTIONS = listOf(15, 30, 60, 120, 240)       // minutos
    val FICHA_SETTLED_OPTIONS = listOf(7, 15, 30, 60, 90)         // días
    val CATALOG_CHECK_OPTIONS = listOf(7, 14, 30)                 // días
    val CATALOG_WALK_OPTIONS = listOf(15, 30, 60, 90)             // días
    val SCHEDULE_HOURS_OPTIONS = listOf(6, 12, 24, 48)            // horas
    val EPISODES_HOURS_OPTIONS = listOf(1, 3, 6, 12, 24)          // horas
    val RATING_DAYS_OPTIONS = listOf(7, 15, 30, 60)               // días
    val PAGES_DAYS_OPTIONS = listOf(1, 3, 7, 14, 30)              // días
    /** Dispersión de las renovaciones: cada elemento (serie, fuente, portada) caduca entre ±N % del plazo, no todos el mismo día. */
    val SPREAD_OPTIONS = listOf(0, 10, 20, 30, 50)                // %
    /** Calidad de las portadas: automática (según el equipo y la red), alta, equilibrada o ligera. */
    val COVER_QUALITY_OPTIONS = listOf(0, 1, 2, 3)
    /** Segundos con el foco quieto antes de que salga el panel de datos de una tarjeta. */
    val FOCUS_PANEL_DELAY_OPTIONS = listOf(1, 2, 3, 4, 5)
    val SEEK_OPTIONS = listOf(5, 10, 15, 30, 60)
    val CONTROLS_HIDE_OPTIONS = listOf(3, 5, 8, 10, 15, 30)
    val BUFFER_OPTIONS = listOf(15, 30, 50, 90, 120)

    // Opciones de los ajustes de medida (nunca estilos): cada lista incluye el valor de fábrica.
    /** Segundos vistos desde los que se ofrece reanudar (0 = siempre). */
    val RESUME_AFTER_OPTIONS = listOf(0, 10, 30, 60, 120)
    /** Salto máximo (s) al mantener pulsada la flecha con los controles ocultos. */
    val SEEK_CAP_OPTIONS = listOf(30, 60, 90, 120, 180)
    /** Multiplicador del tiempo que se espera a una fuente o servidor antes de darlo por caído. */
    val PATIENCE_OPTIONS = listOf(0.5f, 1f, 2f)
    /** Días que una portada guardada se usa sin preguntar a la web. */
    /** Días que se usa una portada guardada sin preguntar a la web; 3650 = «nunca» (solo se pide si falta): las portadas casi no cambian. */
    val COVER_DAYS_OPTIONS = listOf(7, 15, 30, 60, 90, 180, 365, 3650)
    /** Parte de la memoria que ocupan las imágenes ya decodificadas. */
    val IMAGE_MEMORY_OPTIONS = listOf(10, 20, 30)
    /** Días que se guardan las copias automáticas. */
    val BACKUP_DAYS_OPTIONS = listOf(3, 7, 14, 30)
    /** Máximo de ficheros de copia. */
    val BACKUP_FILES_OPTIONS = listOf(10, 30, 60)
    /** Tamaño máximo del registro de depuración, en KB. */
    val LOG_KB_OPTIONS = listOf(256, 512, 1024, 2048)
    /** Días que se guardan los intentos de conexión de las estadísticas. */
    val STATS_DAYS_OPTIONS = listOf(30, 60, 90, 180)
    /** Días que abarcan las gráficas de Estadísticas › Conexión. */
    val CHART_DAYS_OPTIONS = listOf(7, 14, 30, 60, 90)
    /** Fichas que se abren en cada tanda del relleno en segundo plano. */
    val FILL_BATCH_OPTIONS = listOf(10, 20, 50)
    /** Fichas como mucho al día en el relleno en segundo plano. */
    val FILL_DAILY_OPTIONS = listOf(100, 300, 600)

    private const val HIDDEN_ROWS_VERSION = "kaizen_hidden_rows_version"

    private val prefs get() = KaizenStore.settings

    // ---- Ajustes heredados (antes en PrefsUtil): mismas claves de siempre, sin prefijo «kaizen_».

    /** Reproductor: interno (por defecto) o externo; la clave `player_type` guarda «0» o «1». */
    var useInternalPlayer: Boolean
        get() = prefs.getString("player_type", "0") == "0"
        set(value) = prefs.edit { putString("player_type", if (value) "0" else "1") }

    /** Modo oscuro de AppCompat (texto con el número). */
    val themeOption: String
        get() = prefs.getString("theme_option", App.context.getString(R.string.theme_default)) ?: App.context.getString(R.string.theme_default)

    /** Segundos de espera de las conexiones HTTP (0 en la TV: sin límite propio). */
    var timeoutTime: Long
        get() = prefs.getString("timeout_time", if (App.context.resources.getBoolean(R.bool.isTv)) "0" else "10")?.toLong() ?: 0
        set(value) = prefs.edit { putString("timeout_time", value.toString()) }

    /** La imagen del capítulo en las notificaciones de recientes. */
    val showRecentImage: Boolean get() = prefs.getBoolean("recent_image", true)

    /** Agrupar las notificaciones (si el sistema lo permite). */
    val isGroupingEnabled: Boolean get() = prefs.getBoolean("group_notifications", true) && canGroupNotifications

    /** Qué User-Agent usa toda la app (ver [UserAgent]): «local» (el del navegador de esta TV) o «aleatorio». */
    var userAgentMode: String? by stringPref("kaizen_ua_mode", UserAgent.LOCAL)

    /** El User-Agent aleatorio ya generado (se crea la primera vez que hace falta y se conserva). */
    var randomUserAgent: String? by stringPref("kaizen_ua_random", null)

    var openingSeconds: Int by intPref("kaizen_opening_seconds", 85)

    var defaultSpeed: Float by floatPref("kaizen_default_speed", 1f)

    var autoNext: Boolean by booleanPref("kaizen_auto_next", true)

    /** Saltar el opening y el cierre con los tiempos reales de AniSkip (envía a api.aniskip.com el id de MyAnimeList y el número de capítulo). */
    var aniSkip: Boolean by booleanPref("kaizen_aniskip", true)

    /** Deja que el shell de ADB cree copias y consulte recuentos (ver [app.kaizen.control.AdbControlReceiver]). Apagado de fábrica. */
    var adbControl: Boolean by booleanPref("kaizen_adb_control", false)

    /** Empareja las series con AniList y guarda sus fichas en segundo plano (ver [app.kaizen.source.AnilistSyncRunner]). Apagado de fábrica. */
    var anilistSync: Boolean by booleanPref("kaizen_anilist_sync", false)

    /** Versión del criterio por pruebas (año, formato, idMal, episodios) con la que se revisaron los enlaces automáticos «probable» (ver [app.kaizen.source.AnilistMatcher.levelWithEvidence]). */
    var anilistEvidence: Int by intPref("kaizen_anilist_evidence", 0)
    /** Cuándo se hizo la última limpieza de enlaces del emparejado (`AnilistSyncRunner`): el límite de 6 h vale entre arranques (medido: 35,8 s de CPU en cada arranque). */
    var anilistLastClean: Long by longPref("kaizen_anilist_last_clean", 0L)

    /** Versión de la búsqueda por formato con la que se revisaron las fichas «sin resultado» de película, OVA o especial (ver [app.kaizen.source.AnilistMatcher.searchPlan]). */
    var anilistFormatSearch: Int by intPref("kaizen_anilist_format_search", 0)

    /** Completar en segundo plano, a ritmo lento, las fichas de las webs que aún no están enlazadas con AniList (ver [app.kaizen.source.FichaFill]). Apagado de fábrica. */
    var fillFichas: Boolean by booleanPref("kaizen_fill_fichas", false)

    /** Cuándo fue la última tanda y cuántas fichas se abrieron en el día (número de día desde 1970) para no pasar del tope diario. */
    var fillLastAt: Long by longPref("kaizen_fill_last_at", 0L)
    var fillDay: Long by longPref("kaizen_fill_day", 0L)
    var fillCount: Int by intPref("kaizen_fill_count", 0)

    /** Las fichas que no se pudieron abrir en el relleno, con la hora del fallo (`fuente|slug|ms` por línea): no se reintentan hasta pasada una semana. */
    var fillFailed: String? by stringPref("kaizen_fill_failed", null)

    /** La ficha de la app completa su nota con la de AniList cuando la serie tiene anime enlazado (ver [app.kaizen.source.AnilistFicha]). Encendido de fábrica. */
    var anilistFicha: Boolean by booleanPref("kaizen_anilist_ficha", true)
    /** Versión del criterio de emparejado con el que se guardaron las filas automáticas (ver `AnilistMatcher.VERSION`). */
    var anilistMatcher: Int by intPref("kaizen_anilist_matcher", 0)

    /** Usar directamente el último servidor que funcionó (la lista sale solo si falla). */
    var autoServer: Boolean by booleanPref("kaizen_auto_server", true)

    /** Cambiar la frecuencia de la pantalla a la del vídeo (23,976/25/50/60 Hz). */
    var frameRateMatching: Boolean by booleanPref("kaizen_frame_rate_matching", true)

    /** Recorrer el catálogo interno solo (al abrir la app y cuando caduca); se gestiona en Configuración › Biblioteca › Fuentes. */
    var catalogAuto: Boolean
        // Sin elección guardada se respeta la de antes: con «Mejores en general» oculta el catálogo no se recorría solo.
        get() = synchronized(this) {
            if (prefs.contains(KEY_CATALOG_AUTO)) prefs.getBoolean(KEY_CATALOG_AUTO, true)
            else (app.kaizen.tv.ui.MainSections.BESTGLOBAL !in hiddenRows).also { prefs.edit { putBoolean(KEY_CATALOG_AUTO, it) } }
        }
        set(value) = synchronized(this) { prefs.edit { putBoolean(KEY_CATALOG_AUTO, value) } }

    var resumeAfterSeconds: Int by intPref("kaizen_resume_after_seconds", 30)
    var seekCapSeconds: Int by intPref("kaizen_seek_cap_seconds", 90)
    /** Nombre del DNS de relevo (ver [FallbackDns.Provider]); de fábrica Quad9. */
    var dnsProvider: String
        get() = prefs.getString("kaizen_dns_provider", null) ?: "Quad9 (9.9.9.9)"
        set(value) = prefs.edit { putString("kaizen_dns_provider", value) }
    /** Consultar hayahora.futbol al abrir «Fuentes» para avisar de los bloqueos de LaLiga (envía a un tercero la petición, no datos tuyos); el modo WARP «solo con bloqueo» la necesita aunque esté apagado. */
    var laLigaWarning: Boolean by booleanPref("kaizen_laliga_warning", true)

    // --- Preparado para más adelante (sin efecto todavía). Las entradas de Configuración que los usan siguen ocultas (SettingsFlags).

    /** Modo parental («Contenido sensible»): carátulas con blur de las series sensibles. Apagado de fábrica. */
    var sensitiveBlur: Boolean by booleanPref("kaizen_sensitive_blur", false)

    /** Géneros de AniList (en minúsculas) que hacen sensible una serie. */
    var sensitiveGenres: Set<String>
        get() = prefs.getStringSet(KEY_SENSITIVE_GENRES, null)?.toSet() ?: DEFAULT_SENSITIVE_GENRES
        set(value) = prefs.edit { putStringSet(KEY_SENSITIVE_GENRES, value) }

    /** Series marcadas a mano como sensibles, como «fuente|slug». */
    var sensitiveMarked: Set<String>
        get() = prefs.getStringSet(KEY_SENSITIVE_MARKED, emptySet()).orEmpty().toSet()
        set(value) = prefs.edit { putStringSet(KEY_SENSITIVE_MARKED, value) }

    /** Cómo avisar de versiones nuevas: `off` (no mirar), `notice` (aviso discreto) o `dialog` (diálogo). Apagado de fábrica: el repositorio del proyecto es privado. */
    var updateMode: String
        get() = prefs.getString("kaizen_update_mode", null) ?: "off"
        set(value) = prefs.edit { putString("kaizen_update_mode", value) }
    /** Cuándo se usa el túnel WARP solo para Kaizen (ver [app.kaizen.commons.warp.WarpMode]); apagado de fábrica. */
    var warpMode: String
        get() = prefs.getString("kaizen_warp_mode", null) ?: app.kaizen.commons.warp.WarpMode.OFF.label
        set(value) = prefs.edit { putString("kaizen_warp_mode", value) }
    var patience: Float by floatPref("kaizen_patience", 1f)
    var coverDays: Int by intPref("kaizen_cover_days", 3650)
    var imageMemoryPercent: Int by intPref("kaizen_image_memory_percent", 20)
    var backupDays: Int by intPref("kaizen_backup_days", 7)
    var backupFiles: Int by intPref("kaizen_backup_files", 30)
    var logKb: Int by intPref("kaizen_log_kb", 512)
    var statsDays: Int by intPref("kaizen_stats_days", 90)
    var chartDays: Int by intPref("kaizen_chart_days", 30)
    var fillBatch: Int by intPref("kaizen_fill_batch", 20)
    var fillDaily: Int by intPref("kaizen_fill_daily", 300)

    /** Reanudar donde se dejó sin preguntar. */
    var resumeDirectly: Boolean by booleanPref("kaizen_resume_directly", true)

    /** Calidad máxima siempre en lugar de adaptativa según la red (HLS). */
    var maxQuality: Boolean by booleanPref("kaizen_max_quality", false)

    /** Orden de los botones del reproductor (nombres de PlayerButton separados por comas); vacío = el de fábrica. */
    var playerButtonOrder: String
        get() = prefs.getString(KEY_PLAYER_BUTTON_ORDER, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_PLAYER_BUTTON_ORDER, value) }

    /** Orden de las secciones del menú lateral (ids separados por comas); vacío = el de fábrica. */
    var menuOrder: String
        get() = prefs.getString(KEY_MENU_ORDER, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_MENU_ORDER, value) }

    /** Servidor preferido general; se usa si el anime no tiene uno recordado. */
    var preferredServer: String? by stringPref("kaizen_preferred_server", null)

    /** Estados de las series en JSON (ver [app.kaizen.domain.SeriesStatuses]). */
    var seriesStatuses: String? by stringPref("kaizen_series_statuses", null)

    /** La migración única de estados del historial ya se hizo (ver [app.kaizen.domain.StatusMigration]). */
    var statusesMigrated: Boolean by booleanPref("kaizen_statuses_migrated_2", false)

    /** Cuántas veces se ha intentado la migración de estados (se da por hecha tras unos cuantos intentos). */
    var statusesAttempts: Int by intPref("kaizen_statuses_attempts", 0)

    /** Botones del reproductor que el usuario ha ocultado (nombres de [app.kaizen.tv.exoplayer.PlayerButton]); los nuevos salen por defecto. */
    var hiddenPlayerButtons: Set<String>
        get() = prefs.getStringSet(KEY_HIDDEN_PLAYER_BUTTONS, emptySet()).orEmpty()
        set(value) = prefs.edit { putStringSet(KEY_HIDDEN_PLAYER_BUTTONS, value) }

    /** Títulos normalizados de las series que el usuario no quiere que se le recomienden (ver [app.kaizen.domain.NotRecommended]). */
    var notRecommended: Set<String>
        get() = prefs.getStringSet(KEY_NOT_RECOMMENDED, emptySet()).orEmpty()
        set(value) = prefs.edit { putStringSet(KEY_NOT_RECOMMENDED, value) }

    var hiddenRows: Set<Int>
        get() = prefs.getStringSet(KEY_HIDDEN_ROWS, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()
        set(value) = prefs.edit { putStringSet(KEY_HIDDEN_ROWS, value.map { it.toString() }.toSet()) }

    /**
     * Hasta el 2026-10-07 los ids de fila eran otros (6 = «Secciones»; 0 y 8, filas que ahora
     * van dentro de Inicio). Se quitan una vez para que no oculten secciones nuevas.
     */
    fun migrateHiddenRows(obsoleteIds: Set<Int>) {
        if (prefs.getInt(HIDDEN_ROWS_VERSION, 1) >= 2) return
        hiddenRows = hiddenRows - obsoleteIds
        prefs.edit { putInt(HIDDEN_ROWS_VERSION, 2) }
    }

    /**
     * Vuelve a los valores de fábrica: borra todos los ajustes Kaizen y el reproductor interno/externo. No toca
     * estados internos (versión de la migración de filas, pruebas de bloqueo), ni favoritos, historial o progreso.
     */
    fun resetToDefaults() {
        prefs.edit {
            prefs.all.keys.filter { it.startsWith("kaizen_") && it !in KEPT_ON_RESET }.forEach { remove(it) }
        }
        useInternalPlayer = true
    }

    private val KEPT_ON_RESET = setOf(HIDDEN_ROWS_VERSION, "kaizen_ua_mode", "kaizen_ua_random", "kaizen_sync_device_id", "kaizen_series_statuses", "kaizen_statuses_migrated", "kaizen_statuses_migrated_2", "kaizen_statuses_attempts", "kaizen_backup_accepted_at")

    /** Tamaño de toda la interfaz (multiplicador; ver [UiScale]). */
    var uiScale: Float by floatPref("kaizen_ui_scale", 1f)

    /** Adaptar la interfaz a la densidad y la resolución de la pantalla (ver [UiScale.autoFactor]). */
    var autoFit: Boolean by booleanPref("kaizen_auto_fit", true)

    /** Tamaño que se aplica: un valor guardado que ya no es una opción (×1,5 o ×2 de versiones anteriores) cuenta como ×1. */
    val effectiveUiScale: Float get() = uiScale.takeIf { it in UI_SCALE_OPTIONS } ?: 1f

    /** Tamaño anterior mientras el nuevo está a prueba; 0 = nada a prueba. */
    var uiScalePrevious: Float by floatPref("kaizen_ui_scale_previous", 0f)

    /** Hora (ms) hasta la que hay que confirmar el tamaño nuevo; 0 = nada que confirmar. */
    var uiScaleConfirmBy: Long by longPref("kaizen_ui_scale_confirm_by", 0L)

    /** Pone [scale] a prueba: si no se confirma antes de [UI_SCALE_CONFIRM_MS], se vuelve al tamaño anterior. */
    fun trialUiScale(scale: Float) {
        if (uiScalePrevious == 0f) uiScalePrevious = effectiveUiScale
        uiScale = scale
        uiScaleConfirmBy = System.currentTimeMillis() + UI_SCALE_CONFIRM_MS
    }

    fun confirmUiScale() {
        uiScalePrevious = 0f
        uiScaleConfirmBy = 0L
    }

    fun revertUiScale() {
        if (uiScalePrevious != 0f) uiScale = uiScalePrevious
        confirmUiScale()
    }

    /** Plazo pasado sin confirmar (la app se cerró, o no se podía leer la pantalla): vuelve al tamaño anterior. */
    fun revertExpiredUiScale() {
        if (uiScaleConfirmBy != 0L && System.currentTimeMillis() > uiScaleConfirmBy) revertUiScale()
    }

    /** Separación entre tarjetas (multiplicador). */
    var cardSpacing: Float by floatPref("kaizen_card_spacing", 1f)

    /** Tamaño de las tarjetas (multiplicador de su ancho; las rejillas se recalculan con él). */
    var cardScale: Float by floatPref("kaizen_card_scale", 1f)

    /** Velocidad de las animaciones de la interfaz: 0 desactivadas, 1 rápidas, 2 normales, 3 lentas. */
    var animationLevel: Int by intPref("kaizen_animation_level", 2)

    val ANIMATION_OPTIONS = listOf("Desactivadas", "Rápidas", "Normales", "Lentas")

    /** Multiplicador de la duración de las animaciones según [animationLevel] (0 = instantáneo). */
    val animationScale: Float get() = when (animationLevel) { 0 -> 0f; 1 -> 0.5f; 3 -> 2f; else -> 1f }

    /** Qué se anota en el registro de depuración (ver [DebugLog.LEVELS]): 0 nada, 1 errores, 2 información, 3 detalle. */
    var debugLevel: Int by intPref("kaizen_debug_level", DebugLog.INFO)

    /** Fecha de la copia que el usuario aceptó como referencia al dar por buenos sus datos (0 = ninguna): el aviso de «faltan datos» y restaurar solo miran las copias desde ella. */
    var acceptedBackupAt: Long by longPref("kaizen_backup_accepted_at", 0L)

    /** Ampliar la tarjeta al enfocarla (zoom). */
    var focusZoom: Boolean by booleanPref("kaizen_focus_zoom", false)

    /** Panel de datos de AniList sobre la tarjeta de una serie cuando el foco se queda quieto un momento. */
    var focusPanel: Boolean by booleanPref("kaizen_focus_panel", true)

    /** Segundos que tarda en salir ese panel (por defecto 2). */
    var focusPanelDelay: Int by intPref("kaizen_focus_panel_delay", 2)

    /** Hora y fecha en la barra superior. */
    var topClock: Boolean by booleanPref("kaizen_top_clock", true)

    var clock24h: Boolean by booleanPref("kaizen_clock_24h", true)

    /** Sección del menú lateral con la que se abre la app (id de MainSections). */
    var startSection: Int by intPref("kaizen_start_section", -1)

    /** Fuentes de anime activas (ids de AnimeSource); por defecto solo animeav1. */
    var sourceIds: Set<String>
        get() = prefs.getStringSet(KEY_SOURCES, null)?.toSet()?.takeIf { it.isNotEmpty() } ?: setOf("animegratis", "animeav1")
        set(value) {
            prefs.edit { putStringSet(KEY_SOURCES, value.ifEmpty { setOf("animegratis", "animeav1") }) }
            sourceIdsFlow.value = sourceIds
        }

    /** Fuentes activas como flujo: las pantallas que ya están abiertas se rehacen al cambiarlas. */
    val sourceIdsFlow: kotlinx.coroutines.flow.MutableStateFlow<Set<String>> by lazy { kotlinx.coroutines.flow.MutableStateFlow(sourceIds) }

    private const val KEY_SOURCES = "kaizen_source_ids"
    private const val KEY_NOT_RECOMMENDED = "kaizen_not_recommended"
    private const val KEY_CATALOG_AUTO = "kaizen_catalog_auto"

    /** Cuántos capítulos recientes se guardan (animeav1 solo publica los 20 últimos). */
    var recentsCount: Int by intPref("kaizen_recents_count", 60)

    /** Cada cuántos minutos se renuevan los recientes (trabajo periódico y caducidad de lo guardado). */
    var recentsEveryMinutes: Int by intPref("kaizen_recents_every_min", 60)

    var fichaAiringMinutes: Int by intPref("kaizen_ficha_airing_min", 60)
    var fichaSettledDays: Int by intPref("kaizen_ficha_settled_days", 30)
    var catalogCheckDays: Int by intPref("kaizen_catalog_check_days", 14)
    var catalogWalkDays: Int by intPref("kaizen_catalog_walk_days", 30)
    var scheduleHours: Int by intPref("kaizen_schedule_hours", 24)
    var episodesHours: Int by intPref("kaizen_episodes_hours", 6)
    var ratingDays: Int by intPref("kaizen_rating_days", 15)
    var pagesDays: Int by intPref("kaizen_pages_days", 7)
    var spreadPercent: Int by intPref("kaizen_spread_percent", 20)
    var coverQuality: Int by intPref("kaizen_cover_quality", 0)

    /** Fecha (de generación) del último catálogo de `kaizen-sources` aplicado: no se aplica uno más antiguo ni igual. */
    var catalogSeedAt: Long by longPref("kaizen_catalog_seed_at", 0L)

    /** Descargas del repositorio de fuentes (fuentes por fichero, enlaces con AniList y catálogo). Encendidas por defecto desde que el repositorio del proyecto es público (ver `RepoConfig`). */
    var repoDownloads: Boolean by booleanPref("kaizen_repo_downloads", true)

    /** URL base del repositorio (null = la del proyecto) y su clave pública (null = la del proyecto): son de confianza, ver `RepoConfig`. */
    var repoUrl: String? by stringPref("kaizen_repo_url", null)
    var repoKey: String? by stringPref("kaizen_repo_key", null)

    /** Sincronización de estados entre dispositivos por WebDAV (ver `DeviceSync`): destino, usuario, contraseña, id de este dispositivo y cuándo fue la última. */
    var syncUrl: String? by stringPref("kaizen_sync_url", null)
    var syncUser: String? by stringPref("kaizen_sync_user", null)
    /**
     * La clave del servidor: se guarda **cifrada** con el Keystore de Android (ver [SecretStore]) y nunca en claro. Una clave en claro de una versión anterior se cifra la primera
     * vez que se lee. Si no se puede cifrar, no se guarda.
     */
    var syncPassword: String?
        get() {
            prefs.getString("kaizen_sync_password_enc", null)?.let { return SecretStore.decrypt(it) }
            val legacy = prefs.getString("kaizen_sync_password", null) ?: return null
            if (legacy.isEmpty()) { prefs.edit { remove("kaizen_sync_password") }; return null }
            // La antigua en claro solo se borra si el cifrado funcionó: con el Keystore roto se conserva (y se vuelve a intentar) en vez de perderla.
            SecretStore.encrypt(legacy)?.let { sealed -> prefs.edit { putString("kaizen_sync_password_enc", sealed); remove("kaizen_sync_password") } }
            return legacy
        }
        set(value) {
            val sealed = value?.takeIf { it.isNotEmpty() }?.let { SecretStore.encrypt(it) ?: return }
            prefs.edit { remove("kaizen_sync_password"); if (sealed == null) remove("kaizen_sync_password_enc") else putString("kaizen_sync_password_enc", sealed) }
        }
    /** Carpeta de red SMB (un NAS) para copiar las copias de seguridad y restaurarlas en otro equipo (ver `SmbBackup`): dirección, usuario, clave (cifrada, como la del servidor WebDAV) y si cada copia nueva se sube sola. */
    var smbUrl: String? by stringPref("kaizen_smb_url", null)
    var smbUser: String? by stringPref("kaizen_smb_user", null)
    var smbPassword: String?
        get() = prefs.getString("kaizen_smb_password_enc", null)?.let { SecretStore.decrypt(it) }
        set(value) {
            val sealed = value?.takeIf { it.isNotEmpty() }?.let { SecretStore.encrypt(it) ?: return }
            prefs.edit { if (sealed == null) remove("kaizen_smb_password_enc") else putString("kaizen_smb_password_enc", sealed) }
        }
    var smbAuto: Boolean by booleanPref("kaizen_smb_auto", false)
    var smbLastAt: Long by longPref("kaizen_smb_last_at", 0L)
    var syncDeviceId: String? by stringPref("kaizen_sync_device_id", null)
    var syncLastAt: Long by longPref("kaizen_sync_last_at", 0L)

    /** Sincronizar también con la copia diaria. */
    var syncAuto: Boolean by booleanPref("kaizen_sync_auto", false)

    /** Fuentes del repositorio que se descargan; vacío = todas. */
    var repoSources: Set<String>
        get() = prefs.getStringSet("kaizen_repo_sources", null)?.toSet().orEmpty()
        set(value) = prefs.edit { putStringSet("kaizen_repo_sources", value) }

    /** Cuándo se miró por última vez el catálogo de `kaizen-sources` (con catálogo propio, una vez al día). */
    var catalogSeedCheckedAt: Long by longPref("kaizen_catalog_seed_checked_at", 0L)

    /** Salto de los botones retroceder/avanzar del reproductor, en segundos. */
    var seekSeconds: Int by intPref("kaizen_seek_seconds", 30)

    /** Segundos sin tocar el mando hasta que se ocultan los controles del reproductor. */
    var controlsHideSeconds: Int by intPref("kaizen_controls_hide_seconds", 3)

    /** Segundos de vídeo que se descargan por delante (ExoPlayer: 50). */
    var bufferSeconds: Int by intPref("kaizen_buffer_seconds", 50)

    /** Hora actual en el reproductor. */
    var playerClock: Boolean by booleanPref("kaizen_player_clock", true)

    /** Decodificar siempre el vídeo con FFmpeg (si el hardware da problemas). */
    var forceFfmpeg: Boolean by booleanPref("kaizen_force_ffmpeg", false)

    /**
     * Claves de ajustes Kaizen que cambian (para aplicarlos en caliente). El listener vive
     * mientras se recoge el flujo: SharedPreferences solo guarda referencias débiles.
     */
    val changes: Flow<String> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null && key.startsWith("kaizen_")) trySend(key)
        }
        val preferences = prefs
        preferences.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    const val KEY_HIDDEN_ROWS = "kaizen_hidden_rows"
    private const val KEY_SENSITIVE_GENRES = "kaizen_sensitive_genres"
    private const val KEY_SENSITIVE_MARKED = "kaizen_sensitive_marked"
    val DEFAULT_SENSITIVE_GENRES = setOf("ecchi", "hentai")
    private const val KEY_HIDDEN_PLAYER_BUTTONS = "kaizen_hidden_player_buttons"
    private const val KEY_PLAYER_BUTTON_ORDER = "kaizen_player_button_order"
    private const val KEY_MENU_ORDER = "kaizen_menu_order"

    fun formatFactor(value: Float) = "×${trimDecimals(value)}"

    fun formatSpeed(speed: Float) = "${trimDecimals(speed)}x"

    /** 1.50 → «1.5», 2.00 → «2». */
    private fun trimDecimals(value: Float) = "%.2f".format(value).trimEnd('0').trimEnd('.', ',')

    private fun booleanPref(key: String, default: Boolean) = Pref(key, default, SharedPreferences::getBoolean, SharedPreferences.Editor::putBoolean)
    private fun intPref(key: String, default: Int) = Pref(key, default, SharedPreferences::getInt, SharedPreferences.Editor::putInt)
    private fun floatPref(key: String, default: Float) = Pref(key, default, SharedPreferences::getFloat, SharedPreferences.Editor::putFloat)
    private fun longPref(key: String, default: Long) = Pref(key, default, SharedPreferences::getLong, SharedPreferences.Editor::putLong)
    private fun stringPref(key: String, default: String?) = Pref(key, default, SharedPreferences::getString, SharedPreferences.Editor::putString)

    /** Preferencia con clave y valor por defecto fijos, en una línea por ajuste. */
    private class Pref<T>(
        private val key: String,
        private val default: T,
        private val read: SharedPreferences.(String, T) -> T,
        private val write: SharedPreferences.Editor.(String, T) -> SharedPreferences.Editor
    ) : ReadWriteProperty<Any?, T> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = prefs.read(key, default)

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = prefs.edit { write(key, value) }
    }
}
