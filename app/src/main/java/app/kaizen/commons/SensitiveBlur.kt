package app.kaizen.commons

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation

/**
 * Desenfoque de las carátulas del modo parental. No usa `Modifier.blur` (exige Android 12; la TV es Android 9): reduce la imagen a unos 20 píxeles de ancho y la vuelve a ampliar
 * con filtro, y el resultado es una mancha de colores sin detalle. Como transformación de Coil, lo que se guarda en memoria ya va desenfocado.
 */
object SensitiveBlur : Transformation() {
    override val cacheKey: String = "sensitive-blur-v1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width.coerceAtLeast(1)
        val h = input.height.coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(input, (w / 16).coerceAtLeast(2), (h / 16).coerceAtLeast(2), true)
        val blurred = Bitmap.createScaledBitmap(small, w, h, true)
        if (small !== input && small !== blurred) small.recycle()
        return blurred
    }
}
