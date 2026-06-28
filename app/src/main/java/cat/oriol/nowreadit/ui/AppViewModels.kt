package cat.oriol.nowreadit.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cat.oriol.nowreadit.data.LibraryRepository
import cat.oriol.nowreadit.data.SettingsStore
import cat.oriol.nowreadit.data.TtsSettings
import cat.oriol.nowreadit.data.local.LibraryItemEntity
import cat.oriol.nowreadit.playback.PlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    private val applicationContext = appContext.applicationContext
    val item = repository.observeItem(itemId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val playbackState: StateFlow<PlaybackUiState> = PlaybackService.state
        .map { state ->
            PlaybackUiState(
                isReady = state.isReady,
                isPlaying = state.isPlaying,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                error = state.error,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaybackUiState())

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
            val path = item.audioPath ?: return
            PlaybackService.prepare(applicationContext, path, item.title)
        }.onFailure { throwable ->
            _message.value = throwable.message ?: "Could not prepare audio."
        }
    }

    fun togglePlayback(item: LibraryItemEntity) {
        runCatching {
            val path = item.audioPath ?: return
            val state = PlaybackService.state.value
            if (state.audioPath == path && state.isPlaying) {
                PlaybackService.pause(applicationContext)
            } else {
                PlaybackService.play(applicationContext, path, item.title)
            }
        }.onFailure { throwable ->
            _message.value = throwable.message ?: "Could not play audio."
        }
    }

    fun seekTo(positionMs: Long, item: LibraryItemEntity? = null) {
        runCatching {
            item?.let { preparePlayback(it) }
            PlaybackService.seekTo(applicationContext, positionMs)
        }.onFailure { throwable ->
            _message.value = throwable.message ?: "Could not seek audio."
        }
    }

    fun skipBy(deltaMs: Long, item: LibraryItemEntity? = null) {
        runCatching {
            item?.let { preparePlayback(it) }
            PlaybackService.skipBy(applicationContext, deltaMs)
        }.onFailure { throwable ->
            _message.value = throwable.message ?: "Could not seek audio."
        }
    }

    fun restart(item: LibraryItemEntity? = null) {
        runCatching {
            item?.let { preparePlayback(it) }
            PlaybackService.restart(applicationContext)
        }.onFailure { throwable ->
            _message.value = throwable.message ?: "Could not reset audio."
        }
    }

    fun clearMessage() {
        _message.value = null
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
