package cat.oriol.nowreadit.data

object TextChunker {
    fun chunk(text: String, maxChars: Int = 3500, minChars: Int = 0): List<String> {
        return chunksWithPositions(text, maxChars, minChars).map { it.text }
    }

    fun chunksWithPositions(text: String, maxChars: Int = 3500, minChars: Int = 0): List<TextChunk> {
        require(maxChars > 0)
        require(minChars >= 0)
        require(minChars <= maxChars)
        val trimmedText = text.trim()
        val normalized = trimmedText.replace("\r\n", "\n")
        val sourceTextStartOffset = text.indexOf(trimmedText).takeIf { it >= 0 } ?: 0
        val chunks = mutableListOf<TextChunk>()
        val paragraphs = normalized.split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        var searchStart = 0
        fun positionedChunk(chunk: String): TextChunk {
            val start = normalized.indexOf(chunk, startIndex = searchStart).takeIf { it >= 0 }
                ?: normalized.indexOf(chunk).takeIf { it >= 0 }
                ?: searchStart
            val end = (start + chunk.length).coerceAtMost(normalized.length)
            searchStart = end
            return TextChunk(
                text = chunk,
                textStartOffset = sourceTextStartOffset + start,
                textEndOffset = sourceTextStartOffset + end,
            )
        }

        var pendingChunk: String? = null
        fun flushPending() {
            pendingChunk?.let { chunk ->
                chunks += positionedChunk(chunk)
                pendingChunk = null
            }
        }

        paragraphs.forEach { paragraph ->
            if (paragraph.length > maxChars) {
                flushPending()
                splitParagraphBySentences(paragraph, maxChars).forEach { chunk ->
                    chunks += positionedChunk(chunk)
                }
            } else {
                val current = pendingChunk
                pendingChunk = when {
                    current == null -> paragraph
                    current.length < minChars && current.length + paragraph.length + 2 <= maxChars -> "$current\n\n$paragraph"
                    else -> {
                        chunks += positionedChunk(current)
                        paragraph
                    }
                }
            }
        }
        flushPending()
        return chunks
    }

    private fun splitParagraphBySentences(paragraph: String, maxChars: Int): List<String> {
        val sentenceRegex = Regex("(?<=[.!?])\\s+")
        val sentences = paragraph.split(sentenceRegex).filter { it.isNotBlank() }
        if (sentences.size <= 1) return paragraph.chunked(maxChars)

        val result = mutableListOf<String>()
        var current = StringBuilder()

        sentences.forEach { sentence ->
            val candidate = if (current.isEmpty()) sentence else "${current.toString()} $sentence"
            if (candidate.length > maxChars && current.isNotEmpty()) {
                result += current.toString().trim()
                current = StringBuilder(sentence)
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(sentence)
            }
        }

        if (current.isNotBlank()) result += current.toString().trim()
        return result.ifEmpty { paragraph.chunked(maxChars) }
    }

    private fun StringBuilder.isNotBlank(): Boolean = this.toString().isNotBlank()
}

data class TextChunk(
    val text: String,
    val textStartOffset: Int,
    val textEndOffset: Int,
)
