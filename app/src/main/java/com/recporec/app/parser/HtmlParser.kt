package com.recporec.app.parser

import android.content.Context
import android.net.Uri

object HtmlParser {
    fun parse(context: Context, uri: Uri): ParsedDocument {
        val html = context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: ""
        val title = HtmlTextUtil.extractTitleTag(html)
        val text = HtmlTextUtil.htmlToPlainText(html)
        val chapters = if (title != null) listOf(ParsedChapter(title, 0)) else emptyList()
        return ParsedDocument(fullText = text, chapters = chapters)
    }
}
