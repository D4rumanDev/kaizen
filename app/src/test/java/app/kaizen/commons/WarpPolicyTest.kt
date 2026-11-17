package app.kaizen.commons

import app.kaizen.commons.warp.WarpAccount
import app.kaizen.commons.warp.WarpAction
import app.kaizen.commons.warp.WarpMode
import app.kaizen.commons.warp.WarpPolicy
import app.kaizen.tv.ui.KaizenToast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cuándo sube y baja el túnel WARP, la lectura del registro de la cuenta y el aviso viejo de la pastilla (sin Android). */
class WarpPolicyTest {
    private val min = 60_000L

    @Test
    fun `apagado nunca sube y baja el que haya`() {
        assertEquals(WarpAction.NONE, WarpPolicy.decide(WarpMode.OFF, blocked = true, up = false, clearSince = 0, now = 0))
        assertEquals(WarpAction.STOP, WarpPolicy.decide(WarpMode.OFF, blocked = true, up = true, clearSince = 0, now = 0))
    }

    @Test
    fun `siempre sube si esta bajado y no hace nada si ya esta subido`() {
        assertEquals(WarpAction.START, WarpPolicy.decide(WarpMode.ALWAYS, blocked = false, up = false, clearSince = 0, now = 0))
        assertEquals(WarpAction.NONE, WarpPolicy.decide(WarpMode.ALWAYS, blocked = false, up = true, clearSince = 0, now = 0))
    }

    @Test
    fun `con bloqueo sube y se mantiene hasta quince minutos de calma`() {
        assertEquals(WarpAction.START, WarpPolicy.decide(WarpMode.ON_BLOCK, blocked = true, up = false, clearSince = 0, now = 0))
        assertEquals(WarpAction.NONE, WarpPolicy.decide(WarpMode.ON_BLOCK, blocked = true, up = true, clearSince = 0, now = 0))
        // Sin bloqueo desde hace 5 min: todavía no baja; a los 15, sí.
        assertEquals(WarpAction.NONE, WarpPolicy.decide(WarpMode.ON_BLOCK, blocked = false, up = true, clearSince = 100 * min, now = 105 * min))
        assertEquals(WarpAction.STOP, WarpPolicy.decide(WarpMode.ON_BLOCK, blocked = false, up = true, clearSince = 100 * min, now = 115 * min))
        // Sin bloqueo y bajado: nada que hacer.
        assertEquals(WarpAction.NONE, WarpPolicy.decide(WarpMode.ON_BLOCK, blocked = false, up = false, clearSince = 100 * min, now = 200 * min))
    }

    @Test
    fun `un modo guardado que ya no existe es apagado`() {
        assertEquals(WarpMode.OFF, WarpMode.of(null))
        assertEquals(WarpMode.OFF, WarpMode.of("otro"))
        assertEquals(WarpMode.ON_BLOCK, WarpMode.of(WarpMode.ON_BLOCK.label))
    }

    @Test
    fun `la cuenta se lee de la respuesta del registro y usa la IP v4 del extremo`() {
        val response = """{"id":"x","token":"t","config":{"peers":[{"public_key":"PEERKEY=","endpoint":{"v4":"162.159.192.1:0","v6":"[2606:4700:d0::a29f:c001]:0","host":"engage.cloudflareclient.com:2408"}}],"interface":{"addresses":{"v4":"203.0.113.5","v6":"2606:4700:110:8a36::1"}}}}"""
        val account = WarpAccount.parse("PRIV", "PUB", response)
        assertNotNull(account)
        assertEquals("PEERKEY=", account!!.peerPublicKey)
        assertEquals("162.159.192.1:2408", account.endpoint)
        assertEquals("203.0.113.5", account.addressV4)
        assertEquals("2606:4700:110:8a36::1", account.addressV6)
    }

    @Test
    fun `una respuesta a medias no arma un tunel`() {
        assertNull(WarpAccount.parse("a", "b", "{}"))
        assertNull(WarpAccount.parse("a", "b", "no es json"))
        assertNull(WarpAccount.parse("a", "b", """{"config":{"peers":[],"interface":{"addresses":{"v4":"1.1.1.1"}}}}"""))
    }

    @Test
    fun `el cuerpo del registro lleva la clave publica y nunca la privada`() {
        val body = WarpAccount.registrationBody("PUBKEY=", "2026-11-12T00:00:00.000Z")
        assertTrue("PUBKEY=" in body)
        assertFalse("private" in body.lowercase())
    }

    @Test
    fun `un aviso viejo de la pastilla es un resto y uno reciente no`() {
        val entry = KaizenToast.Entry(1, "Pulsa Atrás otra vez para salir", longDuration = false, at = 1_000L)
        assertFalse(entry.isStale(1_000L + 2_200L))
        assertTrue(entry.isStale(1_000L + 2_200L + 501L))
        val long = entry.copy(longDuration = true)
        assertFalse(long.isStale(1_000L + 3_500L + 400L))
        assertTrue(long.isStale(1_000L + 4_100L))
    }
}
