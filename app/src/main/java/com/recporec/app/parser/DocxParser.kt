package com.recporec.app.parser

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * DOCX je zip arhiva; tekst se nalazi u word/document.xml.
 * Pasusi <w:p>, prelomi reda <w:br/>, stilovi naslova w:pStyle val="Heading*" -> poglavlje.
 */
object DocxParser {

    fun parse(context: Context, uri: Uri): ParsedDocument {
        var documentXml: String? = null
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var len: Int
                        while (zis.read(buffer).also { len = it } != -1) out.write(buffer, 0, len)
                        documentXml = out.toString("UTF-8")
                        break
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        val xml = documentXml ?: return ParsedDocument(fullText = "")
        val sb = StringBuilder()
        val chapters = mutableListOf<ParsedChapter>()

        val paragraphs = Regex("<w:p[ >].*?</w:p>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
        for (p in paragraphs) {
            val pXml = p.value
            val isHeading = Regex("w:pStyle[^/]*w:val=\"Heading").containsMatchIn(pXml) ||
                Regex("w:pStyle[^/]*w:val=\"Title").containsMatchIn(pXml)

            val texts = Regex("<w:t[^>]*>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL).findAll(pXml)
                .joinToString("") { it.groupValues[1] }
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")

            if (texts.isBlank()) continue

            if (isHeading) {
                chapters.add(ParsedChapter(texts.trim(), sb.length))
            }
            sb.append(texts)
            sb.append("\n\n")
        }

        return ParsedDocument(fullText = sb.toString(), chapters = chapters)
    }
}
