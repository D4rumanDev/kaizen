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
import kotlin.time.Duration.Companion.seconds
import app.kaizen.commons.AppErrors
import app.kaizen.domain.ConnectionStats.Cause

/**
 * El texto en español de una ficha de Animeoteca (capa `es` de [AnimeFicha], ver `docs/wiki/architecture.md`). [sinopsis] es null si la
 * página solo la trae cortada con «…»; sin sinopsis ni géneros la ficha de Animeoteca no aporta nada y se guarda como «sin datos».
 */
class AnimeotecaText(val sinopsis: String?, val generos: List<String>, val fuente: String? = null) {
    val isEmpty: Boolean get() = sinopsis.isNullOrBlank() && generos.isEmpty()

    /** Lo que se guarda en `AnimeFicha.es`, con la fecha para saber cuándo se miró. */
    internal fun toJson(at: Long): String = JsonObject().also { o ->
        o.addProperty("t", at)
        sinopsis?.let { o.addProperty("sinopsis", it) }
        fuente?.let { o.addProperty("fuente", it) }
        if (generos.isNotEmpty()) o.add("generos", com.google.gson.JsonArray().also { a -> generos.forEach(a::add) })
    }.toString()

    internal companion object {
        fun fromJson(text: String): AnimeotecaText? = runCatching {
            val o = JsonParser.parseString(text).asObjectOrNull() ?: return null
            AnimeotecaText(o.string("sinopsis"), o.get("generos").asArrayOrEmpty().mapNotNull { it.takeIf { g -> g.isJsonPrimitive }?.asString }, o.string("fuente"))
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
 * Lo que sale de leer una página de Animeoteca. Distinguir [Empty] de [Unknown] es lo que impide que un cambio de diseño de su web se guarde, en silencio,
 * como «sin datos» en todas las series.
 */
internal sealed interface AnimeotecaPage {
    /** Hay sinopsis completa, géneros o ambos. */
    class Text(val text: AnimeotecaText) : AnimeotecaPage

    /** La página se entiende pero esa serie no tiene nada que aportar: tiene el apartado «Sinopsis» sin texto utilizable, o la serie no existe (su web contesta 200 con su portada genérica). */
    data object Empty : AnimeotecaPage

    /** No es una ficha que sepamos leer: su web cambió de diseño o es otra cosa (un desafío, un error). No se guarda como «sin datos». */
    data object Unknown : AnimeotecaPage
}

/**
 * Lee la página pública `animeoteca.com/anime/{id}` (el id es el de AniList). Su `robots.txt` permite `/anime/` y veta `/api/`, así que solo
 * se lee la página, nunca su API. El estado «finalizado» y la nota los pinta su aplicación web con JavaScript y no vienen en la página:
 * de ahí solo se toman la sinopsis completa y los géneros.
 */
internal object AnimeotecaParser {
    /** La portada genérica que su web sirve, con un 200, cuando el id no existe: su `h1` es el nombre del sitio. */
    private const val SITE_NAME = "Animeteca"

    /** La línea de atribución que la página añade al final de la sinopsis: «(Fuente: VIZ Media)». */
    private val SOURCE_LINE = Regex("""\s*\((?:Fuente|Source):\s*([^)]+)\)\s*$""", RegexOption.IGNORE_CASE)

    private val HEADING = Regex("h[1-6]")

    /** Compatibilidad: solo el texto, o null si no hay (sea porque no tiene o porque no se entiende la página). */
    fun parse(html: String): AnimeotecaText? = (classify(html) as? AnimeotecaPage.Text)?.text

    fun classify(html: String): AnimeotecaPage {
        val doc = Jsoup.parse(html)
        val heading = doc.select("h2").firstOrNull { it.text().trim().equals("Sinopsis", ignoreCase = true) }
        // El primer párrafo con texto tras el encabezado y antes del siguiente encabezado; los de relleno que vienen después («En la ficha de Animeteca tienes…») no cuentan.
        val raw = heading?.nextElementSiblings()?.takeWhile { !HEADING.matches(it.tagName()) }
            ?.firstOrNull { it.tagName() == "p" && it.text().isNotBlank() }?.text()?.trim()
        val source = raw?.let { SOURCE_LINE.find(it) }
        val body = if (source != null) raw.substring(0, source.range.first).trim() else raw
        // Cortada con «…» (la de las etiquetas meta o la que su base guardó a medias): no es la sinopsis entera.
        val synopsis = body?.takeIf { it.isNotBlank() && !it.endsWith("…") }
        val genres = doc.select("a[href^=/genero/]").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
        val text = AnimeotecaText(synopsis, genres, source?.groupValues?.get(1)?.trim()).takeUnless { it.isEmpty }
        return when {
            text != null -> AnimeotecaPage.Text(text)
            heading != null || doc.selectFirst("h1")?.text()?.trim().equals(SITE_NAME, ignoreCase = true) -> AnimeotecaPage.Empty
            else -> AnimeotecaPage.Unknown
        }
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
                    code == 200 -> return when (val page = AnimeotecaParser.classify(html)) {
                        is AnimeotecaPage.Text -> page.text
                        AnimeotecaPage.Empty -> null
                        // No se guarda «sin datos»: la cola para tras varias seguidas y el fallo queda a la vista (E09) hasta que se arregle el lector.
                        AnimeotecaPage.Unknown -> {
                            runCatching { AppErrors.record(Cause.EXTRACTOR, AppErrors.Area.SOURCE, "La página de Animeoteca no tiene la estructura conocida (id $anilistId): ¿cambió su diseño?", context = "animeoteca.com") }
                            throw AnilistUnavailable("Animeoteca: página no entendida")
                        }
                    }
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
