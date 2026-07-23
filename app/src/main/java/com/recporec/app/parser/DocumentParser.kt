package com.recporec.app.parser

import android.content.Context
import android.net.Uri

object DocumentParser {

    /** Vraća format (txt, epub, pdf, docx) na osnovu imena fajla, ili null ako nije podržan. */
    fun detectFormat(fileName: String): String? {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".txt") -> "txt"
            lower.endsWith(".epub") -> "epub"
            lower.endsWith(".pdf") -> "pdf"
            lower.endsWith(".docx") -> "docx"
            else -> null
        }
    }

    fun parse(context: Context, uri: Uri, format: String): ParsedDocument {
        return when (format) {
            "txt" -> TxtParser.parse(context, uri)
            "epub" -> EpubParser.parse(context, uri)
            "pdf" -> PdfParser.parse(context, uri)
            "docx" -> DocxParser.parse(context, uri)
            else -> ParsedDocument(fullText = "")
        }
    }
}
