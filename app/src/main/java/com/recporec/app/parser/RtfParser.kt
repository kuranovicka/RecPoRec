package com.recporec.app.parser

import android.content.Context
import android.net.Uri
import java.nio.charset.Charset

/**
 * Jednostavan RTF -> čist tekst konvertor. Ne oslanja se na spoljne biblioteke.
 * Preskače tabele fontova/boja i druge "destinacije" bez vidljivog teksta,
 * i podržava \uN Unicode escape (bitno za dijakritike/ćirilicu).
 */
object RtfParser {

    private val skipDestinations = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "generator",
        "pict", "object", "footer", "header", "footnote", "themedata",
        "colorschememapping", "latentstyles", "rsidtbl", "listtable",
        "listoverridetable", "datastore", "xmlnstbl"
    )

    fun parse(context: Context, uri: Uri): ParsedDocument {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return ParsedDocument("")
        val raw = bytes.toString(Charsets.US_ASCII)

        val out = StringBuilder()
        var i = 0
        val n = raw.length
        var groupDepth = 0
        val skipDepthStack = ArrayDeque<Boolean>()
        var currentSkip = false
        var unicodeSkipCount = 1
        var pendingSkipAfterUnicode = 0

        val cp1250 = try { Charset.forName("windows-1250") } catch (e: Exception) { Charsets.ISO_8859_1 }

        while (i < n) {
            val c = raw[i]
            when (c) {
                '{' -> {
                    groupDepth++
                    skipDepthStack.addLast(currentSkip)
                    i++
                }
                '}' -> {
                    groupDepth--
                    currentSkip = if (skipDepthStack.isNotEmpty()) skipDepthStack.removeLast() else false
                    i++
                }
                '\\' -> {
                    i++
                    if (i >= n) break
                    val ctrlChar = raw[i]
                    if (ctrlChar == '\\' || ctrlChar == '{' || ctrlChar == '}') {
                        if (!currentSkip) out.append(ctrlChar)
                        i++
                    } else if (ctrlChar == '\'') {
                        i++
                        if (i + 1 < n) {
                            val hex = raw.substring(i, i + 2)
                            i += 2
                            if (pendingSkipAfterUnicode > 0) {
                                pendingSkipAfterUnicode--
                            } else if (!currentSkip) {
                                val byteVal = hex.toIntOrNull(16)
                                if (byteVal != null) {
                                    out.append(String(byteArrayOf(byteVal.toByte()), cp1250))
                                }
                            }
                        }
                    } else if (ctrlChar.isLetter()) {
                        val start = i
                        while (i < n && raw[i].isLetter()) i++
                        val word = raw.substring(start, i)
                        var numStr = ""
                        if (i < n && (raw[i] == '-' || raw[i].isDigit())) {
                            val numStart = i
                            if (raw[i] == '-') i++
                            while (i < n && raw[i].isDigit()) i++
                            numStr = raw.substring(numStart, i)
                        }
                        if (i < n && raw[i] == ' ') i++

                        if (word in skipDestinations) {
                            currentSkip = true
                        }
                        when (word) {
                            "par", "line" -> if (!currentSkip) out.append('\n')
                            "tab" -> if (!currentSkip) out.append('\t')
                            "u" -> {
                                val code = numStr.toIntOrNull() ?: 0
                                val fixed = if (code < 0) code + 65536 else code
                                if (!currentSkip && fixed in 0..0x10FFFF) {
                                    out.appendCodePoint(fixed)
                                }
                                pendingSkipAfterUnicode = unicodeSkipCount
                            }
                            "uc" -> unicodeSkipCount = numStr.toIntOrNull() ?: 1
                        }
                    } else if (ctrlChar == '*') {
                        currentSkip = true
                        i++
                    } else if (ctrlChar == '~') {
                        if (!currentSkip) out.append(' ')
                        i++
                    } else if (ctrlChar == '\n' || ctrlChar == '\r') {
                        i++
                    } else {
                        i++
                    }
                }
                else -> {
                    if (!currentSkip && c != '\r') {
                        if (pendingSkipAfterUnicode > 0 && c != '\n') {
                            pendingSkipAfterUnicode--
                        } else {
                            out.append(c)
                        }
                    }
                    i++
                }
            }
        }

        val text = out.toString()
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

        return ParsedDocument(fullText = text)
    }
}
