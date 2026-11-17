package app.kaizen.source.spec

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element

/**
 * Un trozo de página sobre el que se leen datos, igual para HTML ([HtmlNode], con selectores CSS) y para JSON ([JsonNode], con
 * rutas con puntos): así un mismo fichero de fuente usa el mismo lenguaje ([Pick]) con los dos formatos.
 */
interface Node {
    /** Los nodos que casan con [sel] (selector CSS en HTML; ruta a un objeto o lista en JSON, y «.» es este mismo nodo). */
    fun select(sel: String): List<Node>

    fun first(sel: String): Node? = select(sel).firstOrNull()

    /** El texto del nodo (en JSON, su valor si es un dato simple). */
    fun text(): String

    /** El atributo [name] (en JSON, el campo o la ruta [name] de este objeto); vacío si no está. */
    fun attr(name: String): String

    /** Los hijos directos en orden (en JSON, los elementos de una lista). */
    fun children(): List<Node>

    /** Si el nodo casa con el selector [sel] (solo HTML). */
    fun matches(sel: String): Boolean
}

class HtmlNode(val element: Element) : Node {
    override fun select(sel: String): List<Node> = element.select(sel).map(::HtmlNode)

    override fun first(sel: String): Node? = element.selectFirst(sel)?.let(::HtmlNode)

    override fun text(): String = element.text()

    // «:data» no es un atributo: es el contenido de un <script> o <style> (el texto de una página no lo incluye).
    override fun attr(name: String): String = if (name == DATA) element.data() else element.attr(name)

    override fun children(): List<Node> = element.children().map(::HtmlNode)

    override fun matches(sel: String): Boolean = element.`is`(sel)

    private companion object {
        const val DATA = ":data"
    }
}

/** Nodo JSON: un objeto, una lista o un dato simple. */
class JsonNode(private val value: Any?) : Node {
    override fun select(sel: String): List<Node> {
        val target = if (sel.isEmpty() || sel == ".") value else (value as? JSONObject)?.let { JsonPath.get(it, sel) }
        return when (target) {
            null, JSONObject.NULL -> emptyList()
            is JSONArray -> List(target.length()) { JsonNode(target.opt(it)) }.filter { it.value != null && it.value !== JSONObject.NULL }
            else -> listOf(JsonNode(target))
        }
    }

    override fun text(): String = when (value) {
        is String, is Number, is Boolean -> value.toString()
        else -> ""
    }

    override fun attr(name: String): String = (value as? JSONObject)?.let { JsonPath.string(it, name) }.orEmpty()

    override fun children(): List<Node> = (value as? JSONArray)?.let { select(".") }.orEmpty()

    override fun matches(sel: String): Boolean = false
}

/** Rutas con puntos dentro de un objeto JSON («media.title», «category.slug»). */
internal object JsonPath {
    fun get(obj: JSONObject, path: String): Any? {
        var current: Any? = obj
        for (part in path.split('.')) {
            current = (current as? JSONObject)?.opt(part) ?: return null
            if (current === JSONObject.NULL) return null
        }
        return current
    }

    fun string(obj: JSONObject, path: String): String? = get(obj, path)?.toString()
}
