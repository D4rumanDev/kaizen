package app.kaizen.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** El desplazamiento al enfocar: lo que cabe se deja a la vista y lo que no cabe llega a un punto fijo (antes alternaba arriba y abajo y la lista «vibraba»). */
class ScrollDistanceTest {
    private val container = 1000f
    private val margin = 20f

    /** Aplica la petición de desplazamiento como lo hace la lista: el elemento se mueve [distance] hacia arriba. */
    private fun settle(offset: Float, size: Float, maxSteps: Int = 10, container: Float = this.container, margin: Float = this.margin): Pair<Float, Int> {
        var position = offset
        repeat(maxSteps) { step ->
            val distance = scrollDistance(position, size, container, margin)
            if (distance == 0f) return position to step
            position -= distance
        }
        return position to maxSteps
    }

    @Test
    fun `un elemento que cabe y ya se ve no se mueve`() {
        assertEquals(0f, scrollDistance(100f, 300f, container, margin), 0f)
    }

    @Test
    fun `un elemento que cabe se deja a la vista con su margen`() {
        assertEquals(-80f, scrollDistance(-60f, 300f, container, margin), 0f)
        // Termina en 1400: hay que avanzar 400 para llegar al borde y 20 más para dejar el margen.
        assertEquals(420f, scrollDistance(700f, 700f, container, margin), 0f)
    }

    @Test
    fun `un elemento que cabe queda a la vista en un solo paso`() {
        listOf(-500f, -60f, 10f, 100f, 400f, 900f, 1500f).forEach { start ->
            val (end, steps) = settle(start, 300f)
            assertTrue("desde $start", steps <= 1)
            assertTrue("desde $start acaba en $end", end >= margin && end + 300f <= container - margin)
        }
    }

    @Test
    fun `un elemento mas alto que la pantalla llega a un punto fijo desde cualquier posicion`() {
        val size = 1600f
        for (start in listOf(-1500f, -900f, -600f, -100f, 0f, 20f, 50f, 300f, 700f, 1000f, 1800f)) {
            val (end, steps) = settle(start, size)
            assertTrue("desde $start no se estabiliza (pasos: $steps)", steps <= 1)
            // Termina cubriendo la zona visible: ni su comienzo ni su final dejan hueco.
            assertTrue("desde $start acaba en $end", end <= margin && end + size >= container - margin)
        }
    }

    @Test
    fun `un elemento alto que empieza dentro sube su comienzo y uno que termina dentro baja su final`() {
        assertEquals(280f, scrollDistance(300f, 1600f, container, margin), 0f)
        assertEquals(-180f, scrollDistance(-800f, 1600f, container, margin), 0f)
    }

    @Test
    fun `en el limite entre caber y no caber ninguno de los dos casos se mueve`() {
        // 960 = 1000 - 2 * 20: cabe exacto y ya está a la vista, no es alto.
        assertEquals(0f, scrollDistance(20f, 960f, container, margin), 0f)
        // 961: ya no cabe (es alto), pero con su comienzo en el margen cubre la zona visible y tampoco se mueve.
        assertEquals(0f, scrollDistance(20f, 961f, container, margin), 0f)
    }

    @Test
    fun `sin margen la regla es la misma`() {
        assertEquals(0f, scrollDistance(0f, 400f, container, 0f), 0f)
        assertEquals(-50f, scrollDistance(-50f, 400f, container, 0f), 0f)
        assertEquals(300f, scrollDistance(900f, 400f, container, 0f), 0f)
        listOf(-2500f, -1000f, 0f, 300f, 2000f).forEach { start ->
            val (end, steps) = settle(start, 1600f, margin = 0f)
            assertTrue("desde $start (pasos: $steps)", steps <= 1 && end <= 0f && end + 1600f >= container)
        }
    }

    @Test
    fun `con un contenedor menor que sus margenes tambien converge en un paso`() {
        // 30 - 2 * 20 < 0: todo elemento es «alto»; el punto fijo se conserva y no hay división ni oscilación.
        for (start in listOf(-200f, -5f, 10f, 15f, 20f, 60f, 400f)) {
            val (_, steps) = settle(start, 10f, container = 30f)
            assertTrue("desde $start (pasos: $steps)", steps <= 1)
        }
    }
}
