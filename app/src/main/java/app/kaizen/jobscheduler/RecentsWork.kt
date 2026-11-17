package app.kaizen.jobscheduler

import app.kaizen.commons.logError
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import app.kaizen.BuildConfig
import app.kaizen.R
import app.kaizen.source.AnimeSources
import app.kaizen.commons.Network
import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.create
import app.kaizen.database.CacheDB
import app.kaizen.pojos.NotificationObj
import app.kaizen.pojos.av1.RecentEpisode
import app.kaizen.recents.RecentsNotReceiver
import java.util.concurrent.TimeUnit

class RecentsWork(val context: Context, workerParameters: WorkerParameters) :
    CoroutineWorker(context, workerParameters) {
    private val RECENTS_GROUP = "recents-group"
    private val recentsDAO = CacheDB.INSTANCE.recentEpisodeDAO()
    private val favsDAO = CacheDB.INSTANCE.favoriteAV1DAO()
    private val notificationDAO = CacheDB.INSTANCE.notificationDAO()
    private val manager: NotificationManager by lazy { context.getSystemService(android.app.NotificationManager::class.java) }

    private val summaryBroadcast: Intent
        get() = Intent(context, RecentsNotReceiver::class.java).putExtra("mode", 1)

    override suspend fun doWork(): Result {
        if (!Network.isConnected) return Result.success().also { Log.e("Recents", "No Network") }
        //setForeground(createForegroundInfo())
        try {
            // Sin recientes guardados no hay con qué comparar ni nada que avisar: no se pide nada a las fuentes.
            val local = recentsDAO.all
            if (local.isEmpty() && !BuildConfig.DEBUG)
                return Result.success()
            // Mismas fuentes activas que la pantalla principal.
            val recents = AnimeSources.recentsAll()
            newEpisodes(local, recents, onlyFollowed = app.kaizen.commons.KaizenStore.settings.getBoolean("notify_favs", false)).forEach { notifyRecent(it) }
            recentsDAO.setCache(recents)
            app.kaizen.commons.runCatchingCoop { app.kaizen.tv.HomeChannels.syncRecents(context) }.onFailure(::logError)
            return Result.success()
        } catch (e: Exception) {
            logError(e)
            return Result.failure()
        }
    }

    /** Los capítulos de [recents] que no estaban en [local]; con [onlyFollowed], solo los de series favoritas o que se están viendo. */
    private fun newEpisodes(local: List<RecentEpisode>, recents: List<RecentEpisode>, onlyFollowed: Boolean): List<RecentEpisode> {
        val known = local.mapTo(HashSet()) { it.eid }
        return recents.filter { it.eid !in known && (!onlyFollowed || isFollowed(it)) }
    }

    private fun isFollowed(episode: RecentEpisode) =
        favsDAO.isFav(episode.source, episode.slug) || app.kaizen.domain.SeriesStatuses.of(episode.name, episode.source, episode.slug) == app.kaizen.domain.SeriesStatus.WATCHING

    private suspend fun notifyRecent(recentObject: RecentEpisode) {
        val obj = NotificationObj(
            recentObject.eid,
            NotificationObj.RECENT
        )
        val icon = getBitmap(recentObject.animeImageUrl)
        val notification = NotificationCompat.Builder(context, CHANNEL_RECENTS).create {
            setSmallIcon(R.drawable.ic_new_recent)
            color = ContextCompat.getColor(context, R.color.colorAccent)
            setContentTitle(recentObject.name)
            setContentText(recentObject.chapter)
            priority = NotificationCompat.PRIORITY_MAX
            setLargeIcon(icon)
            setAutoCancel(true)
            setOnlyAlertOnce(true)
            setContentIntent(
                PendingIntent.getActivity(
                    context,
                    System.currentTimeMillis().toInt(),
                    getAnimeIntent(recentObject, obj),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            setDeleteIntent(
                PendingIntent.getBroadcast(
                    context,
                    System.currentTimeMillis().toInt(),
                    obj.getBroadcast(context),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            setGroup(RECENTS_GROUP)
            setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
        }.build()
        notificationDAO.add(obj)
        manager.notify(obj.key, notification)
        notifySummary()
    }

    /** La imagen de la notificación, acotada a su tamaño (un icono grande no necesita la portada entera); null si está desactivada o no carga. */
    private suspend fun getBitmap(link: String): Bitmap? {
        if (!KaizenSettings.showRecentImage) return null
        val request = ImageRequest.Builder(context).data(link).size(LARGE_ICON_PX, LARGE_ICON_PX).build()
        return app.kaizen.commons.runCatchingCoop { (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap() }.getOrNull()
    }

    private fun getAnimeIntent(animeObject: RecentEpisode, notificationObj: NotificationObj): Intent {
        return Intent(context, app.kaizen.tv.details.TVAnimesDetails::class.java)
                .setData(animeObject.animeUrl.toUri())
                .putExtras(notificationObj.getBroadcast(context))
                .putExtra("title", animeObject.name)
                .putExtra("aid", animeObject.aid)
                .putExtra("img", animeObject.animeImageUrl)
                .putExtra("notification", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun notifySummary() {
        val notification = NotificationCompat.Builder(context, CHANNEL_RECENTS)
            .setSmallIcon(R.drawable.ic_recents_group)
            .setColor(ContextCompat.getColor(context, R.color.colorAccent))
            .setContentTitle("Nuevos capítulos")
            .setContentText("Hay nuevos capítulos recientes")
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setGroup(RECENTS_GROUP)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, app.kaizen.tv.ui.TVMain::class.java),
                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setDeleteIntent(
                PendingIntent.getBroadcast(
                    context,
                    System.currentTimeMillis().toInt(),
                    summaryBroadcast,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
                .build()
        if (KaizenSettings.isGroupingEnabled)
            manager.notify(KEY_SUMMARY, notification)
    }

    companion object {
        const val CHANNEL_RECENTS = "channel.RECENTS"
        const val KEY_SUMMARY = 55971
        internal const val TAG = "recents-job"
        private const val LARGE_ICON_PX = 256

        fun schedule(context: Context) {
            // Cada 60 min por defecto (antes 15): una TV no necesita sondear las fuentes cuatro veces por hora; los recientes se
            // pueden pedir al momento con el botón «Actualizar» de la pantalla de inicio. Se cambia en Configuración › Actualización y caché.
            val time = app.kaizen.commons.KaizenSettings.recentsEveryMinutes
            PeriodicWorkRequestBuilder<RecentsWork>(
                time.coerceAtLeast(15).toLong(),
                TimeUnit.MINUTES,
                5,
                TimeUnit.MINUTES
            ).apply {
                setInitialDelay(15L, TimeUnit.MINUTES)
                //setConstraints(networkConnectedConstraints())
                //setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                addTag(TAG)
            }.build().enqueueUnique(TAG, ExistingPeriodicWorkPolicy.UPDATE) // UPDATE: el trabajo ya programado cada 15 min pasa al nuevo intervalo
        }

        fun run() = OneTimeWorkRequestBuilder<RecentsWork>().build().enqueue()
    }
}