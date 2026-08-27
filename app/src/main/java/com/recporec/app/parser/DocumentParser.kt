package com.recporec.app.parser

import android.content.Context
import android.net.Uri

object DocumentParser {

    /** Podržani zvučni formati za audio knjige (folder → zip uvoz) - pokriveni ugrađenim
     * ExoPlayer ekstraktorima, bez potrebe za FFmpeg ekstenzijom. */
    val AUDIO_EXTENSIONS = setOf("mp3", "wav", "ogg", "flac", "m4a", "aac")

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
            // Audio knjige nemaju tekst za izvlačenje - stvarna reprodukcija (ExoPlayer)
            // se ne oslanja na ovaj parser, dolazi u sledećem koraku.
            "audio" -> ParsedDocument(fullText = "")
            else -> ParsedDocument(fullText = "")
        }
        return raw.copy(fullText = stripJunkCharacters(raw.fullText))
    }

    /** Umesto nabrajanja poznatih "hijeroglifa" (staro: fiksna lista znakova) - PROPUSTA se
     * SVE što je slovo (Character.isLetter - radi ispravno i za ćirilicu i za č/ć/š/đ/ž, ne
     * samo englesku abecedu), broj, razmak/prelom reda, i mala, izričito dozvoljena lista
     * uobičajene interpunkcije. SVE OSTALO (bilo koji simbol, čak i oni koje još nismo videli
     * u nekom budućem dokumentu) se zamenjuje RAZMAKOM - namerno, da se NE pomeri dužina
     * teksta, jer bi to pokvarilo već izračunate offsete poglavlja (chapters) i sačuvane
     * pozicije/oznake koje se čuvaju kao broj karaktera u ovom tekstu.
     * Predlog korisnika - otporniji pristup od nabrajanja, jer ne zavisi od toga da li smo mi
     * unapred pogodile svaki mogući "čudan" znak. */
    private val SAFE_PUNCTUATION: Set<Char> = setOf(
        '.', ',', ';', ':', '!', '?', '-', '–', '—', '(', ')', '[', ']',
        '"', '\'', '\u201C', '\u201D', '\u2018', '\u2019', '…', '/'
    )

    private fun stripJunkCharacters(text: String): String {
        if (text.isEmpty()) return text
        val chars = text.toCharArray()
        var changed = false
        for (i in chars.indices) {
            val c = chars[i]
            val allowed = Character.isLetter(c) || Character.isDigit(c) || c.isWhitespace() || c in SAFE_PUNCTUATION
            if (!allowed) {
                chars[i] = ' '
                changed = true
            }
        }
        return if (changed) String(chars) else text
    }
}
