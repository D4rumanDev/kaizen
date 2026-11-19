package app.kaizen.source

import app.kaizen.database.CatalogDB

/**
 * **La única nota de la app es la de AniList.** La media de las notas que dan las webs, y la de cada web por separado, se descartan: cada fuente puntúa con su
 * propia escala y sus propios votos, y combinarlas daba notas que no cuadraban de una pantalla a otra. Aquí se pasa la nota de AniList (`averageScore`, de 0 a 100)
 * a estrellas (0 a 5). Una serie sin enlace con AniList, o con la ficha aún sin descargar, no tiene nota: se muestra «—» y va al final de las listas por nota.
 */
internal object AnilistScores {
    /** Estrellas (0 a 5) de una nota de AniList (0 a 100); null si no hay nota. */
    fun stars(nota: Int?): Double? = nota?.takeIf { it > 0 }?.let { (it / 20.0).coerceIn(0.0, 5.0) }

    /** Las estrellas de cada anime de AniList con la ficha descargada y nota. */
    fun byAnilistId(): Map<Long, Double> =
        CatalogDB.INSTANCE.animeFichaDao().notes().mapNotNull { row -> stars(row.nota)?.let { row.anilistId to it } }.toMap()
}
