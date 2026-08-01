package com.recporec.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.recporec.app.databinding.ActivityHelpBinding
import java.io.File

class HelpActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHelpBinding

    private val helpText = """
        Ovo je aplikacija za čitanje dokumenata uz pomoć sinteze govora.
        Prvi ekran je lista dokumenata.
        Tu vidiš sve tvoje knjige.
        Dugme Dodaj dokument je desno, na sredini ekrana.
        Njime dodaješ knjigu sa telefona ili sa interneta.
        Dugme Izlaz je ispod njega.
        Zatvara aplikaciju potpuno, i prekida čitanje u pozadini.
        Pored svakog dokumenta je dugme Radnje.
        Njime pomeraš dokument gore ili dole na listi, ili ga brišeš.
        Gore desno je dugme Opcije.
        Tu su tri stavke: Opšta podešavanja glasa, Podešavanja, i Pomoć.
        Prva stavka je Opšta podešavanja glasa.
        Tu biraš jezik i glas za sve nove dokumente, i čuješ kratak primer svakog glasa kad ga dodirneš.
        Tu je i dugme Kombinovani glasovi.
        Njime biraš dva glasa koja se smenjuju dok čitaš.
        Na dnu tog ekrana je dugme Vrati na zadano.
        Vraća jezik, glas, brzinu, jačinu i visinu na početne vrednosti.
        Druga stavka je Podešavanja.
        Tu su prekidači za rad u pozadini, čitanje bez prekida, drmanje telefona za pauzu, zvuk dugmadi, pauze između rečenica i pasusa, i automatski prelazak na sledeći dokument.
        Prvi put kad otvoriš dokument, aplikacija može tražiti dodatnu dozvolu za stanje telefona.
        Nije obavezno, čitanje radi i bez toga.
        Dugme Navigacija bira šta rade dugmad za pomeranje unazad i unapred: stranicu, minute, ili oznaku.
        Na dnu ekrana je dugme Vrati na zadano, koje vraća sva ova podešavanja na početak.
        Treća stavka je Pomoć.
        Sada idemo u sam dokument.
        Tastatura ima devetnaest dugmića, raspoređenih u pet redova.
        Prvo dugme je Oznake.
        Njime dodaješ, uklanjaš ili brišeš sve oznake u dokumentu, a dug pritisak odmah dodaje oznaku na trenutno mesto.
        Drugo dugme je Idi na.
        Njime ideš na tačnu stranicu, minut ili oznaku, a dug pritisak vraća na sam početak dokumenta.
        Treće dugme je Pretraga.
        Pronalazi reč u dokumentu, a dug pritisak ponavlja poslednju pretragu.
        U drugom redu, prvo dugme smanjuje visinu glasa, a peto je povećava.
        Dug pritisak na bilo koje od njih vraća visinu na opštu vrednost.
        Drugo dugme je Prethodno poglavlje.
        Ide na početak prethodnog poglavlja, a dug pritisak ponavlja trenutno poglavlje od početka.
        Treće dugme je Tajmer.
        Otvara klizač od pet do sto dvadeset minuta za automatsku pauzu, a dug pritisak produžava već aktivan tajmer.
        Četvrto dugme je Sledeće poglavlje.
        Ide na sledeće poglavlje, a dug pritisak otvara spisak svih poglavlja za brz izbor.
        U trećem redu, prvo dugme smanjuje jačinu zvuka, a peto je povećava.
        Jačina i dug pritisak, koji je vraća na opštu vrednost, važe samo za ovaj dokument, ne za sam telefon.
        Drugo dugme je Jezik.
        Menja jezik za ovaj dokument, a dug pritisak poništava sve tvoje radnje.
        Npr: vraćanje unazad, odlazak na stranicu.
        Treće dugme je Glas.
        Menja glas za ovaj dokument, a dug pritisak otvara Odmori.
        Odmori pauzira čitanje odmah, i samo ga nastavlja posle izabranog vremena.
        Možeš izabrati broj minuta na klizaču, ili tačno vreme buđenja u polju Probudi me u, ali ne oba odjednom.
        Klizač ide od deset minuta do četiri sata.
        Polje Probudi me u: se upisuje u obliku sat i minut, na primer 5:20.
        Tri minuta pre kraja odmora, počinje da zvoni alarm.
        Ako u međuvremenu drmneš telefon ili nastaviš čitanje dugmetom, odmor i alarm se odmah prekidaju.
        Četvrto dugme je Kombinovani glasovi, isto kao u Opštim podešavanjima, samo za ovaj dokument.
        Dug pritisak na njega, produžava aktivan odmor, ili ga na kratko odlaže posle buđenja, najviše pet puta zaredom.
        Ako i tada pustiš knjigu pre produžetka, odmor je završen.
        Funkcija odmora radi pouzdanije ako u Podešavanjima uključiš čitanje bez prekida.
        U četvrtom redu, prvo dugme smanjuje brzinu čitanja, a treće je povećava.
        Dug pritisak na bilo koje od njih vraća brzinu na opštu vrednost.
        Drugo dugme pušta ili pauzira čitanje.
        Dug pritisak kaže naglas trenutni status: stranicu, poglavlje, proteklo i preostalo vreme, tajmer i odmor.
        U petom redu, prvo dugme ide na prethodni element, stranicu, minut ili oznaku, zavisno od Navigacije.
        Dug pritisak ponavlja trenutnu stranicu od početka.
        Drugo dugme je Podseti me.
        Vraća čitanje unazad za broj minuta koji izabereš na klizaču, a dug pritisak ponavlja poslednje korišćeno vreme.
        Treće dugme ide na sledeći element.
        Dug pritisak poništava tvoju poslednju radnju, koja god da je bila.
        Ispod tastature je veći klizač za lakši dodir.
        Pokazuje gde si u knjizi, i možeš ga prstom prevući na drugo mesto.
        Pri vrhu ekrana piše broj stranica, proteklo i preostalo vreme.
        Hvala ti što čitaš dokumente uz pomoć aplikacije: Reč po reč.
    """.trimIndent()



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.textHelpBody.text = helpText

        binding.btnCopyHelp.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Pomoć", helpText))
            Toast.makeText(this, "Tekst pomoći je kopiran.", Toast.LENGTH_SHORT).show()
        }

        binding.btnShareHelp.setOnClickListener { shareHelpAsTxt() }
    }

    private fun shareHelpAsTxt() {
        try {
            val shareDir = File(cacheDir, "share").apply { mkdirs() }
            val file = File(shareDir, "pomoc_rec_po_rec.txt")
            file.writeText(helpText)
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Podeli pomoć"))
        } catch (e: Exception) {
            Toast.makeText(this, "Deljenje trenutno nije uspelo.", Toast.LENGTH_SHORT).show()
        }
    }
}
