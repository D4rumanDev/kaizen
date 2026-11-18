package app.kaizen.commons.sync

import app.kaizen.commons.SecretStore
import javax.crypto.KeyGenerator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lo que se quita también viaja entre dispositivos, con sus cautelas; y la clave del servidor se guarda cifrada. */
class RemovalsTest {
    private val fav = Removals.favoriteKey("animeav1:one-piece")
    private val rec = Removals.recordKey("animeav1:one-piece", 12.5)

    @Test fun `las claves se leen y lo mal formado se descarta`() {
        val f = checkNotNull(Removals.parse(fav))
        assertTrue(f.favorite); assertEquals("animeav1", f.source); assertEquals("one-piece", f.slug); assertNull(f.number)
        val r = checkNotNull(Removals.parse(rec))
        assertFalse(r.favorite); assertEquals(12.5, r.number!!, 0.0)
        listOf("", "x/a:b", "f/sinfuente", "f/:slug", "r/a:b", "r/a:b#NaN", "r/a:b#abc", "f/" + "a:".repeat(300)).forEach { assertNull(it, Removals.parse(it)) }
    }

    @Test fun `solo cuenta como quitado lo que se sabe que ya no esta`() {
        val present = mapOf(fav to false, rec to true, "f/animeav1:otra" to null)
        assertEquals(listOf(fav), Removals.deleted(listOf(fav, rec, "f/animeav1:otra"), present::get))
        assertEquals(emptyList<String>(), Removals.deleted(null, present::get))
    }

    @Test fun `las lapidas propias caducan, se retiran si se vuelve a poner y suman las nuevas`() {
        val now = 1_000_000_000_000L
        val old = mapOf(fav to now - 1000, rec to now - 1000, "f/a:viejo" to now - Removals.MAX_AGE.inWholeMilliseconds - 1)
        val next = Removals.nextRemoved(old, listOf("f/a:nuevo"), { if (it == rec) true else false }, now)
        assertEquals(setOf(fav, "f/a:nuevo"), next.keys)
        assertEquals(now, next["f/a:nuevo"])
    }

    @Test fun `un borrado masivo no se propaga, uno normal si`() {
        assertTrue(Removals.massive(60, 100))
        assertFalse(Removals.massive(5, 100))
        assertFalse(Removals.massive(4, 5)) // pocas cosas: no hay «masivo»
        assertFalse(Removals.massive(50, 100))
    }

    @Test fun `el tope masivo es por tipo y las fechas del futuro se descartan`() {
        val total = (1..30).map { "f/a:s$it" } + (1..5000).map { "r/a:s#$it.0" }
        assertTrue(Removals.massiveAny((1..20).map { "f/a:s$it" }, total)) // 20 de 30 favoritos, aunque sean pocos frente a los capítulos
        assertFalse(Removals.massiveAny((1..10).map { "f/a:s$it" }, total))
        val now = 1_000_000L
        assertEquals(mapOf(fav to now), Removals.sanitize(mapOf<String, Long?>(fav to now, rec to now + 2 * 24 * 3600 * 1000L), now))
    }

    @Test fun `una copia anterior a la lapida no deshace el borrado y una posterior si puede volver a poner`() {
        val canon = { key: String -> key.replace("animeav1:", "id1:") } // la misma serie con otra ficha
        val blocked = Removals.blocked(listOf(mapOf(fav to 500L), mapOf(fav to 800L)), canon)
        assertEquals(800L, blocked["f/id1:one-piece"])
        assertTrue(Removals.stale(fav, 700L, blocked, canon))
        assertFalse(Removals.stale(fav, 900L, blocked, canon))
        assertFalse(Removals.stale(rec, 100L, blocked, canon))
    }

    @Test fun `las lapidas de una copia ajena se limpian y se acotan`() {
        val raw = mapOf<String, Long?>(fav to 5L, "basura" to 5L, rec to null, "f/a:b" to -1L)
        assertEquals(mapOf(fav to 5L), Removals.sanitize(raw))
        assertEquals(Removals.MAX_ENTRIES, Removals.sanitize((1..Removals.MAX_ENTRIES + 10).associate { "f/a:s$it" to 1L as Long? }).size)
    }

    @Test fun `la sincronizacion llama a finish despues de leer las copias y antes de subir la propia`() = runTest {
        val events = mutableListOf<String>()
        val store = object : RemoteStore {
            override suspend fun list() = listOf(DeviceSync.fileName("bbbbbbbb"))
            override suspend fun read(name: String): ByteArray? = "x".toByteArray()
            override suspend fun write(name: String, bytes: ByteArray) { events += "write" }
        }
        DeviceSync.run(store, "aaaaaaaa", { events += "read"; true }, { events += "own"; "{}".toByteArray() }, finish = { events += "finish" })
        assertEquals(listOf("read", "finish", "own", "write"), events)
    }

    // ----- La clave del servidor, cifrada

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun `la clave cifrada se descifra y no se parece al original`() {
        val sealed = SecretStore.seal("c0ntrasena-ñ", key)
        assertTrue(sealed.startsWith("v1:"))
        assertFalse(sealed.contains("c0ntrasena"))
        assertEquals("c0ntrasena-ñ", SecretStore.open(sealed, key))
        assertNotEquals(sealed, SecretStore.seal("c0ntrasena-ñ", key)) // iv distinto cada vez
    }

    @Test fun `un valor alterado, de otra clave o de otro formato no se descifra`() {
        val sealed = SecretStore.seal("abc", key)
        val tampered = sealed.dropLast(3) + (if (sealed.takeLast(3) == "AAA") "BBB" else "AAA")
        assertNull(SecretStore.open(tampered, key))
        assertNull(SecretStore.open(sealed, KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()))
        assertNull(SecretStore.open("en claro", key))
        assertNull(SecretStore.open("v1:", key))
    }
}
