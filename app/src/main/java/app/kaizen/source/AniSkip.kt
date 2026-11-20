package app.kaizen.source

import app.kaizen.commons.AppHttpClient
import app.kaizen.commons.FallbackDns
import app.kaizen.commons.HostGate
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds
import app.kaizen.commons.connectTimeout
import app.kaizen.commons.readTimeout
import app.kaizen.commons.writeTimeout
import app.kaizen.commons.callTimeout

/**
 * Los tiempos reales del opening y del cierre de un capítulo, de la API pública de AniSkip (api.aniskip.com), por id de MyAnimeList y número de capítulo.
 * Solo sale de la app el id de MAL y el número; no se envía nada del usuario. Se recuerda en memoria (también «no hay»). Si no hay tiempos, o no cuadran con la
 * duración del vídeo, el reproductor sigue con el salto fijo de siempre.
 */
internal object AniSkip {
    private const val BASE = "https://api.aniskip.com/v2/skip-times"
    private const val MAX_SEGMENT_MS = 10 * 60 * 1000L

    /** Un tramo de más de diez minutos no es un opening ni un cierre: se descarta el dato. */
    class Times(
        val openingStartMs: Long?, val openingEndMs: Long?, val endingStartMs: Long?, val endingEndMs: Long?,
        /** La duración del capítulo de la que salió cada tramo (0 = no la dice): cada uno es de su propio corte del capítulo. */
        val openingLengthMs: Long = 0L, val endingLengthMs: Long = 0L
    ) {
        fun inOpening(positionMs: Long) = within(positionMs, openingStartMs, openingEndMs)
        fun inEnding(positionMs: Long) = within(positionMs, endingStartMs, endingEndMs)

        private fun within(position: Long, start: Long?, end: Long?) = start != null && end != null && position >= start - LEAD_MS && position < end - TAIL_MS

        /**
         * Lo que vale para un vídeo de [durationMs]: AniSkip puede traer tramos de **cortes distintos** del mismo capítulo (uno de 49 min y otro de 24, por ejemplo) y cada uno solo
         * sirve en un vídeo de su duración (±[TOLERANCE_MS]). Los que no cuadran, o caen en la mitad que no les toca, se descartan; null si no queda ninguno. Un tramo que no dice su duración no se puede comprobar y no vale.
         */
        fun forDuration(durationMs: Long): Times? {
            if (durationMs <= 0) return null
            fun fits(length: Long) = length > 0 && abs(length - durationMs) <= TOLERANCE_MS
            // Sentido común: un opening va en la primera mitad del vídeo y un cierre en la segunda. AniSkip trae a veces tramos mal etiquetados (un «opening» a 5 min del final de
            // un especial de 49 min, que era un cierre): uno que cae en la mitad contraria no se usa.
            val opening = fits(openingLengthMs) && openingStartMs != null && openingEndMs != null && openingStartMs < durationMs / 2
            val ending = fits(endingLengthMs) && endingStartMs != null && endingEndMs != null && endingStartMs >= durationMs / 2
            if (!opening && !ending) return null
            return Times(if (opening) openingStartMs else null, if (opening) openingEndMs else null, if (ending) endingStartMs else null, if (ending) endingEndMs else null,
                if (opening) openingLengthMs else 0L, if (ending) endingLengthMs else 0L)
        }
    }

    /** El aviso sale un poco antes de que empiece el tramo y se quita justo antes de que acabe. */
    /** Lo que puede diferir la duración del vídeo de la del corte de AniSkip: más ya es otra versión (con o sin resumen, partido en dos…) y los tiempos no valen. */
    const val TOLERANCE_MS = 15_000L

    const val LEAD_MS = 2_000L
    const val TAIL_MS = 1_000L

    /** Lee la respuesta de AniSkip; null si no trae ni opening ni cierre válidos. */
    fun parse(json: String): Times? {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
        if (root.get("found")?.takeIf { it.isJsonPrimitive }?.asBoolean != true) return null
        val results = root.get("results")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        var openingStart: Long? = null
        var openingEnd: Long? = null
        var endingStart: Long? = null
        var endingEnd: Long? = null
        var openingLength = 0L
        var endingLength = 0L
        for (element in results) {
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val interval = item.get("interval")?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val start = interval.get("startTime")?.takeIf { it.isJsonPrimitive }?.asDouble ?: continue
            val end = interval.get("endTime")?.takeIf { it.isJsonPrimitive }?.asDouble ?: continue
            val startMs = (start * 1000).toLong()
            val endMs = (end * 1000).toLong()
            if (startMs < 0 || endMs <= startMs || endMs - startMs > MAX_SEGMENT_MS) continue
            val length = ((item.get("episodeLength")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0) * 1000).toLong()
            when (item.get("skipType")?.takeIf { it.isJsonPrimitive }?.asString) {
                "op", "mixed-op" -> if (openingStart == null) { openingStart = startMs; openingEnd = endMs; openingLength = length }
                "ed", "mixed-ed" -> if (endingStart == null) { endingStart = startMs; endingEnd = endMs; endingLength = length }
            }
        }
        if (openingStart == null && endingStart == null) return null
        return Times(openingStart, openingEnd, endingStart, endingEnd, openingLength, endingLength)
    }

    private val cache = ConcurrentHashMap<String, Times>()
    private val misses = ConcurrentHashMap.newKeySet<String>()

    private val client: OkHttpClient by lazy {
        AppHttpClient.strict().newBuilder().connectTimeout(8.seconds).readTimeout(15.seconds).dns(FallbackDns).build()
    }

    /** Los tiempos del capítulo [episode] del anime [malId], o null si AniSkip no los tiene (o no responde: entonces no se recuerda y se vuelve a intentar). */
    suspend fun times(malId: Long, episode: Int): Times? {
        if (malId <= 0 || episode <= 0) return null
        val key = "$malId/$episode"
        cache[key]?.let { return it }
        if (key in misses) return null
        val (code, body) = withContext(Dispatchers.IO) { get("$BASE/$key?types=op&types=ed&episodeLength=0") } ?: return null
        if (code == 404) { misses += key; return null }
        if (code != 200) return null
        val found = parse(body)
        if (found == null) misses += key else cache[key] = found
        return found
    }

    private fun get(url: String): Pair<Int, String>? {
        try { HostGate.allow(url) } catch (e: HostGate.HostDownException) { return null }
        val request = Request.Builder().url(url).header("User-Agent", AnilistHttp.USER_AGENT).header("Accept", "application/json").build()
        return try {
            client.newCall(request).execute().use { response ->
                HostGate.report(url, response.code)
                response.code to response.body.string()
            }
        } catch (e: IOException) {
            HostGate.reportFailure(url, e)
            null
        }
    }
}
