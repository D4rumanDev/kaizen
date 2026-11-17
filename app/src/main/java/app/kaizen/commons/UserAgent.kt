package app.kaizen.commons

import android.webkit.WebSettings
import app.kaizen.App
import app.kaizen.uagen.UAGenerator

/**
 * **El User-Agent de la app**, uno solo para todo lo que sale a internet (fuentes, portadas, AniList, AniSkip, servidores de vídeo, bypass de Cloudflare): o el del navegador
 * de esta TV ([LOCAL], el del WebView del sistema) o uno de navegador generado al azar ([RANDOM]). Nunca una marca propia ni datos de quien usa la app.
 *
 * El aleatorio se genera una vez y se guarda: cambiarlo en cada petición rompería lo que las webs asocian al agente (cookies de Cloudflare) y parecería un robot; «Generar otro»
 * lo renueva a mano. Una web donde el usuario pasó la verificación de Cloudflare sigue usando el agente de aquel navegador (la cookie va ligada a él), ver `SourceChallenge`.
 *
 * Sin Android a la vista (pruebas), [current] cae en un agente aleatorio de la sesión.
 */
object UserAgent {
    const val LOCAL = "local"
    const val RANDOM = "aleatorio"
    val MODES = listOf(LOCAL, RANDOM)

    /** Un aleatorio para cuando no hay preferencias (pruebas) o el WebView del sistema no responde. */
    private val fallback by lazy { UAGenerator.getRandomUserAgent() }

    /** El del navegador de esta TV: se pide una vez (el WebView tarda en arrancar). */
    private val local: String? by lazy {
        runCatching { WebSettings.getDefaultUserAgent(App.context) }.getOrNull()?.takeIf(::isHeaderSafe)
    }

    /** Modo elegido; un valor que no es de [MODES] vale [LOCAL]. */
    val mode: String get() = runCatching { KaizenSettings.userAgentMode }.getOrNull()?.takeIf { it in MODES } ?: LOCAL

    /** El agente con que sale ahora cualquier petición. */
    fun current(): String = if (mode == RANDOM) random() else local ?: random()

    /** El aleatorio guardado (se crea la primera vez). */
    @Synchronized
    fun random(): String = runCatching {
        KaizenSettings.randomUserAgent?.takeIf(::isHeaderSafe) ?: UAGenerator.getRandomUserAgent().also { KaizenSettings.randomUserAgent = it }
    }.getOrDefault(fallback)

    /** Solo ASCII imprimible: OkHttp rechaza cualquier otro carácter en una cabecera, y un valor guardado dañado no debe romper todas las peticiones. */
    private fun isHeaderSafe(value: String) = value.isNotBlank() && value.length <= 400 && value.all { it in ' '..'~' }

    /** Genera otro aleatorio y lo guarda; devuelve el nuevo. */
    fun regenerate(): String = UAGenerator.getRandomUserAgent().also { runCatching { KaizenSettings.randomUserAgent = it } }
}
