package cat.oriol.nowreadit.data

object TextChunker {
    fun chunk(text: String, maxChars: Int = 3500): List<String> {
        require(maxChars > 100)
        val normalized = text.trim().replace("\r\n", "\n")
        if (normalized.length <= maxChars) return listOf(normalized)

        val chunks = mutableListOf<String>()
        val paragraphs = normalized.split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        var current = StringBuilder()
        fun flush() {
            if (current.isNotBlank()) {
                chunks += current.toString().trim()
                current = StringBuilder()
            }
        }

        paragraphs.forEach { paragraph ->
            if (paragraph.length > maxChars) {
                flush()
                paragraph.chunked(maxChars).forEach { chunks += it }
                return@forEach
            }

            val proposedLength = current.length + paragraph.length + 2
            if (proposedLength > maxChars) flush()
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(paragraph)
        }
        flush()
        return chunks
    }

    private fun StringBuilder.isNotBlank(): Boolean = this.toString().isNotBlank()
}
