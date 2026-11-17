package app.kaizen.commons

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address

/**
 * Busca en la red local servidores que se anuncian como SMB (`_smb._tcp`, mDNS/DNS-SD: NAS, PC con carpetas compartidas) para no teclear su dirección. Es una ayuda, no una
 * necesidad: si el sistema no lo permite o el servidor no se anuncia, simplemente no sale ninguno. Se puede apagar en Configuración › Copia de seguridad › Carpeta de red
 * (`KaizenSettings.smbDiscovery`). Solo IPv4. Android resuelve de uno en uno, así que se encolan.
 */
object SmbDiscovery {
    /** Un servidor encontrado: el nombre con que se anuncia y su dirección IPv4. */
    class Found(val name: String, val address: String)

    /** Emite cada servidor al encontrarlo y para la búsqueda al cancelar el flujo. */
    fun find(context: Context): Flow<Found> = callbackFlow {
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) { close(); return@callbackFlow }
        val queue = ArrayDeque<NsdServiceInfo>()
        var resolving = false
        val seen = HashSet<String>()

        fun next() {
            val service = synchronized(queue) { if (resolving) null else queue.removeFirstOrNull()?.also { resolving = true } } ?: return
            runCatching {
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) { synchronized(queue) { resolving = false }; next() }
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host
                        if (host is Inet4Address && seen.add(host.hostAddress.orEmpty())) trySend(Found(info.serviceName, host.hostAddress.orEmpty()))
                        synchronized(queue) { resolving = false }
                        next()
                    }
                })
            }.onFailure { synchronized(queue) { resolving = false } }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(queue) { queue.addLast(info) }
                next()
            }
        }
        runCatching { nsd.discoverServices("_smb._tcp", NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { close() }
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }
}
