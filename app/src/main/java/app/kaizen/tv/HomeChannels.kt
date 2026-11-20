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
import app.kaizen.domain.Library
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
    private const val LOGO_VERSION = 3

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
        ChannelArt.publish(context, "continue", wanted.map { episodeImage(it.first) })
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
                .setPosterArtUri(ChannelArt.forLauncher(context, episodeImage(record)).toUri())
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                .setIntent(seriesIntent(context, record.animeUrl()))
                .build()
            existing[record.eid.toString()]?.let { helper.updateWatchNextProgram(program, it) } ?: helper.publishWatchNextProgram(program)
        }
    }

    /** Vuelca los recientes al canal «Capítulos recientes» (lo crea la primera vez). Hilo de fondo. */
    suspend fun syncRecents(context: Context) = lock.withLock {
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
        val recents = RecentsArchive.merge(Library.recentsNow()).take(MAX_ROWS)
        ChannelArt.publish(context, "recents", recents.map { it.episodeImageUrl })
        context.contentResolver.delete(TvContractCompat.buildPreviewProgramsUriForChannel(channelId), null, null)
        recents.forEach { recent ->
            helper.publishPreviewProgram(
                PreviewProgram.Builder()
                    .setChannelId(channelId)
                    .setType(TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE)
                    .setTitle(recent.name)
                    .setDescription(recent.chapter)
                    .setPosterArtUri(ChannelArt.forLauncher(context, recent.episodeImageUrl).toUri())
                    .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                    .setIntent(seriesIntent(context, recent.animeUrl))
                    .build()
            )
        }
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
