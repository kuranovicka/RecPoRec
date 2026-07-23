package com.recporec.app.parser

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader

object TxtParser {
    fun parse(context: Context, uri: Uri): ParsedDocument {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
        } ?: ""
        return ParsedDocument(fullText = text)
    }
}
