package com.recporec.app.tts

import android.content.Context
import com.recporec.app.data.AppDatabase
import com.recporec.app.data.AppSettings

/** Rečnik izgovora - primenjuje se SAMO na tekst koji se šalje TTS motoru (ono što se
 * stvarno izgovara), nikad na sam sačuvani tekst dokumenta - offseti za navigaciju,
 * oznake i procenat čitanja ostaju netaknuti.
 *
 * Poklapanje ide u dva koraka:
 * 1. Cela reč se tačno poklapa sa nečim iz rečnika (bez razlike velikih/malih slova).
 * 2. Ako nema tačnog poklapanja, reč možda ima domaći padežni nastavak nalepljen na strano
 *    ime (npr. "Maryn" = "Mary" + "n", "Johnovo" = "John" + "ovo") - probaj da nađeš koren
 *    iz rečnika kao POČETAK reči, uz kratak ostatak (do MAX_SUFFIX_LEN slova). Ostatak se
 *    lepi na kraj zamene nepromenjen ("Mary"->"Meri" + "n" = "Merin").
 * Ovo je heuristika, ne prava gramatička analiza - povremeno će pogrešno "pogoditi", ali
 * pošto je u pitanju samo izgovor (ne menja se sam tekst), greška je bezopasna. */
class PronunciationDictionary private constructor(private val exactMap: Map<String, String>) {

    private val wordPattern = Regex("\\p{L}+")

    fun apply(text: String): String {
        if (exactMap.isEmpty()) return text
        return wordPattern.replace(text) { match ->
            val word = match.value
            val lower = word.lowercase()
            exactMap[lower]?.let { return@replace it }

            var suffixLen = 1
            while (suffixLen <= MAX_SUFFIX_LEN) {
                val prefixLen = lower.length - suffixLen
                if (prefixLen < MIN_PREFIX_LEN) break
                val replacement = exactMap[lower.substring(0, prefixLen)]
                if (replacement != null) {
                    return@replace replacement + word.substring(prefixLen)
                }
                suffixLen++
            }
            word
        }
    }

    companion object {
        private const val MAX_SUFFIX_LEN = 4
        private const val MIN_PREFIX_LEN = 3
        val EMPTY = PronunciationDictionary(emptyMap())

        /** Čita SAMO ugrađeni rečnik iz resursa (bez obzira na prekidač, bez korisnikovih
         * unosa) - koristi ga i ovo kačenje na TTS, i ekran za pregled/pretragu, da se
         * čitanje fajla ne piše na dva mesta. Vraća originalnu reč (ne malim slovima), da
         * bi se lepo prikazala na ekranu za pregled. */
        fun loadBuiltInRaw(context: Context): List<Pair<String, String>> {
            return try {
                val list = mutableListOf<Pair<String, String>>()
                context.assets.open("recnik_izgovora_ugradjeni.txt").bufferedReader(Charsets.UTF_8).use { reader ->
                    reader.forEachLine { line ->
                        val idx = line.indexOf('=')
                        if (idx <= 0) return@forEachLine
                        val word = line.substring(0, idx).trim()
                        val replacement = line.substring(idx + 1).trim()
                        if (word.isNotEmpty() && replacement.isNotEmpty()) list.add(word to replacement)
                    }
                }
                list
            } catch (_: Exception) {
                emptyList()
            }
        }

        /** Učitava ugrađeni (ako je uključen) i korisnikov sopstveni rečnik, i spaja ih -
         * korisnikov unos pobeđuje kod sudara (namernije, konkretnije od ugrađenog). Poziva
         * se jednom po otvaranju dokumenta (iz loadText), ne po rečenici - jeftino je čak i
         * sa par hiljada unosa, nema potrebe za složenijim keširanjem. */
        suspend fun load(context: Context): PronunciationDictionary {
            return try {
                val map = LinkedHashMap<String, String>()
                val settings = AppSettings(context)
                if (settings.builtInPronunciationDictionaryEnabled) {
                    for ((word, replacement) in loadBuiltInRaw(context)) {
                        map[word.lowercase()] = replacement
                    }
                }
                val userEntries = AppDatabase.getInstance(context).pronunciationDao().getAll()
                for (entry in userEntries) map[entry.originalWord.lowercase()] = entry.replacement
                if (map.isEmpty()) EMPTY else PronunciationDictionary(map)
            } catch (_: Exception) {
                EMPTY
            }
        }
    }
}
