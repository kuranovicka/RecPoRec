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
        val raw = when (format) {
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
        return raw.copy(fullText = stripJunkCharacters(raw.fullText))
    }

    /** Znaci koje TTS motor ume da "izgovori" bukvalno (kao neki nepovezan, nerazumljiv
     * hijeroglif) umesto da ih prosto ignoriše - prijava korisnika: rečenice sa ovim znacima
     * su zvučale kao da čitač "zamuca" na neobjašnjiv nacin, a ni Word ni drugi čitač (Voice
     * Aloud Reader) ih uopšte ne izgovaraju. Zamenjuje se RAZMAKOM (ne brisanjem) - namerno,
     * da se NE pomeri duzina teksta, jer bi to pokvarilo sve vec izracunate offsete poglavlja
     * (chapters) i kasnije oznake/pozicije koje se cuvaju kao broj karaktera u ovom tekstu. */
    private val JUNK_CHARS: Set<Char> = (
        "#$*|<>&°`{}¤¶¦§•µ©↨«»~¹²³⁴⁵⁶⁷⁸⁹⁰₀₁₂₃₄₅₆₇₈₉†‡‽※™®…‒–—―" +
            "\u201C\u201D\u201E\u201F\u2018\u2019\u201A\u201B" +
            "⁺⁻⁼⁽⁾ⁱⁿᵃᵇᶜᵈᵉᶠᵍʰʲᵏˡᵐᵒᵖʳˢᵗᵘᵛʷˣʸᶻᵃₐₑₒᵤᵥₓ"
        ).toSet()

    private fun stripJunkCharacters(text: String): String {
        if (text.isEmpty()) return text
        val chars = text.toCharArray()
        var changed = false
        for (i in chars.indices) {
            if (chars[i] in JUNK_CHARS) {
                chars[i] = ' '
                changed = true
            }
        }
        return if (changed) String(chars) else text
    }
}
