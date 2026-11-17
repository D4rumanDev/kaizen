package app.kaizen.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.kaizen.App
import app.kaizen.database.dao.NotificationDAO
import app.kaizen.pojos.NotificationObj
import app.kaizen.pojos.av1.Series
import app.kaizen.pojos.av1.AiringSeries
import app.kaizen.pojos.av1.SourceFichaDao
import app.kaizen.pojos.av1.FavoriteAV1Dao
import app.kaizen.pojos.av1.RecentEpisode
import app.kaizen.pojos.av1.RecentEpisodeDao
import app.kaizen.pojos.av1.RecordDao

@Database(
    entities = [
        NotificationObj::class,
        Series::class,
        RecentEpisode::class,
        AiringSeries::class,
        app.kaizen.source.AnimeIdentity::class,
        app.kaizen.source.ApiCacheEntry::class,
        app.kaizen.source.CatalogEntry::class,
        app.kaizen.source.CatalogLink::class,
        app.kaizen.source.EpisodeAvailability::class
    ],
    version = 16
)
abstract class CacheDB : RoomDatabase() {

    abstract fun animeIdentityDao(): app.kaizen.source.AnimeIdentityDao

    abstract fun notificationDAO(): NotificationDAO

    abstract fun recentEpisodeDAO(): RecentEpisodeDao

    abstract fun sourceFichaDAO(): SourceFichaDao

    // Datos del usuario: viven en `user-db` (ver [UserDB]). Estos métodos no son de Room (no son abstractos) y solo reenvían, para no
    // cambiar a quien los llama. Sus tablas ya no están declaradas aquí (v12) y la migración 15 → 16 borró las que quedaban en el fichero.
    fun favoriteAV1DAO(): FavoriteAV1Dao = UserDB.INSTANCE.favoriteAV1DAO()

    fun recordAV1DAO(): RecordDao = UserDB.INSTANCE.recordAV1DAO()

    abstract fun apiCacheDao(): app.kaizen.source.ApiCacheDao

    abstract fun catalogDao(): app.kaizen.source.CatalogDao

    // El libro de series vive en `user-db` (ver [UserDB]); este método no es de Room y solo reenvía, para no cambiar a quien lo llama.
    fun seriesIdDao(): app.kaizen.source.SeriesIdDao = UserDB.INSTANCE.seriesIdDao()

    companion object {
        val INSTANCE: CacheDB by lazy { init(App.context) }

        private fun init(context: Context): CacheDB =
            Room.databaseBuilder(context, CacheDB::class.java, "cache-db").addMigrations(MIGRATION_15_16).build()

        /** Las tablas que declara esta base (esquema 16). Todo lo demás que haya en el fichero es resto de versiones antiguas. */
        val DECLARED_TABLES = setOf(
            "NotificationObj", "SourceFicha", "RecentEpisode", "AiringSeries", "AnimeIdentity", "ApiCache", "CatalogEntry", "CatalogLink", "EpisodeAvailability"
        )

        /**
         * 15 → 16: se borran las tablas viejas que seguían en el fichero como copia de lo que había antes de mover a `user-db` los favoritos, los
         * vistos y el libro de series (`SeriesRegistry`, `SeriesMap`, `SeriesRedirect`, `FavoriteAV1`, `Record`...). Los datos del usuario viven en `user-db`.
         * Se borra cualquier tabla que no sea de [DECLARED_TABLES] ni de SQLite, Android o Room.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val old = db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
                    .filter { it !in DECLARED_TABLES && !it.startsWith("sqlite_") && !it.startsWith("android_") && !it.startsWith("room_") }
                for (table in old) db.execSQL("DROP TABLE IF EXISTS `$table`")
            }
        }
    }

}
