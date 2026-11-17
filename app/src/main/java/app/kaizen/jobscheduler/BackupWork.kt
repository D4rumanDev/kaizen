package app.kaizen.jobscheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.kaizen.commons.AppScope
import app.kaizen.commons.KaizenBackup
import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.logError
import app.kaizen.commons.runCatchingCoop
import app.kaizen.source.CatalogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Copia de seguridad automática: una al día de favoritos, historial y progreso, borrando las de más de
 * [KaizenBackup.KEEP_DAYS] días. Android puede retrasar el trabajo periódico con la tele en reposo, así que al abrir la
 * app se lanza también una comprobación inmediata: si la última copia es de hace casi un día, se hace entonces.
 */
class BackupWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatchingCoop { KaizenBackup.exportIfDue() }.onFailure(::logError)
        // Con carpeta de red y «copiar cada copia» activo: las copias que le falten (un fallo de red no es un error de la copia).
        if (KaizenSettings.smbAuto && app.kaizen.commons.SmbBackup.configured) runCatchingCoop { app.kaizen.commons.SmbBackup.push() }.onFailure(::logError)
        runCatchingCoop { CatalogBuilder.saveSnapshotIfDue() }.onFailure(::logError)
        // Con la sincronización automática y un destino configurado: los estados con los de los otros dispositivos (un fallo de red no es un error de la copia).
        if (KaizenSettings.syncAuto && app.kaizen.commons.sync.SyncManager.configured) runCatchingCoop { app.kaizen.commons.sync.SyncManager.now() }.onFailure(::logError)
        // Un fallo suelto (disco lleno, base de datos ocupada) no merece reintentos: mañana se vuelve a intentar.
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "kaizen-backup-daily"
        private const val CATCH_UP = "kaizen-backup-catch-up"

        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BackupWork>(1, TimeUnit.DAYS).build()
            )
            // La comprobación inmediata solo se encola si de verdad toca; leer la carpeta de copias va fuera del hilo principal.
            AppScope.launch(Dispatchers.IO) {
                runCatchingCoop { KaizenBackup.checkData() }.onFailure(::logError)
                // Al abrir la app, con la sincronización automática: así lo visto en una tele llega a las demás sin esperar a la copia diaria.
                if (KaizenSettings.syncAuto && app.kaizen.commons.sync.SyncManager.configured) runCatchingCoop { app.kaizen.commons.sync.SyncManager.now() }.onFailure(::logError)
                if (KaizenBackup.isDue(KaizenBackup.latest()?.lastModified(), System.currentTimeMillis())) {
                    manager.enqueueUniqueWork(CATCH_UP, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<BackupWork>().build())
                }
            }
        }
    }
}
