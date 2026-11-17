package app.kaizen.source

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Lo que se sabe de una fuente: cuándo se comprobó, cuándo se recorrió entera y cuántas series anunciaba entonces.
 * Se guarda en una sola clave por fuente y se compara con lo que anuncia ahora ([covers]). No detecta una baja y un
 * alta a la vez ni cambios de nota o año; por eso el recorrido completo caduca a los [FULL_WALK_EVERY].
 */
internal data class SourceState(val checkedAt: Long, val walkedAt: Long, val total: Int) {
    // Con la dispersión, cada web cae en su punto del plazo (semilla: su última fecha) y no todas se revisan el mismo día.
    fun due(now: Long) = now - checkedAt >= app.kaizen.commons.Timings.spread(CHECK_EVERY, checkedAt)

    fun covers(announced: Int?, now: Long) = announced == total && now - walkedAt < app.kaizen.commons.Timings.spread(FULL_WALK_EVERY, walkedAt)

    fun encode() = "$checkedAt:$walkedAt:$total"

    companion object {
        /** Cada cuánto se vuelve a comprobar una fuente (si su total cambió, se recorre entera: cientos de peticiones). */
        val CHECK_EVERY: Long get() = app.kaizen.commons.Timings.catalogCheckMs()

        /** Aunque el total no cambie, se recorre entera cada tanto (bajas, notas, años). */
        val FULL_WALK_EVERY: Long get() = app.kaizen.commons.Timings.catalogWalkMs()

        /** Fuente nunca recorrida (o con reglas de unión antiguas). */
        val NEVER = SourceState(0L, 0L, -1)

        fun decode(raw: String?): SourceState {
            val parts = raw?.split(':')?.mapNotNull { it.toLongOrNull() }?.takeIf { it.size == 3 } ?: return NEVER
            // Un total fuera del rango de Int es un valor corrupto, no un recorte silencioso.
            val total = parts[2].takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt() ?: return NEVER
            return SourceState(parts[0], parts[1], total)
        }
    }
}

/**
 * Estados de las fuentes, guardados en unas preferencias: `store[id]` los lee y `store[id] = estado` los escribe. Con
 * reglas de unión distintas a [RULES_VERSION] todo vale [SourceState.NEVER], y la primera escritura borra los estados
 * de antes (también los de fuentes inactivas).
 */
internal class CatalogStateStore(private val preferences: () -> SharedPreferences) {
    companion object {
        /** Subir al cambiar las reglas de unión de series: fuerza regenerar un catálogo hecho con las anteriores. */
        const val RULES_VERSION = 11
        const val KEY_RULES = "rules"
        const val STATE_PREFIX = "src_"                 // src_<idFuente> = SourceState codificado
    }

    private val prefs get() = preferences()
    private val stale get() = prefs.getInt(KEY_RULES, 0) != RULES_VERSION

    operator fun get(sourceId: String): SourceState =
        if (stale) SourceState.NEVER else SourceState.decode(prefs.getString(STATE_PREFIX + sourceId, null))

    /** Olvida todos los estados: el catálogo se recorrerá entero la próxima vez. */
    fun clear() = prefs.edit { clear() }

    operator fun set(sourceId: String, state: SourceState) = prefs.edit {
        if (stale) {
            prefs.all.keys.filter { it != KEY_RULES }.forEach { remove(it) }
            putInt(KEY_RULES, RULES_VERSION)
        }
        putString(STATE_PREFIX + sourceId, state.encode())
    }
}

/** Los estados de la app, en las preferencias `kaizen_catalog`. */
internal val CatalogStates = CatalogStateStore { app.kaizen.commons.KaizenStore.prefs("kaizen_catalog") }
