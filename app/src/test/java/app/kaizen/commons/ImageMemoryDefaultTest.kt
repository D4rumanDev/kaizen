package app.kaizen.commons

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageMemoryDefaultTest {
    @Test fun `con poca RAM la cache de imagenes de fabrica es la minima`() {
        // La TV de 32 bits tiene unos 1,99 GB.
        assertEquals(10, KaizenSettings.imageMemoryDefault(1_993_356L * 1024))
        assertEquals(10, KaizenSettings.imageMemoryDefault(1_000_000_000L))
    }

    @Test fun `con la RAM normal se mantiene el 20 por ciento`() {
        // El tvbox tiene unos 3 GB.
        assertEquals(20, KaizenSettings.imageMemoryDefault(3_016_424L * 1024))
        assertEquals(20, KaizenSettings.imageMemoryDefault(Long.MAX_VALUE))
    }

    @Test fun `el valor de fabrica es siempre una de las opciones del ajuste`() {
        assertEquals(true, KaizenSettings.imageMemoryDefault(1_000_000_000L) in KaizenSettings.IMAGE_MEMORY_OPTIONS)
        assertEquals(true, KaizenSettings.imageMemoryDefault(Long.MAX_VALUE) in KaizenSettings.IMAGE_MEMORY_OPTIONS)
    }
}
