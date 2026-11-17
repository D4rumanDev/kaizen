package app.kaizen.commons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** La calidad automática de las portadas: poca memoria manda, después la red medida. */
class ImageQualityTest {
    @Test fun `poca memoria elige ligera aunque la red no sea medida`() = assertEquals(ImageQuality.Level.LIGHT, ImageQuality.pick(lowMemory = true, metered = false))
    @Test fun `red medida elige equilibrada`() = assertEquals(ImageQuality.Level.BALANCED, ImageQuality.pick(lowMemory = false, metered = true))
    @Test fun `sin limites elige alta`() = assertEquals(ImageQuality.Level.HIGH, ImageQuality.pick(lowMemory = false, metered = false))

    @Test fun `solo la alta compara portadas de otras fuentes y la ligera baja el ancho`() {
        assertTrue(ImageQuality.Level.HIGH.comparesCovers)
        assertFalse(ImageQuality.Level.BALANCED.comparesCovers || ImageQuality.Level.LIGHT.comparesCovers)
        assertTrue(ImageQuality.Level.LIGHT.widthScale < ImageQuality.Level.BALANCED.widthScale)
    }
}
