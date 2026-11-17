package app.kaizen.commons

import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/**
 * Evalúa JavaScript de las webs con Rhino en modo interpretado (Android no admite generar bytecode JVM).
 * Sin acceso a clases Java (initSafeStandardObjects) y con un tiempo máximo por script.
 */
object JsEngine {
    private const val TIMEOUT_MS = 10_000L

    /**
     * Tope duro: el observador de instrucciones no ve lo que Rhino hace por dentro (una expresión regular que retrocede sin fin,
     * un texto enorme), así que el script corre en otro hilo y quien llama deja de esperar pasado este tiempo. El hilo atascado
     * no se puede matar: se abandona (es demonio, no impide cerrar la app).
     */
    @Volatile internal var hardTimeoutMs = TIMEOUT_MS + 3_000L

    // Como mucho 4 hilos a la vez: con 4 atascados las llamadas siguientes fallan al instante en vez de crear más hilos girando.
    private val executor = java.util.concurrent.ThreadPoolExecutor(
        0, 4, 60, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue()
    ) { runnable -> Thread(runnable, "kaizen-js").apply { isDaemon = true } }

    /** Lanzada cuando un script supera [TIMEOUT_MS]; es un Error para que el propio script no pueda capturarla. */
    private class ScriptTimeout : Error()

    private class TimedContext(factory: ContextFactory) : Context(factory) {
        var deadline = Long.MAX_VALUE
    }

    private val factory = object : ContextFactory() {
        override fun makeContext(): Context = TimedContext(this).apply {
            @Suppress("DEPRECATION") // Rhino en Android solo va interpretado (sin generar clases)
            optimizationLevel = -1
            instructionObserverThreshold = 10_000
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            if (System.currentTimeMillis() > (cx as TimedContext).deadline) throw ScriptTimeout()
        }
    }

    /** Objetos estándar (Object, JSON, RegExp...) creados una vez y sellados: se comparten entre llamadas e hilos. */
    private val standard: ScriptableObject by lazy { inContext { it.initSafeStandardObjects(null, true) } }

    /**
     * Devuelve el resultado como texto, o `null` si el script no devuelve nada. Cada llamada usa un ámbito propio
     * sobre los objetos estándar, así lo que declara un script no lo ve el siguiente.
     * @throws IllegalStateException si el script tarda más de [TIMEOUT_MS], no responde en [hardTimeoutMs] o ya hay 4 atascados.
     */
    fun evaluate(script: String): String? {
        val task = try {
            executor.submit<String?> { run(script) }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            throw IllegalStateException("JavaScript: demasiados scripts bloqueados")
        }
        try {
            return task.get(hardTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            throw IllegalStateException("JavaScript: más de $hardTimeoutMs ms (bloqueado)")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        } finally {
            task.cancel(true)
        }
    }

    private fun run(script: String): String? = inContext { context ->
        context.deadline = System.currentTimeMillis() + TIMEOUT_MS
        val scope: Scriptable = context.newObject(standard).apply {
            prototype = standard
            parentScope = null
        }
        val result = try {
            context.evaluateString(scope, script, "kaizen", 1, null)
        } catch (e: ScriptTimeout) {
            throw IllegalStateException("JavaScript: más de $TIMEOUT_MS ms")
        }
        if (result == null || result === Undefined.instance) null else Context.toString(result)
    }

    private inline fun <T> inContext(block: (TimedContext) -> T): T {
        val context = factory.enterContext() as TimedContext
        try {
            return block(context)
        } finally {
            Context.exit()
        }
    }
}
