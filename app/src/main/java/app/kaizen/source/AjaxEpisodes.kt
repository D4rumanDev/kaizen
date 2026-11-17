package app.kaizen.source

import app.kaizen.commons.HostGate
import app.kaizen.commons.runCatchingCoop
import app.kaizen.source.spec.AjaxChaptersSpec
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil

/**
 * Lista de episodios que da una API de la web ([AjaxChaptersSpec]): una petición con la URL de un atributo de la ficha o con el
 * id de la serie, y, si el fichero lo indica, con método POST, token de la ficha y sus cookies (sin ellos algunas webs
 * responden 419), en una o varias páginas. Todo se decide por las opciones del fichero.
 */
internal class AjaxEpisodes(private val sourceId: String, private val base: String, private val spec: AjaxChaptersSpec) {
    /** La ficha abierta: de ella salen la URL (o el id), el token, las cookies y el referrer de las peticiones. */
    class Context(val slug: String, val animeId: String?, val directUrl: String?, val token: String, val cookies: Map<String, String>, val referrer: String)

    /** Lee de una ficha ya descargada lo que pide el fichero; null si falta lo imprescindible (el id o la URL). */
    fun context(slug: String, body: String, doc: Document, cookies: Map<String, String>, referrer: String, pageUrl: (Document) -> String?): Context? {
        val animeId = spec.idRegex?.find(body)?.groupValues?.getOrNull(1)
        if (spec.idRegex != null && animeId == null) return null
        val direct = pageUrl(doc)?.takeIf { it.startsWith("$base/") }
        if (spec.url == null && direct == null) return null
        val request = spec.request
        val token = request?.tokenSel?.let { doc.selectFirst(it)?.attr(request.tokenAttr) }.orEmpty()
        return Context(slug, animeId, direct, token, cookies, referrer)
    }

    private fun urlOf(ctx: Context, page: Int): String =
        spec.url?.let { fillTemplate(base, it, "id" to ctx.animeId.orEmpty(), "page" to "$page") } ?: ctx.directUrl.orEmpty()

    /** Una respuesta de la API; null si falla. */
    private fun page(ctx: Context, page: Int): JSONObject? = runCatchingCoop {
        val url = urlOf(ctx, page)
        val request = spec.request
        val body = HostGate.call(base) {
            SourceHttp.execute(url, ajax = true) {
                if (request != null) {
                    request.headers.forEach { (name, value) -> header(name, value) }
                    if (request.referrer) referrer(ctx.referrer)
                    if (request.cookies) cookies(ctx.cookies)
                    request.form.forEach { (name, value) -> data(name, value.replace("{token}", ctx.token)) }
                    method(if (request.method == "POST") "POST" else "GET")
                }
            }.body()
        }
        JSONObject(body)
    }.getOrNull()

    internal fun numbersOf(json: JSONObject?): List<Double> {
        val data = json?.optJSONArray(spec.array) ?: return emptyList()
        return List(data.length()) { data.optJSONObject(it)?.optDouble(spec.field, Double.NaN) ?: Double.NaN }.filterNot { it.isNaN() }
    }

    /**
     * Números de episodio. Sin paginación, la lista de la respuesta ordenada. Con ella, sin bajar todos los bloques (One Piece:
     * 1180 episodios en 74 bloques): si la numeración es 1..total se leen solo el primero y el último y se generan los
     * números; si no es contigua (especiales, decimales), se recorren todos los bloques con tope.
     */
    fun numbers(ctx: Context): List<Double> {
        val paged = spec.paged ?: return numbersOf(page(ctx, 1)).distinct().sorted()
        val first = page(ctx, 1) ?: return emptyList()
        val total = first.optInt(paged.total, 0)
        val lastPage = first.optInt(paged.lastPage, 1)
        val firstNumbers = numbersOf(first)
        val lastNumbers = if (lastPage > 1) numbersOf(page(ctx, lastPage)) else firstNumbers
        val contiguous = total > 0 &&
            firstNumbers == (1..firstNumbers.size).map { it.toDouble() } &&
            lastNumbers.lastOrNull() == total.toDouble() &&
            lastNumbers.all { it == Math.floor(it) }
        if (contiguous) return (1..total).map { it.toDouble() }
        val all = mutableListOf<Double>()
        for (pageNo in 1..paged.maxPages) {
            val numbers = numbersOf(page(ctx, pageNo))
            if (numbers.isEmpty()) break
            all += numbers
        }
        return all.distinct()
    }

    private val datePrefs by lazy { app.kaizen.commons.KaizenStore.prefs("kaizen_${sourceId}_dates_utc") }

    private fun key(slug: String, number: Double) = "$slug#$number"

    /**
     * Fecha y hora exactas (ms) de cada capítulo pedido, si la API da fecha ([AjaxChaptersSpec.date]). Se guarda todo lo que
     * llega (una fecha publicada no cambia) y solo se pide lo que falta: la primera vez unas dos peticiones por serie,
     * después ninguna. [open] descarga la ficha de una serie y abre su contexto.
     */
    fun exactDates(episodes: List<Pair<String, Double>>, open: (String) -> Context?): Map<Pair<String, Double>, Long> {
        if (spec.date == null || spec.paged == null) return emptyMap()
        val missing = episodes.filter { (slug, number) -> !datePrefs.contains(key(slug, number)) }
        // Una petición a la vez a la web (HostGate): en orden, sin hilos esperando turno.
        for ((slug, numbers) in missing.groupBy({ it.first }, { it.second }))
            runCatchingCoop { open(slug)?.let { storeDates(it, numbers, spec.paged.perPage) } }
        return episodes.mapNotNull { (slug, number) ->
            datePrefs.getLong(key(slug, number), 0L).takeIf { it > 0 }?.let { (slug to number) to it }
        }.toMap()
    }

    /** Pide las páginas donde están [numbers] y guarda la fecha de todos los capítulos que llegan. */
    private fun storeDates(ctx: Context, numbers: List<Double>, perPage: Double) {
        val dateField = spec.date ?: return
        val format = SimpleDateFormat(spec.dateFormat, Locale.US).apply { timeZone = TimeZone.getTimeZone(spec.tz) }
        val editor = datePrefs.edit()
        for (pageNo in numbers.map { ceil(it / perPage).toInt().coerceAtLeast(1) }.distinct()) {
            val data = page(ctx, pageNo)?.optJSONArray(spec.array) ?: continue
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val time = runCatchingCoop { format.parse(item.optString(dateField))?.time }.getOrNull() ?: continue
                editor.putLong(key(ctx.slug, item.optDouble(spec.field)), time)
            }
        }
        editor.apply()
    }
}
