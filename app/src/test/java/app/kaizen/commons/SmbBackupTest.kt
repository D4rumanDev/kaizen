package app.kaizen.commons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Lo que decide qué se sube, qué se baja y qué se poda en la carpeta de red, y qué direcciones valen, sin red. */
class SmbBackupTest {
    private fun entry(name: String) = RemoteFolder.Entry(name, 10, 0)
    private fun local(vararg names: String) = names.map { File(it) }

    @Test
    fun `sube solo las copias que la carpeta no tiene y no las ajenas`() {
        val remote = listOf(entry("kaizen-backup-20261101-100000.json"))
        val up = SmbBackup.toUpload(local("kaizen-backup-20261101-100000.json", "kaizen-backup-20261102-100000.json", "kaizen-ajustes-20261102-100000.json", "otra-cosa.json", "notas.txt"), remote)
        assertEquals(listOf("kaizen-ajustes-20261102-100000.json", "kaizen-backup-20261102-100000.json"), up.map { it.name })
    }

    @Test
    fun `baja solo las copias de la carpeta que faltan aqui`() {
        val remote = listOf(entry("kaizen-backup-20261101-100000.json"), entry("kaizen-backup-20261103-100000.json"), entry("foto.png"), entry("kaizen-backup-x.json.tmp"))
        val get = SmbBackup.toDownload(remote, local("kaizen-backup-20261101-100000.json"))
        assertEquals(listOf("kaizen-backup-20261103-100000.json"), get.map { it.name })
    }

    @Test
    fun `poda las mas viejas de cada tipo y deja las mas nuevas`() {
        val names = listOf(
            "kaizen-backup-20261101-100000.json", "kaizen-backup-20261102-100000.json", "kaizen-backup-20261103-100000.json",
            "kaizen-ajustes-20261101-100000.json", "kaizen-ajustes-20261102-100000.json", "foto.png"
        )
        assertEquals(setOf("kaizen-backup-20261101-100000.json"), SmbBackup.toPrune(names, 2).toSet())
        assertEquals(
            setOf("kaizen-backup-20261101-100000.json", "kaizen-backup-20261102-100000.json", "kaizen-ajustes-20261101-100000.json"),
            SmbBackup.toPrune(names, 1).toSet()
        )
        assertEquals(emptyList<String>(), SmbBackup.toPrune(names, 5))
    }

    @Test
    fun `nunca poda hasta dejar cero copias`() {
        val names = listOf("kaizen-backup-20261101-100000.json", "kaizen-backup-20261102-100000.json")
        assertEquals(listOf("kaizen-backup-20261101-100000.json"), SmbBackup.toPrune(names, 0))
    }

    @Test
    fun `separa dominio y usuario`() {
        assertEquals("" to "usuario", SmbFolder.splitUser("usuario"))
        assertEquals("DOMINIO" to "usuario", SmbFolder.splitUser("DOMINIO\\usuario"))
        assertEquals("dominio" to "usuario", SmbFolder.splitUser("usuario@dominio"))
    }

    @Test
    fun `valida la direccion de la carpeta`() {
        assertNull(SmbFolder.problem(""))
        assertNull(SmbFolder.problem("smb://192.0.2.10/video"))
        assertNull(SmbFolder.problem("smb://192.0.2.10/video/kaizen/copias"))
        assertNotNull(SmbFolder.problem("https://192.0.2.10/video"))
        assertNotNull(SmbFolder.problem("smb://192.0.2.10"))
        assertNotNull(SmbFolder.problem("smb://usuario:clave@192.0.2.10/video"))
        assertNotNull(SmbFolder.problem("smb://192.0.2.10/video/mi carpeta"))
    }
}
