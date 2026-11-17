package app.kaizen.commons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** El agente de la app: de navegador, estable y sin marca propia ni datos de quien usa la app. */
class UserAgentTest {
    @Test fun `sin Android devuelve un agente de navegador estable`() {
        val agent = UserAgent.current()
        assertTrue(agent.startsWith("Mozilla/5.0"))
        assertEquals(agent, UserAgent.current())
    }

    @Test fun `no lleva marca propia ni correo`() {
        val agent = UserAgent.current()
        assertFalse(agent.contains("kaizen", ignoreCase = true))
        assertFalse(agent.contains("@") || agent.contains("anilist", ignoreCase = true))
    }

    @Test fun `solo hay dos modos y uno desconocido vale el local`() {
        assertEquals(listOf("local", "aleatorio"), UserAgent.MODES)
        assertEquals(UserAgent.LOCAL, UserAgent.mode)
    }
}
