package app.kaizen.commons

import android.app.Activity
import android.content.Intent
import kotlinx.coroutines.cancelChildren

/**
 * Salida completa de la app: corta el trabajo en segundo plano (catálogo, notas, recientes), el proxy y las
 * conexiones abiertas, y cierra todas las pantallas. El proceso no se mata: así terminan las escrituras pendientes
 * (progreso, historial) y Android lo libera cuando quiera.
 */
object AppExit {
    fun exit(activity: Activity) {
        // Antes de cancelar las tareas: el temporizador del aviso «Pulsa Atrás otra vez» es una de ellas y, perdido, el aviso reaparecía al reabrir la app.
        app.kaizen.tv.ui.KaizenToast.clear()
        AppScope.coroutineContext.cancelChildren()
        KaizenProxy.stop()
        activity.stopService(Intent(activity, KaizenProxyService::class.java))
        AppHttpClient.shutdown()
        activity.finishAffinity()
    }
}
