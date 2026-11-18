package app.kaizen.source

import app.kaizen.commons.HostGate
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * El texto en español de una ficha de Animeoteca (capa `es` de [AnimeFicha], ver `docs/wiki/architecture.md`). [sinopsis] es null si la
 * página solo la trae cortada con «…»; sin sinopsis ni géneros la ficha de Animeoteca no aporta nada y se guarda como «sin datos».
 */
class AnimeotecaText(val sinopsis: String?, val generos: List<String>) {
    val isEmpty: Boolean get() = sinopsis.isNullOrBlank() && generos.isEmpty()

    /** Lo que se guarda en `AnimeFicha.es`, con la fecha para saber cuándo se miró. */
    internal fun toJson(at: Long): String = JsonObject().also { o ->
        o.addProperty("t", at)
        sinopsis?.let { o.addProperty("sinopsis", it) }
        if (generos.isNotEmpty()) o.add("generos", com.google.gson.JsonArray().also { a -> generos.forEach(a::add) })
    }.toString()

    internal companion object {
        fun fromJson(text: String): AnimeotecaText? = runCatching {
            val o = JsonParser.parseString(text).asObjectOrNull() ?: return null
            AnimeotecaText(o.string("sinopsis"), o.get("generos").asArrayOrEmpty().mapNotNull { it.takeIf { g -> g.isJsonPrimitive }?.asString })
        }.getOrNull()
    }
}

/** Guarda el texto de Animeoteca de una ficha; sin texto (null) deja el marcador «sin datos» para no volver a pedirla. */
internal fun AnimeFichaDao.putEs(anilistId: Long, text: AnimeotecaText?, at: Long) =
    setEs(anilistId, AnimeFicha.gzip((text ?: AnimeotecaText(null, emptyList())).toJson(at)))

/**
 * Las fichas cuyo «sin datos» (Animeoteca no tenía sinopsis ni géneros) se guardó antes de [before]: pasado un tiempo puede que ya los tenga.
 * Un texto con contenido nunca se vuelve a pedir; un marcador ilegible tampoco.
 */
internal fun AnimeFichaDao.idsToRetryEs(before: Long): List<Long> = esMarks().staleEmpty(before)

internal fun List<AnimeFichaDao.EsMark>.staleEmpty(before: Long): List<Long> = filter { mark ->
    runCatching {
        val o = JsonParser.parseString(AnimeFicha.gunzip(mark.es ?: return@runCatching false)).asJsonObject
        val empty = o.get("sinopsis")?.takeIf { it.isJsonPrimitive }?.asString.isNullOrBlank() && (o.getAsJsonArray("generos")?.size() ?: 0) == 0
        empty && o.get("t")?.asLong?.let { it < before } == true
    }.getOrDefault(false)
}.map { it.anilistId }

/** Lo que la cola necesita de Animeoteca. Las pruebas lo sustituyen. */
internal interface AnimeotecaApi {
    /** El texto de la ficha de ese anime de AniList; null si Animeoteca no lo tiene. Lanza [AnilistUnavailable] si no responde. */
    suspend fun text(anilistId: Long): AnimeotecaText?
}

/**
 * Lee la página pública `animeoteca.com/anime/{id}` (el id es el de AniList). Su `robots.txt` permite `/anime/` y veta `/api/`, así que solo
 * se lee la página, nunca su API. El estado «finalizado» y la nota los pinta su aplicación web con JavaScript y no vienen en la página:
 * de ahí solo se toman la sinopsis completa y los géneros.
 */
internal object AnimeotecaParser {
    fun parse(html: String): AnimeotecaText? {
        val doc = Jsoup.parse(html)
        val synopsis = doc.select("h2").firstOrNull { it.text().trim().equals("Sinopsis", ignoreCase = true) }
            ?.nextElementSibling()?.takeIf { it.tagName() == "p" }?.text()?.trim()
            // Cortada con «…» (la de las etiquetas meta): no es la sinopsis entera.
            ?.takeIf { it.isNotBlank() && !it.endsWith("…") }
        val genres = doc.select("a[href^=/genero/]").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
        return AnimeotecaText(synopsis, genres).takeUnless { it.isEmpty }
    }
}

internal object AnimeotecaHttp : AnimeotecaApi {
    private const val URL = "https://animeoteca.com/anime/"
    private const val MAX_RETRIES = 2

    /** Una ficha pesa unas decenas de KB: lo que pase de esto no es una ficha. */
    private const val MAX_BYTES = 2_000_000

    /** Un sitio pequeño: una petición por segundo como mucho. */
    internal var spacing = 1.seconds
    internal var retryWait = 5.seconds
    internal var baseUrl = URL

    private val gate = Mutex()
    private var last = 0L

    override suspend fun text(anilistId: Long): AnimeotecaText? = gate.withLock {
        repeat(MAX_RETRIES + 1) { attempt ->
            val wait = last + spacing.inWholeMilliseconds - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            // Si HostGate tiene a Animeoteca en pausa (bloqueo o caída seguidos) no se insiste: la cola para y vuelve más tarde.
            try { HostGate.allow(baseUrl) } catch (e: HostGate.HostDownException) { throw AnilistUnavailable("Animeoteca en pausa", e) }
            val request = Request.Builder().url(baseUrl + anilistId).header("User-Agent", AnilistHttp.USER_AGENT).header("Accept", "text/html").build()
            try {
                val (code, html) = withContext(Dispatchers.IO) { AnilistHttp.client.newCall(request).execute().use { it.code to it.peekBody(MAX_BYTES.toLong()).string() } }
                last = System.currentTimeMillis()
                // Un 404 es que esa ficha no existe, no una caída: solo cuentan el bloqueo (403) y los 5xx.
                HostGate.report(baseUrl, code)
                when {
                    code == 200 -> return AnimeotecaParser.parse(html)
                    code == 404 || code == 410 -> return null
                    (code == 403 || code == 408 || code == 425 || code == 429 || code >= 500) && attempt < MAX_RETRIES -> delay(retryWait)
                    else -> throw AnilistUnavailable("Animeoteca respondió $code")
                }
            } catch (e: IOException) {
                last = System.currentTimeMillis()
                if (e is AnilistUnavailable) throw e
                HostGate.reportFailure(baseUrl, e)
                if (attempt == MAX_RETRIES) throw AnilistUnavailable("Sin conexión con Animeoteca", e)
                delay(retryWait)
            }
        }
        throw AnilistUnavailable("Animeoteca no respondió")
    }
}
