package cat.oriol.nowreadit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {
    @Test
    fun singleParagraphBecomesSingleChunkWhenTextIsShort() {
        val text = "Short article text."

        val chunks = TextChunker.chunk(text, maxChars = 200)

        assertEquals(listOf(text), chunks)
    }

    @Test
    fun paragraphsAreSeparateChunksEvenWhenCombinedTextFitsLimit() {
        val text = buildString {
            append("Paragraph one.")
            append("\n\n")
            append("Paragraph two.")
        }

        val chunks = TextChunker.chunk(text, maxChars = 400)

        assertEquals(listOf("Paragraph one.", "Paragraph two."), chunks)
    }

    @Test
    fun smallParagraphsAreFusedUntilMinCharsIsReached() {
        val text = "A.\n\nB.\n\nThis paragraph is long enough."

        val chunks = TextChunker.chunk(text, maxChars = 100, minChars = 10)

        assertEquals(listOf("A.\n\nB.\n\nThis paragraph is long enough."), chunks)
    }

    @Test
    fun smallParagraphsAreNotFusedPastMaxChars() {
        val text = "Tiny.\n\n${"B".repeat(20)}"

        val chunks = TextChunker.chunk(text, maxChars = 24, minChars = 10)

        assertEquals(listOf("Tiny.", "B".repeat(20)), chunks)
    }

    @Test
    fun realisticDialogueFusesOnlySmallParagraphRuns() {
        val text = """
            The Dover mail was in its usual genial position that the guard suspected the passengers, the passengers suspected one another and the guard, they all suspected everybody else, and the coachman was sure of nothing but the horses; as to which cattle he could with a clear conscience have taken his oath on the two Testaments that they were not fit for the journey.

            "Wo-ho!" said the coachman. "So, then! One more pull and you're at the top and be damned to you, for I have had trouble enough to get you to it!—Joe!"

            "Halloa!" the guard replied.

            "What o'clock do you make it, Joe?"

            "Ten minutes, good, past eleven."

            "My blood!" ejaculated the vexed coachman, "and not atop of Shooter's yet! Tst! Yah! Get on with you!"

            The emphatic horse, cut short by the whip in a most decided negative, made a decided scramble for it, and the three other horses followed suit. Once more, the Dover mail struggled on, with the jack-boots of its passengers squashing along by its side. They had stopped when the coach stopped, and they kept close company with it. If any one of the three had had the hardihood to propose to another to walk on a little ahead into the mist and darkness, he would have put himself in a fair way of getting shot instantly as a highwayman.

            The last burst carried the mail to the summit of the hill. The horses stopped to breathe again, and the guard got down to skid the wheel for the descent, and open the coach-door to let the passengers in.

            "Tst! Joe!" cried the coachman in a warning voice, looking down from his box.

            "What do you say, Tom?"

            They both listened.

            "I say a horse at a canter coming up, Joe."

            "I say a horse at a gallop, Tom," returned the guard, leaving his hold of the door, and mounting nimbly to his place. "Gentlemen! In the king's name, all of you!"

            With this hurried adjuration, he cocked his blunderbuss, and stood on the offensive.

            The passenger booked by this history, was on the coach-step, getting in; the two other passengers were close behind him, and about to follow. He remained on the step, half in the coach and half out of it; they remained in the road below him. They all looked from the coachman to the guard, and from the guard to the coachman, and listened. The coachman looked back and the guard looked back, and even the emphatic leader pricked up his ears and looked back, without contradicting.

            The stillness consequent on the cessation of the rumbling and labouring of the coach, added to the stillness of the night, made it very quiet indeed. The panting of the horses communicated a tremulous motion to the coach, as if it were in a state of agitation. The hearts of the passengers beat loud enough perhaps to be heard; but at any rate, the quiet pause was audibly expressive of people out of breath, and holding the breath, and having the pulses quickened by expectation.
        """.trimIndent()

        val chunks = TextChunker.chunk(text, minChars = 200)

        assertEquals(7, chunks.size)
        assertEquals(listOf(1, 3, 3, 1, 5, 2, 1), chunks.map { it.split("\n\n").size })
        assertTrue(chunks[1].startsWith("\"Wo-ho!\""))
        assertTrue(chunks[1].endsWith("\"What o'clock do you make it, Joe?\""))
        assertTrue(chunks[4].startsWith("\"Tst! Joe!\""))
        assertTrue(chunks[4].endsWith("\"I say a horse at a gallop, Tom,\" returned the guard, leaving his hold of the door, and mounting nimbly to his place. \"Gentlemen! In the king's name, all of you!\""))
    }

    @Test
    fun oversizedParagraphIsSplit() {
        val text = "A".repeat(500)

        val chunks = TextChunker.chunk(text, maxChars = 120)

        assertEquals(5, chunks.size)
        assertTrue(chunks.all { it.length <= 120 })
    }

    @Test
    fun oversizedParagraphIsSplitAtSentenceBoundariesWhenPossible() {
        val text = "One. Two. Three. Four."

        val chunks = TextChunker.chunk(text, maxChars = 16)

        assertEquals(2, chunks.size)
        assertTrue(chunks.all { it.length <= 16 })
        assertTrue(chunks.all { it.endsWith(".") })
    }

    @Test
    fun chunkPositionsMatchOriginalTextOffsets() {
        val text = "  One.\n\nTwo. Three."

        val chunks = TextChunker.chunksWithPositions(text, maxChars = 12)

        assertEquals("One.", text.substring(chunks[0].textStartOffset, chunks[0].textEndOffset))
        assertEquals("Two. Three.", text.substring(chunks[1].textStartOffset, chunks[1].textEndOffset))
    }
}
