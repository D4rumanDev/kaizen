package app.kaizen.commons

import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Las comprobaciones de instalar una versión nueva desde la app (ver `02-DOCS/wiki/sdd/specs/actualizar-desde-github.md`). Aquí solo lógica sin Android: elegir el asset
 * de la release, comparar el paquete y la firma, y bajar el fichero con tope de tamaño y su SHA-256. Instalar y pedir el permiso está en [UpdateInstall].
 *
 * Falla cerrado: sin huella (`digest`) publicada por GitHub, con una dirección que no sea exactamente la de nuestra release o con cualquier diferencia de paquete, versión
 * o firma, no se instala nada.
 */
internal object UpdateInstaller {
    const val ASSET = "app-tv.apk"
    /** Tope del APK (el de la 6.2.2 pesa 30 MB). */
    const val MAX_BYTES = 60L * 1024 * 1024
    private const val DOWNLOAD_PREFIX = "https://github.com/D4rumanDev/kaizen/releases/download/"
    private val DIGEST = Regex("^sha256:([0-9a-fA-F]{64})$")

    /** Lo que hace falta saber del APK de la release para bajarlo y comprobarlo. [sha256] va en minúsculas. */
    data class Asset(val tag: String, val url: String, val size: Long, val sha256: String)

    sealed class AssetResult {
        data class Ok(val asset: Asset) : AssetResult()
        /** [reason] va para el usuario. */
        data class Refused(val reason: String) : AssetResult()
    }

    /** El APK de la release [expectedTag] (la de la que se avisó), o por qué no se acepta. */
    fun pickAsset(json: String, expectedTag: String): AssetResult {
        val release = UpdateChecker.parseRelease(json) ?: return AssetResult.Refused("GitHub no devolvió una release válida")
        if (release.tag != expectedTag) return AssetResult.Refused("La última release ya no es la ${expectedTag.removePrefix("v")}")
        val assets = runCatching { JsonParser.parseString(json).asJsonObject.getAsJsonArray("assets") }.getOrNull()
            ?: return AssetResult.Refused("La release no trae ficheros")
        val entry = assets.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            .firstOrNull { it.get("name")?.takeIf { n -> n.isJsonPrimitive }?.asString == ASSET }
            ?: return AssetResult.Refused("La release no trae $ASSET")
        fun text(name: String) = entry.get(name)?.takeIf { it.isJsonPrimitive }?.asString
        val digest = text("digest")?.let { DIGEST.matchEntire(it) }?.groupValues?.get(1)
            ?: return AssetResult.Refused("GitHub no publica la huella (SHA-256) de $ASSET: no se instala")
        val size = runCatching { entry.get("size").asLong }.getOrNull()?.takeIf { it in 1..MAX_BYTES }
            ?: return AssetResult.Refused("El tamaño de $ASSET no es el esperado")
        if (text("state") != "uploaded") return AssetResult.Refused("$ASSET aún no está del todo subido")
        val url = text("browser_download_url")
        if (url != "$DOWNLOAD_PREFIX${release.tag}/$ASSET") return AssetResult.Refused("La dirección de descarga no es la de la release del proyecto")
        return AssetResult.Ok(Asset(release.tag, url, size, digest.lowercase()))
    }

    /** Nombre, versión y certificados (SHA-256 en hexadecimal) de un paquete, instalado o por instalar. */
    data class PackageFacts(val packageName: String, val versionCode: Long, val signatures: Set<String>)

    /** Las firmas de un paquete: las actuales (API 28+) y, si el sistema no las da (algunas teles no las leen de un APK por instalar), las clásicas. */
    fun chooseCerts(current: Set<String>, classic: Set<String>): Set<String> = current.ifEmpty { classic }

    /** Un paquete en una línea para el registro: nombre, versión y las firmas abreviadas (8 primeros caracteres de cada huella pública; «ninguna» si no se pudo leer). */
    fun describe(facts: PackageFacts?): String =
        facts?.let { "${it.packageName} v${it.versionCode}, firmas: ${it.signatures.sorted().joinToString(",") { cert -> cert.take(8) }.ifEmpty { "ninguna" }}" } ?: "ilegible"

    /** Null si [archive] puede actualizar a [installed]: mismo paquete, versión mayor y exactamente las mismas firmas; si no, el motivo. */
    fun verifyPackage(installed: PackageFacts, archive: PackageFacts?): String? = when {
        archive == null -> "No se pudo leer el paquete descargado"
        installed.signatures.isEmpty() -> "No se pudo leer la firma de la app instalada"
        archive.packageName != installed.packageName -> "El paquete descargado no es Kaizen"
        archive.versionCode <= installed.versionCode -> "El paquete descargado no es más nuevo que el instalado"
        archive.signatures.isEmpty() -> "No se pudo leer la firma del paquete descargado"
        archive.signatures != installed.signatures -> "La firma del paquete descargado no es la de Kaizen"
        else -> null
    }

    /**
     * Baja [url] a [dest] (pasando por `dest.part`) y devuelve el SHA-256 de lo escrito, en minúsculas. Exige que el tamaño sea exactamente [expectedSize] (corta en cuanto
     * lo pasa) y borra el parcial y [dest] ante cualquier fallo o cancelación. [progress] recibe lo bajado y el total; si lanza `CancellationException`, se cancela.
     */
    suspend fun download(client: OkHttpClient, url: String, dest: File, expectedSize: Long, progress: (done: Long, total: Long) -> Unit): String = withContext(Dispatchers.IO) {
        val part = File(dest.path + ".part")
        dest.delete()
        part.delete()
        var finished = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val request = Request.Builder().url(url).header("User-Agent", UserAgent.current()).header("Accept", "application/octet-stream").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("GitHub respondió ${response.code}")
                var done = 0L
                val buffer = ByteArray(64 * 1024)
                response.body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            done += read
                            if (done > expectedSize) throw IOException("El fichero es más grande de lo anunciado")
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            currentCoroutineContext().ensureActive()
                            progress(done, expectedSize)
                        }
                    }
                }
                if (done != expectedSize) throw IOException("El fichero es más pequeño de lo anunciado")
            }
            if (!part.renameTo(dest)) throw IOException("No se pudo guardar el fichero")
            finished = true
            digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            if (!finished) { part.delete(); dest.delete() }
        }
    }
}
