package fr.spotifeur.social

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.NetworkType
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * How the Friends connection stays up. Instant (default): this silent foreground service keeps the app's own relay
 * connection open, so requests and shares arrive within a second, with no other app involved. Battery: no service; a
 * WorkManager job connects every 15 minutes for a short sync.
 */
class SocialService : Service() {
    override fun onBind(intent: Intent?) = null
    override fun onCreate() { super.onCreate(); running = true }
    override fun onDestroy() { running = false; super.onDestroy() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SocialNotifier.channels(this)
        val notification = NotificationCompat.Builder(this, SocialNotifier.CONNECTION)
            .setSmallIcon(fr.spotifeur.R.drawable.ic_stat_spotifire)
            .setContentTitle("Friends")
            .setContentText("Ready to receive music from friends")
            .setPriority(NotificationCompat.PRIORITY_MIN).setSilent(true).setOngoing(true)
            .setContentIntent(packageManager.getLaunchIntentForPackage(packageName)?.let {
                it.putExtra(SocialNotifier.EXTRA_OPEN_FRIENDS, true)
                android.app.PendingIntent.getActivity(this, 1000, it, android.app.PendingIntent.FLAG_IMMUTABLE)
            })
            .build()
        val started = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION, notification,
                if (android.os.Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0)
        }
        if (started.isFailure) { stopSelf(); return START_NOT_STICKY }
        Social.init(this)
        Social.start()
        return START_STICKY
    }

    companion object {
        private const val NOTIFICATION = 4100

        /**
         * True while the service lives. Starting it again would post its notification again: a notification the
         * user swiped away came back at every app launch and every 15-minute check.
         */
        @Volatile var running = false; private set

        /** Applies the chosen mode; called at app start, at boot and when the setting changes. */
        fun apply(context: Context) {
            val app = context.applicationContext
            if (!SocialMode.enabled(app)) {
                app.stopService(Intent(app, SocialService::class.java))
                WorkManager.getInstance(app).cancelUniqueWork(SocialSync.NAME)
                return
            }
            SocialSync.schedule(app)
            if (SocialMode.instant(app)) {
                if (!running) runCatching { ContextCompat.startForegroundService(app, Intent(app, SocialService::class.java)) }
            } else {
                app.stopService(Intent(app, SocialService::class.java))
                Social.init(app); Social.start()
            }
        }
    }
}

/** The Friends switches kept on the phone: set up or not, and instant or battery mode. */
object SocialMode {
    private fun prefs(context: Context) = context.getSharedPreferences("social", Context.MODE_PRIVATE)
    /** True once the user has set up Friends; before that the app never contacts a relay. */
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun instant(context: Context) = prefs(context).getBoolean("instant", true)
    /**
     * Whether Android lets Friends run without battery limits. Without it, Android may stop the connection in the
     * background and refuse to restart it until the app is opened, so invitations wait.
     */
    fun unrestricted(context: Context) =
        context.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    /** The system's own "allow to run in background" dialog for this app. */
    fun unrestrictIntent(context: Context) = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(android.net.Uri.parse("package:${context.packageName}"))
    fun askedUnrestricted(context: Context) = prefs(context).getBoolean("asked_unrestricted", false)
    fun setAskedUnrestricted(context: Context) { prefs(context).edit().putBoolean("asked_unrestricted", true).apply() }
    fun setEnabled(context: Context, on: Boolean) { prefs(context).edit().putBoolean("enabled", on).apply(); SocialService.apply(context) }
    /** Friends see what's playing: off until the user turns it on. */
    fun shareListening(context: Context) = prefs(context).getBoolean("share_listening", false)
    fun setShareListening(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("share_listening", on).apply()
        if (!on) Social.stopSharingListening()
    }
    fun setInstant(context: Context, on: Boolean) { prefs(context).edit().putBoolean("instant", on).apply(); SocialService.apply(context) }
}

/** The 15-minute sync: also the safety net in instant mode, if the system stopped the service. */
class SocialSync(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!SocialMode.enabled(applicationContext)) return Result.success()
        Social.init(applicationContext)
        // Instant mode: bring the connection back if Android stopped it (allowed when Friends runs without battery
        // limits; otherwise Android refuses and the short sync below still collects what arrived).
        if (SocialMode.instant(applicationContext) && !SocialService.running) runCatching {
            ContextCompat.startForegroundService(applicationContext, Intent(applicationContext, SocialService::class.java))
        }
        val wasRunning = Social.snapshot.value.connected
        Social.start()
        if (!wasRunning) {
            // Long enough for the relays to send what arrived while we were away.
            delay(25_000)
            if (!SocialMode.instant(applicationContext) && !fr.spotifeur.social.Social.inUse()) Social.stop()
        }
        return Result.success()
    }

    companion object {
        const val NAME = "friends-sync"
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SocialSync>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

class SocialBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) SocialService.apply(context)
    }
}
