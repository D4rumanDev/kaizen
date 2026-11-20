package app.kaizen.commons

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * Direcciones a las que el proxy local no debe llegar nunca: el propio equipo, la red de casa y el enlace local. Un servidor de vídeo (o una
 * redirección, o una lista HLS) que apunte a `192.168.x.x`, `127.x` o `169.254.x` usaría la tele para tocar el router o cualquier aparato de la red.
 * No cubre los prefijos que traducen IPv4 dentro de IPv6 (NAT64, `64:ff9b::/96`).
 */
internal object PrivateAddress {
    fun isPrivate(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return true
        val bytes = address.address
        return when (bytes.size) {
            // 0.0.0.0/8 («esta red»), 100.64.0.0/10 (CGNAT: la red del operador), 198.18.0.0/15 (pruebas de red) y 255.255.255.255 (difusión).
            4 -> bytes[0] == 0.toByte() ||
                (bytes[0] == 100.toByte() && (bytes[1].toInt() and 0xC0) == 0x40) ||
                (bytes[0] == 198.toByte() && (bytes[1].toInt() and 0xFE) == 18) ||
                bytes.all { it == 0xFF.toByte() }
            // fc00::/7 (direcciones locales únicas) y ::ffff:a.b.c.d (IPv4 mapeada: la misma dirección de casa con otra forma).
            16 -> (bytes[0].toInt() and 0xFE) == 0xFC || isMappedPrivate(bytes)
            else -> true
        }
    }

    private fun isMappedPrivate(bytes: ByteArray): Boolean {
        val mapped = (0 until 10).all { bytes[it] == 0.toByte() } && bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
        return mapped && isPrivate(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
    }
}

/** El proxy intentó conectar con un destino privado. */
internal class PrivateDestinationException(message: String) : IOException(message)

/**
 * Fábrica de sockets que comprueba el destino en el momento de conectar. Es el único punto por el que pasan los nombres resueltos, las IP literales
 * (OkHttp no consulta el DNS con ellas) y los saltos de una redirección, y no deja hueco entre «resolver» y «conectar» (DNS rebinding).
 */
internal class GuardedSocketFactory(
    private val blocked: (InetSocketAddress) -> Boolean = { PrivateAddress.isPrivate(it.address) },
) : SocketFactory() {

    private fun guarded(): Socket = object : Socket() {
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val target = endpoint as? InetSocketAddress
            // Sin resolver no hay dirección que mirar: super lanza UnknownHostException.
            if (target != null && !target.isUnresolved && blocked(target)) {
                throw PrivateDestinationException("Destino privado bloqueado: ${target.address.hostAddress}")
            }
            super.connect(endpoint, timeout)
        }
    }

    override fun createSocket(): Socket = guarded()
    override fun createSocket(host: String, port: Int): Socket = guarded().also { it.connect(InetSocketAddress(host, port)) }
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        guarded().also { it.bind(InetSocketAddress(localHost, localPort)); it.connect(InetSocketAddress(host, port)) }
    override fun createSocket(host: InetAddress, port: Int): Socket = guarded().also { it.connect(InetSocketAddress(host, port)) }
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        guarded().also { it.bind(InetSocketAddress(localAddress, localPort)); it.connect(InetSocketAddress(address, port)) }
}
