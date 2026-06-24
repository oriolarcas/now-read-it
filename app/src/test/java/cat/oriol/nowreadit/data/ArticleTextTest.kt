package cat.oriol.nowreadit.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ArticleTextTest {
    @Test
    fun prependsTitleBeforeBody() {
        val text = textWithTitle("A useful title", "First paragraph.\n\nSecond paragraph.")

        assertEquals("A useful title\n\nFirst paragraph.\n\nSecond paragraph.", text)
    }

    @Test
    fun doesNotDuplicateTitleWhenBodyAlreadyStartsWithIt() {
        val text = textWithTitle("A useful title", "A useful title\n\nFirst paragraph.")

        assertEquals("A useful title\n\nFirst paragraph.", text)
    }
}
