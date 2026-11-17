package app.kaizen.commons

import android.view.View
import androidx.annotation.IdRes
import kotlinx.coroutines.launch

/**
 * Tarea en segundo plano (AppScope, hilos de IO). Los errores van a [onError] o, si no hay, al registro común.
 * Sustituye a `doAsync` de Anko (librería abandonada).
 */
fun background(onError: ((Throwable) -> Unit)? = null, block: suspend () -> Unit) {
    AppScope.launch {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError?.invoke(e) ?: logError(e)
        }
    }
}

/** `findViewById` con el tipo deducido (sustituye a `find` de Anko). */
inline fun <reified T : View> View.find(@IdRes id: Int): T = findViewById(id)

/** `findViewById` con el tipo deducido (sustituye a `find` de Anko). */
inline fun <reified T : View> android.app.Activity.find(@IdRes id: Int): T = findViewById(id)
