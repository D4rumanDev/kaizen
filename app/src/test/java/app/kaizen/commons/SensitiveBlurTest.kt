package app.kaizen.commons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveBlurTest {
    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun red(p: Int) = (p shr 16) and 0xFF

    @Test fun `una imagen de un solo color no cambia`() {
        val px = IntArray(20 * 30) { gray(120) }
        boxBlur(px, 20, 30, 4)
        assertTrue(px.all { it == gray(120) })
    }

    @Test fun `un bloque blanco sobre negro se reparte en vez de quedar como un cuadro`() {
        val w = 41
        val px = IntArray(w * w) { gray(0) }
        for (y in 16..24) for (x in 16..24) px[y * w + x] = gray(255)
        val before = px.sumOf { red(it) }
        repeat(3) { boxBlur(px, w, w, 3) }
        val centre = red(px[20 * w + 20])
        // El centro ya no es blanco puro, la luz llega fuera del bloque y la caída es gradual (no un escalón).
        assertTrue("centro=$centre", centre in 150..254)
        val edge = red(px[20 * w + 25])
        val outside = red(px[20 * w + 29])
        assertTrue("borde=$edge", edge in 1 until centre)
        assertTrue("fuera=$outside", outside in 1 until edge)
        // La luz total se conserva salvo el redondeo a enteros de cada pasada (menos del 15 %).
        val total = px.sumOf { red(it) }
        assertTrue("total=$total antes=$before", total in (before * 85 / 100)..before)
    }

    @Test fun `los bordes no se oscurecen`() {
        val px = IntArray(10 * 10) { gray(200) }
        repeat(3) { boxBlur(px, 10, 10, 3) }
        assertEquals(gray(200), px[0])
        assertEquals(gray(200), px[99])
    }

    @Test fun `un radio cero no toca nada y el radio crece con el ancho`() {
        val px = IntArray(16) { gray(it * 10) }
        val copy = px.copyOf()
        boxBlur(px, 4, 4, 0)
        assertTrue(px.contentEquals(copy))
        assertEquals(8, blurRadius(96))
        assertEquals(2, blurRadius(10))
    }
}
