package app.kaizen.source

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.google.gson.Gson
import com.google.gson.JsonParseException
import app.kaizen.BuildConfig
import app.kaizen.database.CacheDB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.reflect.Type
import app.kaizen.commons.logError
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/** Respuesta guardada (texto, normalmente JSON) con la fecha en que se pidió. */
@Entity(tableName = "ApiCache")
data class ApiCacheEntry(
    @PrimaryKey
    val key: String,
    val payload: String,
    val updatedAt: Long
)

@Dao
interface ApiCacheDao {
    @Query("SELECT * FROM ApiCache WHERE `key` = :key LIMIT 1")
    fun get(key: String): ApiCacheEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(entry: ApiCacheEntry)

    @Query("DELETE FROM ApiCache WHERE updatedAt < :before")
    fun purgeOlderThan(before: Long)

    @Query("DELETE FROM ApiCache")
    fun clear()

    @Query("DELETE FROM ApiCache WHERE `key` = :key")
    fun delete(key: String)

    /** Páginas guardadas con el formato de clave anterior (page|…): ya nadie las lee. */
    @Query("DELETE FROM ApiCache WHERE `key` LIKE 'page|%'")
    fun purgeOldPages()

    /** Respuestas vacías guardadas por error (un fallo de red parecía una lista vacía y duraba un día). */
    @Query("DELETE FROM ApiCache WHERE payload IN ('[]', '{}') OR payload LIKE '{\"items\":[]%'")
    fun purgeEmpty()

    /** Fichas guardadas por otras versiones de la app: su clave lleva la versión y ya nadie las lee. */
    @Query("DELETE FROM ApiCache WHERE `key` LIKE 'fiche|%' AND `key` NOT LIKE :currentPrefix")
    fun purgeOtherFicheVersions(currentPrefix: String)

    /** Fichas enteras caducadas: solo se sirven mientras son recientes (hasta 30 días las finalizadas, ver FichaPolicy) y pesan decenas de KB cada una. */
    @Query("DELETE FROM ApiCache WHERE `key` LIKE 'fiche|%' AND updatedAt < :before")
    fun purgeOldFiches(before: Long)
}

/**
 * Caché de respuestas con plazo. Si la copia tiene menos de [ttl] se usa sin consultar. Si la consulta falla y
 * hay copia, se devuelve la copia aunque esté caducada: mejor datos algo viejos que una pantalla vacía.
 */
object ApiCache {
    private val PURGE: Duration get() = app.kaizen.commons.Timings.retention()

    /** Una ficha guardada se sirve como mucho hasta [FichaPolicy.SETTLED_TTL] (series finalizadas y completas); pasado eso no vale para nada. */
    private val FICHE_PURGE: Duration get() = FichaPolicy.SETTLED_TTL * 3 / 2

    /** Subir al cambiar los campos de las clases guardadas (ver [clearIfNewFormat]). */
    private const val FORMAT_VERSION = 2

    private val gson = Gson()

    /** Texto de [key]: de la copia si es reciente, si no de [load] (que no se guarda si devuelve null o vacío). */
    suspend fun text(key: String, plannedTtl: Duration, load: suspend () -> String?): String? = withContext(Dispatchers.IO) {
        // Cada clave caduca en su punto del plazo (±dispersión): no todo lo guardado el mismo día vence el mismo día.
        val ttl = app.kaizen.commons.Timings.spread(plannedTtl, key)
        val dao = CacheDB.INSTANCE.apiCacheDao()
        clearIfNewFormat(dao)
        val cached = lookup(dao, key)
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.updatedAt < ttl.inWholeMilliseconds) return@withContext cached.payload
        // Varias pantallas piden a la vez lo mismo (el arranque): solo una descarga y las demás esperan su resultado.
        flights.run(key) {
            // Quien llega justo después de que otra descarga terminara encuentra ya la copia.
            lookup(dao, key)?.takeIf { System.currentTimeMillis() - it.updatedAt < ttl.inWholeMilliseconds }?.let { return@run it.payload }
            val loaded: String? = try {
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(e)
                return@run cached?.payload ?: throw e
            }
            if (!loaded.isNullOrBlank()) store(dao, ApiCacheEntry(key, loaded, now))
            loaded ?: cached?.payload
        }
    }

    private val flights = SingleFlight<String?>()

    // Las notas y los recuentos se piden decenas de veces al pintar una rejilla: la base de datos solo se lee la primera.
    private val memory = MemoryCache()

    private fun lookup(dao: ApiCacheDao, key: String): ApiCacheEntry? =
        memory.get(key) ?: dao.get(key)?.also { memory.putIfAbsent(it) }

    private fun store(dao: ApiCacheDao, entry: ApiCacheEntry) {
        dao.put(entry)
        memory.put(entry)
    }

    /** Vacía la caché entera, también la de memoria. */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        CacheDB.INSTANCE.apiCacheDao().clear()
        memory.clear()
    }

    private var checkedFormat = false

    /**
     * Las copias se guardan con Gson. Las clases que se guardan (CatalogPage, SeriesSummary, AiringSeries,
     * RatingValue, StoredRecent) llevan @Keep y @SerializedName: R8 no les renombra los campos, así que una copia
     * hecha por una versión anterior se sigue leyendo y la caché sobrevive a las actualizaciones. Si cambia el
     * formato de alguna, subir [FORMAT_VERSION]: se vacía una vez. La ficha entera (Series, «fiche|VERSION_CODE|url») no va
     * protegida de R8: su clave lleva la versión de la app y solo se lee con ella; las copias de versiones anteriores se borran
     * en [purgeOld].
     */
    @Synchronized
    private fun clearIfNewFormat(dao: ApiCacheDao) {
        if (checkedFormat) return
        checkedFormat = true
        val prefs = app.kaizen.commons.KaizenStore.prefs("kaizen_api_cache")
        if (prefs.getInt("format", 0) != FORMAT_VERSION) {
            dao.clear()
            memory.clear()
            prefs.edit().putInt("format", FORMAT_VERSION).apply()
        }
    }

    /** Objeto de [key] como JSON: se guarda la respuesta de [load] y se devuelve ya deserializada. */
    suspend fun <T> obj(key: String, ttl: Duration, type: Type, load: suspend () -> T?): T? {
        val payload = text(key, ttl) { load()?.let { gson.toJson(it) } } ?: return null
        return try {
            gson.fromJson(payload, type)
        } catch (e: JsonParseException) {
            // Copia ilegible (formato antiguo o corrupta): se borra y se pide de nuevo una sola vez.
            logError(e)
            withContext(Dispatchers.IO) { CacheDB.INSTANCE.apiCacheDao().delete(key); memory.remove(key) }
            text(key, ttl) { load()?.let { gson.toJson(it) } }?.let { gson.fromJson(it, type) }
        }
    }

    /** Guarda [value] en [key] como si se hubiera pedido ahora: una ficha ya descargada rellena lo que otras pantallas piden de ella. */
    suspend fun put(key: String, value: Any) = withContext(Dispatchers.IO) {
        val dao = CacheDB.INSTANCE.apiCacheDao()
        clearIfNewFormat(dao)
        store(dao, ApiCacheEntry(key, gson.toJson(value), System.currentTimeMillis()))
    }

    /** Objeto de [key] con su edad en ms, sin pedir nada ni mirar plazos; null si no hay copia o no se puede leer. */
    suspend fun <T> cached(key: String, type: Type): Pair<T, Long>? = withContext(Dispatchers.IO) {
        val dao = CacheDB.INSTANCE.apiCacheDao()
        clearIfNewFormat(dao)
        val entry = lookup(dao, key) ?: return@withContext null
        val value = runCatching { gson.fromJson<T>(entry.payload, type) }.getOrNull() ?: return@withContext null
        value to (System.currentTimeMillis() - entry.updatedAt)
    }

    /** Borra las respuestas con más de [PURGE] para que la base de datos no crezca sin límite. */
    suspend fun purgeOld() = withContext(Dispatchers.IO) {
        CacheDB.INSTANCE.apiCacheDao().purgeOlderThan(System.currentTimeMillis() - PURGE.inWholeMilliseconds)
        CacheDB.INSTANCE.apiCacheDao().purgeEmpty()
        CacheDB.INSTANCE.apiCacheDao().purgeOldPages()
        CacheDB.INSTANCE.apiCacheDao().purgeOtherFicheVersions("fiche|${BuildConfig.VERSION_CODE}|%")
        CacheDB.INSTANCE.apiCacheDao().purgeOldFiches(System.currentTimeMillis() - FICHE_PURGE.inWholeMilliseconds)
        // La memoria es siempre un subconjunto de la base de datos: lo que se acaba de borrar no se sirve de ella.
        memory.clear()
    }
}

