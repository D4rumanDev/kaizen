package app.kaizen.source.spec

import app.kaizen.commons.KaizenSettings
import java.net.URI
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * De dónde bajan la app sus **fuentes por fichero, los enlaces con AniList y el catálogo**: el repositorio del proyecto ([SpecSources.SOURCES_URL]) por defecto, o el de quien quiera
 * usar sus propias fuentes o su propio catálogo. Todo lo que baja va **firmado**: el índice de fuentes, los enlaces y el catálogo solo valen si los firma la clave pública
 * configurada aquí ([publicKey]). Quien use otro repositorio lo firma con su clave y pone su clave pública en la app (o, sin clave propia, solo sirve lo que firme el proyecto);
 * sin firma válida no se usa nada, aunque la URL sea suya.
 *
 * Reglas de la URL: `https`, con servidor, sin usuario ni contraseña, sin `?` ni `#`, acabada en `/`. La URL, la clave y el interruptor son de confianza: ni el control por ADB de
 * valores sueltos ni la restauración de una copia de ajustes los cambian desde un fichero (ver `SettingsBackup`).
 *
 * El interruptor ([enabled]) está **encendido por defecto** desde que el repositorio del proyecto es público (la app lo lee sin credenciales); un repositorio propio y privado no se puede leer sin credenciales, y entonces conviene apagarlo.
 */
object RepoConfig {
    private const val MAX_URL = 200
    private const val MAX_KEY = 400

    /** ¿Están encendidas las descargas del repositorio (fuentes, enlaces y catálogo)? */
    val enabled: Boolean get() = runCatching { KaizenSettings.repoDownloads }.getOrDefault(false)

    /** La URL base en uso: la elegida si es válida, y si no la del proyecto. */
    val baseUrl: String get() = runCatching { KaizenSettings.repoUrl }.getOrNull()?.let(::normalizeUrl) ?: SpecSources.SOURCES_URL

    /** La clave pública en uso: la elegida si es válida, y si no la del proyecto. */
    val publicKey: String get() = runCatching { KaizenSettings.repoKey }.getOrNull()?.takeIf(::validKey) ?: SpecSources.PUBLIC_KEY

    /** Si se usa un repositorio distinto del del proyecto. */
    val isCustom: Boolean get() = baseUrl != SpecSources.SOURCES_URL

    /** Fuentes elegidas para descargar; vacío = todas las del repositorio. */
    val chosenSources: Set<String> get() = runCatching { KaizenSettings.repoSources }.getOrDefault(emptySet())

    /** ¿Se baja la fuente [id]? */
    fun wants(id: String): Boolean = chosenSources.let { it.isEmpty() || id in it }

    /** La URL normalizada (con `/` al final) o null si no vale. */
    fun normalizeUrl(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_URL || trimmed.any { it.isISOControl() || it.isWhitespace() }) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    /** ¿Es [text] una clave pública ECDSA P-256 (X.509 DER en base64)? */
    fun validKey(text: String): Boolean {
        if (text.isBlank() || text.length > MAX_KEY) return false
        return runCatching {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getMimeDecoder().decode(text.trim()))) as ECPublicKey
            key.params.order.bitLength() == 256
        }.getOrDefault(false)
    }
}
