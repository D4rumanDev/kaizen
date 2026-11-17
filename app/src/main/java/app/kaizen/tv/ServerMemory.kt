package app.kaizen.tv

import androidx.core.content.edit
import app.kaizen.commons.KaizenSettings
import app.kaizen.videoservers.Server

/**
 * Kaizen: recuerda el último servidor que funcionó para cada anime y lo ofrece primero.
 * Fichero de preferencias propio (anime -> servidor).
 */
object ServerMemory {
    private const val LEGACY_PREFIX = "kaizen_server_"
    private const val LAST_KEY = "__kaizen_last__"

    private val prefs by lazy {
        app.kaizen.commons.KaizenStore.prefs("kaizen_servers").also(::migrateLegacy)
    }

    /** Las primeras versiones guardaban esto en las preferencias generales con prefijo. */
    private fun migrateLegacy(target: android.content.SharedPreferences) {
        val legacy = app.kaizen.commons.KaizenStore.settings
        val entries = legacy.all.filterKeys { it.startsWith(LEGACY_PREFIX) }
        if (entries.isEmpty()) return
        target.edit { entries.forEach { (k, v) -> if (v is String) putString(k.removePrefix(LEGACY_PREFIX), v) } }
        legacy.edit { entries.keys.forEach { remove(it) } }
    }

    /** Guarda el servidor del anime y como último usado en general. */
    fun remember(anime: String, serverName: String) = prefs.edit {
        putString(anime, serverName)
        putString(LAST_KEY, serverName)
    }

    /** El servidor falló al reproducir: deja de proponerlo automáticamente. */
    fun forget(anime: String?, serverName: String?) {
        serverName ?: return
        prefs.edit {
            if (anime != null && prefs.getString(anime, null) == serverName) remove(anime)
            if (prefs.getString(LAST_KEY, null) == serverName) remove(LAST_KEY)
        }
    }

    /** Orden: el recordado para el anime, el preferido de Ajustes y el último usado. */
    fun preferredName(anime: String): String? =
        prefs.getString(anime, null) ?: KaizenSettings.preferredServer ?: prefs.getString(LAST_KEY, null)

    /** Nombres de servidor que se han usado alguna vez (para elegir el preferido general). */
    fun knownServers(): List<String> = prefs.all.values.filterIsInstance<String>().distinct().sorted()

    fun preferredIndex(anime: String, servers: List<Server>): Int {
        val preferred = preferredName(anime) ?: return -1
        return servers.indexOfFirst { it.name == preferred }
    }

    fun sortPreferredFirst(anime: String, servers: MutableList<Server>) {
        val preferred = preferredName(anime) ?: return
        val index = servers.indexOfFirst { it.name == preferred }
        if (index > 0) servers.add(0, servers.removeAt(index))
    }
}
