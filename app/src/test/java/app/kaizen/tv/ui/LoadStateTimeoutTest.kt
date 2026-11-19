package app.kaizen.tv.ui

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class LoadStateTimeoutTest {
    @Test fun `una carga que pasa del limite queda como fallo en vez de esperar sin fin`() = runBlocking {
        val state = LoadState(50.milliseconds) { delay(5_000); "tarde" }
        state.start()
        assertTrue(state.state.value is Loadable.Failed)
    }

    @Test fun `una carga dentro del limite da su dato`() = runBlocking {
        val state = LoadState(2_000.milliseconds) { "a tiempo" }
        state.start()
        assertEquals(Loadable.Ready("a tiempo"), state.state.value)
    }

    @Test fun `sin limite se espera lo que haga falta`() = runBlocking {
        val state = LoadState<String>(null) { delay(30); "lento pero llega" }
        state.start()
        assertEquals(Loadable.Ready("lento pero llega"), state.state.value)
    }
}
