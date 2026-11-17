package app.kaizen.commons

import android.content.Context
import android.os.SystemClock
import app.kaizen.bypass.BypassStatus
import app.kaizen.bypass.Request
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.HttpStatusException

/**
 * Created by jordy on 17/03/2018.
 */

class BypassUtil {

    companion object {
        /** El User-Agent de la app (ver [UserAgent]): el mismo para el bypass, las webs y los servidores. */
        val userAgent: String get() = UserAgent.current()
        /** Hay un bypass en marcha; caduca solo a los [LOADING_MAX_MS] para que una guarda olvidada no bloquee los siguientes. */
        var isLoading: Boolean
            get() = loadingSince != 0L && SystemClock.elapsedRealtime() - loadingSince < LOADING_MAX_MS
            set(value) { loadingSince = if (value) SystemClock.elapsedRealtime() else 0L }
        @Volatile private var loadingSince = 0L
        private const val LOADING_MAX_MS = 90_000L
        /** Sin esto, en TV (tiempo de espera 0 = sin límite) un Cloudflare que no contesta cuelga la comprobación para siempre. */
        private const val CHECK_TIMEOUT_MS = 10_000

        private const val keyCfClearance = "cf_clearance"
        private const val keyCfDuid = "__cfduid"
        private const val keyCookiesBypass = "bypass_cookies"
        private const val defaultValue = ""
        const val testLink = "https://animeav1.com/"

        fun createRequest(): Request = Request(
            testLink,
            userAgent = UserAgent.current(),
            maxTryCount = AppConfig.BYPASS_MAX_TRIES.toInt()
        )

        fun saveCookies(context: Context, cookies: String): Boolean =
            noCrashLet(false) {
                bypassCookies = cookies
                if (!cookies.contains(keyCfClearance)) return@noCrashLet false
                for (cookie in cookies.split(";")) {
                    if (cookie.contains(keyCfDuid)) setCFDuid(context, cookieValue(cookie))
                    if (!cookie.contains(keyCfClearance)) continue
                    val clearance = cookieValue(cookie)
                    if (clearance.isBlank()) return@noCrashLet false
                    setClearance(context, clearance)
                }
                // Con la verificación hecha el servidor vuelve a responder: si HostGate lo había pausado, se levanta.
                testLink.toHttpUrlOrNull()?.host?.let { HostGate.resume(it) }
                true
            }

        /** El valor de una cookie «nombre=valor». */
        private fun cookieValue(cookie: String) = cookie.trim().let { it.substring(it.indexOf("=") + 1) }

        fun isNeeded(url: String = testLink): Boolean {
            return try {
                RequestCounter.note(url)
                val response = jsoupCookies(url).timeout(CHECK_TIMEOUT_MS).execute()
                response.statusCode().let { it == 503 || it == 403 }.also { needed -> if (!needed) BypassStatus.notNeeded() }
            } catch (e: HttpStatusException) {
                e.statusCode.let { it == 503 || it == 403 }
            } catch (e: Exception) {
                logError(e)
                BypassStatus.unreachable()
                false
            }
        }

        fun getMapCookie(): Map<String, String> {
            val map = LinkedHashMap<String, String>()
            bypassCookies?.split(";")?.forEach {
                if (it.contains("=")) {
                    val split = it.split("=")
                    map[split[0]] = split[1]
                }
            }
            //getClearance(context).let { if (it != defaultValue) map[keyCfClearance] = it }
            //getCFDuid(context).let { if (it != defaultValue) map[keyCfDuid] = it }
            return map
        }

        /** El cf_clearance es de animeav1.com: solo se envía a ese dominio y a sus subdominios, nunca a otras fuentes. */
        fun cookiesFor(url: String?): Map<String, String> {
            val host = url?.toHttpUrlOrNull()?.host ?: return emptyMap()
            val site = testLink.toHttpUrl().host
            return if (host == site || host.endsWith(".$site")) getMapCookie() else emptyMap()
        }

        fun getOKHttpHeaders(): Headers {
            return Headers.Builder().apply {
                add("Cookie",getStringCookie())
                add("User-Agent", userAgent)
            }.build()
        }

        fun getStringCookie(): String {
            val builder = StringBuilder()
            for ((key, value) in getMapCookie())
                builder.append("$key=$value;")
            return builder.toString().dropLastWhile { it == ' ' || it == ';' }
        }

        var bypassCookies: String?
            set(value) = app.kaizen.commons.KaizenStore.settings.edit().putString(keyCookiesBypass, value).apply()
            get() = app.kaizen.commons.KaizenStore.settings.getString(keyCookiesBypass, null)

        private fun setClearance(context: Context, value: String = defaultValue) {
            app.kaizen.commons.KaizenStore.settings.edit().putString(keyCfClearance, value).apply()
        }

        private fun setCFDuid(context: Context, value: String = defaultValue) {
            app.kaizen.commons.KaizenStore.settings.edit().putString(keyCfDuid, value).apply()
        }

        suspend fun clear() {
            bypassCookies = null
        }
    }
}
