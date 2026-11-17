package app.kaizen.commons.warp

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.kaizen.tv.ui.KaizenToast

/**
 * Pantalla transparente que pide a Android el permiso de VPN (el aviso «Solicitud de conexión»): Android obliga a que una persona lo acepte
 * una vez y no se puede saltar. Se abre al elegir un modo del túnel WARP en Configuración. Si ya está concedido, se cierra sola.
 */
class VpnConsentActivity : ComponentActivity() {
    private val ask = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        KaizenToast.toast(if (result.resultCode == RESULT_OK) "Túnel WARP permitido" else "Sin el permiso de VPN el túnel WARP no puede funcionar")
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = VpnService.prepare(this)
        if (intent == null) finish() else ask.launch(intent)
    }

    companion object {
        /** Abre el aviso de VPN si todavía no se ha aceptado. */
        fun askIfNeeded(context: Context) {
            if (VpnService.prepare(context) != null) {
                context.startActivity(Intent(context, VpnConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
