package com.recporec.app.parser

/**
 * Rezultat parsiranja dokumenta bilo kog podržanog formata.
 * Ceo tekst se svodi na jedan niz karaktera; poglavlja su offseti unutar njega.
 */
data class ParsedChapter(
    val title: String,
    val startOffset: Int
)

data class ParsedDocument(
    val fullText: String,
    val chapters: List<ParsedChapter> = emptyList()
) {
    val length: Int get() = fullText.length
}
