package app.kaizen.source

/**
 * Pocas copias recientes de [ApiCache] en memoria: al pintar una rejilla se piden una y otra vez las mismas notas y recuentos, y cada
 * una iría a la base de datos. Solo guarda respuestas pequeñas (las fichas enteras no) y las menos usadas salen primero.
 */
internal class MemoryCache(private val maxEntries: Int = 512, private val maxPayload: Int = 4_096) {
    private val map = object : LinkedHashMap<String, ApiCacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ApiCacheEntry>) = size > maxEntries
    }

    @Synchronized
    fun get(key: String): ApiCacheEntry? = map[key]

    @Synchronized
    fun put(entry: ApiCacheEntry) {
        if (entry.payload.length <= maxPayload) map[entry.key] = entry else map.remove(entry.key)
    }

    /** Como [put] pero sin pisar una copia que ya esté: una lectura lenta de la base de datos no debe sustituir a una escritura posterior. */
    @Synchronized
    fun putIfAbsent(entry: ApiCacheEntry) {
        if (!map.containsKey(entry.key) && entry.payload.length <= maxPayload) map[entry.key] = entry
    }

    @Synchronized
    fun remove(key: String) {
        map.remove(key)
    }

    @Synchronized
    fun clear() = map.clear()
}
