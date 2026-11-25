package app.kaizen.tv

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import android.view.WindowManager
import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.postDelayed
import java.lang.ref.WeakReference
import kotlin.time.Duration.Companion.seconds

/**
 * ¿Toca oscurecer? Sí cuando hay un tiempo elegido (> 0), la pantalla no está ocupada (vídeo en marcha) y el último gesto fue hace [timeoutSeconds] o más.
 * Aparte de [ScreenDimmer] para poder probarlo sin Android.
 */
internal fun shouldDim(idleMs: Long, timeoutSeconds: Int, busy: Boolean): Boolean = timeoutSeconds > 0 && !busy && idleMs >= timeoutSeconds * 1000L

/**
 * Protector de pantalla de toda la app (como el de SmartTube, para no marcar la pantalla con una imagen fija): pasado el tiempo de Ajustes sin pulsar nada, una capa negra
 * translúcida cubre la pantalla. Cualquier tecla o toque la quita y **ese primer gesto no llega a la app** (solo despierta). No actúa mientras suena un vídeo
 * ([TVBaseActivity.isScreenBusy]); al pausarlo, el tiempo cuenta desde la pausa.
 *
 * La capa es una ventana propia, no sensible al toque ni con foco: va por encima de los diálogos ya abiertos (ajustes, menús) y no les roba el foco. Como los diálogos son ventanas
 * aparte, cuentan los gestos con [watch], que se conecta desde el `Dialog` de la app.
 */
object ScreenDimmer {
    private val handler = Handler(Looper.getMainLooper())
    private var activity: WeakReference<TVBaseActivity>? = null
    private var overlay: Dialog? = null
    private var lastInput = SystemClock.uptimeMillis()
    // El gesto que despertó la pantalla se traga entero (hasta soltar la tecla o el dedo): si no, su «soltar» llegaría a la app sin su «pulsar».
    private var swallowKey = -1
    private var swallowTouch = false

    private val tick = object : Runnable {
        override fun run() {
            check()
            handler.postDelayed(this, TICK)
        }
    }

    /** La actividad [activity] pasa a primer plano: empieza a contar. */
    fun start(activity: TVBaseActivity) {
        this.activity = WeakReference(activity)
        swallowKey = -1
        swallowTouch = false
        lastInput = SystemClock.uptimeMillis()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK)
    }

    /** [activity] deja el primer plano: se quita la capa y se deja de contar (si ya ha tomado el relevo otra actividad, no se toca). */
    fun stop(activity: TVBaseActivity) {
        if (this.activity?.get() !== activity) return
        hide()
        swallowKey = -1
        swallowTouch = false
        handler.removeCallbacks(tick)
        this.activity = null
    }

    /** Una tecla: true si hay que tragarla (acaba de despertar la pantalla). */
    fun onKey(event: KeyEvent): Boolean {
        lastInput = SystemClock.uptimeMillis()
        if (overlay != null) {
            hide()
            if (event.action == KeyEvent.ACTION_DOWN) swallowKey = event.keyCode
            return true
        }
        if (swallowKey == event.keyCode) {
            // Una pulsación nueva (no una repetición) de esa tecla ya no es la que despertó la pantalla.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) swallowKey = -1
            else {
                if (event.action == KeyEvent.ACTION_UP) swallowKey = -1
                return true
            }
        }
        return false
    }

    /** Un toque o un movimiento del ratón o del mando: true si hay que tragarlo. */
    fun onPointer(event: MotionEvent): Boolean {
        lastInput = SystemClock.uptimeMillis()
        val ending = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
        if (overlay != null) {
            hide()
            // Solo un toque que empieza se traga hasta que acabe; un evento suelto (ratón, stick, rueda) no tiene final y despierta tragándose él solo.
            swallowTouch = event.actionMasked == MotionEvent.ACTION_DOWN
            return true
        }
        if (swallowTouch) {
            if (ending) swallowTouch = false
            return true
        }
        return false
    }

    /** Hace que los gestos de una ventana de diálogo cuenten (y despierten); idempotente. */
    fun watch(window: Window) {
        val current = window.callback ?: return
        if (current is Watched) return
        window.callback = Watched(current)
    }

    private fun check() {
        val current = activity?.get() ?: return
        if (overlay != null) return
        val busy = current.isScreenBusy()
        // Con el vídeo en marcha el reloj no corre: al pausarlo, el tiempo cuenta desde ese momento.
        if (busy) lastInput = SystemClock.uptimeMillis()
        if (shouldDim(SystemClock.uptimeMillis() - lastInput, KaizenSettings.idleDimSeconds, busy)) show(current)
    }

    private fun show(current: TVBaseActivity) {
        if (current.isFinishing || current.isDestroyed) return
        val alpha = (KaizenSettings.idleDimPercent.coerceIn(0, 100) * 255 / 100)
        overlay = runCatching {
            Dialog(current, android.R.style.Theme_Translucent_NoTitleBar).apply {
                setCancelable(false)
                window?.run {
                    setBackgroundDrawable(ColorDrawable(Color.argb(alpha, 0, 0, 0)))
                    setDimAmount(0f)
                    setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                    // Ni foco ni toque: los gestos siguen yendo a la ventana de debajo, que los avisa con [onKey] y [onPointer].
                    addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                }
                show()
            }
        }.getOrNull()
    }

    private fun hide() {
        overlay?.let { runCatching { it.dismiss() } }
        overlay = null
    }

    /** El `Window.Callback` de un diálogo con los gestos avisados al protector; todo lo demás sigue en el original. */
    private class Watched(private val base: Window.Callback) : Window.Callback by base {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean = onKey(event) || base.dispatchKeyEvent(event)
        override fun dispatchTouchEvent(event: MotionEvent): Boolean = onPointer(event) || base.dispatchTouchEvent(event)
        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = onPointer(event) || base.dispatchGenericMotionEvent(event)
    }

    private val TICK = 1.seconds
}
