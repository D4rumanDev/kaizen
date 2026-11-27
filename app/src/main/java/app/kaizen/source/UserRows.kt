package app.kaizen.source

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/**
 * Lo del usuario que se guarda por serie (favoritos y capítulos vistos) sigue a su ficha cuando el libro de ids cambia: una serie que se une
 * a otra, una ficha que se separa o que recibe otro id. Se llama dentro de la transacción de `user-db` que cambió el libro, así las filas y el libro
 * nunca quedan en estados distintos.
 */
interface UserRows {
    /**
     * Deja cada fila del usuario con el id de la serie de su ficha (el libro ya tiene los ids vigentes). Si dos filas acaban con la misma clave (el mismo capítulo visto en dos fichas que se
     * unen), se queda la que ya estaba en la serie.
     */
    fun followLedger()

    /**
     * Al separar fichas de su serie, el favorito que guardó una de ellas (la fila nombra su fuente y su slug) se va con ella a la serie nueva [seriesId]:
     * hay una fila por serie, así que si no se mueve a mano la serie de la que salió se queda con ella y la nueva se queda sin favorito.
     */
    fun moveFavorites(fichas: List<Pair<String, String>>, seriesId: Long) {}
}

/** Sin filas del usuario que seguir (pruebas del libro y del catálogo sin base de datos). */
internal object NoUserRows : UserRows {
    override fun followLedger() {}
}

@Dao
abstract class UserRowsDao : UserRows {
    @Query(
        "UPDATE OR IGNORE FavoriteAV1 SET seriesId = (SELECT m.seriesId FROM Ficha m WHERE m.fuente = FavoriteAV1.source AND m.slug = FavoriteAV1.slug) " +
            "WHERE EXISTS (SELECT 1 FROM Ficha m WHERE m.fuente = FavoriteAV1.source AND m.slug = FavoriteAV1.slug AND m.seriesId != FavoriteAV1.seriesId)"
    )
    abstract fun followFavorites()

    @Query("UPDATE OR IGNORE FavoriteAV1 SET seriesId = :seriesId WHERE source = :source AND slug = :slug")
    abstract fun moveFavorite(source: String, slug: String, seriesId: Long): Int

    override fun moveFavorites(fichas: List<Pair<String, String>>, seriesId: Long) {
        // Un favorito por serie: solo uno de los de las fichas puede quedarse con el id nuevo.
        for ((source, slug) in fichas) if (moveFavorite(source, slug, seriesId) > 0) break
    }

    /** Favoritos que no pueden seguir a su ficha porque su serie nueva ya tiene otro: al borrarse se anotan (con su categoría) para poder recuperarlos. */
    @Query("SELECT source || ':' || slug || ' (' || category || ')' FROM FavoriteAV1 WHERE EXISTS (SELECT 1 FROM Ficha m WHERE m.fuente = FavoriteAV1.source AND m.slug = FavoriteAV1.slug AND m.seriesId != FavoriteAV1.seriesId)")
    abstract fun favoritesAboutToBeDropped(): List<String>

    @Query(
        "DELETE FROM FavoriteAV1 WHERE EXISTS (SELECT 1 FROM Ficha m WHERE m.fuente = FavoriteAV1.source AND m.slug = FavoriteAV1.slug AND m.seriesId != FavoriteAV1.seriesId)"
    )
    abstract fun dropFavoritesAlreadyInTheirSeries()

    @Query(
        "UPDATE OR IGNORE Record SET seriesId = (SELECT m.seriesId FROM Ficha m WHERE m.fuente = Record.source AND m.slug = Record.slug) " +
            "WHERE EXISTS (SELECT 1 FROM Ficha m WHERE m.fuente = Record.source AND m.slug = Record.slug AND m.seriesId != Record.seriesId)"
    )
    abstract fun followRecords()

    @Query(
        "DELETE FROM Record WHERE EXISTS (SELECT 1 FROM Ficha m WHERE m.fuente = Record.source AND m.slug = Record.slug AND m.seriesId != Record.seriesId)"
    )
    abstract fun dropRecordsAlreadyInTheirSeries()

    @Transaction
    override fun followLedger() {
        followFavorites()
        // Dos fichas favoritas que acaban en la misma serie dejan un solo favorito (hay una fila por serie): el que se pierde queda anotado.
        favoritesAboutToBeDropped().takeIf { it.isNotEmpty() }?.let {
            app.kaizen.commons.DebugLog.info("Series", "Favoritos que se funden con el de su nueva serie y se borran: ${it.joinToString()}")
        }
        dropFavoritesAlreadyInTheirSeries()
        followRecords()
        dropRecordsAlreadyInTheirSeries()
    }
}
