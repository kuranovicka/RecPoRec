package com.recporec.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.recporec.app.databinding.ActivityHelpBinding

class HelpActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHelpBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.textHelpBody.text = """
            RečPoReč — kako se koristi

            Lista dokumenata:
            Dodirnite "Dodaj dokument" da izaberete fajl sa telefona ili sa Google diska (podržani formati: TXT, EPUB, PDF, DOCX). Dodirom na dokument otvara se čitanje. Dugme "Obriši" pored svakog dokumenta trajno briše dokument i sav napredak čitanja.

            Tastatura u čitaču (12 dugmadi):

            Gornji red: prethodno poglavlje — tajmer za automatsku pauzu — sledeće poglavlje.
            Drugi red: jezik za ovaj dokument — smanji jačinu zvuka — izbor glasa — pojačaj zvuk.
            Treći red: smanji brzinu čitanja — pusti ili pauziraj — povećaj brzinu čitanja.
            Donji red: pomeri pet posto unazad — idi na određenu stranicu — pomeri pet posto unapred.

            Ispod tastature nalazi se klizač koji pokazuje vaš položaj u knjizi.

            Tajmer za automatsku pauzu (dugme u gornjem redu) menja se svakim dodirom: 15, 30, 45, 60, 90 minuta, pa se ponovo isključuje.

            Pri vrhu ekrana ispisano je: ukupan broj stranica, proteklo vreme čitanja i procenjeno preostalo vreme do kraja knjige.

            Podešavanja (gore desno):
            - Rad u pozadini: čitanje se nastavlja i kada napustite aplikaciju.
            - Čitanje bez prekida: čitanje se nastavlja i kada se ekran telefona isključi.
            - Prodrmaj telefon za pauzu i nastavak: drmanjem telefona pauzirate ili nastavljate čitanje.

            Sve se pamti automatski: mesto gde ste stali, brzina, jačina zvuka i izabrani glas — sve dok ne obrišete dokument.

            Opšta podešavanja glasa (meni na listi dokumenata, gore desno):
            Ovde se bira podrazumevani jezik, glas, brzina i jačina za sve nove dokumente. Dugme jezika u samom dokumentu menja jezik samo za taj dokument.

            Izbor jezika i glasa: prvo se izabere stavka sa liste (može i pretraga), pa se dodirne "Potvrdi" — dok se ne potvrdi, izmena se ne primenjuje, i to jasno piše na ekranu.
        """.trimIndent()
    }
}
