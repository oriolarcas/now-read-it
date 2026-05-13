package cat.oriol.nowreadit.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import java.net.URI

data class ExtractedPage(
    val url: String,
    val title: String,
    val siteName: String?,
    val text: String,
)

class PageContentExtractor(
    private val httpClient: OkHttpClient,
) {
    suspend fun extract(url: String): ExtractedPage = withContext(Dispatchers.IO) {
        val normalizedUrl = URI(url.trim()).toString()
        val response = httpClient.newCall(
            Request.Builder()
                .url(normalizedUrl)
                .header("User-Agent", "NowReadIt/1.0")
                .build(),
        ).execute()

        response.use { httpResponse ->
            if (!httpResponse.isSuccessful) {
                error("Page download failed: HTTP ${httpResponse.code}")
            }

            val html = httpResponse.body?.string().orEmpty()
            if (html.isBlank()) error("Downloaded page was empty.")

            val document = Jsoup.parse(html, normalizedUrl)
            val title = document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?: document.title().takeIf { it.isNotBlank() }
                ?: normalizedUrl
            val siteName = document.selectFirst("meta[property=og:site_name]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?: runCatching { URI(normalizedUrl).host }.getOrNull()

            val extractedText = extractReadableText(document)
            if (extractedText.isBlank()) error("Could not extract article text from the page.")

            ExtractedPage(
                url = normalizedUrl,
                title = title,
                siteName = siteName,
                text = extractedText,
            )
        }
    }

    private fun extractReadableText(document: Document): String {
        val candidates = listOfNotNull(
            document.selectFirst("article"),
            document.selectFirst("main"),
            document.select("div").maxByOrNull { scoreElement(it) },
            document.body(),
        )

        return candidates
            .asSequence()
            .map { element -> collectText(element) }
            .firstOrNull { it.length >= MIN_READABLE_CHARS }
            ?: candidates.firstOrNull()?.let(::collectText).orEmpty()
    }

    private fun scoreElement(element: Element): Int {
        val paragraphs = element.select("p")
        val paragraphChars = paragraphs.sumOf { it.text().trim().length }
        val headingChars = element.select("h1, h2, h3").sumOf { it.text().trim().length / 2 }
        return paragraphChars + headingChars
    }

    private fun collectText(root: Element): String {
        root.select("script, style, nav, header, footer, form, aside, noscript").remove()
        val blocks = root.select("h1, h2, h3, p, li, blockquote, pre")
            .map { it.text().trim() }
            .filter { it.length >= MIN_BLOCK_CHARS }

        return if (blocks.isNotEmpty()) {
            blocks.joinToString("\n\n")
        } else {
            root.text().trim()
        }
    }

    companion object {
        private const val MIN_BLOCK_CHARS = 30
        private const val MIN_READABLE_CHARS = 280
    }
}
