package app.kaizen.commons

import app.kaizen.database.CacheDB
import app.kaizen.source.AnimeAv1
import app.kaizen.source.AnimeSources

/**
 * Historial guardado antes de arreglar `source`: los episodios de animegratis y jkanime se marcaron como
 * animeav1 (portada y enlace equivocados). Se reconocen porque el id de episodio de cada fuente es
 * determinista. Se ejecuta una sola vez.
 */
object RecordSourceRepair {
    private const val PREFS = "kaizen_repairs"
    private const val DONE = "record_source_v1"

    fun runOnce() {
        val prefs = app.kaizen.commons.KaizenStore.prefs(PREFS)
        if (prefs.getBoolean(DONE, false)) return
        val dao = CacheDB.INSTANCE.recordAV1DAO()
        dao.all.filter { it.source == AnimeAv1.ID }.forEach { record ->
            AnimeSources.all
                .firstOrNull { it.id != AnimeAv1.ID && it.episodeEid(record.slug, record.number) == record.eid }
                ?.let { dao.addChapter(record.copy(source = it.id, seriesId = 0L)) }
        }
        prefs.edit().putBoolean(DONE, true).apply()
    }
}
