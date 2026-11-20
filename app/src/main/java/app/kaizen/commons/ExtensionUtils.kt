package app.kaizen.commons

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.distinctUntilChanged
import androidx.lifecycle.lifecycleScope
import app.kaizen.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Connection
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

val <T>LiveData<T>.distinct: LiveData<T>
    get() = this.distinctUntilChanged()

fun Double.roundedString(): String = if (this % 1.0 == 0.0) this.toInt().toString() else this.toString()

operator fun JSONArray.iterator(): Iterator<JSONObject> = (0 until length()).asSequence().map { get(it) as JSONObject }.iterator()

val canGroupNotifications: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

/**
 * Como `get()` de Jsoup, pero con el turno y el corte de [HostGate]: una petición a la vez por servidor y, si bloquea (403) o cae
 * (5xx) seguido, se pausa. Un error HTTP lanza [HttpStatusException] igual que antes; un 403 de verificación de Cloudflare no cuenta.
 */
fun Connection.getGated(): Document {
    val url = request().url().toString()
    return HostGate.call(url) {
        RequestCounter.note(url)
        val response = ignoreHttpErrors(true).execute()
        val code = response.statusCode()
        if (code >= 400) throw HostGate.failureFor(url, code, response.header("cf-mitigated")) ?: HttpStatusException("HTTP error fetching URL", code, url)
        response.parse()
    }
}

/**
 * Como `execute()` de Jsoup con `ignoreHttpErrors`: devuelve la respuesta sea cual sea su código (quien llama lo comprueba), pero
 * si el servidor está en pausa no se le pide nada ([HostGate.HostDownException]) y se anota lo que responde para el corte.
 */
fun Connection.executeGated(): Connection.Response {
    val url = request().url().toString()
    HostGate.allow(url)
    RequestCounter.note(url)
    try {
        val response = ignoreHttpErrors(true).execute()
        HostGate.report(url, response.statusCode(), response.header("cf-mitigated"))
        return response
    } catch (e: IOException) {
        HostGate.reportFailure(url, e)
        throw e
    }
}

val safeContext: Context
    get() = App.context

fun <T> retry(numOfRetries: Int, block: () -> T): T {
    var throwable: Throwable? = null
    (1..numOfRetries).forEach {
        try {
            return block()
        } catch (e: Throwable) {
            throwable = e
        }
    }
    throw throwable!!
}

fun noCrash(enableLog: Boolean = true, func: () -> Unit): String? {
    return try {
        func()
        null
    } catch (e: Exception) {
        if (enableLog)
            logError(e)
        e.message
    }
}

suspend fun noCrashSuspend(enableLog: Boolean = true, func: suspend () -> Unit): String? {
    return try {
        func()
        null
    } catch (e: kotlinx.coroutines.CancellationException) {
        // Cancelar no es un fallo: si se tragara, la corrutina seguiría trabajando tras cancelarla.
        throw e
    } catch (e: Exception) {
        if (enableLog)
            logError(e)
        e.message
    }
}

fun <T> noCrashLet(onCrash: () -> Unit = {}, func: () -> T): T? {
    return try {
        func()
    } catch (e: Exception) {
        logError(e)
        onCrash()
        null
    }
}

fun <T> noCrashLet(onCrash: T, func: () -> T): T {
    return try {
        func()
    } catch (e: Exception) {
        logError(e)
        onCrash
    }
}

fun String?.toast() {
    if (!this.isNullOrEmpty())
        app.kaizen.tv.ui.KaizenToast.show(this)
}

fun String?.toastLong() {
    if (!this.isNullOrEmpty())
        app.kaizen.tv.ui.KaizenToast.show(this, longDuration = true)
}

fun LifecycleOwner.doOnUI(enableLog: Boolean = true, onLog: (text: String) -> Unit = {}, func: suspend () -> Unit) {
    lifecycleScope.launch(Dispatchers.Main) {
        noCrashSuspend(enableLog) {
            func()
        }?.also { onLog(it) }
    }
}

fun doOnUIGlobal(enableLog: Boolean = true, onLog: (text: String) -> Unit = {}, func: suspend () -> Unit) {
    AppScope.launch(Dispatchers.Main) {
        noCrashSuspend(enableLog) {
            func()
        }?.also { onLog(it) }
    }
}

fun <T> MutableList<T>.removeAll(vararg elements: Collection<T>) {
    elements.forEach {
        removeAll(it)
    }
}

fun NotificationCompat.Builder.create(func: NotificationCompat.Builder.() -> Unit): NotificationCompat.Builder {
    this.func()
    return this
}

fun ViewGroup.inflate(@LayoutRes layout: Int, attachToRoot: Boolean = false, context: Context = this.context): View = LayoutInflater.from(context).inflate(layout, this, attachToRoot)

fun inflate(context: Context, @LayoutRes layout: Int, attachToRoot: Boolean = false): View = LayoutInflater.from(context).inflate(layout, null, attachToRoot)

fun String.r(from: String, to: String) = replace(from, to)

fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) {
            return context
        }
        context = context.baseContext
    }
    return null
}

fun jsoupCookies(url: String?, followRedirects: Boolean = true): Connection =
        Jsoup.connect(url.orEmpty())
                .cookies(BypassUtil.cookiesFor(url))
                .userAgent(BypassUtil.userAgent)
                .timeout(KaizenSettings.timeoutTime.seconds.toInt(DurationUnit.MILLISECONDS))
                .followRedirects(followRedirects)

private fun isIntentResolved(ctx: Context, intent: Intent): Boolean {
    return ctx.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
}

val isMIUI: Boolean by lazy {
    Build.MANUFACTURER.lowercase().contains("huawei") ||
            Build.BRAND.contains("huawei") ||
            isIntentResolved(
                safeContext,
                Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT)
            ) ||
            isIntentResolved(
                safeContext,
                Intent().setComponent(
                    ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                )
            )
            || isIntentResolved(
        safeContext, Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST").addCategory(Intent.CATEGORY_DEFAULT)
    )
            || isIntentResolved(
        safeContext,
        Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")
        )
    )
}