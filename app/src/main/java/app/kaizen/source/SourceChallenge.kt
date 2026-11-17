package app.kaizen.source

import androidx.core.content.edit
import app.kaizen.commons.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Webs que piden una verificación de seguridad (respuesta 403 con `"challenge":true`). La app no la resuelve sola: lo anota y
 * Recientes ofrece abrir la web para que la haga el usuario, que decide. Con el resultado (las cookies de esa sesión y el
 * User-Agent del navegador que la hizo) [SourceHttp] pide a esa web como lo haría el navegador. Si la web vuelve a pedirla, la
 * copia guardada se descarta y se vuelve a ofrecer.
 */
internal object SourceChallenge {
    private val CHALLENGE = Regex("\"challenge\"\\s*:\\s*true")

    private val prefs by lazy { app.kaizen.commons.KaizenStore.prefs("kaizen_source_challenge") }

    private val _pending = MutableStateFlow<Set<String>>(emptySet())

    /** Webs (hosts) que han pedido verificación y aún no se ha hecho. */
    val pending: StateFlow<Set<String>> = _pending

    /** Si la respuesta es la petición de verificación: 403 y el principio del cuerpo trae `"challenge":true`. */
    internal fun isChallenge(code: Int, head: String): Boolean = code == 403 && CHALLENGE.containsMatchIn(head)

    /** Anota que [host] pide verificación; lo que se guardó de una anterior ya no vale. */
    /** Páginas donde abrir la verificación de una web cuando la de inicio no sirve (la que dispara el desafío de seguridad). Sin entrada, la de inicio. */
    private val VERIFY_PATHS = mapOf("animegratis.net" to "/horario")

    /** La dirección que se abre para verificar [host]. */
    fun verifyUrl(host: String): String = "https://$host" + (VERIFY_PATHS[host] ?: "/")

    fun note(host: String) {
        // Si ya había una verificación guardada y la web la vuelve a pedir, la copia no le vale (otra IP, otra cookie, un token que no se guarda): queda en el registro.
        val had = runCatching { prefs.getString("cookie|$host", null) }.getOrNull()
        if (had != null) DebugLog.info("Verificación", "$host vuelve a pedir verificación pese a la guardada (cookies: ${cookieNames(had)})")
        else DebugLog.info("Verificación", "$host pide verificación")
        runCatching { prefs.edit { remove("cookie|$host"); remove("ua|$host") } }
        _pending.update { it + host }
    }

    /** Las cookies de la verificación hecha en [host], si hay. Sin Android (pruebas) no hay nada guardado. */
    fun cookieFor(host: String): String? = runCatching { prefs.getString("cookie|$host", null) }.getOrNull()

    /** El User-Agent del navegador que hizo la verificación de [host]: la web lo asocia a las cookies. */
    fun userAgentFor(host: String): String? = runCatching { prefs.getString("ua|$host", null) }.getOrNull()

    /** Guarda el resultado de la verificación que hizo el usuario en [host]; false si no es válido como cabecera (no se guarda nada). */
    fun save(host: String, cookie: String, userAgent: String): Boolean {
        if (!isHeaderValue(cookie) || !isHeaderValue(userAgent)) return false
        prefs.edit { putString("cookie|$host", cookie); putString("ua|$host", userAgent) }
        DebugLog.info("Verificación", "$host: verificación guardada (cookies: ${cookieNames(cookie)})")
        _pending.update { it - host }
        // Con la verificación hecha el servidor vuelve a responder: si HostGate lo había pausado por los 403 previos, se levanta.
        app.kaizen.commons.HostGate.resume(host)
        return true
    }

    /** Solo los nombres de las cookies, nunca sus valores: para el registro. */
    internal fun cookieNames(cookie: String): String = cookie.split(';').filter { '=' in it }.map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.joinToString(", ")

    /** Texto ASCII imprimible: lo único que admite una cabecera HTTP sin que la petición falle. */
    internal fun isHeaderValue(value: String): Boolean = value.isNotBlank() && value.all { it.code in 32..126 }
}
