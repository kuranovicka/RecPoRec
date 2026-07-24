package com.recporec.app.parser

object HtmlTextUtil {

    fun htmlToPlainText(html: String): String {
        var body = Regex("<head[^>]*>.*?</head>", RegexOption.DOT_MATCHES_ALL).replace(html, "")
        body = Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL).replace(body, "")
        body = Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL).replace(body, "")
        body = body.replace(Regex("(?i)<br\\s*/?>"), "\n")
        body = body.replace(Regex("(?i)</p>"), "\n\n")
        return stripTags(body).trim()
    }

    fun stripTags(html: String): String {
        val noTags = Regex("<[^>]+>").replace(html, "")
        return noTags
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
    }

    fun extractTitleTag(html: String): String? {
        Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(html)?.let {
            val t = it.groupValues[1].trim()
            if (t.isNotBlank()) return t
        }
        return null
    }
}
