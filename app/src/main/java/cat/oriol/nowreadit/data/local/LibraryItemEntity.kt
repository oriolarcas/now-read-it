package cat.oriol.nowreadit.data.local

enum class ImportStatus {
    READY,
    FAILED,
}

enum class AudioStatus {
    NOT_STARTED,
    QUEUED,
    GENERATING,
    READY,
    FAILED,
}

data class LibraryItemEntity(
    val id: Long = 0,
    val sourceUrl: String,
    val title: String,
    val siteName: String?,
    val importedAt: Long,
    val extractedText: String,
    val textEditedAt: Long? = null,
    val importStatus: ImportStatus,
    val audioStatus: AudioStatus,
    val audioProgressPercent: Int? = null,
    val audioTextHash: String? = null,
    val audioGenerationTextHash: String? = null,
    val audioPath: String? = null,
    val audioDurationMs: Long? = null,
    val audioChunks: List<AudioChunkMetadata> = emptyList(),
    val lastError: String? = null,
)

data class AudioChunkMetadata(
    val index: Int,
    val textStartOffset: Int,
    val textEndOffset: Int,
    val audioStartMs: Long,
    val durationMs: Long?,
)
