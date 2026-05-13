package cat.oriol.nowreadit.data

import android.media.MediaMetadataRetriever

object AudioMetadataReader {
    fun readDurationMs(path: String): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            retriever.release()
        }
    }
}
