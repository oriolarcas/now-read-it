package cat.oriol.nowreadit.data.local

import java.security.MessageDigest

private val whitespacePattern = Regex("\\s+")

fun normalizedTextForAudio(text: String): String = text.trim().replace(whitespacePattern, " ")

fun textAudioHash(text: String): String {
    val normalized = normalizedTextForAudio(text)
    val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

fun LibraryItemEntity.currentTextAudioHash(): String = textAudioHash(extractedText)

fun LibraryItemEntity.hasCurrentAudio(): Boolean =
    audioStatus == AudioStatus.READY &&
        audioPath != null &&
        audioTextHash == currentTextAudioHash()

fun LibraryItemEntity.hasGenerationForCurrentText(): Boolean =
    (audioStatus == AudioStatus.QUEUED || audioStatus == AudioStatus.GENERATING) &&
        audioGenerationTextHash == currentTextAudioHash()

fun LibraryItemEntity.needsAudioForCurrentText(): Boolean =
    extractedText.isNotBlank() && !hasCurrentAudio() && !hasGenerationForCurrentText()
