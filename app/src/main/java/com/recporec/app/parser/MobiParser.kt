package com.recporec.app.parser

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parser za MOBI i AZW (stariji Kindle format, PalmDOC/MOBI kontejner).
 * AZW3 (KF8) obično i dalje sadrži kompatibilan MOBI7 deo pa uglavnom radi i za njega.
 * DRM-ovane knjige nije moguće otvoriti (nema legalnog načina za to).
 */
object MobiParser {

    fun parse(context: Context, uri: Uri): ParsedDocument {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return ParsedDocument("")

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (bytes.size < 78) return ParsedDocument("")

        val numRecords = buf.getShort(76).toInt() and 0xFFFF
        val offsets = IntArray(numRecords)
        for (r in 0 until numRecords) {
            offsets[r] = buf.getInt(78 + r * 8)
        }

        fun recordLength(index: Int): Int {
            val start = offsets[index]
            val end = if (index + 1 < numRecords) offsets[index + 1] else bytes.size
            return (end - start).coerceAtLeast(0)
        }

        if (numRecords == 0) return ParsedDocument("")
        val record0Offset = offsets[0]

        val compression = buf.getShort(record0Offset).toInt() and 0xFFFF
        val recordCount = buf.getShort(record0Offset + 8).toInt() and 0xFFFF
        val encryptionType = buf.getShort(record0Offset + 12).toInt() and 0xFFFF

        if (encryptionType != 0) {
            return ParsedDocument(fullText = "[Ova knjiga je zaštićena DRM-om i ne može se otvoriti.]")
        }
        if (compression != 1 && compression != 2) {
            return ParsedDocument(fullText = "[Ovaj MOBI fajl koristi nepodržanu kompresiju.]")
        }

        var textEncoding = 65001
        try {
            val magic = String(bytes, record0Offset + 16, 4, Charsets.US_ASCII)
            if (magic == "MOBI") {
                textEncoding = buf.getInt(record0Offset + 28)
            }
        } catch (_: Exception) { /* nema MOBI zaglavlja, koristimo podrazumevano */ }

        val charset = when (textEncoding) {
            1252 -> Charsets.ISO_8859_1 // dovoljno blizu za osnovni tekst
            65001 -> Charsets.UTF_8
            else -> Charsets.UTF_8
        }

        val out = ByteArrayOutputStream()
        for (i in 1..recordCount) {
            if (i >= numRecords) break
            val start = offsets[i]
            val len = recordLength(i)
            if (len <= 0 || start + len > bytes.size) continue
            val recordBytes = bytes.copyOfRange(start, start + len)
            if (compression == 2) {
                out.write(decompressPalmDoc(recordBytes))
            } else {
                out.write(recordBytes)
            }
        }

        val rawText = out.toByteArray().toString(charset)
        val plainText = HtmlTextUtil.htmlToPlainText(rawText)
        val chapters = detectChapters(plainText)

        return ParsedDocument(fullText = plainText, chapters = chapters)
    }

    private fun detectChapters(text: String): List<ParsedChapter> {
        val chapters = mutableListOf<ParsedChapter>()
        var offset = 0
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (looksLikeHeading(trimmed)) {
                chapters.add(ParsedChapter(trimmed, offset))
            }
            offset += line.length + 1
        }
        return chapters
    }

    private fun looksLikeHeading(line: String): Boolean {
        if (line.length > 60 || line.length < 3) return false
        val letters = line.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        val upperRatio = letters.count { it.isUpperCase() }.toFloat() / letters.length
        return upperRatio > 0.6f || line.startsWith("Poglavlje", ignoreCase = true) ||
            line.startsWith("Chapter", ignoreCase = true)
    }

    /** PalmDOC LZ77-slična dekompresija (standardni algoritam). */
    private fun decompressPalmDoc(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(input.size * 3)
        var i = 0
        while (i < input.size) {
            val c = input[i].toInt() and 0xFF
            when {
                c == 0x00 -> {
                    out.write(c)
                    i++
                }
                c in 0x01..0x08 -> {
                    i++
                    val count = c
                    for (j in 0 until count) {
                        if (i < input.size) {
                            out.write(input[i].toInt() and 0xFF)
                            i++
                        }
                    }
                }
                c in 0x09..0x7F -> {
                    out.write(c)
                    i++
                }
                c in 0x80..0xBF -> {
                    if (i + 1 >= input.size) { i++; continue }
                    val c2 = input[i + 1].toInt() and 0xFF
                    val combined = ((c and 0x3F) shl 8) or c2
                    val distance = combined shr 3
                    val length = (combined and 0x07) + 3
                    i += 2
                    val currentOutBytes = out.toByteArray()
                    var srcPos = currentOutBytes.size - distance
                    for (j in 0 until length) {
                        if (srcPos < 0 || srcPos >= out.size()) break
                        val b = if (srcPos < currentOutBytes.size) currentOutBytes[srcPos]
                        else out.toByteArray()[srcPos]
                        out.write(b.toInt() and 0xFF)
                        srcPos++
                    }
                }
                else -> { // c >= 0xC0
                    out.write(' '.code)
                    out.write(c xor 0x80)
                    i++
                }
            }
        }
        return out.toByteArray()
    }
}
