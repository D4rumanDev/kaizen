package app.kaizen.source

import app.kaizen.App
import app.kaizen.commons.AppScope
import app.kaizen.commons.logError
import app.kaizen.commons.runCatchingCoop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import app.kaizen.tv.ui.KaizenToast
import java.io.File

/**
 * Las reglas del usuario ([TitleRules]) guardadas en `titles.json` dentro del almacenamiento de la app. Se leen una vez y se
 * escriben enteras en cada cambio (a un fichero temporal y renombrado, para no dejar un fichero a medias).
 */
object UserTitles {
    private const val FILE = "titles.json"

    @Volatile
    private var loaded: TitleRules? = null

    private val _changes = MutableStateFlow(0)

    /** Cambia con cada modificación de las reglas (para refrescar lo que dependa de ellas). */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private val file get() = File(App.context.filesDir, FILE)

    /** El texto guardado de las reglas (null sin ellas): basta para saber si cambiaron desde la última vez. */
    // Un fichero que existe pero no se puede leer no es «sin reglas»: la firma tiene que ser otra.
    internal fun rawText(): String? = runCatching { file.takeIf { it.isFile }?.readText() }.getOrElse { "<ilegible>" }

    val rules: TitleRules
        get() = loaded ?: synchronized(this) { loaded ?: read().also { loaded = it } }

    private fun read(): TitleRules =
        // Sin fichero legible (o sin contexto de la app, como en las pruebas) no hay reglas.
        runCatching { file.takeIf { it.isFile }?.readText()?.let(TitleRules::parse) }.getOrNull() ?: TitleRules.EMPTY

    /** Aplica [change] a las reglas actuales y las guarda. */
    fun update(regroup: Boolean = true, change: (TitleRules) -> TitleRules): Boolean {
        val saved = synchronized(this) {
            val next = change(rules)
            // Si no se pudo guardar no se da por hecho: lo que se ve tiene que ser lo que habrá al reiniciar.
            if (!write(next)) return@synchronized false
            loaded = next
            _changes.value = _changes.value + 1
            true
        }
        // El catálogo se reagrupa con las reglas nuevas al momento (sin vaciarlo), para poder seguir uniendo series.
        if (saved && regroup) AppScope.launch(Dispatchers.IO) { runCatchingCoop { CatalogBuilder.regroup() }.onFailure(::logError) }
        return saved
    }

    /**
     * La clave con la que las reglas nombran la serie de la ficha ([source], [slug]): su id. Llamar desde un hilo de fondo.
     */
    fun keyOf(source: String, slug: String): String {
        return SeriesLedger.idOf(source, slug).toString()
    }

    /** [read] sobre las reglas de la serie de la ficha, por su id. No toca la base de datos. */
    private fun <T> byFicha(source: String, slug: String, read: (TitleRules, String) -> T?): T? {
        val r = rules
        if (r.isEmpty) return null
        return SeriesLedger.peekKey(source, slug)?.let { read(r, it) }
    }

    /** El mayor id de serie que nombran las reglas (0 si no hay ninguna): el libro no reparte ids por debajo de él. */
    fun highestRuleId(): Long = runCatching { rules.idKeys().maxOfOrNull { it.toLongOrNull() ?: 0L } ?: 0L }.getOrDefault(0L)

    /** Lanza [block] en segundo plano; si no guarda (devuelve false) o falla, avisa con [failMessage]. */
    private fun background(failMessage: String, block: () -> Boolean) {
        AppScope.launch(Dispatchers.IO) {
            runCatchingCoop { block() }
                .onSuccess { saved -> if (!saved) KaizenToast.toast(failMessage) }
                .onFailure { logError(it); KaizenToast.toast(failMessage) }
        }
    }

    /**
     * Cambia las reglas de la serie de la ficha ([source], [slug]) en segundo plano: [change] recibe las reglas y la clave de la
     * serie (su id; se le da uno si no lo tenía).
     */
    fun editFor(source: String, slug: String, change: (TitleRules, String) -> TitleRules) =
        background("No se pudo guardar el cambio") {
            val key = keyOf(source, slug)
            update { change(it, key) }
        }

    /**
     * Separa la ficha ([otherSource], [otherSlug]) de la serie de ([source], [slug]) en segundo plano: la otra ficha sale con un
     * id nuevo y se anota que ese id y el de la serie no se juntan nunca.
     */
    fun separate(source: String, slug: String, otherSource: String, otherSlug: String) =
        background("No se pudo guardar la separación") {
            val own = keyOf(source, slug)
            val detached = SeriesLedger.detach(otherSource, otherSlug).toString()
            update { it.withSplit(own, detached) }
        }

    /**
     * Une la serie de la ficha ([source], [slug]) con la de ([otherSource], [otherSlug]) en segundo plano. Sirve cuando alguna no
     * está en el catálogo y no tiene aún clave de serie: se le da una.
     */
    fun mergeFichas(source: String, slug: String, otherSource: String, otherSlug: String) =
        background("No se pudo guardar la unión") {
            val a = keyOf(source, slug)
            val b = keyOf(otherSource, otherSlug)
            update { it.withMerge(a, b) }
        }

    /**
     * Separa varias fichas de una serie ([fichas], la primera es la que se queda): cada una de las demás sale con un id nuevo y
     * ningún par de ellas se vuelve a juntar solo. Para deshacer lo que el catálogo juntó por su cuenta.
     */
    fun separateAll(fichas: List<Pair<String, String>>) {
        if (fichas.size < 2) return
        background("No se pudo guardar la separación") {
            val keys = listOf(keyOf(fichas.first().first, fichas.first().second)) +
                fichas.drop(1).map { (source, slug) -> SeriesLedger.detach(source, slug).toString() }
            val pairs = keys.flatMapIndexed { index, a -> keys.drop(index + 1).map { b -> a to b } }
            update { rules -> pairs.fold(rules) { acc, (a, b) -> acc.withSplit(a, b) } }
        }
    }

    /**
     * Rehace las uniones desde cero: quita las que hiciste, deja cada ficha con su id y vacía el catálogo, que al reconstruirse
     * solo junta lo que cumple las reglas automáticas. Los títulos, las portadas y las separaciones se conservan.
     */
    fun resetJoins(onDone: (Boolean) -> Unit) {
        AppScope.launch(Dispatchers.IO) {
            val done = runCatchingCoop {
                SeriesLedger.splitAll()
                update(regroup = false) { it.withoutMerges() }
            }.onFailure(::logError).getOrDefault(false)
            // Las portadas «mejores» se eligieron entre las fichas que estaban unidas: si alguna unión era mala, la portada de otra serie se quedaba.
            if (done) BestCovers.clear()
            val cleared = done && runCatchingCoop { CatalogBuilder.clear() }.onFailure(::logError).isSuccess
            // Si algo falló, los ids pueden haber cambiado sin que cambie titles.json: se reagrupa para no dejar el catálogo con los antiguos.
            if (!cleared) runCatchingCoop { CatalogBuilder.regroup() }.onFailure(::logError)
            onDone(done)
            // El catálogo vacío se rehace ya (con red), sin esperar a que se reinicie la app ni depender de «Recorrido automático»: sin él no vuelve ninguna unión.
            if (cleared) runCatchingCoop { CatalogBuilder.ensureFresh(force = true) }.onFailure(::logError)
        }
    }

    /**
     * Las reglas que nombran un id de serie absorbido por otra pasan a la vigente ([resolve] da el id vigente de un id): así la tabla de
     * redirecciones deja de hacer falta a las reglas. Devuelve cuántos ids se pasaron; no hace nada (ni escribe) si no hay ninguno.
     */
    fun followLedger(resolve: (Long) -> Long): Int {
        fun moves(rules: TitleRules) = rules.idKeys().mapNotNull { key -> key.toLongOrNull()?.let { id -> resolve(id).takeIf { it != id }?.let { key to it.toString() } } }
        val count = moves(rules).size
        if (count == 0) return 0
        // Sin guardar no se da por hecho: el registro no dice que se pasaron ids que siguen igual en el fichero.
        val saved = update(regroup = false) { current -> moves(current).fold(current) { acc, (old, live) -> acc.remapKey(old, listOf(live)) } }
        return if (saved) count else 0
    }

    /** La portada que el usuario eligió para la serie de la ficha, si la hay (también si aún se guarda al modo antiguo); no toca la base de datos. */
    fun coverOf(source: String, slug: String): String? = byFicha(source, slug) { r, key -> validCover(r, key) }

    /** La portada elegida para la serie [key] (su id), si la hay y es de esa serie: una regla que quedó apuntando a otra serie no se enseña (ver [CoverGuard]). */
    fun coverOfKey(key: String): String? = validCover(rules, key)

    private fun validCover(r: TitleRules, key: String): String? = r.cover(key)?.takeIf { url ->
        CoverGuard.fits(url, key,
            ownersOf = { named -> AnimeSources.all.mapNotNull { SeriesLedger.peekKey(it.id, named) } },
            same = { a, b -> r.canonical(a) == r.canonical(b) })
    }

    /**
     * El contenido del fichero, para la copia de seguridad. Los ids de serie son de esta instalación: la copia lleva aparte, en
     * `fichas`, a qué fichas (fuente, slug) corresponde cada uno, para poder colocar las reglas en otra.
     */
    fun export(): String = rules.toJson().put("fichas", fichasByKey(rules.idKeys())).toString()

    /**
     * Las fichas de cada id de serie de [keys]. Un id que se unió a otro y ya no es una serie (redirección) lleva las fichas de la serie
     * a la que pasó: las reglas pueden seguir nombrándolo, y sin esto se perdían en la copia. Un id sin ninguna ficha se omite.
     */
    internal fun fichasByKey(keys: Collection<String>): JSONObject {
        val fichas = JSONObject()
        for (key in keys) {
            val members = SeriesLedger.fichasOfKeys(listOf(key)).sortedWith(compareBy({ it.first }, { it.second }))
            if (members.isEmpty()) continue
            fichas.put(key, JSONArray(members.map { (source, slug) -> JSONArray(listOf(source, slug)) }))
        }
        return fichas
    }

    /**
     * Añade a las reglas actuales las de [text] (copia de seguridad) que falten; lo que no cumple el formato se ignora. Los ids de
     * la copia se cambian por los de esta instalación a través de sus fichas (puede crear ids: llamar desde un hilo de fondo).
     */
    fun import(text: String): Boolean {
        val fichas = runCatching { JSONObject(text).optJSONObject("fichas") }.getOrNull()
        // Una copia ajena (también la de otro dispositivo) no fabrica fuentes que no existen ni ids sin tope: solo fuentes conocidas, con un máximo de reglas y de fichas por regla.
        val known = AnimeSources.all.map { it.id }.toSet()
        var keys = 0
        val translated = TitleRules.parse(text).translateIds { key ->
            if (++keys > MAX_IMPORT_KEYS) return@translateIds null
            val members = fichas?.optJSONArray(key) ?: return@translateIds null
            (0 until minOf(members.length(), MAX_IMPORT_MEMBERS)).mapNotNull { index ->
                val ficha = members.optJSONArray(index) ?: return@mapNotNull null
                val source = ficha.optString(0)
                val slug = ficha.optString(1)
                if (source !in known || slug.isBlank() || slug.length > MAX_IMPORT_SLUG) null else SeriesLedger.idOf(source, slug).toString()
            }
        }
        return update { it.fillFrom(translated) }
    }

    private const val MAX_IMPORT_KEYS = 20_000
    private const val MAX_IMPORT_MEMBERS = 30
    private const val MAX_IMPORT_SLUG = 400

    private fun write(rules: TitleRules): Boolean =
        runCatchingCoop {
            val target = file
            val temp = File(target.parentFile, "$FILE.tmp")
            temp.writeText(rules.toJson().toString())
            if (!temp.renameTo(target)) {
                target.delete()
                check(temp.renameTo(target)) { "no se pudo guardar $FILE" }
            }
        }.onFailure(::logError).isSuccess
}
