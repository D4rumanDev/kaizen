package app.kaizen.commons.sync

import app.kaizen.commons.RemoteFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * [RemoteStore] sobre la carpeta de red SMB de las copias (ver [app.kaizen.commons.SmbFolder]): cada dispositivo escribe su `kaizen-estados-<id>.json` y lee los de los demás, igual que con
 * WebDAV. Los ficheros de estados no se parecen a los de las copias (`kaizen-backup-*`), así que conviven en la misma carpeta sin tocarse. Pasa por ficheros temporales porque jcifs trabaja con ficheros.
 */
class SmbStore(private val folder: RemoteFolder, private val tempDir: File = File(System.getProperty("java.io.tmpdir") ?: ".")) : RemoteStore {
    override suspend fun list(): List<String> = withContext(Dispatchers.IO) { folder.list().map { it.name } }

    override suspend fun read(name: String): ByteArray? = withContext(Dispatchers.IO) {
        if (folder.list().none { it.name == name }) return@withContext null
        val temp = File.createTempFile("sync-", ".json", tempDir)
        try {
            folder.download(name, temp)
            if (temp.length() > DeviceSync.MAX_BYTES) throw IOException("SMB: copia demasiado grande")
            temp.readBytes()
        } finally { temp.delete() }
    }

    override suspend fun write(name: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val temp = File.createTempFile("sync-", ".json", tempDir)
        try { temp.writeBytes(bytes); folder.upload(temp, name) } finally { temp.delete() }
    }

    override suspend fun delete(name: String) = withContext(Dispatchers.IO) { folder.delete(name) }
}
