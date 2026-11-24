package app.kaizen.commons

import android.content.Context
import app.kaizen.home.TasteRepository
import app.kaizen.source.ApiCache
import app.kaizen.source.CatalogBuilder
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Vacía lo que la app puede volver a pedir: respuestas guardadas de las fuentes, catálogo interno (y cuándo se
 * recorrió), lo aprendido de tus series para recomendar y portadas (Coil). NO toca favoritos, historial, progreso, recientes acumulados ni los ajustes.
 */
object CacheCleaner {
    suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
        ApiCache.clearAll()
        CatalogBuilder.clear()
        TasteRepository.clear()
        val images = SingletonImageLoader.get(context)
        images.diskCache?.clear()
        images.memoryCache?.clear()
        CoverPipeline.clear()
    }
}
