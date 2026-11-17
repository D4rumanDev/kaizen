package app.kaizen.source.spec

import java.security.KeyPairGenerator
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El repositorio de descargas: URL https sin sorpresas y claves ECDSA P-256 válidas. */
class RepoConfigTest {
    @Test fun `una url https valida se normaliza con barra final`() {
        assertEquals("https://example.org/kaizen/", RepoConfig.normalizeUrl("https://example.org/kaizen"))
        assertEquals("https://example.org/kaizen/", RepoConfig.normalizeUrl("  https://example.org/kaizen/  "))
    }

    @Test fun `no se aceptan http, usuario, query, fragmento, espacios ni texto raro`() {
        listOf("http://example.org/", "https://user:pw@example.org/", "https://example.org/?x=1", "https://example.org/#a", "https://exa mple.org/",
            "ftp://example.org/", "https:///sin-host/", "", "   ", "javascript:alert(1)", "https://example.org/\u0000", "https://" + "a".repeat(250) + ".org/")
            .forEach { assertNull(it, RepoConfig.normalizeUrl(it)) }
    }

    @Test fun `una clave ECDSA P-256 vale y cualquier otra cosa no`() {
        val ec = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        assertTrue(RepoConfig.validKey(Base64.getEncoder().encodeToString(ec.public.encoded)))
        val p384 = KeyPairGenerator.getInstance("EC").apply { initialize(384) }.generateKeyPair()
        assertFalse(RepoConfig.validKey(Base64.getEncoder().encodeToString(p384.public.encoded)))
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertFalse(RepoConfig.validKey(Base64.getEncoder().encodeToString(rsa.public.encoded)))
        assertFalse(RepoConfig.validKey("no es una clave"))
        assertFalse(RepoConfig.validKey(""))
        assertTrue(RepoConfig.validKey(SpecSources.PUBLIC_KEY))
    }

    @Test fun `sin Android el repositorio es el del proyecto, apagado y con todas las fuentes`() {
        assertFalse(RepoConfig.enabled)
        assertEquals(SpecSources.SOURCES_URL, RepoConfig.baseUrl)
        assertEquals(SpecSources.PUBLIC_KEY, RepoConfig.publicKey)
        assertFalse(RepoConfig.isCustom)
        assertTrue(RepoConfig.wants("animeav1"))
    }
}
