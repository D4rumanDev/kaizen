package app.kaizen.commons

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation

/**
 * Desenfoque de las carátulas del modo parental. No usa `Modifier.blur` (exige Android 12; la TV es Android 9) ni RenderScript (en desuso): la imagen se reduce a unos
 * [WORK_WIDTH] píxeles de ancho, se le pasan tres desenfoques de caja seguidos (que se parecen a uno gaussiano) y se amplía con filtro. Antes solo se reducía a 20 píxeles y se
 * volvía a ampliar, y el resultado eran cuadros de colores (pixelación), no un desenfoque. Como transformación de Coil, lo que se guarda en memoria ya va desenfocado.
 */
class SensitiveBlur private constructor(private val level: Int) : Transformation() {
    // v3: la intensidad se elige en Configuración y va en la clave, para no reutilizar en la caché de imágenes lo desenfocado con otra.
    override val cacheKey: String = "sensitive-blur-v3-$level"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width.coerceAtLeast(1)
        val h = input.height.coerceAtLeast(1)
        val sw = minOf(w, WORK_WIDTH).coerceAtLeast(2)
        val sh = (h.toLong() * sw / w).toInt().coerceAtLeast(2)
        val small = Bitmap.createScaledBitmap(input, sw, sh, true)
        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        repeat(PASSES) { boxBlur(pixels, sw, sh, blurRadius(sw, level)) }
        val soft = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        soft.setPixels(pixels, 0, sw, 0, 0, sw, sh)
        val blurred = Bitmap.createScaledBitmap(soft, w, h, true)
        if (small !== input && small !== blurred) small.recycle()
        if (soft !== blurred) soft.recycle()
        return blurred
    }

    companion object {
        private const val WORK_WIDTH = 96
        private const val PASSES = 3
        private val levels = List(KaizenSettings.BLUR_LEVEL_OPTIONS.size) { SensitiveBlur(it) }

        /** La transformación de la intensidad elegida en Configuración (índice de [KaizenSettings.BLUR_LEVEL_OPTIONS]). */
        fun of(level: Int): SensitiveBlur = levels[level.coerceIn(0, levels.lastIndex)]
    }
}

/** Divisores del ancho para cada intensidad (suave, media, fuerte): con la media, una doceava parte, para que no se reconozca la imagen y sí queden manchas de color suaves. */
private val RADIUS_DIVISORS = intArrayOf(24, 12, 6)

/** Radio del desenfoque para una imagen de [width] píxeles de ancho con la intensidad [level] (la media por defecto). */
internal fun blurRadius(width: Int, level: Int = 1): Int = (width / RADIUS_DIVISORS[level.coerceIn(0, RADIUS_DIVISORS.lastIndex)]).coerceAtLeast(2)

/**
 * Desenfoque de caja de [pixels] (ARGB, [width] × [height], por filas) con ventana de 2·[radius]+1: primero por filas y luego por columnas. En los bordes repite el píxel
 * del borde, así que no se oscurece. Recorre cada línea una sola vez (ventana deslizante), sin depender del radio.
 */
internal fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
    require(pixels.size >= width * height) { "faltan píxeles" }
    if (radius <= 0 || width <= 0 || height <= 0) return
    val line = IntArray(maxOf(width, height))
    for (y in 0 until height) blurLine(pixels, y * width, 1, width, radius, line)
    for (x in 0 until width) blurLine(pixels, x, width, height, radius, line)
}

private fun blurLine(px: IntArray, start: Int, stride: Int, count: Int, radius: Int, out: IntArray) {
    val window = 2 * radius + 1
    var a = 0; var r = 0; var g = 0; var b = 0
    for (i in -radius..radius) {
        val p = px[start + i.coerceIn(0, count - 1) * stride]
        a += p ushr 24; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
    }
    for (i in 0 until count) {
        out[i] = ((a / window) shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
        val add = px[start + (i + radius + 1).coerceAtMost(count - 1) * stride]
        val remove = px[start + (i - radius).coerceAtLeast(0) * stride]
        a += (add ushr 24) - (remove ushr 24)
        r += ((add shr 16) and 0xFF) - ((remove shr 16) and 0xFF)
        g += ((add shr 8) and 0xFF) - ((remove shr 8) and 0xFF)
        b += (add and 0xFF) - (remove and 0xFF)
    }
    for (i in 0 until count) px[start + i * stride] = out[i]
}
