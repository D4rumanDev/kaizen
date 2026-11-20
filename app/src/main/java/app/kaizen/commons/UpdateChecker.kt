package app.kaizen.commons

import app.kaizen.BuildConfig
import com.google.gson.JsonParser
import java.io.IOException
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Aviso de versiones nuevas: mira la última release de GitHub y, si es más nueva que la instalada, avisa una vez y la deja anotada para Configuración › Acerca de.
 * Este objeto **solo mira y avisa**: bajar e instalar es de [UpdateFlow], que el usuario inicia desde la etiqueta de Inicio ([available]) y que no se salta ninguna comprobación.
 *
 * La petición va con el cliente estricto (TLS siempre con el sistema), el DNS de relevo y el User-Agent de la app, y solo revela la IP a GitHub. Solo se aceptan etiquetas
 * numéricas (`v6.3.0`); la dirección que se enseña se construye con la etiqueta validada, no se toma de la respuesta.
 */
internal object UpdateChecker {
    private const val REPO = "D4rumanDev/kaizen"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val PAGE = "https://github.com/$REPO/releases/tag/"
    internal val INTERVAL = 24.hours
    /** Espera mínima tras un intento fallido. */
    internal val RETRY_AFTER_FAILURE = 6.hours
    /** La respuesta normal pesa decenas de KB: más que esto no es una release. */
    private const val MAX_BODY = 256L * 1024
    private val TAG = Regex("^[vV]?(\\d+(?:\\.\\d+){0,3})$")

    /** Lo que se recuerda entre arranques. Las pruebas lo cambian; de verdad va a [KaizenSettings]. */
    interface State {
        var mode: String
        var checkedAt: Long
        /** Último intento, con éxito o no: un fallo (sin red, 404…) no se reintenta en cada apertura de la app. */
        var attemptedAt: Long
        var latestTag: String?
        var notifiedTag: String?
        /** Versión que el usuario eligió «Omitir»: no se enseña la etiqueta hasta que salga una mayor. */
        var skippedTag: String?
    }

    private object SettingsState : State {
        override var mode: String
            get() = KaizenSettings.updateMode
            set(value) { KaizenSettings.updateMode = value }
        override var checkedAt: Long
            get() = KaizenSettings.updateCheckedAt
            set(value) { KaizenSettings.updateCheckedAt = value }
        override var attemptedAt: Long
            get() = KaizenSettings.updateAttemptedAt
            set(value) { KaizenSettings.updateAttemptedAt = value }
        override var latestTag: String?
            get() = KaizenSettings.updateLatestTag
            set(value) { KaizenSettings.updateLatestTag = value }
        override var notifiedTag: String?
            get() = KaizenSettings.updateNotifiedTag
            set(value) { KaizenSettings.updateNotifiedTag = value }
        override var skippedTag: String?
            get() = KaizenSettings.updateSkippedTag
            set(value) { KaizenSettings.updateSkippedTag = value }
    }

    data class Release(val tag: String, val version: String) {
        val url: String get() = PAGE + tag
    }

    sealed class Outcome {
        /** Ajuste apagado o ya se miró hace menos de 24 h: no hubo petición. */
        object Skipped : Outcome()
        object UpToDate : Outcome()
        object Failed : Outcome()
        data class Available(val release: Release) : Outcome()
    }

    @Volatile internal var state: State = SettingsState

    private val _available = MutableStateFlow<Release?>(null)
    /** La versión nueva que se enseña en Inicio (etiqueta y diálogo): null si no hay, el ajuste está en «No mirar» o el usuario la omitió. */
    val available: StateFlow<Release?> get() = _available

    /** Recalcula [available] con lo guardado; se llama al arrancar, tras mirar GitHub, al omitir y al cambiar el ajuste. */
    fun refreshAvailable() {
        val s = state
        val release = s.latestTag?.let { tag -> parseRelease("""{"tag_name":"$tag"}""") }
        _available.value = release?.takeIf { s.mode == "notice" && isNewer(currentVersion, it.version) && it.tag != s.skippedTag }
    }

    /** «Omitir esta versión»: se oculta la etiqueta hasta que salga una mayor. */
    fun skip(tag: String) {
        state.skippedTag = tag
        refreshAvailable()
    }
    @Volatile internal var currentVersion: String = BuildConfig.VERSION_NAME

    /** Devuelve el cuerpo JSON de «última release». Las pruebas lo cambian. */
    @Volatile internal var fetcher: suspend () -> String = ::fetchFromGitHub

    private val client by lazy { AppHttpClient.strict().newBuilder().connectTimeout(8.seconds).readTimeout(15.seconds).callTimeout(20.seconds).dns(FallbackDns).build() }

    private suspend fun fetchFromGitHub(): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(API).header("User-Agent", UserAgent.current()).header("Accept", "application/vnd.github+json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub respondió ${response.code}")
            response.peekBody(MAX_BODY).string()
        }
    }

    /** `v6.3.0` → [6, 3, 0]; null si no es una versión numérica (prereleases, texto, vacío). */
    private fun parse(version: String): List<Int>? =
        TAG.matchEntire(version.trim())?.groupValues?.get(1)?.split('.')?.map { it.toIntOrNull() ?: return null }

    /** [latest] es una versión numérica mayor que [current] (comparada por partes, no como texto: 6.10 > 6.9). Falso si alguna no es numérica. */
    fun isNewer(current: String, latest: String): Boolean {
        val a = parse(current) ?: return false
        val b = parse(latest) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return y > x
        }
        return false
    }

    /** La release de la respuesta de GitHub; null si es borrador, prerelease, de etiqueta rara o el texto no es una release. */
    fun parseRelease(json: String): Release? {
        val root = runCatching { JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject }.getOrNull() ?: return null
        fun flag(name: String) = root.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false
        if (flag("draft") || flag("prerelease")) return null
        val tag = root.get("tag_name")?.takeIf { it.isJsonPrimitive }?.asString?.trim() ?: return null
        val match = TAG.matchEntire(tag) ?: return null
        return Release(tag, match.groupValues[1])
    }

    private fun isJsonObject(json: String): Boolean = runCatching { JsonParser.parseString(json).isJsonObject }.getOrDefault(false)

    /** Toca mirar: han pasado 24 h desde la última comprobación (o el reloj fue hacia atrás). */
    fun due(now: Long, last: Long): Boolean = last > now || now - last >= INTERVAL.inWholeMilliseconds

    /** Pasó el tiempo de espera tras el último intento (o el reloj fue hacia atrás). */
    fun retryable(now: Long, attemptedAt: Long): Boolean = attemptedAt > now || now - attemptedAt >= RETRY_AFTER_FAILURE.inWholeMilliseconds

    /**
     * Mira GitHub si el ajuste está en «Avisar» y toca (o [force], que es el botón «Comprobar ahora» y no mira el ajuste ni el límite). Si hay una versión nueva la anota y,
     * la primera vez que la ve, llama a [notify]. Un fallo de red devuelve [Outcome.Failed] sin cambiar nada; la cancelación se propaga.
     */
    suspend fun check(force: Boolean, now: Long = System.currentTimeMillis(), notify: (String) -> Unit): Outcome {
        val s = state
        if (!force && (s.mode != "notice" || !due(now, s.checkedAt) || !retryable(now, s.attemptedAt))) return Outcome.Skipped
        s.attemptedAt = now
        val body = try {
            fetcher()
        } catch (e: IOException) {
            DebugLog.info("Actualizaciones", "No se pudo mirar GitHub: ${e.javaClass.simpleName}")
            return Outcome.Failed
        }
        val release = parseRelease(body)
        if (release == null && !isJsonObject(body)) return Outcome.Failed
        s.checkedAt = now
        if (release == null || !isNewer(currentVersion, release.version)) {
            s.latestTag = null
            refreshAvailable()
            return Outcome.UpToDate
        }
        s.latestTag = release.tag
        refreshAvailable()
        if (s.notifiedTag != release.tag) {
            s.notifiedTag = release.tag
            notify("Kaizen ${release.version} disponible (Configuración › Acerca de)")
        }
        return Outcome.Available(release)
    }

    /** El texto de la fila de Configuración › Acerca de › Actualizaciones. */
    fun summary(current: String, latestTag: String?): String {
        val release = latestTag?.let { tag -> parseRelease("""{"tag_name":"$tag"}""") }
        if (release == null || !isNewer(current, release.version)) return "Estás al día ($current)"
        return "Versión ${release.version} disponible: ${release.url.removePrefix("https://")} (tienes la $current)"
    }
}
