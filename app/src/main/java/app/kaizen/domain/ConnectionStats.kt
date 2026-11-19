package app.kaizen.domain

import androidx.core.content.edit
import androidx.media3.common.PlaybackException
import app.kaizen.App
import java.util.TimeZone

/**
 * Los intentos de conexión de cada día, **separados** en fuentes (las webs de capítulos y fichas) y servidores (los alojadores del vídeo): cuántos funcionaron
 * y cuántos fallaron cada día. De ahí salen los porcentajes de fallos (sobre el total de intentos, no solo los fallidos), los días de la semana en que más se falla
 * y qué fuentes o servidores fallan más. Un fichero de preferencias propio: clave «tipo|día|nombre», valor «aciertos;fallos». Se guardan [keepDays] días.
 */
object ConnectionStats {
    /** [SOURCE]: las webs de capítulos y fichas. [SERVER]: los alojadores del vídeo. [LINK]: los enlaces que ofrece cada fuente (lo que falla al abrir sus servidores, sea de quien sea la culpa). */
    enum class Kind(val code: String) { SOURCE("S"), SERVER("V"), LINK("L") }

    /** De quién es el problema, según la causa (corregido con la historia del servidor y la fuente: ver [diagnose]). */
    enum class Origin(val label: String) {
        DEVICE("Esta TV"), NETWORK("Tu red"), SERVER("El servidor"), SOURCE("La fuente (web de capítulos)"),
        FILE("El archivo de vídeo"), APP("Kaizen (el extractor)"), UNKNOWN("Sin determinar")
    }

    /**
     * **El único catálogo de errores de la app**: conexiones (fuentes, servidores, enlaces) y reproducción. Cada causa lleva su número (E01…, el mismo en
     * «Estadísticas» y en el panel de depuración), qué significa, de quién suele ser ([origin]) y cómo se arregla ([fix]). Se guarda por [code]: no cambiarlo.
     */
    enum class Cause(val number: Int, val code: String, val label: String, val meaning: String, val origin: Origin, val fix: String) {
        DNS(1, "dns", "Nombre sin resolver", "El DNS de tu red no encuentra ese servidor: lo filtra (p. ej. AdGuard) o el dominio ya no existe. Quad9 sirve para distinguirlo.", Origin.NETWORK,
            "Probar con otro servidor del capítulo; si todos fallan, revisar el DNS de la red."),
        TIMEOUT(2, "timeout", "Sin respuesta a tiempo", "El servidor tardó demasiado o la comprobación se cortó por tiempo: está saturado o lento.", Origin.SERVER,
            "Reintentar en unos minutos o elegir otro servidor."),
        CONNECTION(3, "conexion", "Conexión rechazada o cortada", "No se pudo abrir o mantener la conexión (rechazada, reiniciada o sin ruta): el servidor está caído o te bloquea.", Origin.NETWORK,
            "Comprobar la red de la TV; si otras webs van, cambiar de servidor."),
        TLS(4, "tls", "Error de seguridad (TLS)", "Falla el certificado o el cifrado: la web tiene el certificado caducado o no coincide con su nombre.", Origin.SERVER,
            "Revisar fecha y hora de la TV (si están mal, es de la TV); si son correctas, cambiar de servidor."),
        BLOCKED(5, "bloqueo", "Bloqueo (403)", "La web rechaza las peticiones de la app: protección anti-bots o bloqueo por IP.", Origin.SERVER,
            "Reabrir el capítulo para obtener un enlace nuevo; si persiste, otro servidor."),
        NOT_FOUND(6, "no-existe", "No encontrado (404)", "La página o el vídeo ya no existe en esa dirección.", Origin.SOURCE,
            "Reabrir el capítulo (enlace nuevo) u otro servidor; si es siempre de la misma fuente, esa fuente ofrece enlaces muertos."),
        REJECTED(7, "rechazo", "Petición rechazada (4xx)", "El servidor no acepta lo que se le pide (405: método no admitido, 429: demasiadas peticiones…): el extractor puede estar desactualizado.", Origin.SERVER,
            "Esperar un minuto y reintentar; si no cambia, el extractor de ese servidor está desactualizado."),
        SERVER_ERROR(8, "servidor", "Error del servidor (5xx)", "El servidor falla por dentro o está en mantenimiento.", Origin.SERVER,
            "No es cosa de la app: probar otro servidor o más tarde."),
        EXTRACTOR(9, "extractor", "Página no entendida", "La web respondió, pero con algo distinto de lo esperado (una verificación de Cloudflare, otro formato…): el extractor de ese servidor está desactualizado o la web lo bloquea.", Origin.APP,
            "Probar otro servidor; si siempre falla el mismo, hay que actualizar su extractor."),
        NO_VIDEO(10, "sin-video", "Sin vídeo", "El servidor respondió, pero no dio ningún vídeo reproducible: el vídeo se borró o el extractor ya no entiende su página.", Origin.SERVER,
            "Otro servidor del mismo capítulo."),
        BAD_FILE(11, "fichero", "Vídeo dañado o no admitido", "El vídeo llegó, pero su contenedor está corrupto o su formato no lo entiende ningún decodificador (ni el del sistema ni el de software).", Origin.FILE,
            "Reintentar (puede ser una descarga cortada) u otro servidor o calidad; el panel de depuración enseña el códec."),
        DECODER(12, "decodificador", "El decodificador falla", "El decodificador no arranca o se rompe a mitad: el vídeo excede lo que soporta la TV (resolución, 10 bits…) o falta memoria.", Origin.DEVICE,
            "Probar el decodificador de software o una calidad menor."),
        AUDIO_OUTPUT(13, "salida-audio", "La salida de audio falla", "El sistema no consigue abrir o escribir en la salida de audio (HDMI, receptor).", Origin.DEVICE,
            "Reiniciar el reproductor; si persiste, revisar la salida de audio de la TV."),
        SLOW(14, "lenta", "Cargas lentas", "El vídeo se detiene a esperar datos o el audio se corta: llega menos de lo que el vídeo necesita.", Origin.NETWORK,
            "Elegir una calidad menor u otro servidor; revisar el Wi-Fi (mejor cable)."),
        DROPPED(15, "saltos", "Saltos de imagen", "Se pierden fotogramas: el decodificador no llega a tiempo o la TV va justa de CPU.", Origin.DEVICE,
            "Cambiar de decodificador (hardware o software) o bajar la calidad."),
        LOW_MEMORY(16, "memoria", "Poca memoria libre", "La app se acerca al límite de memoria: el sistema puede cerrarla o ralentizarla.", Origin.DEVICE,
            "Cerrar otras apps de la TV y reiniciar Kaizen."),
        NO_NETWORK(17, "sin-red", "Sin red", "La TV no tiene conexión a Internet (Wi-Fi caído, router apagado, cable suelto): no es culpa de ningún servidor.", Origin.NETWORK,
            "Revisar el router y el Wi-Fi o el cable de la TV; en cuanto vuelva la red se recupera solo."),
        IMAGE(18, "imagen", "Carátula no cargada", "Una portada o un fotograma no se pudo descargar ni decodificar: la imagen está rota, no existe o su servidor no contesta.", Origin.SOURCE,
            "No afecta a ver el capítulo; se reintenta al volver a la pantalla. Si son muchas de la misma fuente, esa fuente tiene el CDN de imágenes caído."),
        CACHE(19, "cache", "Caché ilegible", "Lo guardado en el disco (respuestas de las fuentes o imágenes) está corrupto o no se puede leer ni escribir.", Origin.DEVICE,
            "Configuración › Borrar la caché: se vuelve a descargar todo."),
        STORAGE(20, "almacenamiento", "Almacenamiento lleno o sin acceso", "La TV no tiene espacio libre o la app no puede escribir en su carpeta.", Origin.DEVICE,
            "Liberar espacio en la TV (borrar apps o la caché) y reintentar."),
        DATABASE(21, "base-datos", "Base de datos dañada", "Falla la base de datos local (favoritos, historial, catálogo): puede estar corrupta o bloqueada.", Origin.DEVICE,
            "Reiniciar Kaizen; si persiste, restaurar la copia desde Configuración › Copia de seguridad."),
        INTERNAL(22, "interno", "Fallo interno de la app", "Un error de programación de Kaizen (un dato que faltaba, un índice fuera de rango…) que no viene de la red.", Origin.APP,
            "No lo causa tu red ni un servidor: guardar el debug.log (adb pull) para corregirlo."),
        LALIGA(23, "laliga", "Bloqueo de LaLiga", "Tu operador bloquea por IP las direcciones de Cloudflare mientras hay un partido de LaLiga, y la fuente está detrás de ellas.", Origin.NETWORK,
            "Se pasa solo al acabar el partido (suele ser un par de horas). Una VPN lo evita; cambiar el DNS no."),
        NO_INTERNET(24, "sin-internet", "Red sin salida a Internet", "La TV está conectada a una red (el móvil como repetidor sin datos, un router sin línea) pero por ella no sale nada: fallan a la vez varias webs distintas y Android no da la red por validada.", Origin.NETWORK,
            "Revisar los datos del móvil o la línea del router. Mientras tanto se usa lo guardado y no se abren pausas; al volver la salida se recupera solo."),
        OFFLINE(25, "modo-sin-conexion", "Modo sin conexión activado", "El interruptor «Modo sin conexión» está encendido: no se pide nada a las webs y se usa solo lo guardado. No es un fallo.", Origin.DEVICE,
            "Apagarlo en Configuración › Datos y caché › Modo sin conexión."),
        PAUSED(26, "en-pausa", "Fuente en pausa", "Una web falló varias veces seguidas y se deja de consultar un rato (2 min, 5 min, 10 min y luego 1 h) para no parecer un ataque ni arriesgar que bloqueen la IP.", Origin.SOURCE,
            "Esperar a que acabe la pausa o pulsar la fuente en «Fuentes» y «Comprobar todas»; si responde, vuelve a usarse."),
        CLOUDFLARE(27, "cloudflare", "Cloudflare no responde", "El bypass de Cloudflare no consigue la verificación o ni siquiera llega a Cloudflare, así que las webs que la piden no se abren.", Origin.SERVER,
            "Esperar a que acabe la pausa del bypass o lanzarlo a mano desde «Fuentes › Bypass Cloudflare»."),
        HOME_CHANNEL(28, "canal-inicio", "Miniatura del canal de inicio no disponible", "El launcher descarga por su cuenta las imágenes del canal «Capítulos recientes» de la pantalla de inicio y, sin red o sin la imagen guardada, pinta un «!».", Origin.DEVICE,
            "Con conexión se rellenan solas al renovar los recientes; las ya guardadas se sirven sin red."),
        OTHER(99, "otro", "Otro", "Un error que no encaja en los anteriores.", Origin.UNKNOWN,
            "Mirar el detalle técnico y el registro de depuración.");

        /** «E03»: el número como se enseña. */
        val tag: String get() = "E%02d".format(number)
    }

    /** Las causas que pueden salir de un intento de conexión con una fuente o un servidor (lo que cuentan las pestañas de Conexión); el resto son del reproductor y de la app. */
    val CONNECTION_CAUSES: List<Cause> = listOf(
        Cause.DNS, Cause.TIMEOUT, Cause.CONNECTION, Cause.TLS, Cause.BLOCKED, Cause.NOT_FOUND, Cause.REJECTED,
        Cause.SERVER_ERROR, Cause.EXTRACTOR, Cause.NO_VIDEO, Cause.OTHER
    )

    /** De quién es un fallo y con qué cifras se dice: [evidence] enseña la historia del servidor y de la fuente. */
    data class Diagnosis(val origin: Origin, val evidence: String)

    /** Los intentos de [name] ([kind]) en el día [day] (días desde 1970, en la zona del usuario). */
    data class Row(val kind: Kind, val day: Long, val name: String, val ok: Int, val fail: Int) {
        val total: Int get() = ok + fail
    }

    /** Una línea agregada (una fuente, un servidor, un día de la semana…): sus fallos sobre el total de intentos. */
    data class Total(val name: String, val fail: Int, val total: Int, val worstWeekday: Int? = null) {
        /** Fracción de intentos que fallaron (0 a 1): lo que permite comparar una fuente con otra aunque se usen distinto. */
        val failRate: Float get() = if (total > 0) fail / total.toFloat() else 0f

        /**
         * Límite inferior (Wilson, ~80 %) del porcentaje de fallos: el que se puede afirmar con los intentos que hay. Es lo que ordena:
         * 3 fallos de 3 (100 %) no pasan por delante de 90 de 100 (90 %), porque con tres intentos puede ser casualidad.
         */
        val likelyFailRate: Float get() {
            if (total == 0) return 0f
            val n = total.toDouble()
            val p = fail / n
            val z2 = WILSON_Z * WILSON_Z
            val centre = p + z2 / (2 * n)
            val margin = WILSON_Z * Math.sqrt(p * (1 - p) / n + z2 / (4 * n * n))
            return ((centre - margin) / (1 + z2 / n)).toFloat().coerceIn(0f, 1f)
        }
    }

    /** Cuántos fallos de [kind] hubo de [name] un [day] por esa [cause]. */
    data class ErrorRow(val kind: Kind, val day: Long, val cause: Cause, val name: String, val count: Int)

    /** Intentos y fallos de un día. */
    data class Day(val day: Long, val total: Int, val fail: Int)

    /** Dónde se guardan los datos (las pruebas lo cambian por uno en memoria). */
    interface Storage {
        fun all(): Map<String, String>
        fun put(key: String, value: String)
        fun remove(keys: Collection<String>)
    }

    private object PrefsStorage : Storage {
        private val prefs by lazy {
            app.kaizen.commons.KaizenStore.prefs("kaizen_connection_stats").also { current ->
                // Los contadores antiguos (sin día, con la fuente y el servidor mezclados y con el fallo de la comprobación contado dos veces) no valen para esto.
                if (!current.getBoolean(MIGRATED, false)) {
                    runCatching { App.context.deleteSharedPreferences(LEGACY_FILE) }
                    current.edit { putBoolean(MIGRATED, true) }
                }
            }
        }

        override fun all(): Map<String, String> = prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
        override fun put(key: String, value: String) = prefs.edit { putString(key, value) }
        override fun remove(keys: Collection<String>) = prefs.edit { keys.forEach { remove(it) } }
    }

    @Volatile internal var storage: Storage = PrefsStorage

    // Lo guardado, en memoria: se lee una sola vez (y se vuelve a leer si cambia [storage], en las pruebas) en lugar de copiar todo el fichero en cada intento.
    private var cache: MutableMap<String, String>? = null
    private var cacheOf: Storage? = null

    private fun data(): MutableMap<String, String> {
        val current = storage
        val loaded = cache
        if (loaded != null && cacheOf === current) return loaded
        return current.all().toMutableMap().also { cache = it; cacheOf = current }
    }
    @Volatile internal var clock: () -> Long = System::currentTimeMillis
    @Volatile internal var zone: () -> TimeZone = { TimeZone.getDefault() }

    /**
     * Anota un intento de [name] ([kind]); si falló, [cause] dice por qué (se cuenta aparte, por día y nombre). Nunca lanza: las estadísticas
     * no pueden romper una conexión.
     */
    fun record(kind: Kind, name: String, ok: Boolean, cause: Cause? = null) {
        if (name.isBlank()) return
        // Sin red no es que la fuente o el servidor fallen: no se les carga el fallo (ensuciaría su porcentaje).
        // Sin red, sin salida o en modo sin conexión no es culpa de la fuente ni del servidor: no cuenta.
        if (!ok && (cause == Cause.NO_NETWORK || cause == Cause.NO_INTERNET || cause == Cause.OFFLINE)) return
        runCatching {
            synchronized(this) {
                val today = dayOf(clock())
                val key = "${kind.code}$SEP$today$SEP$name"
                val saved = data()[key]
                if (saved == null) prune(today)
                val (good, bad) = parse(saved)
                val updated = if (ok) "${good + 1};$bad" else "$good;${bad + 1}"
                storage.put(key, updated)
                data()[key] = updated
                if (!ok && cause != null) {
                    val errorKey = "$ERROR$SEP$today$SEP${kind.code}$SEP${cause.code}$SEP$name"
                    val count = (data()[errorKey]?.toIntOrNull() ?: 0) + 1
                    storage.put(errorKey, count.toString())
                    data()[errorKey] = count.toString()
                }
            }
        }
    }

    /** Todo lo guardado, sin los días de hace más de [keepDays]. */
    fun rows(): List<Row> {
        val today = dayOf(clock())
        val snapshot = synchronized(this) { data().toMap() }
        return snapshot.mapNotNull { (key, value) ->
            val parts = key.split(SEP, limit = 3).takeIf { it.size == 3 } ?: return@mapNotNull null
            val kind = Kind.entries.firstOrNull { it.code == parts[0] } ?: return@mapNotNull null
            val day = parts[1].toLongOrNull()?.takeIf { it > today - keepDays } ?: return@mapNotNull null
            val (good, bad) = parse(value)
            Row(kind, day, parts[2], good, bad)
        }
    }

    /** Los fallos con su causa, sin los días de hace más de [keepDays]. */
    fun errorRows(): List<ErrorRow> {
        val today = dayOf(clock())
        val snapshot = synchronized(this) { data().toMap() }
        return snapshot.mapNotNull { (key, value) ->
            val parts = key.split(SEP, limit = 5).takeIf { it.size == 5 && it[0] == ERROR } ?: return@mapNotNull null
            val day = parts[1].toLongOrNull()?.takeIf { it > today - keepDays } ?: return@mapNotNull null
            val kind = Kind.entries.firstOrNull { it.code == parts[2] } ?: return@mapNotNull null
            val cause = Cause.entries.firstOrNull { it.code == parts[3] } ?: return@mapNotNull null
            ErrorRow(kind, day, cause, parts[4], value.toIntOrNull() ?: return@mapNotNull null)
        }
    }

    fun reset() = synchronized(this) {
        val keys = data().keys.filter { it.contains(SEP) }
        storage.remove(keys)
        data().keys.removeAll(keys.toSet())
    }

    /** Quita los días que ya no se guardan (se llama con el cerrojo tomado). */
    private fun prune(today: Long) {
        val old = data().keys.filter { key -> key.split(SEP, limit = 3).getOrNull(1)?.toLongOrNull()?.let { it <= today - keepDays } == true }
        if (old.isNotEmpty()) {
            storage.remove(old)
            data().keys.removeAll(old.toSet())
        }
    }

    private fun parse(value: String?): Pair<Int, Int> {
        val parts = value?.split(";") ?: return 0 to 0
        return (parts.getOrNull(0)?.toIntOrNull() ?: 0) to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    /** El día natural (días desde 1970) de [time] en la zona del usuario. */
    fun dayOf(time: Long): Long = Math.floorDiv(time + zone().getOffset(time), MS_PER_DAY)

    /** Día de la semana de [day]: 0 = lunes … 6 = domingo (el 1 de enero de 1970 fue jueves). */
    fun weekdayOf(day: Long): Int = Math.floorMod(day + 3, 7L).toInt()

    // ---- agregados (funciones puras, para probarlos sin Android)

    /** Intentos y fallos de [kind] en cada uno de los últimos [days] días (el último es hoy), también los días sin intentos. */
    fun perDay(rows: List<Row>, kind: Kind, today: Long, days: Int): List<Day> {
        val byDay = rows.filter { it.kind == kind }.groupBy { it.day }
        return (today - days + 1..today).map { day ->
            val of = byDay[day].orEmpty()
            Day(day, of.sumOf { it.total }, of.sumOf { it.fail })
        }
    }

    /** Intentos y fallos de [kind] por día de la semana (lunes = 0), con los siete días aunque no haya datos. */
    fun perWeekday(rows: List<Row>, kind: Kind): List<Total> {
        val byWeekday = rows.filter { it.kind == kind }.groupBy { weekdayOf(it.day) }
        return (0..6).map { weekday -> byWeekday[weekday].orEmpty().let { Total(WEEKDAYS[weekday], it.sumOf { r -> r.fail }, it.sumOf { r -> r.total }) } }
    }

    /**
     * Las fuentes (o servidores) con intentos, de la que más falla a la que menos: por el porcentaje de fallos sobre el total de intentos y teniendo en cuenta
     * cuántos intentos lo respaldan ([Total.likelyFailRate]); salen también las que nunca han fallado. Con menos de [MIN_SAMPLE] intentos el porcentaje no
     * dice nada (1 de 1 sería «100 %») y van al final. Cada una lleva su peor día de la semana, si hay base ([MIN_WEEKDAY_SAMPLE] intentos ese día) y falló alguna vez.
     */
    fun ranked(rows: List<Row>, kind: Kind): List<Total> =
        rows.filter { it.kind == kind }.groupBy { it.name }.map { (name, group) ->
            val worst = group.groupBy { weekdayOf(it.day) }
                .mapValues { (_, day) -> day.sumOf { it.fail } to day.sumOf { it.total } }
                .filter { (_, counts) -> counts.second >= MIN_WEEKDAY_SAMPLE && counts.first > 0 }
                .maxByOrNull { (_, counts) -> counts.first / counts.second.toFloat() }?.key
            Total(name, group.sumOf { it.fail }, group.sumOf { it.total }, worst)
        }.filter { it.total > 0 }
            .sortedWith(compareBy<Total> { it.total < MIN_SAMPLE }.thenByDescending { it.likelyFailRate }.thenByDescending { it.total }.thenBy { it.name })

    /** Los fallos de [kind] por causa, de la más frecuente a la menos. */
    fun causes(errors: List<ErrorRow>, kind: Kind): List<Pair<Cause, Int>> =
        errors.filter { it.kind == kind }.groupBy { it.cause }.map { (cause, rows) -> cause to rows.sumOf { it.count } }
            .sortedWith(compareByDescending<Pair<Cause, Int>> { it.second }.thenBy { it.first.ordinal })

    /** La causa más frecuente de los fallos de [name] ([kind]); null si no hay ninguno con causa. */
    fun mainCause(errors: List<ErrorRow>, kind: Kind, name: String): Cause? =
        errors.filter { it.kind == kind && it.name == name }.groupBy { it.cause }.mapValues { (_, rows) -> rows.sumOf { it.count } }
            .maxWithOrNull(compareBy<Map.Entry<Cause, Int>> { it.value }.thenByDescending { it.key.ordinal })?.key

    /**
     * La causa de una excepción de conexión: nombre sin resolver, tiempo agotado, conexión rechazada o cortada, TLS, o el código HTTP
     * (403 bloqueo, 404 no existe, 4xx rechazo, 5xx error del servidor). null si no hay excepción.
     */
    fun causeOf(e: Throwable?): Cause? = when (e) {
        null -> null
        is java.net.UnknownHostException -> Cause.DNS
        is java.net.SocketTimeoutException, is kotlinx.coroutines.TimeoutCancellationException -> Cause.TIMEOUT
        // Una cancelación (extiende IllegalStateException) no es un fallo de la página: que no caiga en «Página no entendida».
        is kotlinx.coroutines.CancellationException -> Cause.OTHER
        is javax.net.ssl.SSLException -> Cause.TLS
        is org.jsoup.HttpStatusException -> causeOfHttp(e.statusCode)
        is java.net.ConnectException, is java.net.NoRouteToHostException, is java.net.SocketException, is java.io.EOFException -> Cause.CONNECTION
        // El extractor esperaba otra página (JSON y llega HTML, un patrón que no aparece…): ha cambiado o la web devuelve una verificación.
        is org.json.JSONException, is IllegalStateException, is NullPointerException, is IndexOutOfBoundsException, is NumberFormatException -> Cause.EXTRACTOR
        else -> Cause.OTHER
    }

    /** La causa de un código HTTP de error: 403 bloqueo, 404 no existe, 4xx rechazo, 5xx error del servidor. */
    fun causeOfHttp(status: Int): Cause = when (status) {
        403 -> Cause.BLOCKED
        404, 410 -> Cause.NOT_FOUND
        in 400..499 -> Cause.REJECTED
        in 500..599 -> Cause.SERVER_ERROR
        else -> Cause.OTHER
    }

    /**
     * La causa de un fallo del **reproductor**: primero la cadena de causas de la excepción (el mismo [causeOf] de las conexiones y el código HTTP, si lo hay)
     * y, si no dice nada, el [errorCode] de ExoPlayer.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun causeOfPlayback(errorCode: Int, e: Throwable?): Cause {
        val chain = generateSequence(e) { it.cause }.take(8).toList()
        chain.filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.let { return causeOfHttp(it.responseCode) }
        chain.firstNotNullOfOrNull { causeOf(it).takeIf { cause -> cause != Cause.OTHER && cause != Cause.EXTRACTOR } }?.let { return it }
        return when (errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> Cause.TIMEOUT
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> Cause.CONNECTION
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> Cause.NOT_FOUND
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> Cause.NO_VIDEO
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> Cause.BAD_FILE
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> Cause.DECODER
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> Cause.AUDIO_OUTPUT
            else -> Cause.OTHER
        }
    }

    /**
     * De quién es un fallo de [cause]: el origen habitual de la causa, corregido con la historia. Si la causa apunta a la red o a la fuente pero [server] falla mucho
     * ([BAD_RATE] o más con [MIN_SAMPLE] intentos), el culpable es ese servidor; si casi nunca falla, se dice que apunta a tu red o a la TV. [link] son los enlaces
     * de la fuente que lo ofreció.
     */
    fun diagnose(cause: Cause, server: Total?, link: Total?): Diagnosis {
        // Solo se juzga a un servidor con intentos suficientes.
        val serverRate = server?.takeIf { it.total >= MIN_SAMPLE }?.failRate
        val serverBad = serverRate != null && serverRate >= BAD_RATE
        val serverGood = serverRate != null && serverRate < GOOD_RATE
        val origin = if ((cause.origin == Origin.NETWORK || cause.origin == Origin.SOURCE) && serverBad) Origin.SERVER else cause.origin
        val parts = buildList {
            server?.let { add("servidor ${it.name}: falla ${percent(it)} (${it.fail} de ${it.total})") }
            link?.let { add("enlaces de ${it.name}: fallan ${percent(it)} (${it.fail} de ${it.total})") }
            if (serverGood && cause.origin == Origin.NETWORK) add("el servidor funciona en otros intentos: apunta a tu red o a la TV")
            if (serverBad && origin != cause.origin) add("el servidor falla con frecuencia: probablemente es suyo")
        }
        return Diagnosis(origin, parts.joinToString(" · ").ifEmpty { "sin historia suficiente" })
    }

    private fun percent(total: Total) = "${Math.round(total.failRate * 100)} %"

    val WEEKDAYS =listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")

    private const val SEP = "|"
    private const val ERROR = "E"
    private const val MIGRATED = "_migrated"
    private const val LEGACY_FILE = "kaizen_server_stats"
    private const val MS_PER_DAY = 86_400_000L
    private const val WILSON_Z = 1.28
    private const val BAD_RATE = 0.3f
    private const val GOOD_RATE = 0.1f

    /** Días que se guardan (ajuste «Días de estadísticas»; ver [app.kaizen.commons.Tuning]). */
    @Volatile var keepDays = 90

    /** Intentos mínimos para que el porcentaje de fallos cuente. */
    const val MIN_SAMPLE = 3

    /** Intentos mínimos en un día de la semana para decir que es el peor de una fuente o un servidor. */
    const val MIN_WEEKDAY_SAMPLE = 3
}
