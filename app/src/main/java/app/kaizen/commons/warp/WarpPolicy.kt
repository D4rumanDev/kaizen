package app.kaizen.commons.warp

import org.json.JSONObject

/** Cuándo se usa el túnel WARP (Configuración › Servidores y red › Túnel WARP). Apagado de fábrica. */
enum class WarpMode(val label: String) {
    OFF("Apagado"),
    ON_BLOCK("Solo con bloqueo de LaLiga"),
    ALWAYS("Siempre");

    companion object {
        fun of(label: String?): WarpMode = entries.firstOrNull { it.label == label } ?: OFF
    }
}

enum class WarpAction { START, STOP, NONE }

/** La decisión de subir o bajar el túnel, sin Android para probarla. */
object WarpPolicy {
    /** Con el bloqueo ya levantado el túnel se mantiene este tiempo: los partidos se alargan y el bloqueo va y viene. */
    const val KEEP_AFTER_CLEAR_MS = 15 * 60 * 1000L

    /**
     * [blocked]: alguna fuente activa resuelve ahora a una IP bloqueada. [up]: el túnel está subido. [clearSince]: desde cuándo no hay
     * bloqueo (0 = hay bloqueo o no se sabe), para no bajar el túnel a la primera lectura limpia.
     */
    fun decide(mode: WarpMode, blocked: Boolean, up: Boolean, clearSince: Long, now: Long): WarpAction = when (mode) {
        WarpMode.OFF -> if (up) WarpAction.STOP else WarpAction.NONE
        WarpMode.ALWAYS -> if (up) WarpAction.NONE else WarpAction.START
        WarpMode.ON_BLOCK -> when {
            blocked -> if (up) WarpAction.NONE else WarpAction.START
            up && clearSince > 0 && now - clearSince >= KEEP_AFTER_CLEAR_MS -> WarpAction.STOP
            else -> WarpAction.NONE
        }
    }
}

/** La cuenta gratuita de WARP que registra la app: lo que hace falta para armar el túnel WireGuard. */
data class WarpAccount(
    val privateKey: String,
    val publicKey: String,
    val peerPublicKey: String,
    val endpoint: String,
    val addressV4: String,
    val addressV6: String?
) {
    companion object {
        /** El cuerpo del registro: la clave pública y datos mínimos de un cliente de escritorio. */
        fun registrationBody(publicKey: String, nowIso: String): String = JSONObject()
            .put("install_id", "").put("fcm_token", "").put("tos", nowIso).put("key", publicKey)
            .put("type", "Android").put("model", "PC").put("locale", "en_US").toString()

        /**
         * Lee la respuesta del registro: el par (clave pública del servidor, dirección v4 y v6 del cliente, extremo). Devuelve null si falta algo
         * (la respuesta ha cambiado de forma o no era la esperada): mejor no armar un túnel que armarlo con datos a medias.
         */
        fun parse(privateKey: String, publicKey: String, response: String): WarpAccount? = runCatching {
            val config = JSONObject(response).getJSONObject("config")
            val peer = config.getJSONArray("peers").getJSONObject(0)
            val addresses = config.getJSONObject("interface").getJSONObject("addresses")
            val endpoint = peer.getJSONObject("endpoint")
            // Se usa la IP v4 del extremo (sin depender del DNS de la red) y el puerto fijo de WireGuard de WARP.
            val host = endpoint.optString("v4").substringBefore(':').ifBlank { endpoint.getString("host").substringBefore(':') }
            WarpAccount(privateKey, publicKey, peer.getString("public_key"), "$host:2408", addresses.getString("v4"), addresses.optString("v6").ifBlank { null })
        }.getOrNull()
    }
}
