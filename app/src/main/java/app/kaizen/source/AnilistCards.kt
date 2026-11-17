package app.kaizen.source

import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.logError
import app.kaizen.commons.runCatchingCoop
import app.kaizen.database.CatalogDB
import app.kaizen.database.UserDB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Lo que una tarjeta de lista toma de AniList cuando la serie está enlazada: la nota (0 a 100), su portada, su título en romaji y el resumen del panel de datos. */
internal class AnilistCard(val score: Int?, val cover: String?, val romaji: String?, val info: CardInfo? = null)

/**
 * El resumen que enseña el panel de una tarjeta enfocada: sale de las fichas ya descargadas (sin red) y **siempre en español**. La sinopsis, de Animeoteca (capa `es` de la ficha de
 * AniList) o, si no, de la ficha de la propia web si la da en español; si no hay ninguna en español, no se enseña (la de AniList es inglesa). Los géneros, igual y, si no, los de AniList
 * traducidos. La sinopsis va recortada para no cargar la memoria.
 */
internal class CardInfo(val year: Int?, val episodes: Int?, val format: String?, val status: String?, val genres: List<String>, val synopsis: String?) {
    companion object {
        const val SYNOPSIS_MAX = 170
        const val GENRES_MAX = 3

        fun of(data: FichaData, web: FichaData? = null): CardInfo = CardInfo(
            data.anio, data.episodios, data.formato, data.estado,
            (data.es?.generos?.takeIf { it.isNotEmpty() } ?: web?.generos?.takeIf { it.isNotEmpty() } ?: data.generos.map(AnilistLabels::genre)).take(GENRES_MAX),
            (data.es?.sinopsis?.takeIf { it.isNotBlank() } ?: web?.descripcionEs)?.let { AnilistLabels.plain(it).lines().joinToString(" ") }?.let { if (it.length > SYNOPSIS_MAX) it.take(SYNOPSIS_MAX).trimEnd() + "…" else it }?.takeIf { it.isNotBlank() }
        )
    }
}

/**
 * La nota y la portada de AniList para las tarjetas de las listas, de la base local (sin red). Así una serie se ve igual en la lista y en su
 * ficha, y las tarjetas dejan de pedir la nota a la web de cada fuente. Solo se guardan en memoria los aciertos: una serie que se enlaza
 * después se ve en cuanto se vuelve a pintar.
 */
internal object AnilistCards {
    private val memory = ConcurrentHashMap<String, AnilistCard>()

    /** Pura: la tarjeta de una ficha de fuente según su fila de enlace y la ficha de AniList descargada; null si no están las dos. */
    fun card(row: AnimeFuente?, ficha: AnimeFicha?, web: FichaData? = null): AnilistCard? {
        if (row?.estado != AnimeFuente.APLICADO || row.anilistId == null) return null
        if (ficha == null || ficha.consulta != AnilistClient.FICHA_VERSION) return null
        val data = FichaData.ofAnilist(ficha) ?: return null
        return AnilistCard(data.puntuacion, data.portada, data.romaji, CardInfo.of(data, web))
    }

    /** Olvida lo memorizado de una serie: se llama al guardar su ficha de la web, que puede traer la sinopsis en español que la tarjeta no tenía. */
    fun forget(source: String, slug: String) { memory.remove("$source|$slug") }

    /** Lo ya leído, sin esperar: las tarjetas que se repintan no pasan otra vez por «…». */
    fun peek(source: String, slug: String): AnilistCard? = if (KaizenSettings.anilistFicha) memory["$source|$slug"] else null

    suspend fun of(source: String, slug: String): AnilistCard? {
        if (!KaizenSettings.anilistFicha) return null
        val key = "$source|$slug"
        memory[key]?.let { return it }
        val found = runCatchingCoop {
            withContext(Dispatchers.IO) {
                val row = UserDB.INSTANCE.animeFuenteDao().get(source, slug)
                card(row, row?.anilistId?.let { CatalogDB.INSTANCE.animeFichaDao().get(it) }, runCatching { FichaData.get(CatalogDB.INSTANCE.webFichaDao(), source, slug) }.getOrNull())
            }
        }.onFailure(::logError).getOrNull()
        if (found != null) memory[key] = found
        return found
    }
}
