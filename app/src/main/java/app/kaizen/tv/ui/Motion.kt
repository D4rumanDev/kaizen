package app.kaizen.tv.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import app.kaizen.commons.KaizenSettings

/** Animaciones suaves de la interfaz: una sola opción (Configuración › Pantalla › Animaciones) fija su velocidad o las desactiva. */
internal object Motion {
    /** Duración con la velocidad elegida; 0 con las animaciones desactivadas. */
    fun ms(base: Int): Int = (base * KaizenSettings.animationScale).toInt()

    /** Animación de [base] ms a velocidad normal; con las animaciones desactivadas el cambio es instantáneo. */
    fun <T> spec(base: Int = 300): FiniteAnimationSpec<T> = if (KaizenSettings.animationScale <= 0f) snap() else tween(ms(base))
}
