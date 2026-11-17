package app.kaizen.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.kaizen.App
import app.kaizen.pojos.av1.FavoriteAV1
import app.kaizen.pojos.av1.FavoriteAV1Dao
import app.kaizen.pojos.av1.Record
import app.kaizen.pojos.av1.RecordDao

/**
 * Lo que el usuario ha guardado (favoritos y capítulos vistos), en su propio fichero `user-db`, aparte de
 * `cache-db` (catálogo, ids de serie y caché): reconstruir o vaciar el catálogo no puede tocarlo. Los DAO son los mismos de siempre
 * (`CacheDB.INSTANCE.favoriteAV1DAO()` y compañía reenvían aquí). Las filas identifican su ficha con (fuente, slug); a qué serie
 * pertenece esa ficha (el grupo de fichas de varias fuentes) se resuelve al leer con el libro de ids, que también vive aquí desde la
 * versión 3 (en la 6 pasó a la tabla `Ficha`). Desde la versión 8 no hay redirecciones: una unión de series cambia el id de sus fichas.
 */
@Database(
    entities = [
        FavoriteAV1::class, Record::class, app.kaizen.source.FichaRow::class
    ],
    version = 8
)
abstract class UserDB : RoomDatabase() {
    abstract fun favoriteAV1DAO(): FavoriteAV1Dao

    abstract fun recordAV1DAO(): RecordDao

    /** Las fichas con su serie y su enlace con AniList, y las uniones: son datos del usuario, no caché del catálogo. Lo usan [seriesIdDao] y [animeFuenteDao]. */
    abstract fun fichaDao(): app.kaizen.source.FichaDao

    private fun inTransaction(body: () -> Unit) = runInTransaction(Runnable { body() })

    private val seriesStore by lazy { app.kaizen.source.SeriesIdStore(fichaDao(), ::inTransaction, app.kaizen.source.UserTitles::highestRuleId) }

    private val fuenteStore by lazy { app.kaizen.source.AnimeFuenteStore(fichaDao(), seriesStore, ::inTransaction) }

    /** El libro de series (qué ficha es de qué serie y qué ids se unieron). */
    fun seriesIdDao(): app.kaizen.source.SeriesIdDao = seriesStore

    /** Mantiene las filas del usuario (favoritos y vistos) con el id de la serie de su ficha cuando el libro cambia. */
    abstract fun userRowsDao(): app.kaizen.source.UserRowsDao

    /** A qué anime de AniList pertenece cada ficha de una fuente (lo que decide el sistema y, por encima, lo que decide el usuario). */
    fun animeFuenteDao(): app.kaizen.source.AnimeFuenteDao = fuenteStore

    companion object {
        val INSTANCE: UserDB by lazy { init(App.context) }

        private fun init(context: Context): UserDB =
            Room.databaseBuilder(context, UserDB::class.java, "user-db").addMigrations(MIGRATION_7_8).build()

        /**
         * 7 → 8: se quitan `SeriesRedirect` y `UserMigration`. Antes de borrar las uniones se pasa a su id vigente cualquier ficha, favorito o
         * capítulo visto que aún nombrara un id absorbido (lo normal es que ninguno, porque cada cambio del libro ya los movía); una fila que
         * chocara con otra de la serie vigente se deja como está, igual que hacía `UserRows.followLedger`. La fila `fromId = toId` solo guardaba el mayor id repartido
         * (ver la migración 6 → 7); desde ahora lo da `SeriesIdStore` con el mayor id de las fichas y de las reglas del usuario.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in MIGRATION_7_8_SQL) db.execSQL(statement)
            }
        }

        /** Las sentencias de [MIGRATION_7_8], aparte para poder probarlas con SQLite sin Android (`tools/audit/check_migration.py`). */
        val MIGRATION_7_8_SQL: List<String> = buildList {
            // Las uniones pueden encadenarse (A → B → C): varias vueltas, con tope por si hubiera un ciclo.
            repeat(8) {
                add("UPDATE Ficha SET seriesId = (SELECT toId FROM SeriesRedirect WHERE fromId = Ficha.seriesId) WHERE seriesId IN (SELECT fromId FROM SeriesRedirect WHERE fromId != toId)")
                add("UPDATE OR IGNORE FavoriteAV1 SET seriesId = (SELECT toId FROM SeriesRedirect WHERE fromId = FavoriteAV1.seriesId) WHERE seriesId IN (SELECT fromId FROM SeriesRedirect WHERE fromId != toId)")
                add("UPDATE OR IGNORE Record SET seriesId = (SELECT toId FROM SeriesRedirect WHERE fromId = Record.seriesId) WHERE seriesId IN (SELECT fromId FROM SeriesRedirect WHERE fromId != toId)")
            }
            add("DROP TABLE IF EXISTS SeriesRedirect")
            add("DROP TABLE IF EXISTS UserMigration")
        }
    }
}
