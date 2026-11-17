package app.kaizen.commons

import android.content.SharedPreferences
import androidx.core.content.edit
import app.kaizen.App
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Cierres por fallo seguidos: si la app se cae [LIMIT] veces sin llegar a funcionar con normalidad, el siguiente arranque
 * restablece ajustes y caché (nunca favoritos, historial ni progreso), por si una configuración rota impide abrirla.
 * Funcionar con normalidad = llevar [HEALTHY_AFTER_MS] abierta; cerrar la app a mano no cuenta como fallo.
 */
class CrashStreak(
    private val preferences: () -> SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis
) {
    companion object {
        const val LIMIT = 3
        const val HEALTHY_AFTER_MS = 30_000L

        /** Dos fallos separados por más tiempo que esto no forman racha (cierres sueltos en días distintos). */
        const val WINDOW_MS = 10L * 60_000
        private const val KEY_STREAK = "streak"
        private const val KEY_NOTICE = "notice"
        private const val KEY_LAST_CRASH = "last_crash"
    }

    private val prefs get() = preferences()
    val count: Int get() = prefs.getInt(KEY_STREAK, 0)

    /** Síncrono (`commit`): el proceso está muriendo y un `apply` podría no llegar a escribirse. */
    fun recordCrash() {
        val now = clock()
        val consecutive = now - prefs.getLong(KEY_LAST_CRASH, 0L) <= WINDOW_MS
        prefs.edit(commit = true) {
            putInt(KEY_STREAK, if (consecutive) count + 1 else 1)
            putLong(KEY_LAST_CRASH, now)
        }
    }

    fun recordHealthy() {
        if (count != 0) prefs.edit { remove(KEY_STREAK) }
    }

    /** true si hay que restablecer; en ese caso olvida la racha y deja un aviso para enseñar al usuario. */
    fun consumeResetRequest(): Boolean {
        if (count < LIMIT) return false
        prefs.edit(commit = true) {
            remove(KEY_STREAK)
            putBoolean(KEY_NOTICE, true)
        }
        return true
    }

    /** true una sola vez tras un restablecimiento automático. */
    fun takeNotice(): Boolean {
        if (!prefs.getBoolean(KEY_NOTICE, false)) return false
        prefs.edit { remove(KEY_NOTICE) }
        return true
    }
}

object CrashGuard {
    private val streak = CrashStreak(preferences = { app.kaizen.commons.KaizenStore.prefs("kaizen_crash_guard") })

    /** Cuenta los fallos no capturados (y sigue con el manejador anterior) y aplica el restablecimiento si toca. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { streak.recordCrash() }
            previous?.uncaughtException(thread, error)
        }
        if (streak.consumeResetRequest()) {
            KaizenSettings.resetToDefaults()
            AppScope.launch { runCatchingCoop { CacheCleaner.clearAll(App.context) }.onFailure(::logError) }
        }
        AppScope.launch {
            delay(CrashStreak.HEALTHY_AFTER_MS)
            streak.recordHealthy()
        }
    }

    fun takeResetNotice(): Boolean = streak.takeNotice()
}
