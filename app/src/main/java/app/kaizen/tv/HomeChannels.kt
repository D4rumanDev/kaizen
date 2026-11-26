package app.kaizen.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import app.kaizen.R
import app.kaizen.commons.runCatchingCoop
import app.kaizen.commons.KaizenProgress
import app.kaizen.commons.RecentsArchive
import app.kaizen.commons.roundedString
import app.kaizen.commons.logError
import app.kaizen.commons.DebugLog
import app.kaizen.domain.Library
import app.kaizen.pojos.av1.RecentEpisode
import app.kaizen.pojos.av1.Record
import app.kaizen.source.AnimeSources
import app.kaizen.source.seriesKeyOf
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

/**
 * Filas de la pantalla de inicio de Android TV: «Continuar viendo» (Watch Next, capítulos empezados) y el canal «Capítulos recientes».
 * Un launcher que no implemente los canales de Android TV no las muestra; escribirlas no falla por eso.
 */
object HomeChannels {
    private const val MAX_ROWS = 20

    /** Una sincronización de filas a la vez (sustituye a @Synchronized, que no admite esperar imágenes sin bloquear el hilo). */
    private val lock = Mutex()

    // Si Kaizen ya está abierto, el TVMain existente recibe el enlace (onNewIntent) en vez de apilarse otro encima.
    private const val OPEN_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    private const val PREFS = "home_channels"
    private const val KEY_CHANNEL = "recents_channel_id"
    private const val KEY_LOGO = "logo_version"
    private const val KEY_SIGNATURE = "recents_signature"
    private const val KEY_BULK_FAILED = "bulk_insert_failed"
    private const val LOGO_VERSION = 4

    /** Capítulos de «Seguir viendo» para Watch Next: el más reciente de cada serie y capítulo, hasta [MAX_ROWS]. */
    internal fun pickContinue(history: List<Record>, inProgress: Map<KaizenProgress.Key, KaizenProgress.Progress>): List<Pair<Record, KaizenProgress.Progress>> =
        history.mapNotNull { record -> inProgress[record.progressKey()]?.let { record to it } }
            .distinctBy { (record, _) -> seriesKeyOf(record.source, record.slug) to record.number }
            .take(MAX_ROWS)

    /** Abre la ficha de la serie desde fuera de la app: [TVMain] atiende `kaizen://tv/series` (la ficha no está exportada). */
    internal fun seriesIntent(context: Context, url: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.Builder().scheme("kaizen").authority("tv").path("/series").appendQueryParameter("url", url).build(), context, app.kaizen.tv.ui.TVMain::class.java)
            .addFlags(OPEN_FLAGS)

    /** Vuelca «Seguir viendo» a Watch Next; lo que ya no está en curso se quita. Hilo de fondo. */
    suspend fun syncWatchNext(context: Context) = lock.withLock {
        val helper = PreviewChannelHelper(context)
        val history = Library.historyNow().sortedByDescending { it.date }
        KaizenProgress.migrateEidKeys(history)
        val wanted = pickContinue(history, KaizenProgress.inProgress())
        val existing = existingWatchNext(context)
        val wantedIds = wanted.map { it.first.eid.toString() }.toSet()
        // Primero se publica qué imágenes se pueden servir; después se dan al launcher como content:// (ver ChannelArt).
        val art = ChannelArt.publish(context, "continue", wanted.map { episodeImage(it.first) })
        existing.filterKeys { it !in wantedIds }.values.forEach { context.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(it), null, null) }
        wanted.forEach { (record, progress) ->
            val program = WatchNextProgram.Builder()
                .setType(TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE)
                .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                .setLastEngagementTimeUtcMillis(record.date)
                .setLastPlaybackPositionMillis(progress.positionMs.toInt())
                .setDurationMillis(progress.durationMs.toInt())
                .setInternalProviderId(record.eid.toString())
                .setTitle(record.name)
                .setDescription(record.chapter())
                .setPosterArtUri(art.forLauncher(context, episodeImage(record)).toUri())
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                .setIntent(seriesIntent(context, record.animeUrl()))
                .build()
            existing[record.eid.toString()]?.let { helper.updateWatchNextProgram(program, it) } ?: helper.publishWatchNextProgram(program)
        }
    }

    /**
     * Vuelca los recientes al canal «Capítulos recientes» (lo crea la primera vez). Hilo de fondo. Si la lista, las miniaturas y los programas del canal
     * son los de la vez anterior no toca nada (casi todos los arranques); si no, rehace los programas con una sola operación al proveedor, en el mismo orden.
     * Anota en el registro cuánto tarda cada tramo («Medición»).
     */
    suspend fun syncRecents(context: Context) = lock.withLock {
        val started = System.nanoTime()
        val helper = PreviewChannelHelper(context)
        val prefs = app.kaizen.commons.KaizenStore.prefs(PREFS)
        var channelId = prefs.getLong(KEY_CHANNEL, -1L)
        // Con otra versión del logotipo se actualiza el canal ya creado (borrarlo y recrearlo perdería su visibilidad en inicio).
        if (channelId >= 0 && prefs.getInt(KEY_LOGO, 0) != LOGO_VERSION && helper.getPreviewChannel(channelId) != null) {
            helper.updatePreviewChannel(channelId, channelBuilder(context).build())
            prefs.edit().putInt(KEY_LOGO, LOGO_VERSION).apply()
        }
        if (channelId < 0 || helper.getPreviewChannel(channelId) == null) {
            channelId = helper.publishDefaultChannel(channelBuilder(context).build())
            prefs.edit().putLong(KEY_CHANNEL, channelId).putInt(KEY_LOGO, LOGO_VERSION).apply()
        }
        val channelDone = System.nanoTime()
        val recents = RecentsArchive.merge(Library.recentsNow()).take(MAX_ROWS)
        val dataDone = System.nanoTime()
        val art = ChannelArt.publish(context, "recents", recents.map { it.episodeImageUrl })
        val imagesDone = System.nanoTime()
        val signature = recentsSignature(channelId, recents.map { "${it.source}|${it.eid}|${it.name}|${it.chapter}|${it.episodeImageUrl}|${it.animeUrl}" })
        val existing = programCount(context, channelId)
        val outcome = if (canSkip(prefs.getString(KEY_SIGNATURE, null), signature, art.missing, existing, recents.size)) "sin cambios" else {
            rewritePrograms(context, helper, prefs, channelId, recents, art)
            prefs.edit().putString(KEY_SIGNATURE, signature).apply()
            "rehechos"
        }
        val done = System.nanoTime()
        fun ms(from: Long, to: Long) = (to - from) / 1_000_000
        DebugLog.info("Medición", "Fila de recientes: ${recents.size} capítulos, $outcome (canal ${ms(started, channelDone)} ms, datos ${ms(channelDone, dataDone)} ms, miniaturas ${ms(dataDone, imagesDone)} ms con ${art.missing} por bajar, programas ${ms(imagesDone, done)} ms)")
    }

    /** Qué hay en la fila: una línea por capítulo (con lo que lo distingue: textos, miniatura, enlace), en su orden, más el canal y la versión del logotipo. Igual firma = nada que rehacer. */
    internal fun recentsSignature(channelId: Long, rows: List<String>): String {
        val text = rows.joinToString("\n", prefix = "$channelId|$LOGO_VERSION\n")
        return java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * La fila ya está bien: misma firma que la de la última vez, ninguna miniatura por bajar (si faltaba alguna, los programas llevaban su dirección web) y el canal
     * conserva todos sus programas (el sistema puede haberlos quitado: entonces el recuento no coincide y se rehacen).
     */
    internal fun canSkip(storedSignature: String?, signature: String, missingArt: Int, existingPrograms: Int, wanted: Int): Boolean =
        storedSignature == signature && missingArt == 0 && existingPrograms == wanted

    /** Cuántos programas tiene ahora el canal; -1 si el proveedor no contesta (así nunca coincide y se rehace). */
    private fun programCount(context: Context, channelId: Long): Int = runCatching {
        context.contentResolver.query(TvContractCompat.buildPreviewProgramsUriForChannel(channelId), arrayOf(TvContractCompat.PreviewPrograms._ID), null, null, null)?.use { it.count }
    }.getOrNull() ?: -1

    /** Quita los programas del canal y publica los de [recents] en una sola operación al proveedor (antes, 20 llamadas seguidas); si no los acepta todos, vuelve al camino de uno en uno. */
    private fun rewritePrograms(context: Context, helper: PreviewChannelHelper, prefs: android.content.SharedPreferences, channelId: Long, recents: List<RecentEpisode>, art: ChannelArt.Published) {
        context.contentResolver.delete(TvContractCompat.buildPreviewProgramsUriForChannel(channelId), null, null)
        val programs = recents.map { recent ->
            PreviewProgram.Builder()
                .setChannelId(channelId)
                .setType(TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE)
                .setTitle(recent.name)
                .setDescription(recent.chapter)
                .setPosterArtUri(art.forLauncher(context, recent.episodeImageUrl).toUri())
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                .setIntent(seriesIntent(context, recent.animeUrl))
                .build()
        }
        if (programs.isEmpty()) return
        // Un proveedor que ya no aceptó el golpe una vez no lo vuelve a intentar (se recuerda): cada arranque sería borrar, probar y repetir uno a uno.
        if (!prefs.getBoolean(KEY_BULK_FAILED, false)) {
            val inserted = runCatching { context.contentResolver.bulkInsert(TvContractCompat.PreviewPrograms.CONTENT_URI, programs.map { it.toContentValues() }.toTypedArray()) }.getOrDefault(0)
            if (inserted == programs.size) return
            DebugLog.info("Medición", "Fila de recientes: el proveedor aceptó $inserted de ${programs.size} programas de golpe; desde ahora se publican uno a uno")
            prefs.edit().putBoolean(KEY_BULK_FAILED, true).apply()
            context.contentResolver.delete(TvContractCompat.buildPreviewProgramsUriForChannel(channelId), null, null)
        }
        programs.forEach { helper.publishPreviewProgram(it) }
    }

    /** Miniatura del capítulo (apaisada); si la fuente no la tiene, la portada. */
    private fun episodeImage(record: Record): String =
        AnimeSources.of(record.source).thumbnailUrl(record.aid, record.number.roundedString()).ifBlank { record.imageUrl() }

    private fun channelBuilder(context: Context) = PreviewChannel.Builder()
        .setDisplayName(context.getString(R.string.ui_capitulos_recientes))
        .setAppLinkIntent(Intent(context, app.kaizen.tv.ui.TVMain::class.java).addFlags(OPEN_FLAGS))
        // El proveedor de TV rechaza un canal sin logotipo.
        .setLogo(logo(context))

    private fun logo(context: Context) = android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.ic_home_channel)

    /** eid → id de programa de lo que esta app ya tiene en Watch Next. */
    private fun existingWatchNext(context: Context): Map<String, Long> {
        val found = mutableMapOf<String, Long>()
        context.contentResolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, WatchNextProgram.PROJECTION, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val program = WatchNextProgram.fromCursor(cursor)
                program.internalProviderId?.let { found[it] = program.id }
            }
        }
        return found
    }

    /** Sigue los cambios de progreso y de recientes y mantiene las dos filas al día. Se llama una vez, desde un hilo de fondo. */
    @OptIn(FlowPreview::class)
    suspend fun keepInSync(context: Context) {
        // Sin TvProvider (dispositivo sin Leanback) no hay filas que mantener.
        if (!context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)) return
        // Los recientes los renueva RecentsWork; aquí solo una vez al arrancar.
        runCatchingCoop { app.kaizen.commons.DebugLog.timed("Arranque", "Filas de la pantalla de inicio de Android TV actualizadas") { syncRecents(context) } }.onFailure(::logError)
        KaizenProgress.changes.debounce(2.seconds).collect {
            runCatchingCoop { syncWatchNext(context) }.onFailure(::logError)
        }
    }
}
