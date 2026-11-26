package app.kaizen.commons

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

/**
 * La parte de Android de instalar una versión nueva: el permiso «instalar apps desconocidas», leer el paquete y su firma, y la sesión de `PackageInstaller`.
 * Las comprobaciones están en [UpdateInstaller]; el orden de todo, en [UpdateFlow]. El sistema pide siempre la confirmación final y vuelve a comprobar la firma.
 */
internal object UpdateInstall {
    private const val ACTION_RESULT = "app.kaizen.action.UPDATE_INSTALL_RESULT"

    /** Android deja a Kaizen instalar APKs (en Android TV se concede una vez en «Instalar apps desconocidas»). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Abre el ajuste del sistema para conceder el permiso. Falso si esa TV no tiene la pantalla. */
    fun openPermissionSettings(context: Context): Boolean = try {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: android.content.ActivityNotFoundException) {
        false
    }

    @Suppress("DEPRECATION")
    private fun signingFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun facts(info: android.content.pm.PackageInfo): UpdateInstaller.PackageFacts {
        // Primero las firmas actuales (API 28+); si el sistema no las da (algunas teles no las leen de un APK por instalar), las clásicas. Las dos app se leen igual, así que se comparan como siempre.
        val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners else null
        val signers = current?.takeIf { it.isNotEmpty() } ?: info.signatures
        val certs = signers.orEmpty().map { sig -> MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
        return UpdateInstaller.PackageFacts(info.packageName, PackageInfoCompat.getLongVersionCode(info), certs)
    }

    /** Lo de la app instalada; si no se puede leer, sin firmas (y [UpdateInstaller.verifyPackage] no deja instalar). */
    fun installedFacts(context: Context): UpdateInstaller.PackageFacts =
        runCatching { facts(context.packageManager.getPackageInfo(context.packageName, signingFlag())) }
            .getOrElse { UpdateInstaller.PackageFacts(context.packageName, Long.MAX_VALUE, emptySet()) }

    /** Lo del APK descargado, o null si el sistema no lo entiende. */
    fun archiveFacts(context: Context, apk: File): UpdateInstaller.PackageFacts? =
        runCatching { context.packageManager.getPackageArchiveInfo(apk.path, signingFlag())?.let(::facts) }.getOrNull()

    /** Abre una sesión de instalación con [apk] y la envía; el resultado llega a [UpdateInstallReceiver]. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(context.packageName) }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite(UpdateInstaller.ASSET, 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                // El sistema rellena el intent con el estado: tiene que ser mutable (a partir de Android 12 se exige decirlo).
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val result = PendingIntent.getBroadcast(context, id, Intent(context, UpdateInstallReceiver::class.java).setAction(ACTION_RESULT), flags)
                session.commit(result.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }
}

/** Recibe el resultado de la sesión: enseña al usuario la confirmación del sistema o, si falla o se cancela, lo dice. Sin exportar. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) UpdateFlow.onInstallResult(status, null) else context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            // Al instalarse, el sistema cierra la app y arranca la nueva: no queda nada que hacer.
            PackageInstaller.STATUS_SUCCESS -> UpdateFlow.cleanup(context)
            else -> UpdateFlow.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}
