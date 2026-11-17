package app.kaizen.commons

import app.kaizen.tv.settings.folderNameProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** El asistente de la carpeta de red: servidor normalizado, carpetas visibles, direcciones, nombres y los textos de los errores, sin red. */
class SmbPickerTest {
    @Test
    fun `normaliza el servidor que se escribe`() {
        assertEquals("192.0.2.10", SmbFolder.normalizeServer("192.0.2.10"))
        assertEquals("nas", SmbFolder.normalizeServer("  nas  "))
        assertEquals("nas", SmbFolder.normalizeServer("smb://nas/"))
        assertEquals("nas.local", SmbFolder.normalizeServer("SMB://nas.local/video/kaizen"))
        assertNull(SmbFolder.normalizeServer(""))
        assertNull(SmbFolder.normalizeServer("usuario@nas"))
        assertNull(SmbFolder.normalizeServer("mi nas"))
    }

    @Test
    fun `solo enseña carpetas normales y por orden`() {
        val raw = listOf("video/", "IPC$", "homes/", ".hidden", "Photo/", "C$", "docs", "video/")
        assertEquals(listOf("docs", "homes", "Photo", "video"), SmbFolder.visibleFolders(raw))
    }

    @Test
    fun `construye y separa la direccion de la carpeta`() {
        assertEquals("smb://192.0.2.10/video/kaizen", SmbFolder.urlOf("192.0.2.10", listOf("video", "kaizen")))
        assertEquals("smb://192.0.2.10/", SmbFolder.urlOf("192.0.2.10", emptyList()))
        assertEquals("192.0.2.10" to listOf("video", "kaizen"), SmbFolder.splitUrl("smb://192.0.2.10/video/kaizen/"))
        assertNull(SmbFolder.splitUrl(""))
        assertNull(SmbFolder.splitUrl("https://x/y"))
    }

    @Test
    fun `lee el lugar con o sin protocolo y con barras de cualquier tipo`() {
        assertEquals("192.0.2.10" to listOf("video", "kaizen"), SmbFolder.parseLocation("192.0.2.10/video/kaizen", withServer = true))
        assertEquals("nas" to listOf("video"), SmbFolder.parseLocation("smb://nas/video/", withServer = true))
        assertEquals("nas" to listOf("video", "kaizen"), SmbFolder.parseLocation("\\\\nas\\video\\kaizen", withServer = true))
        assertEquals(null to listOf("video", "mi carpeta"), SmbFolder.parseLocation("video/mi carpeta", withServer = false))
        assertEquals("nas" to listOf("video"), SmbFolder.parseLocation("smb://nas/video", withServer = false))
        assertNull(SmbFolder.parseLocation("video/../x", withServer = false))
        assertNull(SmbFolder.parseLocation("video/a:b", withServer = false))
    }

    @Test
    fun `da la direccion completa y la enseña sin protocolo`() {
        assertEquals("smb://192.0.2.10/video/kaizen", SmbFolder.normalizeUrl("192.0.2.10/video/kaizen"))
        assertEquals("smb://nas/video", SmbFolder.normalizeUrl("\\\\nas\\video\\"))
        assertNull(SmbFolder.normalizeUrl("192.0.2.10"))
        assertEquals("192.0.2.10/video/kaizen", SmbFolder.display("smb://192.0.2.10/video/kaizen/"))
        assertEquals("", SmbFolder.display(null))
    }

    @Test
    fun `valida el nombre de una carpeta nueva`() {
        assertNull(folderNameProblem("Kaizen"))
        assertNull(folderNameProblem("copias 2026"))
        assertNotNull(folderNameProblem(""))
        assertNotNull(folderNameProblem("a/b"))
        assertNotNull(folderNameProblem("a:b"))
        assertNotNull(folderNameProblem(".oculta"))
    }

    @Test
    fun `traduce los fallos a un texto que dice que hacer`() {
        assertTrue(SmbErrors.describe(RuntimeException("Logon failure: unknown user name or bad password."), "nas").startsWith("Usuario o contraseña"))
        assertTrue(SmbErrors.describe(RuntimeException("wrapper", UnknownHostException("nas")), "nas").contains("No se encuentra nas"))
        assertTrue(SmbErrors.describe(SocketTimeoutException("connect timed out"), "nas").contains("tarda demasiado"))
        assertTrue(SmbErrors.describe(ConnectException("Connection refused"), "nas").contains("No se puede conectar con nas"))
        assertTrue(SmbErrors.describe(RuntimeException("STATUS_ACCESS_DENIED"), "nas").contains("permiso"))
        assertEquals("No se pudo conectar con nas.", SmbErrors.describe(IllegalStateException("algo raro"), "nas"))
    }

    @Test
    fun `un error no cuenta nunca la contrasena`() {
        val text = SmbErrors.describe(RuntimeException("Logon failure for user usuario password hunter2"), "nas")
        assertTrue("hunter2" !in text)
    }
}
