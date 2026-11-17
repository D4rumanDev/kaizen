package app.kaizen.bypass

import android.app.Activity
import android.content.Intent

/**
 * Petición para superar el desafío de Cloudflare con un WebView sin interfaz.
 * Se empieza sin el cf_clearance del sitio. El resultado llega a `onActivityResult` con los extras [BypassActivity.RESULT_COOKIES]
 * y [BypassActivity.RESULT_USER_AGENT]; si no se consigue, llega `RESULT_CANCELED` sin datos.
 */
data class Request(
    val url: String,
    val userAgent: String,
    val maxTryCount: Int = 3
)

/**
 * Lanza el bypass salvo que ya haya uno en marcha (TV: lo crea la pantalla principal o Configuración) y devuelve si se lanzó.
 * Sin [force], tras varios fallos seguidos hace poco no se lanza (ver [BypassStatus.coolingDown]).
 */
fun Activity.launchBypass(requestCode: Int, force: Boolean = false): Boolean {
    if (app.kaizen.commons.BypassUtil.isLoading) return false
    if (!force && BypassStatus.coolingDown()) return false
    app.kaizen.commons.BypassUtil.isLoading = true
    BypassStatus.start()
    startBypass(requestCode, app.kaizen.commons.BypassUtil.createRequest())
    return true
}

fun Activity.startBypass(requestCode: Int, request: Request) {
    startActivityForResult(
        Intent(this, BypassActivity::class.java)
            .putExtra(BypassActivity.EXTRA_URL, request.url)
            .putExtra(BypassActivity.EXTRA_USER_AGENT, request.userAgent)
            .putExtra(BypassActivity.EXTRA_MAX_TRIES, request.maxTryCount),
        requestCode
    )
}
