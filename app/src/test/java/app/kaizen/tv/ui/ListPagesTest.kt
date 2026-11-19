package app.kaizen.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListPagesTest {
    @Test fun `la ventana crece una tanda sin pasar del total`() {
        assertEquals(120, ListPages.grown(60, 60, 6465))
        assertEquals(6465, ListPages.grown(6420, 60, 6465))
        assertEquals(50, ListPages.grown(50, 60, 50))
    }

    @Test fun `pide mas cuando la ultima tarjeta visible esta cerca del final`() {
        // 61 elementos en la rejilla (60 tarjetas y el pie): con margen 15 pide desde el elemento 46.
        assertFalse(ListPages.needsMore(lastVisible = 30, gridItems = 61, canGrow = true))
        assertTrue(ListPages.needsMore(lastVisible = 46, gridItems = 61, canGrow = true))
        assertTrue(ListPages.needsMore(lastVisible = 60, gridItems = 61, canGrow = true))
    }

    @Test fun `no pide mas si ya se muestra todo o la rejilla esta vacia`() {
        assertFalse(ListPages.needsMore(lastVisible = 60, gridItems = 61, canGrow = false))
        assertFalse(ListPages.needsMore(lastVisible = 0, gridItems = 0, canGrow = true))
    }

    @Test fun `la decision no depende de cuantas cabeceras haya antes de la lista`() {
        // El historial lleva «Seguir viendo» y su titulo delante: la rejilla tiene mas elementos, pero lo que importa es la distancia al final.
        assertTrue(ListPages.needsMore(lastVisible = 70, gridItems = 80, canGrow = true))
        assertFalse(ListPages.needsMore(lastVisible = 40, gridItems = 80, canGrow = true))
    }

    @Test fun `la clave de reinicio cambia cuando cambia un campo del orden aunque sea la misma instancia`() {
        val state = ListSortState(ListSort.ORIGINAL, false)
        val before = sortResetKey(state)
        assertEquals(before, sortResetKey(state))
        state.choose(ListSort.NAME)
        val named = sortResetKey(state)
        assertNotEquals(before, named)
        state.filter = 2
        assertNotEquals(named, sortResetKey(state))
    }
}
