package app.kaizen.commons

import com.google.gson.Gson

/**
 * Un único [Gson] con la configuración por defecto, para las rutas que se repiten (convertidores de Room, estados de serie, bajas, copia).
 * Un `Gson` es seguro entre hilos y guarda en caché sus adaptadores: crear uno por llamada los reconstruía por reflexión cada vez.
 * Quien necesite otra configuración (p. ej. `setPrettyPrinting` en [SettingsBackup]) sigue teniendo su propia instancia.
 */
internal object SharedGson {
    val gson: Gson = Gson()
}
