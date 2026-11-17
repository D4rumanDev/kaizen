package app.kaizen.tv

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import app.kaizen.R
import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.UiScale

/** Base de las actividades de TV: aplica el tamaño de la interfaz de Ajustes. El contenido lo pone cada una con Compose. */
open class TVBaseActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(UiScale.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Solo en aperturas reales: recreate() (Ajustes › escala) trae un estado guardado y no debe dejar un fundido pendiente.
        if (savedInstanceState == null) screenTransition(open = true)
    }

    override fun finish() {
        super.finish()
        screenTransition(open = false)
    }

    /** Fundido al abrir y cerrar pantallas, con la velocidad de Ajustes › Interfaz › Animaciones (0 = sin animación). */
    @Suppress("DEPRECATION")
    private fun screenTransition(open: Boolean) {
        val (fadeIn, fadeOut) = when (KaizenSettings.animationLevel) {
            1 -> R.anim.screen_in_fast to R.anim.screen_out_fast
            3 -> R.anim.screen_in_slow to R.anim.screen_out_slow
            2 -> R.anim.screen_in_normal to R.anim.screen_out_normal
            else -> 0 to 0
        }
        // Al abrir entra la nueva con fundido; al cerrar se desvanece la que se va y la de debajo ya está.
        if (open) overridePendingTransition(fadeIn, 0) else overridePendingTransition(0, fadeOut)
    }
}
