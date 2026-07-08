package net.hlan.sushi

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

/**
 * Foreground service that keeps the process (and the JSch reader thread it hosts)
 * alive while a terminal session is connected. Without this, Android freezes or
 * kills the app soon after it leaves the foreground and the SSH session drops.
 *
 * Ownership of the actual [TerminalBackend] stays in [TerminalSessionHolder]; this
 * service only holds the foreground-priority notification and reacts to the
 * connect/disconnect state already tracked there.
 */
class SshConnectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            TerminalSessionHolder.getActiveBackend()?.disconnect()
            TerminalSessionHolder.clearActiveConnection()
            return START_NOT_STICKY
        }

        ensureChannel()
        val hostLabel = TerminalSessionHolder.getActiveConfig()?.displayTarget()
            ?: getString(R.string.ssh_notification_title)
        val notification = buildNotification(hostLabel)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun buildNotification(hostLabel: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, TerminalActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, SshConnectionService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentTitle(getString(R.string.ssh_notification_title))
            .setContentText(hostLabel)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .addAction(R.drawable.ic_link_off, getString(R.string.ssh_notification_action_disconnect), disconnectIntent)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.ssh_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "ssh_connection"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_DISCONNECT = "net.hlan.sushi.action.DISCONNECT_SSH"

        fun start(context: Context) {
            val intent = Intent(context, SshConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SshConnectionService::class.java))
        }
    }
}
