package app.kaizen.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lo que el catálogo interno tiene de una fuente: cuántas series, cuándo se recorrió y se comprobó por última vez. */
internal class SourceCatalog(val source: AnimeSource, val series: Int, val walkedAt: Long, val checkedAt: Long)

/** Una serie de una fuente tal como la guarda el catálogo: el nombre de su ficha y su slug. */
internal class CatalogRow(val name: String, val slug: String)

/** Configuración › Catálogo: ver lo que tiene cada fuente y recorrerla a mano. */
internal object CatalogOverview {
    /** Todas las fuentes, con sus cifras; las que nunca se recorrieron salen con 0 series y fecha 0. */
    suspend fun sources(): List<SourceCatalog> = withContext(Dispatchers.IO) {
        val counts = catalogDao.countsBySource().associate { it.sourceId to it.total }
        AnimeSources.all.map { source ->
            val state = CatalogStates[source.id]
            SourceCatalog(source, counts[source.id] ?: 0, state.walkedAt, state.checkedAt)
        }
    }

    /** Las fichas de [sourceId] en el catálogo, por nombre. */
    suspend fun series(sourceId: String): List<CatalogRow> = withContext(Dispatchers.IO) {
        catalogDao.linksOfSource(sourceId)
            .map { CatalogRow(it.name ?: it.slug, it.slug) }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * Recorre [sourceId] ahora, aunque su recorrido no haya caducado. Devuelve si se recorrió; si no (sin red, fuente inactiva o
     * en pausa, otro recorrido en marcha, la fuente falló), la fecha anterior se conserva.
     */
    suspend fun walkNow(sourceId: String): Boolean {
        val previous = CatalogStates[sourceId]
        CatalogStates[sourceId] = SourceState.NEVER
        try {
            CatalogBuilder.ensureFresh(force = true)
        } finally {
            if (CatalogStates[sourceId].walkedAt <= previous.walkedAt) CatalogStates[sourceId] = previous
        }
        return CatalogStates[sourceId].walkedAt > previous.walkedAt
    }
}
