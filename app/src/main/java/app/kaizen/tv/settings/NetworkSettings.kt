package app.kaizen.tv.settings

import android.content.Context
import app.kaizen.R
import app.kaizen.bypass.launchBypass
import app.kaizen.commons.AppScope
import app.kaizen.commons.FallbackDns
import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.TlsPolicy
import app.kaizen.commons.UserAgent
import app.kaizen.commons.appString
import app.kaizen.commons.findActivity
import app.kaizen.commons.logError
import app.kaizen.commons.runCatchingCoop
import app.kaizen.commons.warp.VpnConsentActivity
import app.kaizen.commons.warp.WarpMode
import app.kaizen.commons.warp.WarpTunnel
import app.kaizen.tv.ServerMemory
import app.kaizen.tv.ui.KaizenToast
import app.kaizen.tv.ui.TVMain
import kotlinx.coroutines.launch

/**
 * Configuración › Fuentes y red. Arriba, el servidor (usar el último, preferido); debajo, dos submenús: Red (cómo se identifica y resuelve la app, certificados, acceso a
 * las webs protegidas) y Bloqueos (aviso de LaLiga y túnel). Las fuentes se activan y desactivan en su página, no aquí.
 */
internal class NetworkSettingsItems(private val context: Context) {
    private val icon = R.drawable.settings_ic_network

    fun items(): List<SettingItem> {
        val servers = listOf(NO_SERVER) + ServerMemory.knownServers()
        return listOf(
            CheckItem(appString(R.string.ui_usar_ultimo_servidor), appString(R.string.ui_sin_preguntar_la_lista_sale_solo_si_falla), KaizenSettings::autoServer),
            ChoiceItem(appString(R.string.ui_servidor_preferido), servers,
                { servers.indexOf(KaizenSettings.preferredServer ?: NO_SERVER).coerceAtLeast(0) },
                { KaizenSettings.preferredServer = servers[it].takeIf { name -> name != NO_SERVER } }),
            submenu("Red", icon, { "DNS de relevo: ${KaizenSettings.dnsProvider}" }, ::networkItems),
            submenu("Bloqueos", icon, { "Túnel: ${WarpMode.of(KaizenSettings.warpMode).label}" }, ::blockItems)
        )
    }

    private fun networkItems() = listOf(
        // Un solo agente para todo lo que sale a internet: el del navegador de esta TV o uno aleatorio (ver UserAgent). Las cookies de Cloudflare ya guardadas siguen con su agente.
        ChoiceItem("Identificación ante las webs (User-Agent)", listOf("Navegador de esta TV", "Aleatorio"),
            { if (UserAgent.mode == UserAgent.RANDOM) 1 else 0 }) { position ->
            KaizenSettings.userAgentMode = UserAgent.MODES[position]
        },
        ActionItem("Generar otro agente aleatorio", { UserAgent.random().take(60) }) {
            KaizenToast.toast("Nuevo agente: ${UserAgent.regenerate().take(60)}")
        },
        tunedChoice("DNS de relevo (si el de la red no resuelve una fuente)", FallbackDns.Provider.entries.map { it.label }, KaizenSettings::dnsProvider) { it },
        ChoiceItem(appString(R.string.ui_validacion_tls), listOf(appString(R.string.ui_estricta), appString(R.string.ui_permisiva_hasta_cerrar_la_app)),
            { if (TlsPolicy.isPermissive) 1 else 0 }, { TlsPolicy.isPermissive = it == 1 }),
        ActionItem(appString(R.string.ui_servidores_rechazados), { TlsPolicy.rejectedHosts.take(5).joinToString().ifEmpty { appString(R.string.ui_ninguno) } }) { },
        ActionItem("Recrear el acceso a las webs protegidas", { "Si una web no abre" }) { context ->
            KaizenToast.toast("Comprobando Cloudflare...")
            context.findActivity()?.launchBypass(TVMain.BYPASS_REQUEST, force = true)
        }
    )

    private fun blockItems() = listOf(
        CheckItem("Avisar de bloqueos de LaLiga", "Consulta hayahora.futbol al abrir Fuentes (no envía datos tuyos). Apagado: sin consulta, salvo con el túnel en «solo con bloqueo»", KaizenSettings::laLigaWarning),
        // El túnel solo para Kaizen: elegir un modo pide, la primera vez, el aviso de VPN de Android (una pantalla con un botón).
        ChoiceItem("Evitar bloqueos (túnel solo para Kaizen)", WarpMode.entries.map { it.label }, { WarpMode.entries.indexOf(WarpMode.of(KaizenSettings.warpMode)).coerceAtLeast(0) }) { position ->
            KaizenSettings.warpMode = WarpMode.entries[position].label
            if (WarpMode.entries[position] != WarpMode.OFF) VpnConsentActivity.askIfNeeded(context)
            AppScope.launch { runCatchingCoop { WarpTunnel.evaluate() }.onSuccess { it?.let(KaizenToast::toastLong) }.onFailure(::logError) }
        }
    )

    private companion object {
        const val NO_SERVER = "Ninguno"
    }
}
