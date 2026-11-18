package app.kaizen.commons.warp

import android.content.Context
import app.kaizen.App
import app.kaizen.commons.DebugLog
import app.kaizen.commons.FallbackDns
import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.LaLigaBlock
import app.kaizen.commons.logError
import app.kaizen.source.AnimeSources
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.minutes

/**
 * El túnel WARP (WireGuard de Cloudflare) **solo para el tráfico de Kaizen**: es una VPN de Android limitada a esta app
 * (`includeApplication`), así que el resto de la TV no se toca. Lo usa para llegar a las fuentes cuando un operador bloquea por IP las
 * direcciones de Cloudflare durante un partido de LaLiga (ver [LaLigaBlock]). Sin ajustes técnicos: la cuenta gratuita se registra sola la primera vez.
 *
 * Android exige que el usuario acepte una vez el aviso de VPN ([VpnConsentActivity]); no se puede saltar. El motor es el de la librería
 * oficial `com.wireguard.android:tunnel` (trae su código nativo; no hace falta instalar Go). El registro usa la API de cliente de Cloudflare, que no
 * es pública ni está documentada: puede dejar de funcionar y sus condiciones de uso pueden no permitirlo. Por eso va apagado de fábrica.
 */
object WarpTunnel {
    private const val FILE = "kaizen_warp"
    private const val API = "https://api.cloudflareclient.com/v0a1922/reg"
    private const val PEER_FALLBACK_NAME = "kaizen"

    private val backend by lazy { GoBackend(App.context) }
    private val tunnel = object : Tunnel {
        override fun getName() = PEER_FALLBACK_NAME
        override fun onStateChange(newState: Tunnel.State) { DebugLog.info("WARP", "Túnel: $newState") }
    }

    @Volatile private var clearSince = 0L

    /** Un cliente HTTP con la validación TLS estricta del sistema siempre, aunque el usuario ponga «permisiva» en las fuentes: aquí se reciben las claves del túnel. */
    private val strictClient by lazy { okhttp3.OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build() }

    /** Una sola evaluación a la vez: subir o bajar el túnel y registrar la cuenta no se hacen en paralelo. */
    private val lock = kotlinx.coroutines.sync.Mutex()
    private var watcher: kotlinx.coroutines.Job? = null

    /** Revisa el modo al abrir la app y cada [CHECK]; llamarla otra vez no abre otro bucle. */
    @Synchronized fun watch() {
        if (watcher?.isActive == true) return
        watcher = app.kaizen.commons.AppScope.launch {
            while (true) {
                if (WarpMode.of(KaizenSettings.warpMode) != WarpMode.OFF || isUp()) {
                    app.kaizen.commons.runCatchingCoop { evaluate() }.onFailure { logError(it) }
                }
                kotlinx.coroutines.delay(CHECK)
            }
        }
    }

    private val CHECK = 5.minutes

    private fun prefs() = app.kaizen.commons.KaizenStore.prefs(FILE)

    /**
     * El túnel está subido. Sin cuenta guardada nunca se ha subido, así que ni se toca el motor nativo (con el modo apagado, que es lo normal,
     * la librería ni se carga).
     */
    fun isUp(): Boolean = prefs().contains("private") && runCatching { backend.getState(tunnel) == Tunnel.State.UP }.getOrDefault(false)

    /** El usuario ya aceptó el aviso de VPN de Android para esta app. */
    fun hasConsent(context: Context): Boolean = android.net.VpnService.prepare(context) == null

    private fun savedAccount(): WarpAccount? = prefs().let { p ->
        WarpAccount(
            p.getString("private", null) ?: return null, p.getString("public", null) ?: return null, p.getString("peer", null) ?: return null,
            p.getString("endpoint", null) ?: return null, p.getString("v4", null) ?: return null, p.getString("v6", null)
        )
    }

    private fun save(account: WarpAccount) {
        prefs().edit().putString("private", account.privateKey).putString("public", account.publicKey).putString("peer", account.peerPublicKey)
            .putString("endpoint", account.endpoint).putString("v4", account.addressV4).putString("v6", account.addressV6).apply()
    }

    /** La cuenta guardada o, la primera vez, una nueva registrada en Cloudflare (clave generada aquí; la privada no sale del dispositivo). */
    private suspend fun account(): WarpAccount? = savedAccount() ?: withContext(Dispatchers.IO) {
        val pair = KeyPair()
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val request = Request.Builder().url(API)
            .header("User-Agent", "okhttp/3.12.1").header("CF-Client-Version", "a-6.3-1922")
            .post(WarpAccount.registrationBody(pair.publicKey.toBase64(), iso).toRequestBody("application/json".toMediaType())).build()
        runCatching {
            strictClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null.also { DebugLog.info("WARP", "El registro respondió HTTP ${response.code}") }
                WarpAccount.parse(pair.privateKey.toBase64(), pair.publicKey.toBase64(), response.body?.string().orEmpty())
            }
        }.getOrNull()?.also(::save)
    }

    private fun config(account: WarpAccount): Config {
        val address = mutableListOf(InetNetwork.parse("${account.addressV4}/32"))
        account.addressV6?.let { address += InetNetwork.parse("$it/128") }
        val dns = InetAddress.getByAddress(FallbackDns.provider.servers.first())
        val iface = Interface.Builder().addAddresses(address).addDnsServer(dns).setMtu(1280)
            .setKeyPair(KeyPair(Key.fromBase64(account.privateKey)))
            // Solo esta app: el resto del tráfico de la TV no entra en el túnel.
            .includeApplication(App.context.packageName).build()
        val peer = Peer.Builder().setPublicKey(Key.fromBase64(account.peerPublicKey))
            .addAllowedIp(InetNetwork.parse("0.0.0.0/0")).addAllowedIp(InetNetwork.parse("::/0"))
            .setEndpoint(InetEndpoint.parse(account.endpoint)).setPersistentKeepalive(25).build()
        return Config.Builder().setInterface(iface).addPeer(peer).build()
    }

    /** Sube el túnel; devuelve por qué no si no se pudo. */
    suspend fun start(): String? {
        if (isUp()) return null
        if (!hasConsent(App.context)) return "Falta aceptar el aviso de VPN de Android: Configuración › Servidores y red › Túnel WARP"
        val account = account() ?: return "No se pudo registrar la cuenta gratuita de WARP"
        return withContext(Dispatchers.IO) {
            try {
                backend.setState(tunnel, Tunnel.State.UP, config(account))
                DebugLog.info("WARP", "Túnel subido solo para Kaizen")
                null
            } catch (e: Exception) {
                logError(e)
                "No se pudo subir el túnel: ${e.javaClass.simpleName}"
            }
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }.onFailure(::logError)
        DebugLog.info("WARP", "Túnel bajado")
    }

    /** Estado para el menú y para ADB. */
    fun status(context: Context): String {
        val mode = WarpMode.of(KaizenSettings.warpMode)
        return "modo=${mode.label} | permiso_vpn=${if (hasConsent(context)) "concedido" else "falta"} | cuenta=${if (savedAccount() != null) "registrada" else "sin registrar"} | tunel=${if (isUp()) "subido" else "bajado"}"
    }

    /**
     * Mira el modo y, si hace falta, sube o baja el túnel. Se llama al abrir la app y cada pocos minutos mientras está abierta. Devuelve un aviso si
     * había que subirlo y no se pudo (falta el aviso de VPN, o el registro falló).
     */
    suspend fun evaluate(): String? = lock.withLock {
        val mode = WarpMode.of(KaizenSettings.warpMode)
        val blocked = mode == WarpMode.ON_BLOCK && LaLigaBlock.blockedHosts(AnimeSources.selected.map { it.host }).isNotEmpty()
        val now = System.currentTimeMillis()
        if (blocked) clearSince = 0L else if (clearSince == 0L) clearSince = now
        when (WarpPolicy.decide(mode, blocked, isUp(), clearSince, now)) {
            WarpAction.START -> start()
            WarpAction.STOP -> { stop(); null }
            WarpAction.NONE -> null
        }
    }
}
