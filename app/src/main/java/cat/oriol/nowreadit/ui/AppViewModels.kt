package cat.oriol.nowreadit.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cat.oriol.nowreadit.data.LibraryRepository
import cat.oriol.nowreadit.data.SettingsStore
import cat.oriol.nowreadit.data.TtsSettings
import cat.oriol.nowreadit.data.local.LibraryItemEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val repository: LibraryRepository,
) : ViewModel() {
    val items = repository.observeLibrary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    fun importUrl(url: String, onComplete: (Long?) -> Unit = {}) {
        if (url.isBlank()) return
        viewModelScope.launch {
            _isImporting.value = true
            val result = repository.importFromUrl(url.trim())
            _isImporting.value = false
            result.fold(
                onSuccess = { itemId ->
                    _message.value = "Saved to library."
                    onComplete(itemId)
                },
                onFailure = { throwable ->
                    _message.value = throwable.message ?: "Import failed."
                    onComplete(null)
                },
            )
        }
    }

    fun deleteItem(itemId: Long) {
        viewModelScope.launch {
            repository.deleteItem(itemId)
            _message.value = "Item deleted."
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}

data class PlaybackUiState(
    val isReady: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null,
)

class ItemDetailViewModel(
    itemId: Long,
    appContext: Context,
    private val repository: LibraryRepository,
) : ViewModel() {
    val item = repository.observeItem(itemId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _playbackState = MutableStateFlow(PlaybackUiState())
    val playbackState: StateFlow<PlaybackUiState> = _playbackState.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var currentAudioPath: String? = null
    private var playbackPositionJob: Job? = null
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            -> pauseForFocusLoss()
        }
    }

    fun saveText(itemId: Long, text: String) {
        viewModelScope.launch {
            repository.updateItemText(itemId, text)
            _message.value = "Text updated."
        }
    }

    fun generateAudio(itemId: Long) {
        viewModelScope.launch {
            runCatching { repository.enqueueAudioGeneration(itemId) }
                .onSuccess { _message.value = "Audio generation queued." }
                .onFailure { throwable -> _message.value = throwable.message ?: "Could not queue audio generation." }
        }
    }

    fun preparePlayback(item: LibraryItemEntity) {
        runCatching {
            ensurePlayer(item)
        }.onFailure { throwable ->
            reportPlaybackError(throwable.message ?: "Could not prepare audio.")
        }
    }

    fun togglePlayback(item: LibraryItemEntity) {
        runCatching {
            if (!ensurePlayer(item)) return
            val player = mediaPlayer ?: return
            if (player.isPlaying) {
                player.pause()
                abandonAudioFocus()
                _playbackState.value = _playbackState.value.copy(
                    isPlaying = false,
                    positionMs = player.currentPosition.toLong(),
                    durationMs = player.duration.toLong(),
                    error = null,
                )
                stopPositionPolling()
            } else {
                if (player.currentPosition >= player.duration) {
                    player.seekTo(0)
                }
                if (!requestAudioFocus()) {
                    _message.value = "Could not start audio playback."
                    return
                }
                player.start()
                _playbackState.value = _playbackState.value.copy(
                    isReady = true,
                    isPlaying = true,
                    positionMs = player.currentPosition.toLong(),
                    durationMs = player.duration.toLong(),
                    error = null,
                )
                startPositionPolling()
            }
        }.onFailure { throwable ->
            reportPlaybackError(throwable.message ?: "Could not play audio.")
        }
    }

    fun seekTo(positionMs: Long, item: LibraryItemEntity? = null) {
        if (mediaPlayer == null && item != null) {
            preparePlayback(item)
        }
        val player = mediaPlayer ?: return
        runCatching {
            val clampedPosition = positionMs.coerceIn(0L, player.duration.toLong())
            player.seekTo(clampedPosition.toInt())
            _playbackState.value = _playbackState.value.copy(
                positionMs = clampedPosition,
                durationMs = player.duration.toLong(),
                error = null,
            )
        }.onFailure { throwable ->
            reportPlaybackError(throwable.message ?: "Could not seek audio.")
        }
    }

    fun skipBy(deltaMs: Long, item: LibraryItemEntity? = null) {
        if (mediaPlayer == null && item != null) {
            preparePlayback(item)
        }
        val player = mediaPlayer ?: return
        seekTo(player.currentPosition.toLong() + deltaMs)
    }

    fun restart(item: LibraryItemEntity? = null) {
        if (mediaPlayer == null && item != null) {
            preparePlayback(item)
        }
        val player = mediaPlayer ?: return
        runCatching {
            stopPositionPolling()
            if (player.isPlaying) {
                player.pause()
            }
            abandonAudioFocus()
            player.seekTo(0)
            _playbackState.value = PlaybackUiState(
                isReady = true,
                isPlaying = false,
                positionMs = 0L,
                durationMs = player.duration.toLong(),
            )
        }.onFailure { throwable ->
            reportPlaybackError(throwable.message ?: "Could not reset audio.")
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun ensurePlayer(item: LibraryItemEntity): Boolean {
        val path = item.audioPath ?: return false
        if (mediaPlayer != null && currentAudioPath == path) return true

        releasePlayback()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(path)
            prepare()
            setOnCompletionListener { completedPlayer ->
                stopPositionPolling()
                abandonAudioFocus()
                _playbackState.value = _playbackState.value.copy(
                    isReady = true,
                    isPlaying = false,
                    positionMs = completedPlayer.duration.toLong(),
                    durationMs = completedPlayer.duration.toLong(),
                    error = null,
                )
            }
        }
        currentAudioPath = path
        val player = mediaPlayer ?: return false
        _playbackState.value = PlaybackUiState(
            isReady = true,
            isPlaying = false,
            positionMs = 0L,
            durationMs = player.duration.toLong(),
        )
        return true
    }

    private fun startPositionPolling() {
        playbackPositionJob?.cancel()
        playbackPositionJob = viewModelScope.launch {
            while (isActive) {
                updatePlaybackPosition()
                delay(500)
            }
        }
    }

    private fun stopPositionPolling() {
        playbackPositionJob?.cancel()
        playbackPositionJob = null
    }

    private fun updatePlaybackPosition() {
        val player = mediaPlayer ?: return
        runCatching {
            _playbackState.value = _playbackState.value.copy(
                isReady = true,
                isPlaying = player.isPlaying,
                positionMs = player.currentPosition.toLong(),
                durationMs = player.duration.toLong(),
                error = null,
            )
        }
    }

    private fun reportPlaybackError(message: String) {
        _message.value = message
        _playbackState.value = _playbackState.value.copy(error = message)
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

    private fun pauseForFocusLoss() {
        val player = mediaPlayer ?: return
        if (!player.isPlaying) return
        player.pause()
        stopPositionPolling()
        hasAudioFocus = false
        _playbackState.value = _playbackState.value.copy(
            isPlaying = false,
            positionMs = player.currentPosition.toLong(),
            durationMs = player.duration.toLong(),
            error = null,
        )
    }

    private fun releasePlayback() {
        stopPositionPolling()
        abandonAudioFocus()
        mediaPlayer?.release()
        mediaPlayer = null
        currentAudioPath = null
        _playbackState.value = PlaybackUiState()
    }

    override fun onCleared() {
        releasePlayback()
    }
}

class SettingsViewModel(
    private val settingsStore: SettingsStore,
    private val repository: LibraryRepository,
) : ViewModel() {
    private val _settings = MutableStateFlow(TtsSettings())
    val settings: StateFlow<TtsSettings> = _settings.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            _settings.value = settingsStore.load()
        }
    }

    fun updateSettings(settings: TtsSettings) {
        _settings.value = settings
    }

    fun save() {
        viewModelScope.launch {
            settingsStore.save(_settings.value)
            _message.value = "Settings saved."
        }
    }

    fun exportLibrary(destination: Uri) {
        viewModelScope.launch {
            repository.exportLibrary(destination).fold(
                onSuccess = { count -> _message.value = "Exported $count library items." },
                onFailure = { throwable -> _message.value = throwable.message ?: "Export failed." },
            )
        }
    }

    fun importLibrary(source: Uri) {
        viewModelScope.launch {
            repository.importLibrary(source).fold(
                onSuccess = { count -> _message.value = "Imported $count library items." },
                onFailure = { throwable -> _message.value = throwable.message ?: "Import failed." },
            )
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}

class LibraryViewModelFactory(
    private val repository: LibraryRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
            "Unsupported ViewModel type: ${modelClass.name}"
        }
        return requireNotNull(modelClass.cast(LibraryViewModel(repository))) {
            "Failed to create LibraryViewModel"
        }
    }
}

class ItemDetailViewModelFactory(
    private val itemId: Long,
    private val appContext: Context,
    private val repository: LibraryRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ItemDetailViewModel::class.java)) {
            "Unsupported ViewModel type: ${modelClass.name}"
        }
        return requireNotNull(
            modelClass.cast(
                ItemDetailViewModel(
                    itemId = itemId,
                    appContext = appContext,
                    repository = repository,
                ),
            ),
        ) {
            "Failed to create ItemDetailViewModel"
        }
    }
}

class SettingsViewModelFactory(
    private val settingsStore: SettingsStore,
    private val repository: LibraryRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            "Unsupported ViewModel type: ${modelClass.name}"
        }
        return requireNotNull(modelClass.cast(SettingsViewModel(settingsStore, repository))) {
            "Failed to create SettingsViewModel"
        }
    }
}
