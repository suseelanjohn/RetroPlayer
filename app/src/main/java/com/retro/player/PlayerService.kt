package com.retro.player

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.widget.RemoteViews

/** Owns the MediaPlayer: background playback, retro notification, lock screen + headset controls. */
class PlayerService : Service(), AudioManager.OnAudioFocusChangeListener {
    inner class LocalBinder : Binder() { val service: PlayerService get() = this@PlayerService }

    companion object {
        const val A_TOGGLE = "retro.TOGGLE"; const val A_NEXT = "retro.NEXT"
        const val A_PREV = "retro.PREV"; const val A_STOP = "retro.STOP"
        private const val CH = "retro_playback"; private const val ID = 7
    }

    private val binder = LocalBinder()
    var queue: List<Song> = emptyList(); private set
    var idx = -1; private set
    var current: Song? = null; private set
    var shuffle = false
    var repeat = 0 // 0 off, 1 all, 2 one
    var onChange: (() -> Unit)? = null
    private var mp: MediaPlayer? = null
    private lateinit var session: MediaSession
    private lateinit var nm: NotificationManager
    private lateinit var prefs: SharedPreferences

    val playing: Boolean get() = try { mp?.isPlaying == true } catch (e: Exception) { false }
    val position: Int get() = try { mp?.currentPosition ?: 0 } catch (e: Exception) { 0 }
    val duration: Int get() = try { mp?.duration ?: 0 } catch (e: Exception) { 0 }

    override fun onBind(i: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        prefs = getSharedPreferences("retro", MODE_PRIVATE)
        shuffle = prefs.getBoolean("shuffle", false)
        repeat = prefs.getInt("repeat", 0)
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel(CH, "Playback", NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "RetroPlayer")
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { resume() }
            override fun onPause() { pause() }
            override fun onSkipToNext() { next() }
            override fun onSkipToPrevious() { prev() }
            override fun onSeekTo(pos: Long) { seekTo(pos.toInt()) }
        })
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        when (i?.action) {
            A_TOGGLE -> toggle()
            A_NEXT -> next()
            A_PREV -> prev()
            A_STOP -> stopAll()
        }
        return START_NOT_STICKY
    }

    fun play(list: List<Song>, i: Int) {
        if (list.isEmpty()) return
        queue = list; idx = i; current = list[i]
        mp?.release(); mp = null
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        val m = MediaPlayer()
        try {
            m.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            m.setDataSource(this, current!!.uri)
            m.prepare()
            m.setOnCompletionListener { onCompleted() }
            m.start()
        } catch (e: Exception) {
            m.release(); current = null; changed(); return
        }
        mp = m
        val k = "pc_" + current!!.key
        prefs.edit().putInt(k, prefs.getInt(k, 0) + 1).apply()
        changed()
    }

    private fun onCompleted() {
        when {
            repeat == 2 -> play(queue, idx)
            repeat == 0 && !shuffle && idx >= queue.size - 1 -> { mp?.seekTo(0); changed() }
            else -> next()
        }
    }

    fun resume() { mp?.let { if (!it.isPlaying) { it.start(); changed() } } }
    fun pause() { mp?.let { if (it.isPlaying) { it.pause(); changed() } } }
    fun toggle() { if (playing) pause() else resume() }
    fun seekTo(ms: Int) { mp?.seekTo(ms); updateSession() }
    fun next() { if (queue.isNotEmpty()) play(queue, if (shuffle) (0 until queue.size).random() else (idx + 1) % queue.size) }
    fun prev() { if (queue.isNotEmpty()) play(queue, (idx - 1 + queue.size) % queue.size) }

    private fun stopAll() {
        mp?.release(); mp = null; current = null
        session.isActive = false
        stopForeground(true); nm.cancel(ID)
        onChange?.invoke()
        stopSelf()
    }

    private fun changed() { updateSession(); updateNotification(); onChange?.invoke() }

    private fun updateSession() {
        val s = current
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, position.toLong(), 1f)
            .build())
        if (s != null) session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration.toLong()).build())
        session.isActive = s != null
    }

    private fun pf() = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
    private fun act(a: String) = PendingIntent.getService(this, a.hashCode(), Intent(this, PlayerService::class.java).setAction(a), pf())

    private fun updateNotification() {
        val s = current ?: return
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_disc)
            .setContentTitle(s.title.uppercase())
            .setShowWhen(false)
            .setColor(Color.parseColor("#33FF33"))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), pf()))
            .setDeleteIntent(act(A_STOP))
        val n: Notification
        if (Build.VERSION.SDK_INT >= 24) {
            // Newer Android: system media notification with dark-green pixel icons
            b.addAction(R.drawable.n_prev, "Previous", act(A_PREV))
                .addAction(if (playing) R.drawable.n_pause else R.drawable.n_play, if (playing) "Pause" else "Play", act(A_TOGGLE))
                .addAction(R.drawable.n_next, "Next", act(A_NEXT))
                .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            n = b.build()
        } else {
            // Android 5/6: fully custom black + green CRT notification (also shown on the lock screen)
            val rv = RemoteViews(packageName, R.layout.notif)
            rv.setTextViewText(R.id.n_title, s.title.uppercase())
            rv.setImageViewResource(R.id.n_play, if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            rv.setOnClickPendingIntent(R.id.n_prev, act(A_PREV))
            rv.setOnClickPendingIntent(R.id.n_play, act(A_TOGGLE))
            rv.setOnClickPendingIntent(R.id.n_next, act(A_NEXT))
            n = b.build()
            n.contentView = rv
            n.bigContentView = rv
        }
        try {
            if (playing) startForeground(ID, n) else { stopForeground(false); nm.notify(ID, n) }
        } catch (e: Exception) { nm.notify(ID, n) }
    }

    override fun onAudioFocusChange(change: Int) {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
    }

    override fun onDestroy() {
        nm.cancel(ID); mp?.release(); mp = null; session.release()
        super.onDestroy()
    }
}
