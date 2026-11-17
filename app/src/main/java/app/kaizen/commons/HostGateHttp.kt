package app.kaizen.commons

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/*
 * Adaptadores para las salidas que no pasan por [HostGate.call] porque piden en paralelo o las gestiona otra librería
 * (portadas, proxy local, reproductor). Todos hacen lo mismo con las tres funciones de HostGate: [HostGate.allow] antes de pedir
 * (un servidor en pausa no recibe nada), [HostGate.report] con el código que respondió y [HostGate.reportFailure] si no hubo
 * respuesta. La respuesta o el error se devuelven tal cual: solo se anota, no se cambia lo que ve quien pide.
 */

/** Para cualquier cliente OkHttp: `builder.addInterceptor(HostGateInterceptor)`. */
object HostGateInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        HostGate.allow(url)
        RequestCounter.note(url)
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            // Una petición cancelada (se sale de la pantalla, se cambia de capítulo) no es un fallo del servidor.
            if (!chain.call().isCanceled()) HostGate.reportFailure(url, e)
            throw e
        }
        // Con redirecciones el servidor que responde es el último, no el de la URL pedida.
        HostGate.report(response.request.url.toString(), response.code, response.header("cf-mitigated"))
        return response
    }
}

/**
 * Si una carga se cortó porque el reproductor la interrumpió (el hilo está interrumpido o la causa es una `InterruptedIOException`)
 * y no porque el servidor no respondiera. `SocketTimeoutException` hereda de `InterruptedIOException` pero es justo el servidor que
 * no contesta: esa sí cuenta como fallo.
 */
internal fun isInterruption(cause: Throwable?, threadInterrupted: Boolean): Boolean =
    threadInterrupted || (cause is InterruptedIOException && cause !is SocketTimeoutException)

/**
 * Para Media3: envuelve la fuente de datos HTTP del reproductor, que pide la lista y cada segmento. Un servidor en pausa lanza
 * [HostGate.HostDownException] al abrir (el reproductor lo trata como un error de entrada/salida y propone otro servidor).
 */
@OptIn(UnstableApi::class)
class HostGateDataSource(private val upstream: DataSource) : DataSource by upstream {

    override fun open(dataSpec: DataSpec): Long {
        val url = dataSpec.uri.toString()
        HostGate.allow(url)
        RequestCounter.note(url)
        try {
            return upstream.open(dataSpec).also { HostGate.report(url, HTTP_OK) }
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            val challenge = e.headerFields.entries.firstOrNull { it.key.equals("cf-mitigated", ignoreCase = true) }?.value?.firstOrNull()
            HostGate.report(url, e.responseCode, challenge)
            throw e
        } catch (e: HttpDataSource.HttpDataSourceException) {
            if (!isCancellation(e)) HostGate.reportFailure(url, e.cause as? IOException ?: e)
            throw e
        }
    }

    /** Cargas que el reproductor interrumpe al saltar o parar: no cuentan. */
    private fun isCancellation(e: HttpDataSource.HttpDataSourceException): Boolean =
        isInterruption(e.cause, Thread.currentThread().isInterrupted)

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = HostGateDataSource(upstream.createDataSource())
    }

    private companion object {
        const val HTTP_OK = 200
    }
}
