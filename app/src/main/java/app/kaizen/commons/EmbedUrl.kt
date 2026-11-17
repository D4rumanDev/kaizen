package app.kaizen.commons

import java.util.Base64

/** Texto en base64 estándar (ignora espacios y saltos de línea, el relleno es opcional), o null si no se puede decodificar. */
fun decodeBase64Text(value: String): String? = runCatchingCoop {
    String(Base64.getDecoder().decode(value.filterNot { it.isWhitespace() }), Charsets.UTF_8).trim()
}.getOrNull()

/**
 * URL del `src` de un iframe escrito en HTML y codificado en base64 («PGlmcmFtZSBzcmM9…» = `<iframe src="https://…">`); también vale si lo
 * codificado ya es la URL. Null si no hay una URL http(s) (una `//host/…` sin esquema se toma como https).
 */
fun decodeEmbedIframeUrl(value: String): String? {
    val text = decodeBase64Text(value) ?: return null
    if (text.startsWith("http://") || text.startsWith("https://")) return text
    val src = IFRAME_SRC.find(text)?.groupValues?.get(1)?.replace("&amp;", "&") ?: return null
    return (if (src.startsWith("//")) "https:$src" else src).takeIf { it.startsWith("http://") || it.startsWith("https://") }
}

private val IFRAME_SRC = Regex("""<iframe[^>]*?\ssrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

/** URL http(s) de un embed codificada en base64, o null si no decodifica a una URL. */
fun decodeEmbedUrl(value: String): String? =
    decodeBase64Text(value)?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
