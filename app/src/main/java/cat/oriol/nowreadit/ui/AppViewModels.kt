package cat.oriol.nowreadit.ui

import android.media.MediaPlayer
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cat.oriol.nowreadit.data.LibraryRepository
import cat.oriol.nowreadit.data.SettingsStore
import cat.oriol.nowreadit.data.TtsSettings
import cat.oriol.nowreadit.data.local.LibraryItemEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

class ItemDetailViewModel(
    itemId: Long,
    private val repository: LibraryRepository,
) : ViewModel() {
    val item = repository.observeItem(itemId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null

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

    fun playAudio(item: LibraryItemEntity) {
        val path = item.audioPath ?: return
        runCatching {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(path)
                prepare()
                start()
            }
        }.onFailure { throwable ->
            _message.value = throwable.message ?: "Could not play audio."
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    override fun onCleared() {
        mediaPlayer?.release()
        mediaPlayer = null
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
    override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryViewModel(repository) as T
}

class ItemDetailViewModelFactory(
    private val itemId: Long,
    private val repository: LibraryRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ItemDetailViewModel(itemId, repository) as T
}

class SettingsViewModelFactory(
    private val settingsStore: SettingsStore,
    private val repository: LibraryRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(settingsStore, repository) as T
}
