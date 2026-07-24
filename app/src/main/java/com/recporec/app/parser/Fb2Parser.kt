package com.recporec.app.parser

import android.content.Context
import android.net.Uri

/**
 * FB2 (FictionBook) je XML format. Glavni tekst je unutar <body> (ne notes/name="notes"),
 * podeljen na <section> sa <title> naslovima poglavlja i <p> pasusima.
 */
object Fb2Parser {

    fun parse(context: Context, uri: Uri): ParsedDocument {
        val xml = context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: ""

        // Uzimamo prvi <body> koji nije notes/komentari (obično bez name atributa)
        val bodyMatch = Regex("<body(?![^>]*name=)[^>]*>(.*?)</body>", RegexOption.DOT_MATCHES_ALL)
            .find(xml) ?: Regex("<body[^>]*>(.*?)</body>", RegexOption.DOT_MATCHES_ALL).find(xml)
        val bodyXml = bodyMatch?.groupValues?.get(1) ?: xml

        val sb = StringBuilder()
        val chapters = mutableListOf<ParsedChapter>()

        val elementRegex = Regex("<title[^>]*>(.*?)</title>|<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
        for (match in elementRegex.findAll(bodyXml)) {
            val titleContent = match.groupValues[1]
            val paraContent = match.groupValues[2]
            if (titleContent.isNotEmpty()) {
                val titleText = HtmlTextUtil.stripTags(titleContent).trim().replace("\n", " ")
                if (titleText.isNotBlank()) {
                    chapters.add(ParsedChapter(titleText, sb.length))
                    sb.append(titleText).append("\n\n")
                }
            } else if (paraContent.isNotEmpty()) {
                val text = HtmlTextUtil.stripTags(paraContent).trim()
                if (text.isNotBlank()) {
                    sb.append(text).append("\n\n")
                }
            }
        }

        return ParsedDocument(fullText = sb.toString(), chapters = chapters)
    }
}
