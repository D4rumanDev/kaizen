package app.kaizen.commons

import coil3.EventListener
import coil3.network.HttpException
import coil3.request.ErrorResult
import coil3.request.ImageRequest

/**
 * Las portadas y fotogramas que no cargan pasan por el gestor único de errores ([AppErrors]) con su causa (404, DNS, sin red, imagen no decodificable…) y
 * el servidor de imágenes afectado. Cientos de portadas fallando a la vez del mismo servidor se anotan una vez en el registro (y todas cuentan en el resumen).
 *
 * Una imagen que no existe (404/410) o un icono que no se puede decodificar se anota en [ImageMisses] y no se vuelve a pedir en una semana;
 * las ya conocidas no se anotan otra vez, y los iconos que «Fuentes» prueba por orden (`apple-touch-icon`, `favicon`) no son un fallo.
 */
object ImageErrorListener : EventListener() {
    /** El error es un [android.graphics.ImageDecoder.DecodeException] de datos mal formados (no de una descarga cortada). */
    internal fun malformed(error: Throwable, url: String): Boolean {
        if (error.javaClass.simpleName != "DecodeException") return false
        if (android.os.Build.VERSION.SDK_INT < 28) return url.endsWith(".ico")
        return (error as? android.graphics.ImageDecoder.DecodeException)?.error == android.graphics.ImageDecoder.DecodeException.SOURCE_MALFORMED_DATA
    }

    override fun onError(request: ImageRequest, result: ErrorResult) {
        val error = result.throwable
        if (error is MissingImageException) return
        // Una tarjeta sin portada (la dirección es nula) no es un fallo de ninguna web: no hay nada que anotar.
        if (error is coil3.request.NullRequestDataException) return
        val url = request.data as? String
        val gone = error is HttpException && error.response.code in intArrayOf(404, 410)
        // Solo los datos mal formados o de un formato que Android no sabe leer (un .ico, un formato raro): dan el mismo error cada vez. Un corte a mitad de descarga
        // (SOURCE_INCOMPLETE) o un fallo de lectura (SOURCE_EXCEPTION) pueden ser pasajeros y no marcan nada. Antes de Android 9 no hay código de error: solo los .ico.
        val undecodable = url != null && malformed(error, url)
        if (url != null && (gone || undecodable)) {
            ImageMisses.mark(url)
            // Una imagen que no se puede decodificar no se reutiliza de la caché (con plazos largos seguiría ahí meses): se quita del disco.
            if (undecodable) runCatching { coil3.SingletonImageLoader.get(request.context).diskCache?.remove(url) }
            if (ImageMisses.isProbe(url)) return
        }
        val uri = runCatching { java.net.URI(request.data.toString()) }.getOrNull()
        val host = uri?.host.orEmpty()
        // De qué fuente es la imagen (si el servidor es de una) y cuál se pedía: la ruta, nunca los parámetros (pueden llevar tokens). Los servidores que no son
        // de una fuente (CDN de terceros, AniList…) siguen sin ruta.
        val source = runCatching { HostGate.sourceOf(host) }.getOrNull()
        val context = listOfNotNull(source, host.takeIf { it.isNotEmpty() }).joinToString(" · ")
        AppErrors.report(error, AppErrors.Area.IMAGE, context, what = if (source != null) uri?.path.orEmpty() else "")
    }
}
