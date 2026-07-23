package com.recporec.app.parser

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Jednostavan EPUB parser bez spoljnih biblioteka.
 * EPUB je zip arhiva: META-INF/container.xml -> pokazuje na .opf fajl,
 * .opf sadrži manifest (id -> href) i spine (redosled poglavlja).
 */
object EpubParser {

    fun parse(context: Context, uri: Uri): ParsedDocument {
        val entries = readZipEntries(context, uri)

        val containerXml = entries["META-INF/container.xml"]?.toString(Charsets.UTF_8) ?: ""
        val opfPath = Regex("full-path=\"([^\"]+)\"").find(containerXml)?.groupValues?.get(1)
            ?: return fallbackParse(entries)

        val opfBytes = entries[opfPath] ?: return fallbackParse(entries)
        val opfXml = opfBytes.toString(Charsets.UTF_8)
        val opfDir = if (opfPath.contains("/")) opfPath.substringBeforeLast("/") + "/" else ""

        // Manifest: id -> href
        val manifest = mutableMapOf<String, String>()
        Regex("<item\\s+[^>]*id=\"([^\"]+)\"[^>]*href=\"([^\"]+)\"[^>]*/?>").findAll(opfXml).forEach {
            manifest[it.groupValues[1]] = it.groupValues[2]
        }
        // href moze biti pre id u atributima, probaj i obrnut redosled
        if (manifest.isEmpty()) {
            Regex("<item\\s+[^>]*href=\"([^\"]+)\"[^>]*id=\"([^\"]+)\"[^>]*/?>").findAll(opfXml).forEach {
                manifest[it.groupValues[2]] = it.groupValues[1]
            }
        }

        // Spine: redosled idref-ova
        val spineIds = Regex("<itemref\\s+[^>]*idref=\"([^\"]+)\"").findAll(opfXml)
            .map { it.groupValues[1] }.toList()

        if (spineIds.isEmpty() || manifest.isEmpty()) return fallbackParse(entries)

        val textBuilder = StringBuilder()
        val chapters = mutableListOf<ParsedChapter>()

        for (id in spineIds) {
            val href = manifest[id] ?: continue
            val fullPath = normalizePath(opfDir + href)
            val fileBytes = entries[fullPath] ?: continue
            val html = fileBytes.toString(Charsets.UTF_8)

            val chapterTitle = extractTitle(html) ?: href.substringAfterLast("/").substringBeforeLast(".")
            val plainText = htmlToPlainText(html)
            if (plainText.isBlank()) continue

            chapters.add(ParsedChapter(chapterTitle, textBuilder.length))
            textBuilder.append(plainText)
            textBuilder.append("\n\n")
        }

        return ParsedDocument(fullText = textBuilder.toString(), chapters = chapters)
    }

    private fun fallbackParse(entries: Map<String, ByteArray>): ParsedDocument {
        // Ako struktura nije prepoznata, spoji sve .xhtml/.html fajlove kakvim redom dođu
        val sb = StringBuilder()
        entries.entries
            .filter { it.key.endsWith(".xhtml") || it.key.endsWith(".html") || it.key.endsWith(".htm") }
            .sortedBy { it.key }
            .forEach { sb.append(htmlToPlainText(it.value.toString(Charsets.UTF_8))).append("\n\n") }
        return ParsedDocument(fullText = sb.toString())
    }

    private fun normalizePath(path: String): String {
        val parts = path.split("/")
        val stack = mutableListOf<String>()
        for (p in parts) {
            when (p) {
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                "." -> {}
                "" -> {}
                else -> stack.add(p)
            }
        }
        return stack.joinToString("/")
    }

    private fun extractTitle(html: String): String? {
        Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(html)?.let {
            val t = it.groupValues[1].trim()
            if (t.isNotBlank()) return t
        }
        Regex("<h1[^>]*>(.*?)</h1>", RegexOption.DOT_MATCHES_ALL).find(html)?.let {
            val t = stripTags(it.groupValues[1]).trim()
            if (t.isNotBlank()) return t
        }
        return null
    }

    private fun htmlToPlainText(html: String): String {
        // Ukloni head, script, style
        var body = Regex("<head[^>]*>.*?</head>", RegexOption.DOT_MATCHES_ALL).replace(html, "")
        body = Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL).replace(body, "")
        body = Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL).replace(body, "")
        // Prelomi pasusa i redova
        body = body.replace(Regex("(?i)<br\\s*/?>"), "\n")
        body = body.replace(Regex("(?i)</p>"), "\n\n")
        return stripTags(body).trim()
    }

    private fun stripTags(html: String): String {
        val noTags = Regex("<[^>]+>").replace(html, "")
        return noTags
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
    }

    private fun readZipEntries(context: Context, uri: Uri): Map<String, ByteArray> {
        val map = mutableMapOf<String, ByteArray>()
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var len: Int
                        while (zis.read(buffer).also { len = it } != -1) {
                            out.write(buffer, 0, len)
                        }
                        map[entry.name] = out.toByteArray()
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        return map
    }
}
