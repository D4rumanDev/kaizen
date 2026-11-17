package app.kaizen.commons

import app.kaizen.commons.sync.SmbStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** El almacén de sincronización sobre una carpeta (aquí una carpeta local en vez de un NAS). */
class SmbStoreTest {
    private class Disk(val dir: File) : RemoteFolder {
        override fun list() = dir.listFiles().orEmpty().map { RemoteFolder.Entry(it.name, it.length(), it.lastModified()) }
        override fun upload(local: File, name: String) { local.copyTo(File(dir, name), overwrite = true) }
        override fun download(name: String, dest: File) { File(dir, name).copyTo(dest, overwrite = true) }
        override fun delete(name: String) { File(dir, name).delete() }
    }

    @Test
    fun `escribe lee lista y borra`() = runBlocking {
        val dir = kotlin.io.path.createTempDirectory("smb").toFile()
        val tmp = kotlin.io.path.createTempDirectory("tmp").toFile()
        val store = SmbStore(Disk(dir), tmp)
        assertNull(store.read("kaizen-estados-0a1b2c3d.json"))
        store.write("kaizen-estados-0a1b2c3d.json", "{\"a\":1}".toByteArray())
        assertEquals(listOf("kaizen-estados-0a1b2c3d.json"), store.list())
        assertArrayEquals("{\"a\":1}".toByteArray(), store.read("kaizen-estados-0a1b2c3d.json"))
        store.delete("kaizen-estados-0a1b2c3d.json")
        assertEquals(emptyList<String>(), store.list())
        assertEquals(0, tmp.listFiles().orEmpty().size)
    }
}
