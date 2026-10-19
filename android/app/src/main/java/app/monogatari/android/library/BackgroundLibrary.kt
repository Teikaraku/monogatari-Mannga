package app.monogatari.android.library

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.work.*
import app.monogatari.android.R
import app.monogatari.android.data.*
import app.monogatari.android.ui.Localization
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

object LibraryRefresh {
    private val foregroundJobs = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()
    private val dueMutex = kotlinx.coroutines.sync.Mutex()
    private val refreshMutex = kotlinx.coroutines.sync.Mutex()
    private fun hours(repo: AppRepository) = when (repo.settings.string("Library.updateInterval")) { "12hours" -> 12L; "2days" -> 48; "weekly" -> 168; "never" -> 0; else -> 24 }
    fun isWifi(context: Context): Boolean {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        return manager.getNetworkCapabilities(manager.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
    }
    fun requiresWifi(repo:AppRepository)=repo.settings.bool("Library.updateOnlyOnWifi") && !isWifi(repo.context)
    fun appStopped(repo: AppRepository) { if (!repo.settings.bool("Library.backgroundRefresh")) foregroundJobs.forEach { it.cancel() } }
    suspend fun foregroundIfDue(repo: AppRepository) {
        if (!dueMutex.tryLock()) return
        try {
        val period = hours(repo)
        if (period <= 0 || requiresWifi(repo)) return
        if (System.currentTimeMillis() / 1000.0 - repo.settings.double("Library.lastUpdated") < period * 3600) return
        val job = currentCoroutineContext()[Job] ?: return
        foregroundJobs += job
        try { refresh(repo) } finally { foregroundJobs -= job }
        } finally { dueMutex.unlock() }
    }
    suspend fun manualRefresh(repo: AppRepository,category:String?=null,allowCellular:Boolean=false): List<String> {
        if(!allowCellular && requiresWifi(repo))error(Localization.string(repo.context,"REFRESH_NO_WIFI_TEXT"))
        val job = currentCoroutineContext()[Job]
        if (job != null) foregroundJobs += job
        try { return refresh(repo,category=category,allowCellular=allowCellular) } finally { if (job != null) foregroundJobs -= job }
    }
    fun schedule(repo: AppRepository) {
        val settings = repo.settings
        val period = hours(repo)
        if (!settings.bool("Library.backgroundRefresh") || period == 0L) { repo.work.cancelUniqueWork("library-refresh"); return }
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<LibraryRefreshWorker>(period, TimeUnit.HOURS).setConstraints(constraints).build()
        repo.work.enqueueUniquePeriodicWork("library-refresh", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
    suspend fun refresh(repo: AppRepository,category:String?=null,allowCellular:Boolean=false,forceAll:Boolean=false): List<String> = refreshMutex.withLock {
        if(!allowCellular && requiresWifi(repo))return@withLock emptyList()
        val settings = repo.settings
        val skip = if (forceAll) emptySet() else settings.strings("Library.skipTitles").toSet()
        val excluded = if(forceAll)emptySet() else settings.strings("Library.excludedUpdateCategories").toSet()
        val sources=repo.state.value.sources.filter { it.type!="wasm" || runCatching { repo.client.runtimes.installed(it.id) }.isSuccess }.map { it.id }.toSet()
        app.monogatari.android.tracking.TrackingRepository.get(repo.context).retryPending()
        val manga = repo.state.value.manga.filter { m ->
            LibraryRefreshPolicy.eligible(m,sources,skip,excluded,category)
        }
        if(manga.isEmpty())return@withLock emptyList()
        val notifyInBackground=!app.monogatari.android.downloads.DownloadService.get(repo.context).appVisible
        val errors = mutableListOf<String>(); var count = 0
        manga.forEach { m ->
            try {
                val updated = repo.refresh(m, forceAll || settings.bool("Library.refreshMetadata"))
                count+=LibraryRefreshPolicy.newNotifiableChapters(m,updated).size
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { errors += "${m.title}: ${e.message}" }
        }
        settings.put("Library.lastUpdated", System.currentTimeMillis() / 1000.0)
        if (count > 0 && notifyInBackground && settings.bool("Library.notifyNewChapters")) notify(repo.context, count)
        errors
    }
    private fun notify(context: Context, count: Int) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("library-updates", Localization.string(context, "MANGA_UPDATES"), NotificationManager.IMPORTANCE_DEFAULT))
        manager.notify(3001, NotificationCompat.Builder(context, "library-updates").setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(Localization.string(context, "MANGA_UPDATES")).setContentText("$count ${Localization.string(context, "CHAPTERS")}")
            .setContentIntent(PendingIntent.getActivity(context, 3001, Intent(context, app.monogatari.android.MainActivity::class.java).putExtra("monogatari.openUpdates", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true).build())
    }
}
class LibraryRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result { return try {
        val repo = AppRepository.get(applicationContext)
        if (repo.settings.bool("Library.updateOnlyOnWifi") && !LibraryRefresh.isWifi(applicationContext)) return Result.retry()
        val errors = LibraryRefresh.refresh(repo)
        if (errors.isEmpty()) Result.success() else Result.retry()
    } catch (e: CancellationException) { throw e } catch (_: Exception) { Result.retry() } }
}
