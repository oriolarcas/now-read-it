package cat.oriol.nowreadit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {
    @Test
    fun singleChunkWhenTextIsShort() {
        val text = "Short article text."

        val chunks = TextChunker.chunk(text, maxChars = 200)

        assertEquals(listOf(text), chunks)
    }

    @Test
    fun paragraphBoundariesArePreservedWhenPossible() {
        val text = buildString {
            append("Paragraph one ".repeat(20))
            append("\n\n")
            append("Paragraph two ".repeat(20))
        }

        val chunks = TextChunker.chunk(text, maxChars = 400)

        assertEquals(2, chunks.size)
        assertTrue(chunks.all { it.length <= 400 })
    }

    @Test
    fun oversizedParagraphIsSplit() {
        val text = "A".repeat(500)

        val chunks = TextChunker.chunk(text, maxChars = 120)

        assertEquals(5, chunks.size)
        assertTrue(chunks.all { it.length <= 120 })
    }
}
