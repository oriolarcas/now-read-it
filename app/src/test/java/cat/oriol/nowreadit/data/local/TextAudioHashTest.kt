package cat.oriol.nowreadit.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextAudioHashTest {
    @Test
    fun whitespaceOnlyChangesKeepAudioCurrent() {
        val item = item(
            text = "One two three",
            audioStatus = AudioStatus.READY,
            audioPath = "/tmp/item.mp3",
            audioTextHash = textAudioHash("  One\n\ttwo   three  "),
        )

        assertTrue(item.hasCurrentAudio())
        assertFalse(item.needsAudioForCurrentText())
    }

    @Test
    fun realTextChangeRequiresAudio() {
        val item = item(
            text = "One two four",
            audioStatus = AudioStatus.READY,
            audioPath = "/tmp/item.mp3",
            audioTextHash = textAudioHash("One two three"),
        )

        assertFalse(item.hasCurrentAudio())
        assertTrue(item.needsAudioForCurrentText())
    }

    @Test
    fun queuedCurrentTextDoesNotRequireAnotherGeneration() {
        val item = item(
            text = "One two three",
            audioStatus = AudioStatus.QUEUED,
            audioGenerationTextHash = textAudioHash("One two three"),
        )

        assertTrue(item.hasGenerationForCurrentText())
        assertFalse(item.needsAudioForCurrentText())
    }

    private fun item(
        text: String,
        audioStatus: AudioStatus,
        audioPath: String? = null,
        audioTextHash: String? = null,
        audioGenerationTextHash: String? = null,
    ) = LibraryItemEntity(
        sourceUrl = "https://example.com",
        title = "Example",
        siteName = null,
        importedAt = 0L,
        extractedText = text,
        importStatus = ImportStatus.READY,
        audioStatus = audioStatus,
        audioTextHash = audioTextHash,
        audioGenerationTextHash = audioGenerationTextHash,
        audioPath = audioPath,
    )
}
