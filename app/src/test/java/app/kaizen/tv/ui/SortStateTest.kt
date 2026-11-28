package app.kaizen.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SortStateTest {
    @Test
    fun `el orden elegido se guarda y se recupera igual`() {
        val state = ListSortState(ListSort.SCORE, true, 2, true, SeenFilter.UNSEEN)
        val back = decodeListSort(state.encode())!!
        assertEquals(ListSort.SCORE, back.sort)
        assertTrue(back.descending)
        assertEquals(2, back.filter)
        assertTrue(back.favoritesOnly)
        assertEquals(SeenFilter.UNSEEN, back.seen)
    }

    @Test
    fun `lo vacio o estropeado no se entiende y vale el orden de fabrica`() {
        listOf("", "x", "1,2,3", "99,false,-1,false,0", "0,quizas,-1,false,0", "0,false,-1,false,9").forEach { assertNull(it, decodeListSort(it)) }
    }
}
