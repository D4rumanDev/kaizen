package app.kaizen.commons

import android.os.Handler
import okhttp3.OkHttpClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Lock
import kotlin.time.Duration

/**
 * **La frontera de los plazos con las APIs que piden un número y una unidad.** Todo plazo de la app es una [Duration]; estas funciones son el único sitio
 * que la convierte a milisegundos para Android, OkHttp y `java.util.concurrent`. Así no quedan `TimeUnit.SECONDS` ni `* 1000` repartidos por el código.
 * (Las APIs de WorkManager que aceptan `java.time.Duration` se llaman con `toJavaDuration()`: el `minSdk` es 26.)
 */
internal fun Handler.postDelayed(runnable: Runnable, delay: Duration): Boolean = postDelayed(runnable, delay.inWholeMilliseconds)

internal fun OkHttpClient.Builder.connectTimeout(timeout: Duration): OkHttpClient.Builder = connectTimeout(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
internal fun OkHttpClient.Builder.readTimeout(timeout: Duration): OkHttpClient.Builder = readTimeout(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
internal fun OkHttpClient.Builder.writeTimeout(timeout: Duration): OkHttpClient.Builder = writeTimeout(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
internal fun OkHttpClient.Builder.callTimeout(timeout: Duration): OkHttpClient.Builder = callTimeout(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)

/** Espera el turno de un cerrojo como mucho [timeout]; false si no llegó a tomarlo. */
internal fun Lock.tryLock(timeout: Duration): Boolean = tryLock(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)

internal fun CountDownLatch.await(timeout: Duration): Boolean = await(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)

internal fun <T> Future<T>.get(timeout: Duration): T = get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
