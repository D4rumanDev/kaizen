package app.kaizen.commons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.days

/**
 * La lógica del progreso guardado ([ProgressMap]) sin Android: claves por serie («s:»), por ficha («p:») y su hora («t:»), y la sincronización entre dos dispositivos
 * que tienen ids de serie distintos para la misma ficha (los ids son locales; lo que viaja es la ficha).
 */
class ProgressMapTest {
    private val now = 1_800_000_000_000L

    /** Un dispositivo: su mapa de progreso y los ids locales que da a cada ficha. */
    private class Device(val ids: Map<String, Long>, val now: Long) {
        val entries = HashMap<String, String>()
        fun map() = ProgressMap(entries) { now }
        fun put(ficha: String, number: Double, value: String, at: Long) = map().store(KaizenProgress.Key(ids.getValue(ficha), number).text, value, at)
        fun export() = map().exportPortable { id -> ids.entries.firstOrNull { it.value == id }?.key?.let { it.substringBefore(':') to it.substringAfter(':') } }

        /** Lo que hace la sincronización con la copia de otro: añade lo que falta y lo pasa a su serie (gana el más reciente). */
        fun receive(other: Device): ProgressMap.Moved {
            val map = map()
            map.importMissing(other.export())
            return map.migrateFichaKeys { source, slug -> ids["$source:$slug"] ?: 0L }
        }

        fun valueOf(ficha: String, number: Double) = entries[KaizenProgress.Key(ids.getValue(ficha), number).text]
    }

    private fun pair(): Pair<Device, Device> = Device(mapOf("animeflv:serie" to 1L), now) to Device(mapOf("animeflv:serie" to 7L), now)

    @Test fun `el mas reciente gana en los dos dispositivos aunque tengan ids distintos`() {
        val (a, b) = pair()
        a.put("animeflv:serie", 3.0, "600000/1400000", at = now - 2000)
        b.put("animeflv:serie", 3.0, "300000/1400000", at = now - 1000)
        val inB = b.receive(a)
        assertEquals("b conserva el suyo, más reciente", 0, inB.replaced)
        val inA = a.receive(b)
        assertEquals(1, inA.replaced)
        assertEquals("300000/1400000", a.valueOf("animeflv:serie", 3.0))
        assertEquals("300000/1400000", b.valueOf("animeflv:serie", 3.0))
        // La hora viaja con el valor: a queda con la de b.
        assertEquals(now - 1000, a.map().timeOf("s:1#3"))
    }

    @Test fun `con la misma hora gana la posicion mayor en los dos`() {
        val (a, b) = pair()
        a.put("animeflv:serie", 3.0, "600000/1400000", at = now - 1000)
        b.put("animeflv:serie", 3.0, "300000/1400000", at = now - 1000)
        b.receive(a); a.receive(b)
        assertEquals("600000/1400000", a.valueOf("animeflv:serie", 3.0))
        assertEquals("600000/1400000", b.valueOf("animeflv:serie", 3.0))
    }

    @Test fun `un empate total de hora y posicion acaba igual en los dos`() {
        val (a, b) = pair()
        a.put("animeflv:serie", 3.0, "600000/1400000", at = now - 1000)
        b.put("animeflv:serie", 3.0, "600000/1450000", at = now - 1000) // otra duración (otro vídeo del mismo capítulo)
        b.receive(a); a.receive(b); b.receive(a)
        assertEquals(a.valueOf("animeflv:serie", 3.0), b.valueOf("animeflv:serie", 3.0))
    }

    @Test fun `sin hora en ninguno se vale la posicion mayor y converge`() {
        val (a, b) = pair()
        a.put("animeflv:serie", 3.0, "600000/1400000", at = 0L)
        b.put("animeflv:serie", 3.0, "300000/1400000", at = 0L)
        b.receive(a); a.receive(b)
        assertEquals("600000/1400000", a.valueOf("animeflv:serie", 3.0))
        assertEquals("600000/1400000", b.valueOf("animeflv:serie", 3.0))
    }

    @Test fun `una ficha que este dispositivo no conoce se queda por ficha y no se pierde`() {
        val a = Device(mapOf("animeflv:serie" to 1L), now)
        val b = Device(emptyMap(), now) // b aún no sabe de esa serie
        a.put("animeflv:serie", 2.0, "300000/1400000", at = now - 10)
        val moved = b.receive(a)
        assertEquals(0, moved.moved)
        assertEquals("300000/1400000", b.entries["p:animeflv:serie#2"])
        // Y vuelve a salir tal cual hacia otro equipo, con su hora.
        assertEquals("300000/1400000", b.export()["p:animeflv:serie#2"])
        assertEquals((now - 10).toString(), b.export()["t:p:animeflv:serie#2"])
        val (bySeries, byFicha) = b.map().chapters()
        assertTrue(bySeries.isEmpty())
        assertEquals(listOf("animeflv:serie" to 2.0), byFicha)
    }

    @Test fun `el progreso de una serie sin fichas no sale porque su id es local`() {
        val a = Device(emptyMap(), now)
        a.map().store("s:99#1", "300000/1400000", at = now)
        assertTrue(a.export().isEmpty())
    }

    @Test fun `importMissing no pisa lo que hay, ignora valores rotos y marcas y trae la hora`() {
        val entries = hashMapOf("p:a:x#1" to "1000/2000")
        val map = ProgressMap(entries) { now }
        val added = map.importMissing(mapOf("p:a:x#1" to "9000/9999", "p:a:y#1" to "5000/9000", "t:p:a:y#1" to "123", "p:a:z#1" to "basura", "otra" to "1/2"))
        assertEquals(1, added)
        assertEquals("1000/2000", entries["p:a:x#1"])
        assertEquals("123", entries["t:p:a:y#1"])
        assertFalse(entries.containsKey("p:a:z#1"))
        assertTrue("p:a:y#1" in map.touched)
    }

    @Test fun `una hora del futuro de un reloj roto no cuenta como cambio`() {
        val map = ProgressMap(HashMap()) { now }
        map.store("s:5#3", "1000/2000", at = now + 2.days.inWholeMilliseconds)
        assertEquals(0L, map.latestChange(5L, "a:x", 3.0))
        map.store("p:a:x#3", "1000/2000", at = now - 50)
        assertEquals("la ficha cuenta aunque la serie tenga una hora rota", now - 50, map.latestChange(5L, "a:x", 3.0))
        map.store("s:5#3", "1000/2000", at = now + 1.days.inWholeMilliseconds - 1)
        assertEquals("menos de un día adelantado aún cuenta", now + 1.days.inWholeMilliseconds - 1, map.latestChange(5L, "a:x", 3.0))
    }

    @Test fun `quitar un capitulo quita su clave por serie, por ficha y sus horas`() {
        val entries = HashMap<String, String>()
        val map = ProgressMap(entries) { now }
        map.store("s:5#3", "1000/2000")
        map.store("p:a:x#3", "1000/2000")
        map.store("s:5#4", "1000/2000")
        assertTrue(map.removeChapter(5L, listOf("a:x"), 3.0))
        assertEquals(setOf("s:5#4", "t:s:5#4"), entries.keys)
        assertFalse(map.removeChapter(5L, listOf("a:x"), 3.0))
        assertFalse("sin serie conocida solo mira la ficha", ProgressMap(entries) { now }.removeChapter(null, listOf("a:x"), 4.0))
    }

    @Test fun `rekey une el progreso de dos series y gana el mas reciente`() {
        val entries = HashMap<String, String>()
        val map = ProgressMap(entries) { now }
        map.store("s:10#1", "100000/1400000", at = now - 10)
        map.store("s:11#1", "900000/1400000", at = now - 500)
        map.store("s:10#2", "50000/1400000", at = now - 10)
        assertEquals(2, map.rekey { if (it == 10L) 11L else it })
        assertEquals("100000/1400000", entries["s:11#1"])
        assertEquals("50000/1400000", entries["s:11#2"])
        assertNull(entries["s:10#1"])
        assertEquals(0, ProgressMap(entries) { now }.rekey { if (it == 10L) 11L else it })
    }

    @Test fun `migrar dos veces no cambia nada`() {
        val entries = hashMapOf("p:a:x#1" to "1000/2000")
        assertEquals(1, ProgressMap(entries) { now }.migrateFichaKeys { _, _ -> 4L }.moved)
        assertEquals(0, ProgressMap(entries) { now }.migrateFichaKeys { _, _ -> 4L }.moved)
        assertEquals(mapOf("s:4#1" to "1000/2000"), entries)
    }

    @Test fun `exportAll deja fuera las marcas internas y las horas`() {
        val entries = hashMapOf("s:1#1" to "1/2", "t:s:1#1" to "5", "p:a:b#2" to "1/2", "12345" to "1/2", "marca" to "x")
        assertEquals(setOf("s:1#1", "p:a:b#2", "12345"), ProgressMap(entries) { now }.exportAll().keys)
    }
}
