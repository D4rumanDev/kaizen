package app.kaizen.commons

import android.content.ComponentCallbacks2
import app.kaizen.commons.MemoryBudget.Action
import app.kaizen.commons.MemoryBudget.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryBudgetTest {
    // RAM real de las dos teles (de /proc/meminfo, en kB): la TV de 32 bits y el tvbox.
    private val tv = 1_993_356L * 1024
    private val tvbox = 3_016_424L * 1024

    @Test fun `la TV va justa de RAM y el tvbox no`() {
        assertEquals(Tier.TIGHT, MemoryBudget.tierOf(tv))
        assertEquals(Tier.NORMAL, MemoryBudget.tierOf(tvbox))
        assertEquals(Tier.NORMAL, MemoryBudget.tierOf(Long.MAX_VALUE))
    }

    @Test fun `la cache de imagenes de fabrica es la minima en un equipo justo`() {
        assertEquals(10, MemoryBudget.imageCachePercent(Tier.TIGHT))
        assertEquals(20, MemoryBudget.imageCachePercent(Tier.NORMAL))
    }

    @Test fun `el valor de fabrica de la cache es siempre una opcion del ajuste`() {
        Tier.values().forEach { assertTrue(MemoryBudget.imageCachePercent(it) in KaizenSettings.IMAGE_MEMORY_OPTIONS) }
    }

    @Test fun `el bufer de video tiene tope solo en un equipo justo y cabe con holgura en el heap`() {
        assertEquals(MemoryBudget.TIGHT_PLAYER_BUFFER_BYTES, MemoryBudget.playerBufferBytes(Tier.TIGHT))
        assertEquals(MemoryBudget.NO_CAP, MemoryBudget.playerBufferBytes(Tier.NORMAL))
        // 48 MB es menos de un tercio de los 192 MB de heap que da Android a la app en la TV.
        assertTrue(MemoryBudget.TIGHT_PLAYER_BUFFER_BYTES < 192L * 1024 * 1024 / 3)
    }

    @Test fun `con la interfaz oculta se vacia la cache de imagenes`() {
        assertEquals(Action.CLEAR, MemoryBudget.actionFor(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
    }

    @Test fun `lo que Coil ya hace por su cuenta no se repite`() {
        // Coil vacía con 40 o más y recorta a la mitad entre 10 y 19.
        listOf(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, ComponentCallbacks2.TRIM_MEMORY_MODERATE, ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, 0)
            .forEach { assertEquals(Action.NONE, MemoryBudget.actionFor(it)) }
    }
}
