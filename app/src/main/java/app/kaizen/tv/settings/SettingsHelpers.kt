package app.kaizen.tv.settings

import android.content.Context
import androidx.annotation.DrawableRes
import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.Tuning
import app.kaizen.commons.findActivity
import kotlin.reflect.KMutableProperty0

/**
 * Entradas de Configuración que existen en el código pero aún no se enseñan. Mientras estén apagadas **no se añaden a la lista**:
 * así no hay filas enfocables vacías. Se encienden cuando su lógica esté hecha.
 */
internal object SettingsFlags {
    /** «Contenido sensible» (modo parental con carátulas en blur), en Pantalla. */
    const val SENSITIVE = false

    /** «Actualizaciones» (comprobar y avisar de versiones nuevas), en Acerca de. */
    const val UPDATES = false
}

/** Elección de un ajuste de medida: al cambiarla se reparte a los módulos que no leen preferencias ([Tuning]). */
internal fun <T> tunedChoice(title: String, values: List<T>, property: KMutableProperty0<T>, label: (T) -> String) =
    ChoiceItem(title, values.map(label), { values.indexOf(property.get()).coerceAtLeast(0) }) { position ->
        property.set(values[position])
        Tuning.apply()
    }

/** Tamaño y separación de las tarjetas: las rejillas no leen el ajuste como estado, así que se recrea la pantalla para verlo al momento (como «Tamaño de la interfaz»). */
internal fun cardChoice(context: Context, title: String, values: List<Float>, property: KMutableProperty0<Float>) =
    ChoiceItem(title, values.map(KaizenSettings::formatFactor), { values.indexOf(property.get()) }) { position ->
        if (values[position] != property.get()) {
            property.set(values[position])
            context.findActivity()?.recreate()
        }
    }

internal fun minutesLabel(minutes: Int) = if (minutes < 60) "$minutes min" else "${minutes / 60} h"

/** Un submenú de un nivel con su resumen (el valor actual): es la única forma de agrupar en los paneles de Configuración. */
internal fun submenu(title: String, @DrawableRes icon: Int, summary: () -> String, items: () -> List<SettingItem>): SubMenuItem =
    SubMenuItem(title, summary, SettingsCategory(title, icon, items))
