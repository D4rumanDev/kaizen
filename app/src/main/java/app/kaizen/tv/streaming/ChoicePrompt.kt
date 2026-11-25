package app.kaizen.tv.streaming

import app.kaizen.tv.ui.theme.*

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import app.kaizen.tv.ui.Dialog
import androidx.compose.ui.window.DialogProperties

/** Lista de opciones (servidor, calidad, idioma) sobre la pantalla actual, sin lanzar otra actividad. */
object ChoicePrompt {
    /**
     * Muestra [options] y llama a [onResult] con el id elegido, o con null si se cierra con Atrás.
     * Se puede llamar desde cualquier hilo.
     */
    fun show(activity: ComponentActivity, title: String, subtitle: String, options: List<ChoiceOption>, onResult: (Int?) -> Unit) {
        activity.runOnUiThread {
            val host = ComposeView(activity)
            var finished = false
            fun finish(result: Int?) {
                if (finished) return
                finished = true
                host.post { (host.parent as? ViewGroup)?.removeView(host) }
                onResult(result)
            }
            host.setContent {
                KaizenTheme {
                    Dialog(onDismissRequest = { finish(null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                        ChoiceScreen(title, subtitle, options) { finish(it) }
                    }
                }
            }
            activity.addContentView(host, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
}
