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
    private val same = { a: StatusEntry, b: StatusEntry -> a.slug == b.slug }

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
