package com.recporec.app.parser

import android.content.Context
import android.net.Uri

object DocumentParser {

    /** Vraća format na osnovu imena fajla i/ili MIME tipa, ili null ako nije podržan. */
    fun detectFormat(fileName: String, mimeType: String? = null): String? {
        val lower = fileName.lowercase()
        val byName = when {
            lower.endsWith(".txt") -> "txt"
            lower.endsWith(".epub") -> "epub"
            lower.endsWith(".pdf") -> "pdf"
            lower.endsWith(".docx") -> "docx"
            lower.endsWith(".html") || lower.endsWith(".htm") -> "html"
            lower.endsWith(".fb2") -> "fb2"
            lower.endsWith(".rtf") -> "rtf"
            lower.endsWith(".mobi") || lower.endsWith(".azw") || lower.endsWith(".azw3") || lower.endsWith(".prc") -> "mobi"
            else -> null
        }
        if (byName != null) return byName

        return when (mimeType) {
            "text/plain" -> "txt"
            "application/epub+zip" -> "epub"
            "application/pdf" -> "pdf"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
            "text/html" -> "html"
            "application/x-fictionbook+xml" -> "fb2"
            "text/rtf", "application/rtf" -> "rtf"
            "application/x-mobipocket-ebook", "application/vnd.amazon.ebook" -> "mobi"
            else -> null
        }
    }

    fun parse(context: Context, uri: Uri, format: String): ParsedDocument {
        return when (format) {
            "txt" -> TxtParser.parse(context, uri)
            "epub" -> EpubParser.parse(context, uri)
            "pdf" -> PdfParser.parse(context, uri)
            "docx" -> DocxParser.parse(context, uri)
            "html" -> HtmlParser.parse(context, uri)
            "fb2" -> Fb2Parser.parse(context, uri)
            "rtf" -> RtfParser.parse(context, uri)
            "mobi" -> MobiParser.parse(context, uri)
            else -> ParsedDocument(fullText = "")
        }
    }
}
