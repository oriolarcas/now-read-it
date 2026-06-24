package cat.oriol.nowreadit.data

internal fun textWithTitle(title: String, body: String): String {
    val cleanTitle = title.trim()
    val cleanBody = body.trim()
    if (cleanTitle.isBlank()) return cleanBody
    if (cleanBody.isBlank()) return cleanTitle

    val normalizedTitle = cleanTitle.normalizedForTitleComparison()
    val normalizedBodyStart = cleanBody
        .lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.normalizedForTitleComparison()
        .orEmpty()

    return if (normalizedBodyStart == normalizedTitle) {
        cleanBody
    } else {
        "$cleanTitle\n\n$cleanBody"
    }
}

private fun String.normalizedForTitleComparison(): String =
    trim()
        .replace(Regex("\\s+"), " ")
        .trimEnd('.', ':', '-', '—')
        .lowercase()
