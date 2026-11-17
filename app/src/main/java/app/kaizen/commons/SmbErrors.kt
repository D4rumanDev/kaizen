package app.kaizen.commons

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Lo que una persona entiende de un fallo de SMB: los mensajes de jcifs («Logon failure», «STATUS_ACCESS_DENIED»…) no dicen qué hacer. [describe] recorre la cadena de causas y
 * devuelve un texto en español (nunca con la contraseña ni la dirección con usuario); el detalle técnico lo deja en el registro quien llama.
 */
object SmbErrors {
    fun describe(error: Throwable, host: String): String {
        val chain = generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.take(8).toList()
        val text = chain.joinToString(" | ") { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }.lowercase()
        fun has(vararg parts: String) = parts.any { it in text }
        return when {
            has("smbauthexception", "logon failure", "status_logon_failure", "bad password", "wrong password", "unknown user name") ->
                "Usuario o contraseña incorrectos. Revísalos y vuelve a intentarlo."
            has("access is denied", "status_access_denied", "access denied", "permission denied", "no tienes permiso") ->
                "No tienes permiso para entrar aquí."
            has("write protect", "read-only", "read only", "status_media_write_protected") ->
                "Este recurso es de solo lectura."
            chain.any { it is UnknownHostException } || has("unknownhostexception", "unable to find", "no such host", "nodename nor servname") ->
                "No se encuentra $host. Comprueba que está encendido y en la misma red."
            chain.any { it is SocketTimeoutException } || has("timed out", "timeout", "sockettimeoutexception") ->
                "El servidor tarda demasiado en responder. Inténtalo de nuevo."
            chain.any { it is ConnectException || it is NoRouteToHostException } || has("connection refused", "no route to host", "network is unreachable", "failed to connect") ->
                "No se puede conectar con $host. Comprueba que el NAS está encendido, en la misma red y con SMB activado."
            has("smb1", "negotiat", "unsupported dialect", "protocol") ->
                "No se pudo acordar una versión segura de SMB. Kaizen solo usa SMB 2 o 3: actívalo en el servidor."
            has("not found", "bad_network_name", "status_object_name_not_found", "status_object_path_not_found") ->
                "No existe esa carpeta o ese recurso compartido."
            else -> "No se pudo conectar con $host."
        }
    }
}
