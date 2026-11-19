package app.kaizen.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ListPagesTest {
    @Test fun `una lista vacia o corta es una sola pagina`() {
        assertEquals(1, ListPages.count(0, 60))
        assertEquals(1, ListPages.count(60, 60))
        assertEquals(2, ListPages.count(61, 60))
    }

    @Test fun `el historial largo se parte en paginas del tamano pedido`() {
        val all = (1..6465).toList()
        assertEquals(108, ListPages.count(all.size, 60))
        assertEquals((1..60).toList(), ListPages.slice(all, 0, 60))
        assertEquals((6421..6465).toList(), ListPages.slice(all, 107, 60))
        assertEquals(45, ListPages.slice(all, 107, 60).size)
    }

    @Test fun `una pagina fuera de rango se lleva a la ultima o a la primera`() {
        val all = (1..130).toList()
        assertEquals(ListPages.slice(all, 2, 60), ListPages.slice(all, 99, 60))
        assertEquals(ListPages.slice(all, 0, 60), ListPages.slice(all, -3, 60))
    }

    @Test fun `todas las paginas juntas son la lista entera sin repetir`() {
        val all = (1..257).toList()
        val pages = ListPages.count(all.size, 40)
        assertEquals(all, (0 until pages).flatMap { ListPages.slice(all, it, 40) })
    }

    @Test fun `sin elementos la pagina esta vacia`() {
        assertEquals(emptyList<Int>(), ListPages.slice(emptyList<Int>(), 0, 60))
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
