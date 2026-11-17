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

    /**
     * Las carpetas de esta dirección; en la raíz del servidor (`smb://servidor/`), sus recursos compartidos. Sin los ocultos ni los administrativos y por orden alfabético.
     * Sirve para examinar y, de paso, para comprobar que el servidor, el usuario y la clave valen: lanza la excepción que corresponda si no.
     */
    fun folders(): List<String> = visibleFolders(file().listFiles().orEmpty().filter { it.isDirectory }.map { it.name })

    /** ¿Existe esta carpeta (o recurso compartido)? Lanza la excepción que corresponda si el servidor, el usuario o la clave no valen. */
    fun exists(): Boolean = file().exists()

    /** Crea una carpeta dentro de esta (falla si el recurso es de solo lectura o no hay permiso). */
    fun makeFolder(name: String) {
        file("$name/").mkdir()
    }

    companion object {
        /** («DOMINIO», «usuario») de «DOMINIO\usuario» o «usuario@dominio»; sin dominio, el dominio va vacío. */
        fun splitUser(user: String): Pair<String, String> = when {
            '\\' in user -> user.substringBefore('\\') to user.substringAfter('\\')
            '@' in user -> user.substringAfter('@') to user.substringBefore('@')
            else -> "" to user
        }

        /** Los nombres de carpeta que se enseñan: sin «/» final, sin ocultos (punto) ni recursos administrativos (acaban en «$»), ordenados sin distinguir mayúsculas. */
        fun visibleFolders(names: List<String>): List<String> =
            names.map { it.trimEnd('/') }.filter { it.isNotEmpty() && !it.endsWith("$") && !it.startsWith(".") }.distinct().sortedBy { it.lowercase() }

        /** El servidor que se quiso decir: acepta `192.0.2.10`, `nas`, `smb://nas/` o `smb://nas/video/x` y devuelve solo el nombre o la IP; null si no vale. */
        fun normalizeServer(input: String): String? {
            var v = input.trim()
            if (v.startsWith("smb://", ignoreCase = true)) v = v.substring(6)
            v = v.substringBefore('/').trim()
            if (v.isEmpty() || v.any { it.isWhitespace() || it == '@' || it == '?' || it == '#' || it == '\\' }) return null
            return v
        }

        /**
         * Lo que se escribió como lugar, con o sin protocolo y con barras de cualquier tipo: `192.0.2.10/video/kaizen`, `smb://nas/video`, `\\\\nas\\video\\kaizen` (como en Windows) o solo
         * `video/kaizen` si no hay servidor. Devuelve (servidor o null, [recurso, carpetas…]); null si trae caracteres que no valen.
         */
        fun parseLocation(input: String, withServer: Boolean): Pair<String?, List<String>>? {
            var v = input.trim().replace('\\', '/')
            val hadPrefix = v.startsWith("smb://", ignoreCase = true) || v.startsWith("//")
            if (v.startsWith("smb://", ignoreCase = true)) v = v.substring(6)
            v = v.trimStart('/')
            if (v.any { it.isWhitespace() && it != ' ' || it == '?' || it == '#' || it == '@' || it == ':' || it == '*' || it == '"' || it == '<' || it == '>' || it == '|' }) return null
            val parts = v.split('/').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.any { it == "." || it == ".." }) return null
            return if (withServer || hadPrefix) { if (parts.isEmpty()) null else parts.first() to parts.drop(1) } else null to parts
        }

        /** Una dirección guardada, sin el protocolo, para enseñarla (`192.0.2.10/video/kaizen`). */
        fun display(url: String?): String = url.orEmpty().removePrefix("smb://").trimEnd('/')

        /** La dirección de una carpeta: `smb://servidor/` más las partes de la ruta (recurso, carpetas…). */
        fun urlOf(host: String, parts: List<String>): String = "smb://$host/" + parts.joinToString("/")

        /** (servidor, [recurso, carpetas…]) de una dirección guardada; null si no es una dirección smb:// válida. */
        fun splitUrl(url: String): Pair<String, List<String>>? {
            if (url.isBlank() || problem(url) != null) return null
            val (host, parts) = parseLocation(url, withServer = true) ?: return null
            return host.orEmpty() to parts
        }

        /**
         * Por qué una dirección no vale (null si vale). El protocolo es opcional: `servidor/recurso/carpeta` basta (con `smb://` o con barras de Windows también). Sin usuario ni clave
         * en ella: van aparte.
         */
        fun problem(url: String): String? {
            val v = url.trim()
            if (v.isEmpty()) return null
            if ("://" in v && !v.startsWith("smb://", ignoreCase = true)) return "Solo SMB: sin protocolo, o con smb://"
            if ('@' in v.substringBefore('/')) return "Sin usuario ni clave en la dirección: van aparte"
            val parsed = parseLocation(v, withServer = true) ?: return "Sin espacios raros ni ? # : * \" < > |"
            return if (parsed.second.isEmpty()) "Falta el recurso compartido: servidor/recurso/carpeta" else null
        }

        /** La dirección completa (`smb://servidor/recurso/carpeta`) de lo que se escribió; null si no vale. */
        fun normalizeUrl(input: String): String? =
            if (problem(input) != null || input.isBlank()) null else parseLocation(input, withServer = true)?.let { (host, parts) -> urlOf(host.orEmpty(), parts).trimEnd('/') }
    }
}
