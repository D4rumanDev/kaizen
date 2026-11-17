package app.kaizen.commons

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import jcifs.smb.SmbFileOutputStream
import java.io.File
import java.util.Properties

/** Una carpeta remota donde guardar y leer ficheros (hoy, una carpeta SMB de un NAS). Separada de [SmbFolder] para probar la lógica de las copias sin red. */
interface RemoteFolder {
    class Entry(val name: String, val size: Long, val modified: Long)

    /** Los ficheros (no las carpetas) de la carpeta. */
    fun list(): List<Entry>

    /** Sube [local] con el nombre [name]; no deja un fichero a medias: se escribe aparte y se renombra al terminar. */
    fun upload(local: File, name: String = local.name)

    /** Baja [name] a [dest] con el mismo cuidado que [upload]. */
    fun download(name: String, dest: File)

    fun delete(name: String)
}

/**
 * Carpeta SMB (de un NAS, de un PC con carpetas compartidas) con jcifs. Dirección `smb://servidor/recurso/carpeta`; usuario y clave aparte (nunca dentro de la dirección, para que no
 * acaben en un registro). SMB 2 y 3 solamente: SMB 1 está roto de seguridad y los NAS actuales ya no lo ofrecen. Resuelve el nombre por DNS (sin NetBIOS por difusión).
 * Es bloqueante: llamar desde un hilo de trabajo.
 */
class SmbFolder(url: String, user: String?, password: String?) : RemoteFolder {
    /** La dirección de la carpeta, siempre acabada en «/» (así jcifs la trata como carpeta). */
    private val dirUrl = url.trim().let { if (it.endsWith("/")) it else "$it/" }

    private val context: CIFSContext by lazy {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.resolveOrder", "DNS")
            setProperty("jcifs.smb.client.connTimeout", "10000")
            setProperty("jcifs.smb.client.responseTimeout", "20000")
            setProperty("jcifs.smb.client.soTimeout", "20000")
        }
        val base = BaseContext(PropertyConfiguration(props))
        // Sin usuario, como invitado (algunos NAS dejan una carpeta pública así).
        if (user.isNullOrBlank()) base.withCredentials(NtlmPasswordAuthenticator("", "guest", ""))
        else {
            // «DOMINIO\usuario» o «usuario@dominio» llevan el dominio dentro; lo normal en un NAS es solo el usuario.
            val (domain, name) = splitUser(user)
            base.withCredentials(NtlmPasswordAuthenticator(domain, name, password.orEmpty()))
        }
    }

    private fun file(name: String = "") = SmbFile(dirUrl + name, context)

    /** Crea la carpeta si no existe (el recurso compartido, no: ese lo crea el administrador del servidor). */
    private fun ensureFolder() {
        val dir = file()
        if (!dir.exists()) dir.mkdirs()
    }

    override fun list(): List<RemoteFolder.Entry> {
        val dir = file()
        if (!dir.exists()) return emptyList()
        return dir.listFiles().orEmpty().filter { it.isFile }.map { RemoteFolder.Entry(it.name.trimEnd('/'), it.length(), it.lastModified()) }
    }

    override fun upload(local: File, name: String) {
        ensureFolder()
        val temp = file("$name.tmp")
        local.inputStream().use { input -> SmbFileOutputStream(temp).use { out -> input.copyTo(out) } }
        val target = file(name)
        if (target.exists()) target.delete()
        temp.renameTo(target)
    }

    override fun download(name: String, dest: File) {
        val temp = File(dest.parentFile, "${dest.name}.tmp")
        SmbFileInputStream(file(name)).use { input -> temp.outputStream().use { out -> input.copyTo(out) } }
        if (!temp.renameTo(dest)) { dest.delete(); check(temp.renameTo(dest)) { "no se pudo guardar ${dest.name}" } }
    }

    override fun delete(name: String) {
        file(name).takeIf { it.exists() }?.delete()
    }

    companion object {
        /** («DOMINIO», «usuario») de «DOMINIO\usuario» o «usuario@dominio»; sin dominio, el dominio va vacío. */
        fun splitUser(user: String): Pair<String, String> = when {
            '\\' in user -> user.substringBefore('\\') to user.substringAfter('\\')
            '@' in user -> user.substringAfter('@') to user.substringBefore('@')
            else -> "" to user
        }

        /** Por qué una dirección no vale (null si vale): `smb://servidor/recurso` y, si se quiere, una carpeta dentro. Sin usuario ni clave en ella. */
        fun problem(url: String): String? {
            val v = url.trim()
            if (v.isEmpty()) return null
            if (!v.startsWith("smb://", ignoreCase = true)) return "Debe empezar por smb://"
            val rest = v.substring(6)
            if ('@' in rest.substringBefore('/')) return "Sin usuario ni clave en la dirección: van en sus ajustes"
            if (rest.any { it.isWhitespace() || it == '?' || it == '#' }) return "Sin espacios ni ? ni #"
            val parts = rest.split('/').filter { it.isNotEmpty() }
            return if (parts.size < 2) "Falta el recurso compartido: smb://servidor/recurso/carpeta" else null
        }
    }
}
