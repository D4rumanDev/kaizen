package app.kaizen.domain

import app.kaizen.commons.KaizenProgress
import app.kaizen.commons.sync.Removals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** La fusión de estados y de progreso entre dispositivos: gana el cambio más reciente, y lo que una lápida quitó no vuelve. Sin Android. */
class StatusMergeTest {
    private val now = 1_800_000_000_000L
    private fun entry(slug: String, status: SeriesStatus, at: Long) = StatusEntry(status.id, "Serie $slug", "animeflv", slug, 1, at)
    private val same = { e: StatusEntry -> e.slug } // la serie de una entrada (aquí, su slug)

    @Test fun `un estado que aqui no existe entra`() {
        val merged = StatusMerge.merge(emptyMap(), mapOf("a" to entry("a", SeriesStatus.WATCHING, 10)), same, { false }, now)
        assertEquals(SeriesStatus.WATCHING.id, merged["a"]!!.status)
    }

    @Test fun `un estado mas reciente de otro dispositivo sustituye al de aqui y uno mas viejo no`() {
        val local = mapOf("a" to entry("a", SeriesStatus.PLAN, 100), "b" to entry("b", SeriesStatus.WATCHING, 500))
        val incoming = mapOf("a" to entry("a", SeriesStatus.WATCHING, 200), "b" to entry("b", SeriesStatus.COMPLETED, 400))
        val merged = StatusMerge.merge(local, incoming, same, { false }, now)
        assertEquals(SeriesStatus.WATCHING.id, merged["a"]!!.status) // el de la copia es mas nuevo
        assertEquals(SeriesStatus.WATCHING.id, merged["b"]!!.status) // el de aqui es mas nuevo: se queda
    }

    @Test fun `la misma serie guardada con otro titulo queda en una sola entrada`() {
        val local = mapOf("titulo viejo" to entry("a", SeriesStatus.PLAN, 100))
        val incoming = mapOf("titulo nuevo" to entry("a", SeriesStatus.DROPPED, 300))
        val merged = StatusMerge.merge(local, incoming, same, { false }, now)
        assertEquals(setOf("titulo nuevo"), merged.keys)
        assertEquals(SeriesStatus.DROPPED.id, merged["titulo nuevo"]!!.status)
    }

    @Test fun `lo que una lapida descarta no entra aunque sea nuevo`() {
        val merged = StatusMerge.merge(emptyMap(), mapOf("a" to entry("a", SeriesStatus.WATCHING, 900)), same, { it.slug == "a" }, now)
        assertTrue(merged.isEmpty())
    }

    @Test fun `una hora en el futuro de un reloj roto no gana`() {
        val local = mapOf("a" to entry("a", SeriesStatus.WATCHING, 100))
        val incoming = mapOf("a" to entry("a", SeriesStatus.DROPPED, now + 5 * 86_400_000L))
        assertEquals(SeriesStatus.WATCHING.id, StatusMerge.merge(local, incoming, same, { false }, now)["a"]!!.status)
    }

    @Test fun `el progreso mas reciente gana aunque tenga menos posicion`() {
        // Volver a ver un capitulo desde el principio en otro equipo: lo ultimo que se hizo manda.
        assertTrue(KaizenProgress.takeIncoming("900000/1400000", 100, "60000/1400000", 200, now))
        assertFalse(KaizenProgress.takeIncoming("60000/1400000", 300, "900000/1400000", 200, now))
    }

    @Test fun `sin hora en alguno se vale la posicion mayor como antes y sin progreso entra siempre`() {
        assertTrue(KaizenProgress.takeIncoming("60000/1400000", 0, "900000/1400000", 200, now))
        assertFalse(KaizenProgress.takeIncoming("900000/1400000", 200, "60000/1400000", 0, now))
        assertTrue(KaizenProgress.takeIncoming(null, 0, "60000/1400000", 0, now))
        assertFalse(KaizenProgress.takeIncoming("60000/1400000", 0, "basura", 5, now))
    }

    @Test fun `una hora del futuro en el progreso se ignora`() {
        assertFalse(KaizenProgress.takeIncoming("900000/1400000", 100, "60000/1400000", now + 9 * 86_400_000L, now))
    }

    @Test fun `una sustitucion que cambia de clave cuenta una vez`() {
        val local = mapOf("titulo viejo" to entry("a", SeriesStatus.PLAN, 100), "b" to entry("b", SeriesStatus.PLAN, 100))
        val incoming = mapOf("titulo nuevo" to entry("a", SeriesStatus.WATCHING, 300), "c" to entry("c", SeriesStatus.PLAN, 50))
        val result = StatusMerge.mergeCounted(local, incoming, same, { false }, now)
        assertEquals(1, result.replaced)
        assertEquals(1, result.added)
        assertEquals(2, result.changed)
        assertEquals(setOf("titulo nuevo", "b", "c"), result.map.keys)
    }

    @Test fun `con la misma hora y distinto estado los dos dispositivos acaban con el mismo`() {
        val a = mapOf("a" to entry("a", SeriesStatus.WATCHING, 500))
        val b = mapOf("a" to entry("a", SeriesStatus.COMPLETED, 500))
        val inA = StatusMerge.merge(a, b, same, { false }, now)
        val inB = StatusMerge.merge(b, a, same, { false }, now)
        assertEquals(inA, inB)
    }

    @Test fun `con la misma hora y estado pero otro titulo tambien convergen y no se cuenta dos veces`() {
        val a = mapOf("titulo a" to entry("a", SeriesStatus.PLAN, 500))
        val b = mapOf("titulo b" to entry("a", SeriesStatus.PLAN, 500))
        assertEquals(StatusMerge.merge(a, b, same, { false }, now).keys, StatusMerge.merge(b, a, same, { false }, now).keys)
        val again = StatusMerge.mergeCounted(a, a, same, { false }, now)
        assertEquals("lo mismo que ya hay no cuenta", 0, again.changed)
        assertEquals(a, again.map)
    }

    @Test fun `dos estados sin hora no se pisan por desempate`() {
        val local = mapOf("a" to entry("a", SeriesStatus.COMPLETED, 0))
        val incoming = mapOf("a" to entry("a", SeriesStatus.WATCHING, 0))
        assertEquals(SeriesStatus.COMPLETED.id, StatusMerge.merge(local, incoming, same, { false }, now)["a"]!!.status)
        // Ni una hora rota (que cuenta como 0) gana a uno sin hora.
        val future = mapOf("a" to entry("a", SeriesStatus.WATCHING, now + 3 * 86_400_000L))
        assertEquals(SeriesStatus.COMPLETED.id, StatusMerge.merge(local, future, same, { false }, now)["a"]!!.status)
    }

    @Test fun `si el reloj roto es el de aqui, el estado del otro dispositivo entra y convergen`() {
        val broken = mapOf("a" to entry("a", SeriesStatus.DROPPED, now + 5 * 86_400_000L))
        val sane = mapOf("a" to entry("a", SeriesStatus.WATCHING, now - 1000))
        assertEquals(SeriesStatus.WATCHING.id, StatusMerge.merge(broken, sane, same, { false }, now)["a"]!!.status)
        assertEquals(SeriesStatus.WATCHING.id, StatusMerge.merge(sane, broken, same, { false }, now)["a"]!!.status)
    }

    @Test fun `una clave que era de otra serie deja de contar para aquella`() {
        // El mismo título para dos series distintas: la nueva ocupa la clave; una segunda entrada de la primera serie entra como nueva, no como sustitución.
        val local = mapOf("titulo" to entry("a", SeriesStatus.PLAN, 100))
        val incoming = linkedMapOf("titulo" to entry("b", SeriesStatus.WATCHING, 200), "otro titulo" to entry("a", SeriesStatus.COMPLETED, 50))
        val result = StatusMerge.mergeCounted(local, incoming, same, { false }, now)
        assertEquals("b", result.map["titulo"]!!.slug)
        assertEquals("a", result.map["otro titulo"]!!.slug)
        assertEquals(2, result.added)
        assertEquals(0, result.replaced)
    }

    @Test fun `la fusion de miles de estados es lineal`() {
        val local = (1..20_000).associate { "t$it" to entry("s$it", SeriesStatus.PLAN, 100) }
        val incoming = (1..20_000).associate { "t$it" to entry("s$it", SeriesStatus.WATCHING, 200) }
        val started = System.nanoTime()
        val result = StatusMerge.mergeCounted(local, incoming, same, { false }, now)
        assertEquals(20_000, result.replaced)
        // Con la búsqueda una a una eran 400 millones de comparaciones (minutos); por serie, milisegundos. Margen amplio para una máquina lenta.
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
    }

    @Test fun `al restaurar solo entra lo de series sin estado aqui`() {
        val local = mapOf("titulo viejo" to entry("a", SeriesStatus.PLAN, 100))
        val incoming = mapOf("titulo nuevo" to entry("a", SeriesStatus.DROPPED, 300), "b" to entry("b", SeriesStatus.PLAN, 1), "titulo viejo" to entry("z", SeriesStatus.PLAN, 1))
        assertEquals(setOf("b"), StatusMerge.missing(local, incoming, same).keys)
    }

    @Test fun `la hora del ultimo cambio ignora la de un reloj roto`() {
        val map = mapOf("a" to entry("a", SeriesStatus.PLAN, now + 2 * 86_400_000L), "a2" to entry("a", SeriesStatus.PLAN, 300), "b" to entry("b", SeriesStatus.PLAN, 900))
        assertEquals(300L, StatusMerge.latestChange(map, { it.slug == "a" }, now))
        assertEquals(0L, StatusMerge.latestChange(map, { it.slug == "z" }, now))
        assertEquals(0L, StatusMerge.latestChange(mapOf("a" to entry("a", SeriesStatus.PLAN, now + 2 * 86_400_000L)), { true }, now))
    }

    @Test fun `quitar una serie quita todas sus entradas y dice cuales`() {
        val map = mapOf("t1" to entry("a", SeriesStatus.DROPPED, 1), "t2" to entry("a", SeriesStatus.PLAN, 2), "b" to entry("b", SeriesStatus.PLAN, 3))
        val (rest, removed) = StatusMerge.removeSeries(map) { it.slug == "a" }
        assertEquals(setOf("b"), rest.keys)
        assertEquals(2, removed.size)
        assertTrue(StatusMerge.removeSeries(rest) { it.slug == "a" }.second.isEmpty())
    }

    @Test fun `descartadas se liberan y se excluyen segun lo que cambio`() {
        val before = mapOf("x" to entry("x", SeriesStatus.DROPPED, 1), "y" to entry("y", SeriesStatus.PLAN, 1), "z" to entry("z", SeriesStatus.DROPPED, 1))
        val after = mapOf("x" to entry("x", SeriesStatus.WATCHING, 2), "y" to entry("y", SeriesStatus.DROPPED, 2), "z" to entry("z", SeriesStatus.DROPPED, 1))
        val (release, exclude) = StatusMerge.droppedChanges(before, after)
        assertEquals(listOf("x"), release.map { it.slug })
        assertEquals(listOf("y"), exclude.map { it.slug })
    }

    @Test fun `con la misma hora el progreso de mas posicion gana en los dos sentidos`() {
        assertTrue(KaizenProgress.takeIncoming("60000/1400000", 200, "900000/1400000", 200, now))
        assertFalse(KaizenProgress.takeIncoming("900000/1400000", 200, "60000/1400000", 200, now))
        // Misma posición: decide el texto, así que de los dos lados gana el mismo valor.
        val one = "600000/1400000"; val other = "600000/1450000"
        assertTrue(KaizenProgress.takeIncoming(one, 200, other, 200, now) != KaizenProgress.takeIncoming(other, 200, one, 200, now))
        assertFalse(KaizenProgress.takeIncoming(one, 200, one, 200, now))
    }

    @Test fun `las lapidas de estado se leen y cuentan aparte`() {
        val parsed = Removals.parse(Removals.statusKey("animeflv:serie"))!!
        assertTrue(parsed.status); assertFalse(parsed.favorite); assertFalse(parsed.progress)
        assertEquals("serie", parsed.slug)
        val statuses = (1..20).map { Removals.statusKey("a:s$it") }
        val seen = (1..5000).map { Removals.recordKey("a:s$it", 1.0) }
        assertTrue(Removals.massiveAny(statuses.take(15), statuses + seen))
        assertFalse(Removals.massiveAny(statuses.take(3), statuses + seen))
    }
}
