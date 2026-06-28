package cat.oriol.nowreadit.data.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LibraryStore(context: Context) {
    private val file = File(context.filesDir, "library-items.json")
    private val mutex = Mutex()
    private val itemsState = MutableStateFlow<List<LibraryItemEntity>>(emptyList())

    init {
        runCatching { loadFromDisk() }
    }

    fun observeLibrary(): Flow<List<LibraryItemEntity>> = itemsState

    fun observeItem(itemId: Long): Flow<LibraryItemEntity?> = itemsState.map { items ->
        items.firstOrNull { it.id == itemId }
    }

    fun currentItems(): List<LibraryItemEntity> = itemsState.value

    suspend fun getItem(itemId: Long): LibraryItemEntity? = itemsState.value.firstOrNull { it.id == itemId }

    suspend fun insert(item: LibraryItemEntity): Long = mutex.withLock {
        val nextId = (itemsState.value.maxOfOrNull { it.id } ?: 0L) + 1L
        val storedItem = item.copy(id = nextId)
        persist(itemsState.value + storedItem)
        nextId
    }

    suspend fun updateText(itemId: Long, text: String, editedAt: Long) = mutex.withLock {
        persist(
            itemsState.value.map { item ->
                if (item.id == itemId) item.copy(extractedText = text, textEditedAt = editedAt) else item
            },
        )
    }

    suspend fun updateAudioStatus(
        itemId: Long,
        status: AudioStatus,
        lastError: String?,
        progressPercent: Int? = null,
        generationTextHash: String? = null,
    ) = mutex.withLock {
        persist(
            itemsState.value.map { item ->
                if (item.id == itemId) {
                    item.copy(
                        audioStatus = status,
                        audioProgressPercent = progressPercent,
                        audioGenerationTextHash = generationTextHash,
                        lastError = lastError,
                    )
                } else {
                    item
                }
            },
        )
    }

    suspend fun updateAudioProgress(itemId: Long, progressPercent: Int) = mutex.withLock {
        persist(
            itemsState.value.map { item ->
                if (item.id == itemId) {
                    item.copy(audioProgressPercent = progressPercent.coerceIn(0, 100))
                } else {
                    item
                }
            },
        )
    }

    suspend fun markAudioReady(
        itemId: Long,
        audioPath: String,
        durationMs: Long?,
        audioTextHash: String,
        audioChunks: List<AudioChunkMetadata>,
    ) = mutex.withLock {
        persist(
            itemsState.value.map { item ->
                if (item.id == itemId) {
                    item.copy(
                        audioStatus = AudioStatus.READY,
                        audioProgressPercent = null,
                        audioTextHash = audioTextHash,
                        audioGenerationTextHash = null,
                        audioPath = audioPath,
                        audioDurationMs = durationMs,
                        audioChunks = audioChunks,
                        lastError = null,
                    )
                } else {
                    item
                }
            },
        )
    }

    suspend fun deleteById(itemId: Long) = mutex.withLock {
        persist(itemsState.value.filterNot { it.id == itemId })
    }

    private fun loadFromDisk() {
        if (!file.exists()) return
        val raw = file.readText()
        if (raw.isBlank()) return
        val array = JSONArray(raw)
        val loadedItems = buildList {
            for (index in 0 until array.length()) {
                add(array.getJSONObject(index).toEntity())
            }
        }
        val migratedItems = loadedItems.map { item ->
            if (item.audioStatus == AudioStatus.READY && item.audioPath != null && item.audioTextHash == null) {
                item.copy(audioTextHash = item.currentTextAudioHash())
            } else {
                item
            }
        }.sortedByDescending { it.importedAt }
        itemsState.value = migratedItems
        if (migratedItems != loadedItems) {
            val migratedJson = JSONArray().apply {
                migratedItems.forEach { put(it.toJson()) }
            }
            file.writeText(migratedJson.toString())
        }
    }

    private suspend fun persist(items: List<LibraryItemEntity>) = withContext(Dispatchers.IO) {
        itemsState.value = items.sortedByDescending { it.importedAt }
        val json = JSONArray().apply {
            itemsState.value.forEach { put(it.toJson()) }
        }
        file.writeText(json.toString())
    }

    private fun LibraryItemEntity.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("sourceUrl", sourceUrl)
        .put("title", title)
        .put("siteName", siteName)
        .put("importedAt", importedAt)
        .put("extractedText", extractedText)
        .put("textEditedAt", textEditedAt)
        .put("importStatus", importStatus.name)
        .put("audioStatus", audioStatus.name)
        .put("audioProgressPercent", audioProgressPercent)
        .put("audioTextHash", audioTextHash)
        .put("audioGenerationTextHash", audioGenerationTextHash)
        .put("audioPath", audioPath)
        .put("audioDurationMs", audioDurationMs)
        .put(
            "audioChunks",
            JSONArray().apply {
                audioChunks.forEach { put(it.toJson()) }
            },
        )
        .put("lastError", lastError)

    private fun JSONObject.toEntity(): LibraryItemEntity = LibraryItemEntity(
        id = getLong("id"),
        sourceUrl = getString("sourceUrl"),
        title = getString("title"),
        siteName = optString("siteName").takeIf { it.isNotBlank() },
        importedAt = getLong("importedAt"),
        extractedText = getString("extractedText"),
        textEditedAt = optLong("textEditedAt").takeIf { has("textEditedAt") && !isNull("textEditedAt") },
        importStatus = ImportStatus.valueOf(getString("importStatus")),
        audioStatus = AudioStatus.valueOf(getString("audioStatus")),
        audioProgressPercent = optInt("audioProgressPercent").takeIf {
            has("audioProgressPercent") && !isNull("audioProgressPercent")
        },
        audioTextHash = optString("audioTextHash").takeIf { it.isNotBlank() },
        audioGenerationTextHash = optString("audioGenerationTextHash").takeIf { it.isNotBlank() },
        audioPath = optString("audioPath").takeIf { it.isNotBlank() },
        audioDurationMs = optLong("audioDurationMs").takeIf { has("audioDurationMs") && !isNull("audioDurationMs") },
        audioChunks = optJSONArray("audioChunks")?.toAudioChunks().orEmpty(),
        lastError = optString("lastError").takeIf { it.isNotBlank() },
    )

    private fun AudioChunkMetadata.toJson(): JSONObject = JSONObject()
        .put("index", index)
        .put("textStartOffset", textStartOffset)
        .put("textEndOffset", textEndOffset)
        .put("audioStartMs", audioStartMs)
        .put("durationMs", durationMs)

    private fun JSONArray.toAudioChunks(): List<AudioChunkMetadata> = buildList {
        for (index in 0 until length()) {
            val json = getJSONObject(index)
            add(
                AudioChunkMetadata(
                    index = json.getInt("index"),
                    textStartOffset = json.getInt("textStartOffset"),
                    textEndOffset = json.getInt("textEndOffset"),
                    audioStartMs = json.getLong("audioStartMs"),
                    durationMs = json.optLong("durationMs").takeIf {
                        json.has("durationMs") && !json.isNull("durationMs")
                    },
                ),
            )
        }
    }
}
