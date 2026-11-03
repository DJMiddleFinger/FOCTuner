package app.foctuner.logging

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.foctuner.MainActivity
import app.foctuner.R
import app.foctuner.vesc.RideRecorder
import app.foctuner.vesc.VescException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Records a ride log while the app is connected: a foreground service, so the recording goes on with the screen off
 * and the app in the background. It reads the controller the session attached to [RideLogHub] and the phone's GPS
 * (demo mode: a simulated loop), writes VESC Tool's CSV into [LogStorage], and ends when stopped, when the link
 * drops, or when there is no connection to record.
 */
class RideLogService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recording: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
        } else if (recording?.isActive != true) {
            startRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        val source = RideLogHub.source
        val located = hasLocationPermission()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), foregroundTypes(located))
        if (source == null) {
            RideLogHub.update { it.copy(failure = RideLogState.Failure.NoConnection) }
            stopSelf()
            return
        }
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FOCTuner:ridelog").apply { acquire() }
        val fixes: FixSource? = when {
            source.demo -> DemoFixSource()
            located -> GpsFixSource(this)
            else -> null
        }
        val file = LogStorage.newPhoneLog(this)
        val recorder = RideRecorder(source.controller)
        RideLogHub.update { RideLogState(recording = true, locationAllowed = source.demo || located, lastFile = file.name) }
        recording = scope.launch {
            val watcher = launch { recorder.status.collect { s -> RideLogHub.update { it.copy(status = s) } } }
            var failure: RideLogState.Failure? = null
            try {
                file.bufferedWriter().use { out -> recorder.record(out, RATE_HZ) { fixes?.latest() } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VescException) {
                failure = RideLogState.Failure.LinkLost
            } catch (e: IOException) {
                failure = RideLogState.Failure.LinkLost
            } finally {
                watcher.cancel()
                fixes?.close()
                val rows = recorder.status.value.rows
                if (rows < MIN_ROWS) file.delete()
                RideLogHub.update { it.copy(recording = false, failure = failure, lastFile = file.name.takeIf { rows >= MIN_ROWS }) }
            }
        }
        recording?.invokeOnCompletion { stopSelf() }
    }

    private fun stopRecording() {
        recording?.cancel() ?: stopSelf()
    }

    override fun onDestroy() {
        recording?.cancel()
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Location only with the permission, since Android refuses to start a service of that type without it. */
    private fun foregroundTypes(located: Boolean): Int =
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or if (located) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.logging_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, stopIntent(this), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_curve)
            .setContentTitle(getString(R.string.logging_notification_title))
            .setContentText(getString(R.string.logging_notification_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.logging_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val ACTION_STOP = "app.foctuner.logging.STOP"
        private const val CHANNEL = "ride_log"
        private const val NOTIFICATION_ID = 41
        private const val RATE_HZ = 5f
        private const val MIN_ROWS = 2

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RideLogService::class.java))
        }

        fun stop(context: Context) {
            context.startService(stopIntent(context))
        }

        private fun stopIntent(context: Context) = Intent(context, RideLogService::class.java).setAction(ACTION_STOP)
    }
}
