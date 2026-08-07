package com.alekpeed.hearsay.feature.processing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.alekpeed.hearsay.core.model.analysis.AnalysisJob
import com.alekpeed.hearsay.core.model.project.AnalysisProfile
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Keeps an analysis alive and visible while it runs.
 *
 * A user-initiated analysis of a whole song is exactly the case Android's background limits are
 * aimed at, and exactly the case a foreground service exists for: it runs because the user asked,
 * it says so in the notification shade, and it can be stopped from there. WorkManager is not used
 * for the active run — its quotas and deferral are wrong for work somebody is waiting on — but the
 * job's state lives in the database, so nothing is lost if the process dies anyway.
 */
@AndroidEntryPoint
class AnalysisService : Service() {

    @Inject lateinit var engine: AnalysisEngine

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Whether a start has been asked for whose job has not yet appeared in the database.
     *
     * There is a window — from the start command until the job row is committed — where the queue
     * is legitimately empty although work is coming. Reading that emptiness as "finished" and
     * stopping is what used to strand an analysis at "Starting" with nothing behind it.
     */
    private var awaitingWork = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat(NotificationId, buildNotification(null))

        // The notification follows the queue rather than being pushed to; when the last job
        // finishes, the service stops itself.
        scope.launch {
            engine.observeActiveJobs().collectLatest { jobs ->
                val active = jobs.firstOrNull { it.isActive }
                if (active != null) awaitingWork = false

                if (active == null && !engine.isBusy && !awaitingWork) {
                    stopSelf()
                } else {
                    notificationManager().notify(NotificationId, buildNotification(active))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ActionStart -> {
                val projectId = intent.getStringExtra(ExtraProjectId)
                val profile = intent.getStringExtra(ExtraProfile)
                    ?.let { runCatching { AnalysisProfile.valueOf(it) }.getOrNull() }
                    ?: AnalysisProfile.BALANCED
                if (projectId != null) {
                    awaitingWork = true
                    // Deliberately not this scope: the handover must outlive the service, which can
                    // stop for reasons that have nothing to do with whether the analysis should run.
                    engine.enqueue(projectId, profile)
                }
            }

            ActionCancel -> {
                intent.getStringExtra(ExtraProjectId)?.let(engine::cancel)
            }

            ActionCancelAll -> {
                engine.cancelAll()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(job: AnalysisJob?): Notification {
        val progress = job?.weightedProgress ?: 0f
        val stageName = job?.currentStage?.type?.displayName ?: "Preparing"

        val cancelIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, AnalysisService::class.java).setAction(ActionCancelAll),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, ChannelId)
            .setContentTitle("Analyzing")
            .setContentText(stageName)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, (progress * 100).roundToInt(), job == null)
            .addAction(0, "Stop", cancelIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun startForegroundCompat(id: Int, notification: Notification) {
        ServiceCompat.startForeground(
            this,
            id,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            ChannelId,
            "Analysis",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progress while a recording is being analyzed"
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val ChannelId = "analysis"
        private const val NotificationId = 4201

        const val ActionStart = "com.alekpeed.hearsay.action.START_ANALYSIS"
        const val ActionCancel = "com.alekpeed.hearsay.action.CANCEL_ANALYSIS"
        const val ActionCancelAll = "com.alekpeed.hearsay.action.CANCEL_ALL_ANALYSIS"
        const val ExtraProjectId = "projectId"
        const val ExtraProfile = "profile"

        fun start(context: Context, projectId: String, profile: AnalysisProfile) {
            val intent = Intent(context, AnalysisService::class.java)
                .setAction(ActionStart)
                .putExtra(ExtraProjectId, projectId)
                .putExtra(ExtraProfile, profile.name)
            context.startForegroundService(intent)
        }

        fun cancel(context: Context, projectId: String) {
            val intent = Intent(context, AnalysisService::class.java)
                .setAction(ActionCancel)
                .putExtra(ExtraProjectId, projectId)
            context.startService(intent)
        }
    }
}
