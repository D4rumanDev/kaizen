package app.kaizen.tv.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.BringIntoViewSpec
import app.kaizen.commons.KaizenSettings

/** Rigidez del muelle con la velocidad normal de las animaciones: algo mayor que la de Compose (400), que a golpes de mando seguidos iba por detrás del foco. */
private const val SMOOTH_SCROLL_STIFFNESS = 700f

/**
 * Cómo se desplazan las listas y rejillas al mover el foco: lo justo para dejar a la vista el elemento enfocado (nada si ya se ve entero) y con un muelle sin rebote.
 * Un muelle y no una duración fija: con el mando se pulsa varias veces seguidas y cada pulsación cambia el destino a mitad de camino; el muelle conserva la velocidad que
 * llevaba y sigue sin tirones, mientras que una animación de duración fija arranca de cero en cada pulsación. Sigue la velocidad de animaciones de Configuración
 * (la rigidez baja con el cuadrado del tiempo: el doble de lento es la cuarta parte de rigidez; con las animaciones desactivadas, salto directo). [margin] deja un hueco entre el elemento y el borde.
 */
internal fun smoothScroll(margin: Float = 0f): BringIntoViewSpec = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float>
        get() {
            val scale = KaizenSettings.animationScale
            // Sin animaciones (o con un valor que no sea un número positivo): salto directo, nunca una rigidez inválida, que haría fallar al muelle.
            return if (!scale.isFinite() || scale <= 0f) snap() else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = SMOOTH_SCROLL_STIFFNESS / (scale * scale))
        }

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = scrollDistance(offset, size, containerSize, margin)
}

/**
 * Cuánto desplazar para dejar a la vista un elemento que empieza en [offset], mide [size] y está en un contenedor de [containerSize] (positivo: avanzar). Se considera «a la vista»
 * solo lo que deja [margin] libre en los dos bordes: un elemento pegado al borde, con su foco ampliado y su sombra, se veía recortado.
 *
 * Un elemento **más alto que el espacio disponible** nunca puede verse entero, y tratarlo como a los demás no tiene solución estable: si se alinea su final, su comienzo queda fuera y
 * pide subir; si se alinea el comienzo, es el final el que queda fuera y pide bajar. Cada petición (y el propio borde del foco cambia el tamaño) alterna una y otra, y la lista
 * «vibra». Para esos elementos el criterio es otro y tiene un punto fijo: lo que importa es que **cubran la zona visible**; si empiezan dentro de ella se sube su comienzo al borde de
 * arriba, y si terminan dentro, se baja su final al de abajo. Una vez cubriéndola no se mueve más. Lo que no se ve de un bloque así hay que partirlo en partes enfocables más cortas.
 */
internal fun scrollDistance(offset: Float, size: Float, containerSize: Float, margin: Float): Float {
    val trailing = offset + size
    val tall = size > containerSize - 2 * margin
    return when {
        !tall && offset >= margin && trailing <= containerSize - margin -> 0f
        !tall && offset < margin -> offset - margin
        !tall -> trailing - containerSize + margin
        offset <= margin && trailing >= containerSize - margin -> 0f
        offset > margin -> offset - margin
        else -> trailing - (containerSize - margin)
    }
}
