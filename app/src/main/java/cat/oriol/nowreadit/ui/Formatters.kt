package cat.oriol.nowreadit.ui

import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

internal fun formatTimestamp(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))

internal fun formatDuration(durationMs: Long?): String? {
    durationMs ?: return null
    val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60
    return "%d:%02d".format(minutes, seconds)
}

internal fun estimateReadingDuration(text: String): String {
    val wordCount = Regex("\\S+").findAll(text).count()
    val estimatedMinutes = (wordCount / WORDS_PER_MINUTE.toDouble()).coerceAtLeast(1.0)
    val roundedMinutes = kotlin.math.ceil(estimatedMinutes).toInt()
    return if (roundedMinutes == 1) "about 1 minute" else "about $roundedMinutes minutes"
}

private const val WORDS_PER_MINUTE = 160
