package app.kaizen.tv.settings

import android.content.Context
import androidx.annotation.DrawableRes
import app.kaizen.R

/**
 * Las categorías de Configuración que abren un panel lateral. El orden de la rejilla lo decide [SettingsPage]; el contenido de cada una
 * está en su fichero: [PlayerSettingsItems], [ScreenSettingsItems], [NetworkSettingsItems], [DataSettingsItems], [BackupSettingsItems], [AboutSettingsItems] y [DebugSettingsItems].
 */
internal class SettingsCategories(
    context: Context,
    openPlayerButtons: () -> Unit = {},
    openMenuSections: () -> Unit = {},
    openDebugLog: () -> Unit = {}
) {
    private val player = PlayerSettingsItems(openPlayerButtons)
    private val screen = ScreenSettingsItems(context, openMenuSections)
    private val network = NetworkSettingsItems(context)
    private val data = DataSettingsItems()
    private val backup = BackupSettingsItems(context)
    private val about = AboutSettingsItems()
    private val debug = DebugSettingsItems(openDebugLog)

    /** Las categorías de Configuración, cada una por su nombre. */
    class Menu(
        val player: SettingsCategory, val screen: SettingsCategory, val network: SettingsCategory, val data: SettingsCategory,
        val backup: SettingsCategory, val about: SettingsCategory, val debug: SettingsCategory
    )

    fun menu() = Menu(
        player = category("Reproductor", R.drawable.settings_ic_player, player::items),
        screen = category("Pantalla", R.drawable.settings_ic_ui, screen::items),
        network = category("Fuentes y red", R.drawable.settings_ic_network, network::items),
        data = category("Datos y caché", R.drawable.ic_sync, data::items),
        backup = category("Copia de seguridad", R.drawable.settings_ic_backup, backup::items),
        about = category("Acerca de", R.drawable.settings_ic_about, about::items),
        debug = category("Depuración", R.drawable.ic_bug_kaizen, debug::items)
    )

    /** Restablecer y vaciar la caché sin pasar por la rejilla (acceso de emergencia: Atrás mantenido en el menú). */
    fun resetItems() = backup.resetItems()

    private fun category(title: String, @DrawableRes icon: Int, items: () -> List<SettingItem>) = SettingsCategory(title, icon, items)
}
