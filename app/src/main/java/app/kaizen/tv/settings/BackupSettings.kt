package app.kaizen.tv.settings

import android.content.Context
import app.kaizen.R
import app.kaizen.commons.AppScope
import app.kaizen.commons.CacheCleaner
import app.kaizen.commons.KaizenBackup
import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.SettingsBackup
import app.kaizen.commons.appString
import app.kaizen.commons.findActivity
import app.kaizen.commons.logError
import app.kaizen.commons.runCatchingCoop
import app.kaizen.commons.sync.SyncManager
import app.kaizen.retrofit.Repository
import app.kaizen.tv.ui.KaizenToast
import kotlinx.coroutines.launch
import java.text.DateFormat

/**
 * Configuración › Copia de seguridad. Arriba, «Crear copia ahora» (lo más usado); debajo, cuatro submenús: **Local** (restaurar y copia automática), **Remota**
 * (sincronizar con otros dispositivos, WebDAV), **Mis ajustes** (guardar los de ahora como predeterminados y volver a ellos) y **Restablecer y vaciar**.
 * El repositorio de descargas no es una copia: está en Acerca de.
 */
internal class BackupSettingsItems(private val context: Context) {
    private val icon = R.drawable.settings_ic_backup

    /** La copia local y los restablecimientos son la misma tarea (guardar o deshacer): una sola categoría. */
    fun items(): List<SettingItem> = listOf(
        createBackupItem(),
        submenu("Local", icon, { KaizenBackup.warning ?: "Restaurar y copia automática" }, ::localItems),
        submenu("Remota", icon, ::remoteSummary, ::remoteItems),
        submenu("Mis ajustes", icon, { SettingsBackup.defaultsSavedAt()?.let { "Guardados el ${shortDate(it)}" } ?: "Aún sin guardar" }, ::myDefaultsItems),
        submenu("Restablecer y vaciar", icon, { "Ajustes de fábrica, caché" }, ::resetItems)
    )

    private fun shortDate(millis: Long) = DateFormat.getDateInstance(DateFormat.SHORT).format(millis)

    private fun remoteSummary(): String =
        if (!SyncManager.configured) "Sin configurar (WebDAV)"
        else KaizenSettings.syncLastAt.takeIf { it > 0 }?.let { "Última: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(it)}" } ?: "Nunca"

    /** Local: restaurar (estados y ajustes) y cuántas copias automáticas se guardan. */
    private fun localItems(): List<SettingItem> = restoreItems() + backupOptionItems()

    /**
     * Sincronizar varios dispositivos: cada uno sube solo su copia de estados a un servidor WebDAV y funde las de los demás, solo añadiendo.
     */
    private fun remoteItems(): List<SettingItem> {
        val urlProblem = { v: String -> if (v.isBlank() || app.kaizen.source.spec.RepoConfig.normalizeUrl(v) != null) null else "Debe ser una dirección https, sin usuario ni ? ni #" }
        return listOf(
            ActionItem("Sincronizar ahora", {
                val last = KaizenSettings.syncLastAt
                if (!SyncManager.configured) "Primero escribe el servidor WebDAV"
                else if (last == 0L) "Nunca" else "Última: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(last)}"
            }) {
                if (!SyncManager.configured) return@ActionItem KaizenToast.toast("Primero escribe el servidor WebDAV")
                KaizenToast.toast("Sincronizando…")
                AppScope.launch {
                    runCatchingCoop { SyncManager.now() }
                        .onSuccess { KaizenToast.toastLong("Sincronizado: $it") }
                        .onFailure { logError(it); KaizenToast.toastLong("No se pudo sincronizar: ${it.message ?: it.javaClass.simpleName}") }
                }
            },
            TextItem("Servidor WebDAV", { KaizenSettings.syncUrl?.takeIf { it.isNotBlank() } ?: "Sin configurar (Nextcloud, un NAS…)" }, { KaizenSettings.syncUrl.orEmpty() },
                { SyncManager.setUrl(it) }, urlProblem, "https://servidor/remote.php/dav/files/usuario/Kaizen/",
                "Carpeta WebDAV (https). Cada dispositivo guarda en ella su propia copia y lee las de los demás; nunca dos escriben el mismo fichero."),
            TextItem("Usuario del servidor", { KaizenSettings.syncUser?.takeIf { it.isNotBlank() } ?: "Sin usuario" }, { KaizenSettings.syncUser.orEmpty() }, { KaizenSettings.syncUser = it.ifBlank { null } }),
            TextItem("Contraseña del servidor", { if (KaizenSettings.syncPassword.isNullOrEmpty()) "Sin contraseña" else "Guardada (no se muestra)" }, { "" },
                { if (it.isNotEmpty()) { KaizenSettings.syncPassword = it; if (KaizenSettings.syncPassword != it) KaizenToast.toastLong("No se pudo guardar la contraseña: el almacén de claves de la tele falla") } }, hint = "Escribe una nueva para cambiarla", masked = true,
                help = "Se guarda cifrada en esta tele (Keystore), no sale en copias ni en el registro y solo se envía a ese servidor. Usa una contraseña de aplicación si tu servidor la ofrece."),
            CheckItem("Sincronizar con la copia diaria", "Una vez al día, con la copia automática", KaizenSettings::syncAuto),
            ActionItem("Este dispositivo", { "Id ${SyncManager.deviceId} (así se llama su fichero en el servidor)" }) { }
        )
    }

    /** Mis ajustes: lo que se guarda aquí es una foto de los ajustes de ahora a la que se puede volver; no toca favoritos, historial ni estados. */
    private fun myDefaultsItems(): List<SettingItem> = listOf(
        ConfirmItem("Guardar los ajustes actuales como predeterminados",
            SettingsBackup.defaultsSavedAt()?.let { "Sustituye a los guardados el ${shortDate(it)}" } ?: "Aún no has guardado ninguno",
            warning = "Se guarda una foto de los ajustes de ahora (reproductor, pantalla, fuentes activas, orden de menús…). No incluye favoritos, historial, estados ni contraseñas, y puedes volver a ella cuando quieras.",
            confirmLabel = "Guardar") {
            AppScope.launch {
                runCatchingCoop { SettingsBackup.saveDefaults() }
                    .onSuccess { KaizenToast.toastLong("Ajustes guardados como predeterminados") }
                    .onFailure { logError(it); KaizenToast.toastLong("No se pudieron guardar los ajustes") }
            }
        },
        ConfirmItem("Volver a mis ajustes predeterminados",
            SettingsBackup.defaultsSavedAt()?.let { "Los guardados el ${shortDate(it)}" } ?: "Primero guarda unos",
            warning = "Vuelve a poner los ajustes que guardaste como predeterminados. Lo que hayas cambiado desde entonces se pierde. No toca favoritos, historial ni estados.",
            confirmLabel = "Volver") {
            if (SettingsBackup.defaultsSavedAt() == null) return@ConfirmItem KaizenToast.toast("Aún no has guardado ajustes predeterminados")
            AppScope.launch {
                runCatchingCoop { SettingsBackup.restoreDefaults() }
                    .onSuccess { report -> KaizenToast.toastLong(if (report == null) "Los ajustes guardados no son válidos" else "Ajustes predeterminados puestos: $report") }
                    .onFailure { logError(it); KaizenToast.toastLong("No se pudieron poner los ajustes") }
            }
        }
    )

    /** La copia de ajustes elegida para restaurar (null = la más reciente válida). Solo vive mientras la app está abierta. */
    private var pickedSettingsBackup: String? = null

    private fun settingsBackupPicker(): SettingItem {
        val files = SettingsBackup.candidates()
        val options = listOf("La más reciente válida") + files.map { it.name.removePrefix(SettingsBackup.PREFIX).removeSuffix(".json") }
        return ChoiceItem("Copia de ajustes a restaurar", options,
            { files.indexOfFirst { it.name == pickedSettingsBackup } + 1 },
            { position -> pickedSettingsBackup = files.getOrNull(position - 1)?.name })
    }

    /** Crea la copia de estados y, con ella, la de ajustes (van juntas): una sola acción. */
    private fun createBackupItem(): SettingItem =
        ActionItem("Crear copia ahora", { KaizenBackup.latest()?.let { "Última: ${it.name}" } ?: "Favoritos, historial, estados y ajustes" }) {
            AppScope.launch {
                runCatchingCoop { KaizenBackup.export() }
                    .onSuccess { (file, summary) -> KaizenToast.toastLong("Copia creada: ${file.name} ($summary)") }
                    .onFailure { logError(it); KaizenToast.toastLong("No se pudo crear la copia") }
            }
        }

    /** Restaurar: los estados (la copia más completa) y los ajustes (la que se elija); solo añaden o devuelven lo guardado, no borran. */
    private fun restoreItems() = listOfNotNull<SettingItem>(
        // La más completa y no la última: tras una pérdida de datos la última sale vacía. Restaurar solo añade, así que repetirlo no daña.
        ActionItem("Restaurar estados (la copia más completa)", { KaizenBackup.warning ?: KaizenBackup.best()?.name ?: appString(R.string.ui_no_hay_copias) }) {
            if (KaizenBackup.best() == null) return@ActionItem KaizenToast.toast("No hay copias en ${KaizenBackup.directory.path}")
            AppScope.launch {
                runCatchingCoop { KaizenBackup.restoreBest() }
                    .onSuccess { summary ->
                        KaizenToast.toastLong(if (summary == null) appString(R.string.ui_la_copia_no_es_valida) else "Añadido: $summary")
                        // Que el aviso de «faltan datos» se recalcule con lo restaurado.
                        runCatchingCoop { KaizenBackup.checkData() }.onFailure(::logError)
                    }
                    .onFailure { logError(it); KaizenToast.toastLong("No se pudo restaurar la copia") }
            }
        },
        // Elegir cuál restaurar: «La más reciente válida» salta las dañadas; una concreta sirve cuando la última tiene los ajustes mal.
        settingsBackupPicker(),
        ConfirmItem("Restaurar ajustes", "Copia elegida arriba (por defecto, la más reciente que sea válida)",
            warning = "Vuelve a poner los ajustes de la copia elegida (fuentes activas, orden de menús, reproductor…). No toca favoritos, historial ni estados. " +
                "Lo que no sea válido se rechaza y se avisa.",
            confirmLabel = "Restaurar") {
            if (SettingsBackup.candidates().isEmpty()) return@ConfirmItem KaizenToast.toast("No hay copias de ajustes en ${SettingsBackup.directory.path}")
            val picked = pickedSettingsBackup?.let { name -> SettingsBackup.candidates().firstOrNull { it.name == name } }
            AppScope.launch {
                runCatchingCoop { if (picked != null) picked to SettingsBackup.import(picked) else SettingsBackup.restoreNewestValid()?.let { it.first to it.second } }
                    .onSuccess { used ->
                        val report = used?.second
                        KaizenToast.toastLong(if (report == null) "La copia de ajustes no es válida: elige otra" else "Ajustes restaurados de ${used.first.name}: $report")
                    }
                    .onFailure { logError(it); KaizenToast.toastLong("No se pudieron restaurar los ajustes") }
            }
        },
        // Solo con el aviso de «faltan datos» activo: para quien los quitó a propósito.
        ConfirmItem("Aceptar los datos actuales", "Deja de avisar de que faltan datos",
            warning = "Se guarda una copia nueva y, a partir de ella, el aviso y «Restaurar» solo miran las copias posteriores. " +
                "La copia más completa de antes se conserva en su carpeta, pero ya no se restaura desde aquí. Las demás copias siguen caducando según «Días que se guardan las copias».",
            confirmLabel = "Aceptar") {
            AppScope.launch {
                runCatchingCoop { KaizenBackup.acceptCurrent() }
                    .onSuccess { KaizenToast.toastLong("Datos actuales aceptados") }
                    .onFailure { logError(it); KaizenToast.toastLong("No se pudo guardar la copia") }
            }
        }.takeIf { KaizenBackup.warning != null }
    )

    /** Cuántas copias se guardan y dónde. */
    private fun backupOptionItems() = listOf(
        ActionItem(appString(R.string.ui_copia_automatica), { "Una al día; se borran las de más de ${KaizenSettings.backupDays} días" }) { },
        choiceItem("Días que se guardan las copias (bajarlo borra las más viejas)", KaizenSettings.BACKUP_DAYS_OPTIONS, KaizenSettings::backupDays) { "$it días" },
        choiceItem("Máximo de copias (bajarlo borra las más viejas)", KaizenSettings.BACKUP_FILES_OPTIONS, KaizenSettings::backupFiles) { "$it" },
        ActionItem(appString(R.string.ui_carpeta_de_las_copias), { KaizenBackup.directory.path }) { }
    )

    /** Restablecer ajustes y vaciar la caché; con confirmación, porque no se puede deshacer. */
    fun resetItems() = listOf(
        ConfirmItem(appString(R.string.ui_restablecer_ajustes), appString(R.string.ui_vuelve_a_la_configuracion_de_fabrica),
            warning = "Se pierden todos tus ajustes (reproductor, interfaz, secciones, fuentes, servidor preferido y botones). " +
                "Favoritos, historial y progreso se conservan.",
            confirmLabel = appString(R.string.ui_restablecer_ajustes)) { context ->
            KaizenSettings.resetToDefaults()
            Repository.reloadRecents()
            KaizenToast.toastLong(appString(R.string.ui_ajustes_restablecidos))
            // El tamaño de la interfaz se aplica al crear la pantalla.
            context.findActivity()?.recreate()
        },
        ConfirmItem(appString(R.string.ui_vaciar_la_cache), appString(R.string.ui_respuestas_guardadas_catalogo_interno_y_portadas),
            warning = "Se borran las respuestas guardadas, el catálogo interno y las portadas. Favoritos, historial y progreso se conservan. " +
                "El catálogo se rehace al arrancar la app o desde Biblioteca › Fuentes, con cientos de peticiones, y puede tardar varios minutos; " +
                "mientras tanto las pantallas cargan más despacio.",
            confirmLabel = appString(R.string.ui_vaciar_la_cache)) { context ->
            AppScope.launch {
                runCatchingCoop { CacheCleaner.clearAll(context.applicationContext) }
                    .onSuccess { KaizenToast.toastLong(appString(R.string.ui_cache_vaciada)) }
                    .onFailure { logError(it); KaizenToast.toastLong("No se pudo vaciar la caché") }
            }
        },
        ActionItem(appString(R.string.ui_acceso_de_emergencia), { appString(R.string.ui_manten_pulsado_atras_en_el_menu_para_abrir_esto) }) { }
    )
}
