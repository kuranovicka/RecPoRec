package com.recporec.app.util

import com.recporec.app.data.DocumentEntity

/** Deljena logika za formatiranje statistike čitanja - koristi je i StatsActivity (ekran) i
 * DocumentListActivity (dug pritisak na Opcije, izgovara naglas bez otvaranja ekrana), da se
 * dve verzije ne bi vremenom razišle. */
object StatsFormatter {

    fun buildStatsText(docs: List<DocumentEntity>): String {
        val totalSeconds = docs.sumOf { it.elapsedSeconds }
        val totalBooks = docs.size
        val finished = docs.count { it.totalCharacters > 0 && it.currentCharacterOffset >= it.totalCharacters }
        val started = docs.count {
            it.totalCharacters > 0 && it.currentCharacterOffset in 1 until it.totalCharacters
        }
        val notStarted = totalBooks - finished - started

        val lines = StringBuilder()
        lines.append("Ukupno vreme slušanja: ${formatDuration(totalSeconds)}.\n\n")
        lines.append("Ukupno dokumenata u listi: $totalBooks.\n")
        lines.append("Pročitano do kraja: $finished.\n")
        lines.append("Započeto, u toku: $started.\n")
        lines.append("Nije ni započeto: $notStarted.\n")
        if (docs.isNotEmpty()) {
            val longest = docs.maxByOrNull { it.elapsedSeconds }
            if (longest != null && longest.elapsedSeconds > 0) {
                lines.append("\nNajviše vremena provedeno na: \"${longest.title}\" (${formatDuration(longest.elapsedSeconds)}).")
            }
        }
        return lines.toString()
    }

    private fun formatDuration(totalSeconds: Long): String {
        val totalMinutes = totalSeconds / 60
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        val hourWord = serbianPlural(hours.toInt(), "sat", "sata", "sati")
        val minuteWord = serbianPlural(minutes.toInt(), "minut", "minuta", "minuta")
        return if (hours > 0) "$hours $hourWord i $minutes $minuteWord" else "$minutes $minuteWord"
    }

    private fun serbianPlural(n: Int, one: String, few: String, many: String): String {
        val mod100 = n % 100
        val mod10 = n % 10
        return when {
            mod100 in 11..14 -> many
            mod10 == 1 -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }
}
