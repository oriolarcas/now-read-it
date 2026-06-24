package cat.oriol.nowreadit.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import cat.oriol.nowreadit.data.local.AudioStatus
import cat.oriol.nowreadit.data.local.ImportStatus
import cat.oriol.nowreadit.data.local.LibraryItemEntity
import cat.oriol.nowreadit.data.local.LibraryStore
import cat.oriol.nowreadit.data.local.currentTextAudioHash
import cat.oriol.nowreadit.data.local.textAudioHash
import cat.oriol.nowreadit.data.remote.OpenAiTtsClient
import cat.oriol.nowreadit.data.remote.PageContentExtractor
import cat.oriol.nowreadit.worker.TtsWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class LibraryRepository(
    private val appContext: Context,
    private val libraryStore: LibraryStore,
    private val pageContentExtractor: PageContentExtractor,
    private val settingsStore: SettingsStore,
    private val workManager: WorkManager,
    private val ttsClient: OpenAiTtsClient,
) {
    private val tag = "NowReadItTts"
    private val archiveTag = "NowReadItArchive"

    fun observeLibrary(): Flow<List<LibraryItemEntity>> = libraryStore.observeLibrary()

    fun observeItem(itemId: Long): Flow<LibraryItemEntity?> = libraryStore.observeItem(itemId)

    suspend fun exportLibrary(destination: Uri): Result<Int> = runCatching {
        withContext(Dispatchers.IO) {
            val items = libraryStore.currentItems()
            Log.i(archiveTag, "Starting library export items=${items.size} destination=$destination")
            val contentResolver = appContext.contentResolver
            contentResolver.openOutputStream(destination)?.use { output ->
                ZipOutputStream(output.buffered()).use { zip ->
                    val manifest = JSONArray()
                    var audioCount = 0

                    items.forEach { item ->
                        val audioFile = item.audioPath?.let(::File)?.takeIf { it.exists() }
                        val audioEntryName = audioFile?.let { "audio/item-${item.id}.mp3" }
                        manifest.put(item.toArchiveJson(audioEntryName))

                        if (audioFile != null && audioEntryName != null) {
                            zip.putNextEntry(ZipEntry(audioEntryName))
                            audioFile.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                            audioCount += 1
                        }
                    }

                    zip.putNextEntry(ZipEntry(ARCHIVE_LIBRARY_FILE))
                    zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    Log.i(archiveTag, "Finished library export items=${items.size} audioFiles=$audioCount")
                }
            } ?: error("Could not open export destination.")

            items.size
        }
    }

    suspend fun importLibrary(source: Uri): Result<Int> = runCatching {
        withContext(Dispatchers.IO) {
            val contentResolver = appContext.contentResolver
            val archiveItems = mutableListOf<ArchiveItem>()
            val audioBytes = mutableMapOf<String, ByteArray>()

            contentResolver.openInputStream(source)?.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    generateSequence { zip.nextEntry }.forEach { entry ->
                        val entryName = entry.name
                        when {
                            entry.isDirectory -> Unit
                            entryName == ARCHIVE_LIBRARY_FILE -> {
                                val rawJson = zip.readBytes().toString(Charsets.UTF_8)
                                val array = JSONArray(rawJson)
                                for (index in 0 until array.length()) {
                                    archiveItems += array.getJSONObject(index).toArchiveItem()
                                }
                            }
                            entryName.startsWith("audio/") && !entryName.contains("..") -> {
                                audioBytes[entryName] = zip.readBytes()
                            }
                        }
                        zip.closeEntry()
                    }
                }
            } ?: error("Could not open import source.")

            check(archiveItems.isNotEmpty()) { "The selected file does not contain a library archive." }
            Log.i(
                archiveTag,
                "Starting library import items=${archiveItems.size} audioEntries=${audioBytes.size} source=$source",
            )

            val audioDir = File(appContext.filesDir, "audio").apply { mkdirs() }
            var importedAudioCount = 0
            archiveItems.forEach { archiveItem ->
                val audioData = archiveItem.audioEntryName?.let(audioBytes::get)
                val insertedId = libraryStore.insert(
                    archiveItem.item.copy(
                        id = 0,
                        audioPath = null,
                        audioDurationMs = if (audioData != null) archiveItem.item.audioDurationMs else null,
                        audioStatus = if (audioData != null) AudioStatus.READY else AudioStatus.NOT_STARTED,
                        audioProgressPercent = null,
                        audioTextHash = if (audioData != null) {
                            archiveItem.item.audioTextHash ?: archiveItem.item.currentTextAudioHash()
                        } else {
                            null
                        },
                        audioGenerationTextHash = null,
                        lastError = null,
                    ),
                )

                if (audioData != null) {
                    val outputFile = File(audioDir, "item-$insertedId.mp3")
                    outputFile.writeBytes(audioData)
                    libraryStore.markAudioReady(
                        itemId = insertedId,
                        audioPath = outputFile.path,
                        durationMs = archiveItem.item.audioDurationMs ?: AudioMetadataReader.readDurationMs(outputFile.path),
                        audioTextHash = archiveItem.item.audioTextHash ?: archiveItem.item.currentTextAudioHash(),
                    )
                    importedAudioCount += 1
                }
            }

            Log.i(archiveTag, "Finished library import items=${archiveItems.size} audioFiles=$importedAudioCount")
            archiveItems.size
        }
    }

    suspend fun importFromUrl(url: String): Result<Long> = try {
        val extractedPage = pageContentExtractor.extract(url)
        val item = LibraryItemEntity(
            sourceUrl = extractedPage.url,
            title = extractedPage.title,
            siteName = extractedPage.siteName,
            importedAt = System.currentTimeMillis(),
            extractedText = textWithTitle(extractedPage.title, extractedPage.text),
            importStatus = ImportStatus.READY,
            audioStatus = AudioStatus.NOT_STARTED,
        )
        Result.success(libraryStore.insert(item))
    } catch (throwable: Throwable) {
        val failedItem = LibraryItemEntity(
            sourceUrl = url,
            title = url,
            siteName = null,
            importedAt = System.currentTimeMillis(),
            extractedText = "",
            importStatus = ImportStatus.FAILED,
            audioStatus = AudioStatus.NOT_STARTED,
            lastError = throwable.message ?: "Import failed",
        )
        libraryStore.insert(failedItem)
        Result.failure(throwable)
    }

    suspend fun updateItemText(itemId: Long, updatedText: String) {
        libraryStore.updateText(
            itemId = itemId,
            text = updatedText,
            editedAt = System.currentTimeMillis(),
        )
    }

    suspend fun enqueueAudioGeneration(itemId: Long) {
        val settings = settingsStore.load()
        check(settings.apiKey.isNotBlank()) { "Configure your OpenAI API key in Settings first." }
        val item = libraryStore.getItem(itemId) ?: error("Item not found")
        val generationTextHash = item.currentTextAudioHash()

        Log.i(tag, "Queueing audio generation for itemId=$itemId model=${settings.model} voice=${settings.voice}")

        libraryStore.updateAudioStatus(
            itemId = itemId,
            status = AudioStatus.QUEUED,
            lastError = null,
            progressPercent = 0,
            generationTextHash = generationTextHash,
        )

        val request = OneTimeWorkRequestBuilder<TtsWorker>()
            .setInputData(workDataOf(TtsWorker.KEY_ITEM_ID to itemId))
            .build()

        workManager.enqueueUniqueWork(
            TtsWorker.uniqueWorkName(itemId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    suspend fun deleteItem(itemId: Long) {
        val existing = libraryStore.getItem(itemId) ?: return
        existing.audioPath?.let { path -> File(path).delete() }
        libraryStore.deleteById(itemId)
        workManager.cancelUniqueWork(TtsWorker.uniqueWorkName(itemId))
    }

    suspend fun runTtsGeneration(itemId: Long, onProgress: suspend (Int) -> Unit) {
        val item = libraryStore.getItem(itemId) ?: error("Item not found")
        val settings = settingsStore.load()
        check(settings.apiKey.isNotBlank()) { "OpenAI API key is missing." }
        check(item.extractedText.isNotBlank()) { "There is no text to synthesize." }
        val textSnapshot = item.extractedText
        val generationTextHash = textAudioHash(textSnapshot)

        Log.i(
            tag,
            "Starting audio generation for itemId=$itemId title=${item.title.take(80)} textLength=${textSnapshot.length}",
        )

        libraryStore.updateAudioStatus(
            itemId = itemId,
            status = AudioStatus.GENERATING,
            lastError = null,
            progressPercent = 0,
            generationTextHash = generationTextHash,
        )

        val audioDir = File(appContext.filesDir, "audio").apply { mkdirs() }
        val outputFile = File(audioDir, "item-$itemId.mp3")
        val temporaryOutputFile = File(audioDir, "item-$itemId-generating.mp3")

        try {
            temporaryOutputFile.delete()
            ttsClient.synthesizeToFile(
                settings = settings,
                text = textSnapshot,
                outputFile = temporaryOutputFile,
                onProgress = { progress ->
                    libraryStore.updateAudioProgress(itemId, progress)
                    onProgress(progress)
                },
            )

            outputFile.delete()
            if (!temporaryOutputFile.renameTo(outputFile)) {
                temporaryOutputFile.copyTo(outputFile, overwrite = true)
                temporaryOutputFile.delete()
            }
            val duration = AudioMetadataReader.readDurationMs(outputFile.path)
            Log.i(
                tag,
                "Audio generation finished for itemId=$itemId output=${outputFile.path} durationMs=${duration ?: -1}",
            )
            libraryStore.markAudioReady(
                itemId = itemId,
                audioPath = outputFile.path,
                durationMs = duration,
                audioTextHash = generationTextHash,
            )
        } catch (throwable: Throwable) {
            temporaryOutputFile.delete()
            Log.e(tag, "Audio generation failed for itemId=$itemId", throwable)
            val currentItem = libraryStore.getItem(itemId)
            libraryStore.updateAudioStatus(
                itemId = itemId,
                status = if (currentItem?.audioPath != null) AudioStatus.READY else AudioStatus.FAILED,
                lastError = throwable.message ?: "Audio generation failed",
                progressPercent = null,
            )
            throw throwable
        }
    }

    private fun LibraryItemEntity.toArchiveJson(audioEntryName: String?): JSONObject = JSONObject()
        .put("sourceUrl", sourceUrl)
        .put("title", title)
        .put("siteName", siteName)
        .put("importedAt", importedAt)
        .put("extractedText", extractedText)
        .put("textEditedAt", textEditedAt)
        .put("importStatus", importStatus.name)
        .put("audioStatus", if (audioEntryName != null) AudioStatus.READY.name else AudioStatus.NOT_STARTED.name)
        .put("audioProgressPercent", null)
        .put("audioTextHash", if (audioEntryName != null) audioTextHash ?: currentTextAudioHash() else null)
        .put("audioDurationMs", audioDurationMs)
        .put("audioEntryName", audioEntryName)

    private fun JSONObject.toArchiveItem(): ArchiveItem {
        val audioEntryName = optString("audioEntryName").takeIf { it.isNotBlank() }
        return ArchiveItem(
            item = LibraryItemEntity(
                sourceUrl = getString("sourceUrl"),
                title = getString("title"),
                siteName = optString("siteName").takeIf { it.isNotBlank() },
                importedAt = getLong("importedAt"),
                extractedText = getString("extractedText"),
                textEditedAt = optLong("textEditedAt").takeIf { has("textEditedAt") && !isNull("textEditedAt") },
                importStatus = ImportStatus.valueOf(optString("importStatus", ImportStatus.READY.name)),
                audioStatus = if (audioEntryName != null) AudioStatus.READY else AudioStatus.NOT_STARTED,
                audioTextHash = optString("audioTextHash").takeIf { it.isNotBlank() },
                audioDurationMs = optLong("audioDurationMs").takeIf { has("audioDurationMs") && !isNull("audioDurationMs") },
            ),
            audioEntryName = audioEntryName,
        )
    }

    private data class ArchiveItem(
        val item: LibraryItemEntity,
        val audioEntryName: String?,
    )

    companion object {
        private const val ARCHIVE_LIBRARY_FILE = "library-items.json"
    }
}
