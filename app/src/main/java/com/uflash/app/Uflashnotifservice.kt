package com.uflash.app

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

class UFlashNotifService : Service() {

    companion object {
        const val CHANNEL_ID  = "uflash_media"
        const val NOTIF_ID    = 1

        const val ACTION_PREV  = "com.uflash.app.ACTION_PREV"
        const val ACTION_SEND  = "com.uflash.app.ACTION_SEND"
        const val ACTION_NEXT  = "com.uflash.app.ACTION_NEXT"

        const val EXTRA_LABEL  = "cmd_label"
        const val EXTRA_CODE   = "cmd_code"
    }

    private var mediaSession: MediaSessionCompat? = null

    override fun onCreate() {
        super.onCreate()
        // Create channel first — must exist before any notification is posted
        createChannel()

        // Build MediaSession
        mediaSession = MediaSessionCompat(this, "UFlash").apply {
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1f)
                    .setActions(
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                                PlaybackStateCompat.ACTION_PLAY or
                                PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                    ).build()
            )
            isActive = true
        }

        // FIX: Call startForeground() immediately in onCreate so Android 12+
        // does not kill the service before onStartCommand fires.
        val placeholder = buildNotification("READY", "H")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, placeholder, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, placeholder)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: "READY"
        val code  = intent?.getStringExtra(EXTRA_CODE)  ?: "H"
        // Update the already-posted foreground notification with real content
        val notif = buildNotification(label, code)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
        return START_STICKY
    }

    override fun onDestroy() { mediaSession?.release(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(cmdLabel: String, cmdCode: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun broadcast(action: String): PendingIntent {
            val i = Intent(action).apply {
                setPackage(packageName)
                putExtra(EXTRA_CODE, cmdCode)
                putExtra(EXTRA_LABEL, cmdLabel)
            }
            return PendingIntent.getBroadcast(
                this, action.hashCode() and 0xFFFF, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("UFLASH  —  TRANSMIT")
            .setContentText(cmdLabel)
            .setSubText("Tap SEND to transmit  |  use arrows to navigate")
            .setContentIntent(openApp)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setColorized(true)
            .setColor(Color.parseColor("#D4FF00"))
            .addAction(android.R.drawable.ic_media_previous, "PREV", broadcast(ACTION_PREV))
            .addAction(android.R.drawable.ic_media_play,     "SEND", broadcast(ACTION_SEND))
            .addAction(android.R.drawable.ic_media_next,     "NEXT", broadcast(ACTION_NEXT))
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession!!.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(false)
            )
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "UFlash Transmit Controls",
                // FIX: was IMPORTANCE_LOW which silently suppresses lock screen display.
                // IMPORTANCE_DEFAULT is the minimum level required to appear on lock screen.
                // We disable sound/vibration explicitly so it stays silent.
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description          = "Lock-screen quick-send controls"
                setShowBadge(false)
                setSound(null, null)   // silent — no audible ping on update
                enableVibration(false) // no vibration on each command switch
                lockscreenVisibility  = Notification.VISIBILITY_PUBLIC
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }
}
