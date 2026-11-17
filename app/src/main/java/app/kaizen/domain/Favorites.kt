package app.kaizen.domain

import app.kaizen.database.CacheDB
import app.kaizen.pojos.av1.Series
import app.kaizen.source.normalizeTitle
import app.kaizen.source.slugOfUrl
import app.kaizen.pojos.av1.FavoriteAV1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.kaizen.tv.ui.KaizenToast

/**
 * Favoritos: la única regla para saber si una serie lo es y para alternarlo (la usan la ficha, el menú y el reproductor). Un favorito es
 * una serie (id del libro de ids, ver [app.kaizen.source.SeriesIds]), se guarde desde la fuente que se guarde.
 */
object Favorites {
    /**
     * Alterna el favorito leyendo el estado actual y avisa con un toast. Devuelve si queda como favorito.
     * [known] es la ficha ya conocida; si no, se busca en la base de datos y, si no está, en la web.
     */
    suspend fun toggle(name: String, animeUrl: String, known: FavoriteAV1? = null): Boolean = withContext(Dispatchers.IO) {
        val dao = CacheDB.INSTANCE.favoriteAV1DAO()
        val source = app.kaizen.source.AnimeSources.forUrl(animeUrl).id
        val slug = slugOfUrl(animeUrl)
        // Mismo criterio que el corazón de las tarjetas (la serie o el título normalizado): si se ve como favorita, se quita.
        // (Un título que se normaliza a vacío, p. ej. solo símbolos, no casa con nada: casaría con todos los de su clase.)
        val title = normalizeTitle(name)
        val sameTitle = if (title.isEmpty()) emptyList() else dao.allRaw.filter { normalizeTitle(it.name) == title }
        if (dao.isFav(source, slug) || sameTitle.isNotEmpty()) {
            // Un favorito es de la serie: también quita el guardado desde otra ficha de ella.
            dao.removeFav(source, slug)
            sameTitle.forEach(dao::delete)
            KaizenToast.toast("Quitado de favoritos")
            return@withContext false
        }
        // Los capítulos no traen el tipo del anime: se mira la ficha guardada y, si no está, la web.
        val favorite = known
            ?: CacheDB.INSTANCE.sourceFichaDAO().findBySlug(slugOfUrl(animeUrl))?.asFavorite()
            ?: Series.open(animeUrl)?.asFavorite()
        if (favorite == null) {
            KaizenToast.toast("No se pudo cargar la ficha")
            return@withContext false
        }
        // Si no se puede dar de alta la ficha en el libro de series (no se pudo cargar), no se guarda nada y se avisa.
        val saved = app.kaizen.commons.runCatchingCoop { dao.addFav(favorite) }
            .onFailure { app.kaizen.commons.logError(it) }.isSuccess
        if (!saved) {
            KaizenToast.toast("No se pudo guardar el favorito")
            return@withContext false
        }
        KaizenToast.toast("Añadido a favoritos")
        true
    }
}
