package app.kaizen.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.kaizen.App
import app.kaizen.source.AnimeFicha
import app.kaizen.source.AnimeFichaDao
import app.kaizen.source.WebFicha
import app.kaizen.source.WebFichaDao
import app.kaizen.source.WebFichaTables

/**
 * Datos de catálogo que vienen de fuera y se pueden volver a pedir: las fichas de AniList (`AnimeFicha`) y lo que dio la web de cada ficha, con la
 * misma estructura de AniList (`WebFicha`). Fichero propio, aparte de `cache-db` y de `user-db`: borrarlo no pierde nada del usuario.
 *
 * Al ser una caché, si falta una migración se recrea todo (`fallbackToDestructiveMigration`); pero eso borraría las fichas de AniList, que cuestan
 * horas de peticiones, así que cada cambio de esquema lleva su migración (1 → 2: solo crea `WebFicha`, no toca `AnimeFicha`).
 */
@Database(entities = [AnimeFicha::class, WebFicha::class], version = 3, exportSchema = true)
abstract class CatalogDB : RoomDatabase() {
    abstract fun animeFichaDao(): AnimeFichaDao

    abstract fun webFichaDao(): WebFichaDao

    abstract fun fichaExportDao(): app.kaizen.source.FichaExportDao

    companion object {
        val INSTANCE: CatalogDB by lazy { init(App.context) }

        private fun init(context: Context): CatalogDB =
            Room.databaseBuilder(context, CatalogDB::class.java, "catalog-db").addMigrations(MIGRATION_1_3, MIGRATION_2_3).fallbackToDestructiveMigration(true).build()

        /**
         * Hacia la versión 3: `WebFicha` se rehace con el esquema único ([WebFichaTables.REBUILD], comprobada contra el esquema que exporta Room). Es una caché que se
         * vuelve a llenar al abrir las fichas, así que se recrea en lugar de ir añadiendo columnas. `AnimeFicha` no se toca. Desde la 1 y desde la 2 es lo mismo.
         */
        private fun rebuildWebFicha(from: Int) = object : Migration(from, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                WebFichaTables.REBUILD.forEach(db::execSQL)
            }
        }

        val MIGRATION_1_3 = rebuildWebFicha(1)
        val MIGRATION_2_3 = rebuildWebFicha(2)
    }
}
