package com.recporec.app.parser

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

object PdfParser {
    fun parse(context: Context, uri: Uri): ParsedDocument {
        val sb = StringBuilder()
        val chapters = mutableListOf<ParsedChapter>()

        context.contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { doc ->
                val stripper = PDFTextStripper()
                val pageCount = doc.numberOfPages
                for (i in 1..pageCount) {
                    stripper.startPage = i
                    stripper.endPage = i
                    val pageText = stripper.getText(doc)
                    // Heuristika za naslov poglavlja: kratka linija sa velikim slovima na vrhu strane
                    val firstLine = pageText.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                    if (firstLine != null && looksLikeHeading(firstLine)) {
                        chapters.add(ParsedChapter(firstLine, sb.length))
                    }
                    sb.append(pageText)
                    sb.append("\n")
                }
            }
        }
        return ParsedDocument(fullText = sb.toString(), chapters = chapters)
    }

    private fun looksLikeHeading(line: String): Boolean {
        if (line.length > 60 || line.length < 3) return false
        val letters = line.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        val upperRatio = letters.count { it.isUpperCase() }.toFloat() / letters.length
        return upperRatio > 0.6f || line.startsWith("Poglavlje", ignoreCase = true) ||
            line.startsWith("Chapter", ignoreCase = true)
    }
}
