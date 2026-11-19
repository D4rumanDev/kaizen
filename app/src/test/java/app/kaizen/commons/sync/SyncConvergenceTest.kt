package app.kaizen.commons.sync

import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Varios dispositivos simulados (con la lógica real de [Removals.step]) que añaden, quitan y sincronizan en cualquier orden: al final todos tienen lo mismo, lo quitado no
 * vuelve y lo que nadie quitó sigue. El «servidor» es un mapa con un fichero por dispositivo, como en [DeviceSync].
 */
class SyncConvergenceTest {
    /** La copia que sube un dispositivo: lo que tiene, sus lápidas y cuándo. */
    private class Copy(val createdAt: Long, val items: Set<String>, val removed: Map<String, Long>)

    private class Server { val files = HashMap<String, Copy>() }

    private class Sim(val id: String, val server: Server, val clock: () -> Long) : Removals.Local<Copy> {
        val items = HashSet<String>()
        val changed = HashMap<String, Long>() // hora del último cambio de cada clave que la lleva (estados y progreso)
        var state = Removals.State()
        var keptNewer = 0 // lápidas ajenas retenidas en la última sincronización

        override suspend fun presence() = object : Removals.Presence {
            val snapshot = items.toSet()
            override val keys get() = snapshot
            override fun isPresent(key: String) = key in snapshot
        }
        override suspend fun join(item: Copy) {}
        override suspend fun merge(item: Copy, skip: (String) -> Boolean) { item.items.filterNot(skip).forEach { items += it } }
        override suspend fun remove(keys: List<String>): Int = keys.count { items.remove(it) }
        override fun canon(key: String) = key
        override fun changedAfter(key: String, at: Long) = (changed[key] ?: 0L) > at

        suspend fun sync() {
            val incoming = server.files.filterKeys { it != id }.values.toList()
            val outcome = Removals.step(state, clock(), this, incoming, { it.createdAt }, { it.removed })
            state = outcome.state
            keptNewer = outcome.keptNewer
            server.files[id] = Copy(clock(), items.toSet(), outcome.removed)
        }
    }

    private fun scenario(seed: Int) = runTest {
        val random = Random(seed)
        var time = 1_000_000_000_000L
        val clock = { time += 1000; time }
        val server = Server()
        val devices = listOf("a", "b", "c").map { Sim(it, server, clock) }
        val universe = (1..40).map { "f/a:s$it" }
        val everAdded = HashSet<String>()
        val deleted = HashSet<String>()
        repeat(150) {
            val device = devices.random(random)
            when (random.nextInt(10)) {
                in 0..3 -> universe.filter { it !in everAdded }.randomOrNull(random)?.let { device.items += it; everAdded += it }
                4 -> if (deleted.size < 8) device.items.filter { it !in deleted }.randomOrNull(random)?.let { device.items -= it; deleted += it }
                else -> device.sync()
            }
        }
        // Al final cada dispositivo sincroniza, dos vueltas: lo que había por llegar llega.
        repeat(3) { devices.forEach { it.sync() } }
        val expected = everAdded - deleted
        devices.forEach { assertEquals("dispositivo ${it.id} con semilla $seed", expected, it.items.toSet()) }
    }

    @Test fun `tres dispositivos con altas y bajas en cualquier orden acaban iguales`() { (1..60).forEach(::scenario) }

    @Test fun `un dispositivo con una copia vieja no devuelve lo que se quito`() = runTest {
        var time = 1_000L
        val clock = { time += 10; time }
        val server = Server()
        val a = Sim("a", server, clock); val b = Sim("b", server, clock)
        a.items += "f/a:x"; a.sync(); b.sync()
        assertTrue("f/a:x" in b.items)
        b.items -= "f/a:x"; b.sync() // b lo quita y lo anota
        // a aún no ha sincronizado: su copia en el servidor es anterior a la lápida
        b.sync()
        assertFalse("f/a:x" in b.items)
        a.sync()
        assertFalse("f/a:x" in a.items)
        b.sync(); a.sync()
        assertFalse("f/a:x" in a.items || "f/a:x" in b.items)
    }

    @Test fun `lo que se anade despues no lo quita una lapida vieja de otro dispositivo`() = runTest {
        var time = 1_000L
        val clock = { time += 10; time }
        val server = Server()
        val a = Sim("a", server, clock); val b = Sim("b", server, clock)
        a.sync(); b.sync() // ambos con base vacía
        b.items += "f/a:y"; b.items -= "f/a:y"; b.sync() // algo que b añadió y quitó sin llegar a subirse
        a.items += "f/a:y"; a.sync()
        a.sync(); b.sync()
        assertTrue("f/a:y" in a.items) // a lo añadió después: no estaba en su base, no se le quita
    }

    @Test fun `un capitulo quitado de Seguir viendo no vuelve desde el otro dispositivo`() = runTest {
        var time = 1_000L
        val clock = { time += 10; time }
        val server = Server()
        val a = Sim("a", server, clock); val b = Sim("b", server, clock)
        val key = Removals.progressKey("animeflv:serie", 3.0)
        a.items += key; b.items += key; a.sync(); b.sync(); a.sync() // los dos lo tienen en curso y lo saben
        a.items -= key; a.sync() // se quita de «Seguir viendo» en a
        b.sync() // b aún lo tiene y sube su copia
        a.sync()
        assertFalse("volvió a a desde b", key in a.items)
        b.sync(); a.sync()
        assertFalse("sigue en b", key in b.items)
        assertFalse("volvió a a", key in a.items)
    }

    @Test fun `un estado cambiado despues de la lapida de otro dispositivo no se quita`() = runTest {
        var time = 1_000L
        val clock = { time += 10; time }
        val server = Server()
        val a = Sim("a", server, clock); val b = Sim("b", server, clock)
        val key = Removals.statusKey("animeflv:serie")
        a.items += key; b.items += key; a.sync(); b.sync(); a.sync()
        a.items -= key; a.sync() // a quita el estado y lo anota
        b.changed[key] = clock() // b lo cambia después (por ejemplo de Pendiente a Viendo) sin haber sincronizado
        b.sync() // b lee la lápida de a: su cambio es más reciente y se queda
        assertEquals("b no cuenta la lápida retenida", 1, b.keptNewer)
        a.sync(); b.sync(); a.sync()
        assertEquals("una lápida ya vista no se vuelve a contar", 0, b.keptNewer)
        assertTrue("b perdió su cambio más reciente", key in b.items)
        assertTrue("a no recibió el estado más reciente de b", key in a.items)
    }

    @Test fun `un estado quitado y no tocado despues si se quita en el otro`() = runTest {
        var time = 1_000L
        val clock = { time += 10; time }
        val server = Server()
        val a = Sim("a", server, clock); val b = Sim("b", server, clock)
        val key = Removals.statusKey("animeflv:serie")
        a.items += key; b.items += key; b.changed[key] = clock(); a.sync(); b.sync(); a.sync()
        a.items -= key; a.sync()
        b.sync(); a.sync(); b.sync()
        assertFalse(key in a.items || key in b.items)
    }

    @Test fun `el registro dice cuantas bajas se retuvieron y cuanto se sustituyo`() {
        assertEquals(null, SyncManager.syncDetails(0, 0, 0))
        assertEquals("Sincronización de estados: 1 baja de otro dispositivo no se aplicó porque aquí se cambió después",
            SyncManager.syncDetails(1, 0, 0))
        assertEquals("Sincronización de estados: 2 bajas de otro dispositivo no se aplicaron porque aquí se cambió después; " +
            "3 estados de serie se sustituyeron por uno más reciente; 1 capítulo en curso se sustituyó por uno más reciente", SyncManager.syncDetails(2, 3, 1))
    }

    @Test fun `las claves de capitulos en curso se leen y se cuentan aparte`() {
        val parsed = Removals.parse(Removals.progressKey("animeflv:serie", 3.0))!!
        assertTrue(parsed.progress); assertFalse(parsed.favorite)
        assertEquals("animeflv", parsed.source); assertEquals("serie", parsed.slug); assertEquals(3.0, parsed.number!!, 0.0)
        assertFalse(Removals.parse(Removals.recordKey("animeflv:serie", 3.0))!!.progress)
        // Quitar casi todo lo que está en curso es un borrado masivo aunque haya miles de capítulos vistos.
        val inProgress = (1..20).map { Removals.progressKey("a:s$it", 1.0) }
        val seen = (1..5000).map { Removals.recordKey("a:s$it", 1.0) }
        assertTrue(Removals.massiveAny(inProgress.take(15), inProgress + seen))
        assertFalse(Removals.massiveAny(inProgress.take(3), inProgress + seen))
    }
}
