@file:OptIn(ExperimentalTvMaterial3Api::class)

package app.kaizen.tv.streaming

import app.kaizen.tv.ui.theme.*

import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

/**
 * Ventana con las fases de abrir un capítulo (Cloudflare, servidores, servidor, capítulo). Solo aparece pasados
 * [SHOW_DELAY_MS] desde que se pidió: si todo va bien no molesta, y si algo tarda se ve en qué fase está. Atrás cancela
 * y llama a [onCancel], con la ventana a la vista o sin ella. Se puede ocultar mientras se pregunta al usuario (servidor,
 * calidad) y mostrar de nuevo después. Se puede llamar desde cualquier hilo.
 */
class LoadProgress private constructor(
    private val activity: ComponentActivity,
    private val title: String,
    private val subtitle: String,
    private val onCancel: () -> Unit
) {
    private val rows = mutableStateOf(LoadSteps.initial())
    /** Ya pasó el retraso inicial. */
    private val armed = mutableStateOf(false)
    /** Se está preguntando al usuario (servidor, calidad): la ventana se oculta. */
    private val suppressed = mutableStateOf(false)
    @Volatile private var closed = false
    private var host: ComposeView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val arm = Runnable { armed.value = true }
    // Antes de que aparezca la ventana nadie recoge Atrás: este callback lo convierte en cancelar. Con la ventana a la vista,
    // Atrás llega a su propio diálogo.
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = cancelByUser()
    }

    fun begin(step: LoadStep, detail: String = "") = update(step, StepState.RUNNING, detail)
    fun done(step: LoadStep, detail: String = "") = update(step, StepState.DONE, detail)
    fun fail(step: LoadStep, detail: String = "") = update(step, StepState.FAILED, detail)
    /** La fase no aplica (p. ej. Cloudflare en una fuente que no lo usa). */
    fun skip(step: LoadStep, detail: String = "") = update(step, StepState.SKIPPED, detail)
    fun set(step: LoadStep, state: StepState, detail: String = "") = update(step, state, detail)

    fun setVisible(value: Boolean) { suppressed.value = !value }

    @Synchronized private fun update(step: LoadStep, state: StepState, detail: String) {
        if (!closed) rows.value = LoadSteps.set(rows.value, step, state, detail)
    }

    /** Cierra la ventana, tras [afterMs] para dejar ver el último estado (un fallo). */
    fun close(afterMs: Long = 0L) {
        if (closed) return
        closed = true
        activity.runOnUiThread {
            // Ya no hay nada que cancelar: Atrás vuelve a ser de la pantalla, aunque la ventana se quede un instante a la vista.
            handler.removeCallbacks(arm)
            backCallback.remove()
            val view = host ?: return@runOnUiThread
            val remove = Runnable { (view.parent as? ViewGroup)?.removeView(view) }
            if (afterMs > 0) handler.postDelayed(remove, afterMs) else remove.run()
        }
    }

    private fun cancelByUser() {
        if (closed) return
        close()
        onCancel()
    }

    companion object {
        /** Tiempo que debe pasar desde que se pide el capítulo para que aparezca la ventana. */
        const val SHOW_DELAY_MS = 5_000L

        fun show(activity: ComponentActivity, title: String, subtitle: String, onCancel: () -> Unit): LoadProgress {
            val progress = LoadProgress(activity, title, subtitle, onCancel)
            activity.runOnUiThread {
                if (progress.closed) return@runOnUiThread
                val view = ComposeView(activity)
                progress.host = view
                activity.onBackPressedDispatcher.addCallback(activity, progress.backCallback)
                progress.handler.postDelayed(progress.arm, SHOW_DELAY_MS)
                view.setContent {
                    KaizenTheme {
                        if (progress.armed.value && !progress.suppressed.value) {
                            Dialog(onDismissRequest = progress::cancelByUser, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                                ProgressScreen(progress.title, progress.subtitle, progress.rows.value)
                            }
                        }
                    }
                }
                activity.addContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            return progress
        }
    }
}

@Composable
private fun ProgressScreen(title: String, subtitle: String, rows: List<StepRow>) {
    Box(Modifier.fillMaxSize().background(Scrim), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(560.dp).clip(ShapeXl).background(SurfaceCard).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(title, color = Color.White, fontSize = Text22, fontWeight = FontWeight.Bold)
            if (subtitle.isNotEmpty()) Text(subtitle, color = White70, fontSize = Text15, maxLines = 1)
            rows.forEachIndexed { index, row -> StepLine(index + 1, row) }
            Text("Atrás para cancelar", color = White50, fontSize = Text14, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun StepLine(number: Int, row: StepRow) {
    val dim = row.state == StepState.PENDING || row.state == StepState.SKIPPED
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(IconLg), contentAlignment = Alignment.Center) {
            when (row.state) {
                StepState.RUNNING -> Spinner()
                StepState.DONE -> Text("✓", color = StatusOk, fontSize = Text17, fontWeight = FontWeight.Bold)
                StepState.WARN -> Text("!", color = StatusWarn, fontSize = Text17, fontWeight = FontWeight.Bold)
                StepState.FAILED -> Text("✕", color = Accent, fontSize = Text17, fontWeight = FontWeight.Bold)
                else -> Text("$number", color = White50, fontSize = Text18)
            }
        }
        Column {
            Text(row.step.label, color = if (dim) White50 else Color.White, fontSize = Text18, fontWeight = FontWeight.Medium)
            if (row.detail.isNotEmpty()) Text(row.detail, color = White70, fontSize = Text14, maxLines = 1)
        }
    }
}

@Composable
private fun Spinner() {
    val angle by rememberInfiniteTransition(label = "loadSpinner").animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "angle"
    )
    Canvas(Modifier.size(IconSm)) {
        drawArc(Color.White, startAngle = angle, sweepAngle = 270f, useCenter = false, size = Size(size.width, size.height), style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}
