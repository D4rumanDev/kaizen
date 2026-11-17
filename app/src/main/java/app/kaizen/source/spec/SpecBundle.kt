package app.kaizen.source.spec

import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal class IndexEntry(val id: String, val file: String, val sha256: String, val version: Int)

internal class SourceIndex(val schema: Int, val version: Int, val entries: List<IndexEntry>)

/**
 * Formato de distribución de las fuentes: un `index.json` con la lista de ficheros (nombre, hash SHA-256 y versión) y su
 * firma ECDSA P-256 (`index.json.sig`, DER en base64). La app solo usa lo que firma la clave de [SpecSources.PUBLIC_KEY]:
 * quien controle el repositorio sin la clave privada no puede inyectar ni cambiar una fuente.
 */
internal object SpecBundle {
    private val FILE = Regex("[a-z0-9]{2,20}\\.json")
    private val HEX = Regex("[0-9a-f]{64}")

    /** Valida el índice: formato conocido y nombres de fichero simples (sin rutas), para que no pueda apuntar fuera de la carpeta. */
    fun parseIndex(bytes: ByteArray): SourceIndex {
        val j = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw SpecException("índice no válido")
        }
        val schema = j.optInt("schema", -1)
        if (schema != SourceSpec.SUPPORTED_SCHEMA) throw SpecException("índice de formato $schema no soportado")
        val array = j.optJSONArray("sources") ?: throw SpecException("índice sin fuentes")
        val entries = List(array.length()) {
            val e = array.getJSONObject(it)
            val file = e.getString("file")
            val sha = e.getString("sha256").lowercase()
            if (!FILE.matches(file)) throw SpecException("nombre de fichero no válido: $file")
            if (!HEX.matches(sha)) throw SpecException("hash no válido para $file")
            IndexEntry(e.getString("id"), file, sha, e.getInt("version"))
        }
        return SourceIndex(schema, j.optInt("version", 0), entries)
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** ¿Firma [signatureBase64] (DER) de [data] hecha con la clave privada de [publicKeyBase64] (X.509 DER en base64)? */
    fun verify(publicKeyBase64: String, data: ByteArray, signatureBase64: ByteArray): Boolean = try {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decode(publicKeyBase64.toByteArray())))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(data)
            verify(decode(signatureBase64))
        }
    } catch (e: Exception) {
        false
    }

    private fun decode(data: ByteArray): ByteArray = Base64.getMimeDecoder().decode(String(data, Charsets.UTF_8).trim())

    /**
     * Fuentes de un conjunto firmado leído con [read] (nombre de fichero → contenido): el índice y su firma deben ser
     * válidos y cada fichero debe coincidir con su hash; un fichero que no se puede interpretar se omite.
     * Lanza [SpecException] si el índice o la firma no valen.
     */
    fun load(publicKeyBase64: String, read: (String) -> ByteArray?): List<SourceSpec> {
        val index = read("index.json") ?: throw SpecException("falta index.json")
        val signature = read("index.json.sig") ?: throw SpecException("falta la firma")
        if (!verify(publicKeyBase64, index, signature)) throw SpecException("firma no válida")
        return parseIndex(index).entries.mapNotNull { entry ->
            val bytes = read(entry.file) ?: return@mapNotNull null
            if (sha256(bytes) != entry.sha256) return@mapNotNull null
            val spec = try {
                SourceSpec.parse(String(bytes, Charsets.UTF_8))
            } catch (e: SpecException) {
                return@mapNotNull null
            }
            spec.takeIf { it.id == entry.id && it.version == entry.version }
        }
    }
}
