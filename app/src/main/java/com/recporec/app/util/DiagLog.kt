package com.recporec.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Jednostavan dnevnik u memoriji za dijagnostiku (npr. audio fokus problem). NIJE trajan -
 * briše se kad se app potpuno zatvori. Namenjeno da se prikaže korisnici u posebnom
 * ekranu/dijalogu, sa dugmadima Kopiraj/Podeli, ne da se meša u sam tekst dokumenta.
 */
object DiagLog {
    private val entries = mutableListOf<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private const val MAX_ENTRIES = 500

    @Synchronized
    fun log(message: String) {
        val time = timeFormat.format(Date())
        entries.add("[$time] $message")
        if (entries.size > MAX_ENTRIES) entries.removeAt(0)
    }

    @Synchronized
    fun getAll(): String =
        if (entries.isEmpty()) "Dnevnik je prazan - još ništa nije zabeleženo." else entries.joinToString("\n")

    @Synchronized
    fun clear() = entries.clear()
}
