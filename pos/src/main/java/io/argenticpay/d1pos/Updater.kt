package io.argenticpay.d1pos

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Actualización desde ⚙: baja el APK publicado en el release "latest" de GitHub (DownloadManager, con notificación) y al
 * terminar abre el instalador del sistema. Android 8+ pide una vez permitir "instalar apps desconocidas" para D1 POS.
 */
object Updater {
    const val REPO = "https://github.com/timyotolc-jpg/d1-nfc-android/releases/download/latest/"
    const val POS_APK = REPO + "d1-pos.apk"
    const val SHOWCASE_APK = REPO + "d1-showcase-release.apk"
    const val WALLET_APK = REPO + "d1-wallet.apk"

    fun canInstall(ctx: Context): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun askInstallPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) runCatching {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun download(ctx: Context, url: String, fileName: String) {
        val dir = File(ctx.getExternalFilesDir(null), "apk").apply { mkdirs() }
        val target = File(dir, fileName).apply { delete() }
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setDescription("D1 · bajando la última versión")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(target))
            .setMimeType("application/vnd.android.package-archive")
        val id = dm.enqueue(req)
        Toast.makeText(ctx, "Bajando $fileName…", Toast.LENGTH_SHORT).show()
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                runCatching { c.unregisterReceiver(this) }
                if (!target.exists() || target.length() < 100_000) { Toast.makeText(c, "La descarga no terminó bien (¿internet?)", Toast.LENGTH_LONG).show(); return }
                install(c, target)
            }
        }
        ContextCompat.registerReceiver(ctx, rx, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(i) }.onFailure { Toast.makeText(ctx, "No se pudo abrir el instalador: " + it.message, Toast.LENGTH_LONG).show() }
    }
}
