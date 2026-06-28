package cat.oriol.nowreadit.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import cat.oriol.nowreadit.MainActivity
import cat.oriol.nowreadit.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlaybackServiceState(
    val audioPath: String? = null,
    val isReady: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null,
)

class PlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var audioManager: AudioManager
    private var mediaPlayer: MediaPlayer? = null
    private var currentTitle: String = ""
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var pollingStarted = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            -> pausePlayback()
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching {
            when (intent?.action) {
                ACTION_PREPARE -> prepare(
                    path = intent.getStringExtra(EXTRA_AUDIO_PATH).orEmpty(),
                    title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                )
                ACTION_PLAY -> {
                    val path = intent.getStringExtra(EXTRA_AUDIO_PATH)
                    val title = intent.getStringExtra(EXTRA_TITLE)
                    if (path != null) prepare(path, title.orEmpty())
                    startPlayback()
                }
                ACTION_PAUSE -> pausePlayback()
                ACTION_SEEK -> seekTo(intent.getLongExtra(EXTRA_POSITION_MS, 0L))
                ACTION_SKIP -> skipBy(intent.getLongExtra(EXTRA_DELTA_MS, 0L))
                ACTION_RESTART -> restart()
                ACTION_STOP -> stopPlayback()
            }
        }.onFailure { throwable ->
            _state.value = _state.value.copy(error = throwable.message ?: "Playback failed")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releasePlayer()
        scope.cancel()
        super.onDestroy()
    }

    private fun prepare(path: String, title: String) {
        if (path.isBlank()) return
        if (mediaPlayer != null && _state.value.audioPath == path) return

        releasePlayer()
        currentTitle = title
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            setDataSource(path)
            prepare()
            setOnCompletionListener { completedPlayer ->
                abandonAudioFocus()
                stopForegroundCompat(removeNotification = true)
                _state.value = _state.value.copy(
                    isReady = true,
                    isPlaying = false,
                    positionMs = completedPlayer.duration.toLong(),
                    durationMs = completedPlayer.duration.toLong(),
                    error = null,
                )
                stopSelf()
            }
        }
        val player = mediaPlayer ?: return
        _state.value = PlaybackServiceState(
            audioPath = path,
            isReady = true,
            isPlaying = false,
            positionMs = 0L,
            durationMs = player.duration.toLong(),
        )
    }

    private fun startPlayback() {
        val player = mediaPlayer ?: return
        if (player.currentPosition >= player.duration) {
            player.seekTo(0)
        }
        if (!requestAudioFocus()) {
            _state.value = _state.value.copy(error = "Could not start audio playback.")
            return
        }
        updateForeground(isForeground = true)
        player.start()
        _state.value = _state.value.copy(
            isReady = true,
            isPlaying = true,
            positionMs = player.currentPosition.toLong(),
            durationMs = player.duration.toLong(),
            error = null,
        )
        startPolling()
    }

    private fun pausePlayback() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            player.pause()
        }
        abandonAudioFocus()
        updateForeground(isForeground = false)
        _state.value = _state.value.copy(
            isReady = true,
            isPlaying = false,
            positionMs = player.currentPosition.toLong(),
            durationMs = player.duration.toLong(),
            error = null,
        )
    }

    private fun seekTo(positionMs: Long) {
        val player = mediaPlayer ?: return
        val clampedPosition = positionMs.coerceIn(0L, player.duration.toLong())
        player.seekTo(clampedPosition.toInt())
        _state.value = _state.value.copy(
            isReady = true,
            positionMs = clampedPosition,
            durationMs = player.duration.toLong(),
            error = null,
        )
        if (player.isPlaying) updateForeground(isForeground = true)
    }

    private fun skipBy(deltaMs: Long) {
        val player = mediaPlayer ?: return
        seekTo(player.currentPosition.toLong() + deltaMs)
    }

    private fun restart() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            player.pause()
        }
        abandonAudioFocus()
        player.seekTo(0)
        updateForeground(isForeground = false)
        _state.value = _state.value.copy(
            isReady = true,
            isPlaying = false,
            positionMs = 0L,
            durationMs = player.duration.toLong(),
            error = null,
        )
    }

    private fun stopPlayback() {
        releasePlayer()
        stopForegroundCompat(removeNotification = true)
        stopSelf()
    }

    private fun startPolling() {
        if (pollingStarted) return
        pollingStarted = true
        scope.launch {
            while (isActive) {
                val player = mediaPlayer
                if (player != null) {
                    _state.value = _state.value.copy(
                        isReady = true,
                        isPlaying = player.isPlaying,
                        positionMs = player.currentPosition.toLong(),
                        durationMs = player.duration.toLong(),
                        error = null,
                    )
                }
                delay(500)
            }
        }
    }

    private fun releasePlayer() {
        abandonAudioFocus()
        mediaPlayer?.release()
        mediaPlayer = null
        _state.value = PlaybackServiceState()
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
                .also { audioFocusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
        hasAudioFocus = false
    }

    private fun updateForeground(isForeground: Boolean) {
        val notification = createNotification(isPlaying = isForeground)
        if (isForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
            stopForegroundCompat(removeNotification = false)
        }
    }

    private fun createNotification(isPlaying: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(currentTitle.ifBlank { "Playing audio" })
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                android.R.drawable.ic_media_rew,
                "Rewind",
                servicePendingIntent(
                    requestCode = REQUEST_REWIND,
                    action = ACTION_SKIP,
                    deltaMs = -SKIP_INTERVAL_MS,
                ),
            )
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play",
                servicePendingIntent(
                    requestCode = if (isPlaying) REQUEST_PAUSE else REQUEST_PLAY,
                    action = if (isPlaying) ACTION_PAUSE else ACTION_PLAY,
                ),
            )
            .addAction(
                android.R.drawable.ic_media_ff,
                "Forward",
                servicePendingIntent(
                    requestCode = REQUEST_FORWARD,
                    action = ACTION_SKIP,
                    deltaMs = SKIP_INTERVAL_MS,
                ),
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }

    private fun servicePendingIntent(
        requestCode: Int,
        action: String,
        deltaMs: Long? = null,
    ): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        if (deltaMs != null) {
            intent.putExtra(EXTRA_DELTA_MS, deltaMs)
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
        )
    }

    private fun stopForegroundCompat(removeNotification: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(removeNotification)
        }
        if (removeNotification) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Audio playback",
            NotificationManager.IMPORTANCE_LOW,
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "audio_playback"
        private const val NOTIFICATION_ID = 11
        private const val ACTION_PREPARE = "cat.oriol.nowreadit.playback.PREPARE"
        private const val ACTION_PLAY = "cat.oriol.nowreadit.playback.PLAY"
        private const val ACTION_PAUSE = "cat.oriol.nowreadit.playback.PAUSE"
        private const val ACTION_SEEK = "cat.oriol.nowreadit.playback.SEEK"
        private const val ACTION_SKIP = "cat.oriol.nowreadit.playback.SKIP"
        private const val ACTION_RESTART = "cat.oriol.nowreadit.playback.RESTART"
        private const val ACTION_STOP = "cat.oriol.nowreadit.playback.STOP"
        private const val EXTRA_AUDIO_PATH = "audio_path"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_POSITION_MS = "position_ms"
        private const val EXTRA_DELTA_MS = "delta_ms"
        private const val SKIP_INTERVAL_MS = 15_000L
        private const val REQUEST_REWIND = 1
        private const val REQUEST_PAUSE = 2
        private const val REQUEST_FORWARD = 3
        private const val REQUEST_PLAY = 4

        private val _state = MutableStateFlow(PlaybackServiceState())
        val state: StateFlow<PlaybackServiceState> = _state.asStateFlow()

        fun prepare(context: Context, audioPath: String, title: String) {
            context.startService(
                Intent(context, PlaybackService::class.java)
                    .setAction(ACTION_PREPARE)
                    .putExtra(EXTRA_AUDIO_PATH, audioPath)
                    .putExtra(EXTRA_TITLE, title),
            )
        }

        fun play(context: Context, audioPath: String, title: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PlaybackService::class.java)
                    .setAction(ACTION_PLAY)
                    .putExtra(EXTRA_AUDIO_PATH, audioPath)
                    .putExtra(EXTRA_TITLE, title),
            )
        }

        fun pause(context: Context) {
            context.startService(Intent(context, PlaybackService::class.java).setAction(ACTION_PAUSE))
        }

        fun seekTo(context: Context, positionMs: Long) {
            context.startService(
                Intent(context, PlaybackService::class.java)
                    .setAction(ACTION_SEEK)
                    .putExtra(EXTRA_POSITION_MS, positionMs),
            )
        }

        fun skipBy(context: Context, deltaMs: Long) {
            context.startService(
                Intent(context, PlaybackService::class.java)
                    .setAction(ACTION_SKIP)
                    .putExtra(EXTRA_DELTA_MS, deltaMs),
            )
        }

        fun restart(context: Context) {
            context.startService(Intent(context, PlaybackService::class.java).setAction(ACTION_RESTART))
        }
    }
}
