package app.kaizen.commons

import okhttp3.Dns
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit

/**
 * DNS de las fuentes: el de la red (el que dé el router o el servidor DNS de casa) y, solo si con él no se consigue conectar con
 * un servidor, las IP que da Quad9 (9.9.9.9, con bloqueo de malware y publicidad) por DNS sobre TLS
 * (puerto 853, certificado comprobado contra `dns.quad9.net`). [prefer] marca el servidor, y durante [PREFER] se resuelve con
 * Quad9 sin volver a probar el DNS de la red (que da la misma IP inalcanzable); pasado ese tiempo se vuelve al de la red.
 * Las consultas a Quad9 salen solo de este relevo (y solo con nombres de servidores de fuentes): con la red bien no se manda
 * ninguna. Llamar [prefer] desde un hilo de fondo: puede esperar hasta [CONNECT] + [READ] por servidor de Quad9.
 */
object FallbackDns : Dns {
    /**
     * El DNS de relevo (DNS sobre TLS): el nombre de su certificado y sus dos IP (la segunda es el respaldo de la primera). De fábrica Quad9
     * (9.9.9.9); Configuración › Servidores y red permite otro ([Provider]).
     */
    enum class Provider(val label: String, val dotName: String, val servers: List<ByteArray>) {
        QUAD9("Quad9 (9.9.9.9)", "dns.quad9.net", listOf(byteArrayOf(9, 9, 9, 9), byteArrayOf(149.toByte(), 112, 112, 112))),
        CLOUDFLARE("Cloudflare (1.1.1.1)", "cloudflare-dns.com", listOf(byteArrayOf(1, 1, 1, 1), byteArrayOf(1, 0, 0, 1))),
        GOOGLE("Google (8.8.8.8)", "dns.google", listOf(byteArrayOf(8, 8, 8, 8), byteArrayOf(8, 8, 4, 4)));

        companion object {
            fun of(label: String?): Provider = entries.firstOrNull { it.label == label } ?: QUAD9
        }
    }

    @Volatile var provider: Provider = Provider.QUAD9
    private val DOT_NAME get() = provider.dotName
    private val DOT_SERVERS get() = provider.servers
    private const val DOT_PORT = 853
    private val CONNECT = 3.seconds
    private val READ = 4.seconds
    private val PREFER = 10.minutes
    private val ANSWER = 5.minutes
    private val NO_ANSWER = 30.seconds
    private const val MAX_MESSAGE_BYTES = 4096
    private const val MAX_ADDRESSES = 8
    private const val TYPE_A = 1
    private const val CLASS_IN = 1

    private val preferred = ConcurrentHashMap<String, Long>()
    private val answers = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()

    /** Para las pruebas: quien resuelve por Quad9 (nombre → IP), el DNS de la red y el reloj. */
    @Volatile internal var resolver: (String) -> List<InetAddress> = ::queryQuad9
    @Volatile internal var system: Dns = Dns.SYSTEM
    @Volatile internal var clock: () -> Long = System::currentTimeMillis

    /** Tope de nombres recordados: los CDN con subdominios aleatorios (Voe) los llenarían sin fin. */
    private const val MAX_NAMES = 512

    override fun lookup(hostname: String): List<InetAddress> {
        val until = preferred[hostname]
        if (until != null) {
            if (clock() < until) answerFor(hostname).takeIf { it.isNotEmpty() }?.let { return it }
            else forget(hostname)
        }
        return system.lookup(hostname)
    }

    /** Qué pasa con un nombre que la red no resuelve, según Quad9 (solo se pregunta cuando ya ha fallado; no cambia cómo se resuelve nada). */
    enum class Diagnosis {
        /** La red lo resuelve: el fallo no es de DNS. */
        NETWORK_RESOLVES,

        /** La red no lo resuelve y Quad9 sí: lo filtra o falla el DNS configurado (p. ej. AdGuard), no el servidor del vídeo. */
        NETWORK_DNS_FAILS,

        /** Ni la red ni Quad9 lo resuelven (o Quad9 no contesta): el nombre no existe o ya no está. */
        UNRESOLVED
    }

    /**
     * Distingue un fallo del DNS configurado de un fallo del servidor: compara la red con Quad9 (por TLS). Solo para diagnosticar y avisar:
     * no se usa la respuesta de Quad9 para conectar, así que no se salta el filtro de la red. Bloquea hasta lo que tarde Quad9: llamar desde un hilo de fondo.
     */
    fun diagnose(host: String): Diagnosis {
        if (runCatching { system.lookup(host) }.getOrDefault(emptyList()).isNotEmpty()) return Diagnosis.NETWORK_RESOLVES
        if (!eligible(host)) return Diagnosis.UNRESOLVED
        return if (answerFor(host).isNotEmpty()) Diagnosis.NETWORK_DNS_FAILS else Diagnosis.UNRESOLVED
    }

    /** [host] ya se está resolviendo por Quad9. */
    fun isPreferred(host: String): Boolean = preferred[host]?.let { clock() < it } == true

    /** Las IP de [host] según el DNS de relevo (para lo que no puede esperar a que falle la red, como consultar el estado de los bloqueos); vacío si no es un nombre de internet o no contesta. */
    fun relayLookup(host: String): List<InetAddress> = if (eligible(host)) answerFor(host) else emptyList()

    /**
     * Pasa [host] a resolverse por Quad9; false si no merece la pena reintentar: nombre que no es de un servidor de internet,
     * Quad9 sin respuesta, o IP que son las mismas que ya dio la red.
     */
    fun prefer(host: String): Boolean {
        if (!eligible(host)) return false
        val addresses = answerFor(host)
        if (addresses.isEmpty()) return false
        val network = runCatching { system.lookup(host) }.getOrDefault(emptyList())
        if (addresses.all { it in network }) return false
        preferred[host] = clock() + PREFER.inWholeMilliseconds
        return true
    }

    /** Vuelve al DNS de la red para [host] (el relevo tampoco sirvió). */
    fun forget(host: String) {
        preferred.remove(host)
        answers.remove(host)
    }

    /** Olvida lo aprendido (las pruebas). */
    fun reset() {
        preferred.clear()
        answers.clear()
    }

    /** Solo nombres de servidores de internet: ni IP escritas, ni nombres de la red local. */
    private fun eligible(host: String): Boolean {
        val name = host.lowercase()
        if (!name.contains('.') || name.contains(':')) return false
        if (name.all { it.isDigit() || it == '.' }) return false
        return listOf(".local", ".lan", ".internal", ".home", ".localdomain").none { name.endsWith(it) }
    }

    private fun answerFor(host: String): List<InetAddress> {
        answers[host]?.let { (at, found) ->
            if (clock() - at < (if (found.isEmpty()) NO_ANSWER.inWholeMilliseconds else ANSWER.inWholeMilliseconds)) return found
        }
        val found = runCatching { resolver(host) }.getOrDefault(emptyList())
        if (answers.size >= MAX_NAMES) { val now = clock(); answers.entries.removeAll { now - it.value.first >= ANSWER.inWholeMilliseconds }; preferred.entries.removeAll { now >= it.value } }
        if (answers.size >= MAX_NAMES) answers.clear()
        if (preferred.size >= MAX_NAMES) preferred.clear()
        answers[host] = clock() to found
        return found
    }

    /** Consulta el registro A de [host] a Quad9 por TLS (la 9.9.9.9 y, si no contesta, la 149.112.112.112). */
    private fun queryQuad9(host: String): List<InetAddress> {
        val id = (System.nanoTime() and 0xFFFF).toInt()
        val query = buildQuery(host, id) ?: return emptyList()
        for (server in DOT_SERVERS) {
            val reply = runCatching { exchange(InetAddress.getByAddress(DOT_NAME, server), query) }.getOrNull() ?: continue
            return parseAnswer(host, reply, id)
        }
        return emptyList()
    }

    /** Un mensaje DNS por una conexión TLS nueva (RFC 7858: cada mensaje lleva delante su longitud en dos bytes). */
    private fun exchange(address: InetAddress, query: ByteArray): ByteArray {
        Socket().use { raw ->
            raw.connect(InetSocketAddress(address, DOT_PORT), CONNECT.toInt(DurationUnit.MILLISECONDS))
            raw.soTimeout = READ.toInt(DurationUnit.MILLISECONDS)
            // Con el nombre de Quad9 en el SNI y la comprobación de nombre activada, un certificado que no sea el suyo se rechaza.
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, DOT_NAME, DOT_PORT, true) as SSLSocket
            tls.use {
                tls.sslParameters = tls.sslParameters.also { it.endpointIdentificationAlgorithm = "HTTPS" }
                tls.soTimeout = READ.toInt(DurationUnit.MILLISECONDS)
                tls.startHandshake()
                val out = DataOutputStream(tls.outputStream)
                out.writeShort(query.size)
                out.write(query)
                out.flush()
                val input = DataInputStream(tls.inputStream)
                val size = input.readUnsignedShort()
                require(size in 12..MAX_MESSAGE_BYTES) { "respuesta DNS de $size bytes" }
                return ByteArray(size).also { input.readFully(it) }
            }
        }
    }

    /** La pregunta «registro A de [host]»: cabecera de 12 bytes, el nombre por etiquetas, tipo y clase. Null si el nombre no es válido. */
    internal fun buildQuery(host: String, id: Int): ByteArray? {
        val labels = host.trimEnd('.').split('.')
        if (labels.any { it.isEmpty() || it.length > 63 } || host.length > 253) return null
        val out = java.io.ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeShort(id)
            writeShort(0x0100) // consulta estándar con recursión pedida
            writeShort(1); writeShort(0); writeShort(0); writeShort(0)
            labels.forEach { label -> writeByte(label.length); write(label.toByteArray(Charsets.US_ASCII)) }
            writeByte(0)
            writeShort(TYPE_A); writeShort(CLASS_IN)
        }
        return out.toByteArray()
    }

    /**
     * Las IP públicas de los registros A de una respuesta DNS binaria a la consulta [id]; lo que no sea una IPv4 pública (o una
     * respuesta de otra consulta, con error o rota) se descarta.
     */
    internal fun parseAnswer(host: String, message: ByteArray, id: Int): List<InetAddress> = runCatching {
        val data = DataInputStream(message.inputStream())
        if (data.readUnsignedShort() != id) return emptyList()
        val flags = data.readUnsignedShort()
        // Debe ser una respuesta (bit 15) sin error (los 4 bits bajos: 0 = bien, 3 = el nombre no existe).
        if (flags and 0x8000 == 0 || flags and 0x000F != 0) return emptyList()
        val questions = data.readUnsignedShort()
        val answerCount = data.readUnsignedShort()
        data.skipBytes(4)
        repeat(questions) { skipName(data); data.skipBytes(4) }
        val found = ArrayList<InetAddress>()
        repeat(answerCount) {
            skipName(data)
            val type = data.readUnsignedShort()
            data.skipBytes(6) // clase y TTL
            val length = data.readUnsignedShort()
            val record = ByteArray(length).also { data.readFully(it) }
            if (type == TYPE_A && length == 4) publicIpv4(host, IntArray(4) { record[it].toInt() and 0xFF })?.let { found += it }
        }
        found.take(MAX_ADDRESSES)
    }.getOrDefault(emptyList())

    /** Salta un nombre de la respuesta: etiquetas hasta el 0, o un puntero de compresión (2 bytes). */
    private fun skipName(data: DataInputStream) {
        while (true) {
            val length = data.readUnsignedByte()
            if (length == 0) return
            if (length and 0xC0 == 0xC0) { data.readUnsignedByte(); return }
            data.skipBytes(length)
        }
    }

    /** Una IPv4 de internet (ni local, ni privada, ni de la red de compartición 100.64/10, ni reservada); null si no. */
    private fun publicIpv4(host: String, octets: IntArray): InetAddress? {
        val first = octets[0]
        val second = octets[1]
        if (first == 0 || first == 10 || first == 127 || first >= 224) return null
        if (first == 169 && second == 254) return null
        if (first == 172 && second in 16..31) return null
        if (first == 192 && second == 168) return null
        if (first == 100 && second in 64..127) return null
        // Con el nombre puesto, TLS y los registros no necesitan una consulta inversa al DNS de la red (que es el que falla).
        return InetAddress.getByAddress(host, ByteArray(4) { octets[it].toByte() })
    }
}
