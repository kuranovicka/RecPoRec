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
        Tu vidiš sve knjige koje dodaš.
        Dodirni dokument da ga otvoriš i čitaš.

        Dugme "Dodaj dokument" je desno, na sredini ekrana.
        Dodirni ga da izabereš da li dodaješ sa Google diska ili iz telefona.
        Podržani formati su: txt, epub, pdf, docx, html, fb2, rtf, mobi i azw.

        Ispod tog dugmeta je dugme "Izlaz".
        Dodirni ga da potpuno zatvoriš aplikaciju.
        Tada se prekida i čitanje u pozadini.

        Pored svakog dokumenta je dugme "Obriši".
        Ono trajno briše taj dokument i sav napredak čitanja.

        Gore desno je dugme "Opcije".
        Tu se nalaze tri stavke.

        Prva stavka je "Opšta podešavanja glasa".
        Tu biraš jezik, glas, brzinu i jačinu za sve nove dokumente.
        Prvo izaberi jezik.
        Onda izaberi glas.
        Kad dodirneš stavku na listi, ona još nije potvrđena.
        Dodirni dugme "Potvrdi" da sačuvaš izbor.
        Ako dodirneš glas na listi, čućeš kratak primer teksta izgovoren tim glasom.
        Tako lakše prepoznaš koji glas ti se sviđa.

        Druga stavka je "Podešavanja".
        Tu su prekidači i jedno dugme.
        Prekidač "Rad u pozadini" znači da čitanje ne prestaje kad umanjiš aplikaciju na početni ekran.
        Prekidač "Čitanje bez prekida" znači da čitanje ne prestaje ni kad se ekran ugasi.
        Prekidač "Prodrmaj telefon za pauzu i nastavak" znači da drmanje telefona pauzira ili nastavlja čitanje.
        Prekidač "Zvuk" znači da čuješ tihi beep kad dodirneš dugmad.
        Dugme "Navigacija" bira šta rade dugmad za pomeranje unazad i unapred u dokumentu.
        Možeš izabrati: stranicu, jedan minut, pet minuta ili deset minuta.

        Treća stavka je "Pomoć".
        To je baš ovaj ekran koji sada čitaš.

        Sada idemo u sam dokument.
        Tastatura ima osamnaest dugmića.
        Podseća na numeričku tastaturu na računaru.

        Na samom vrhu tastature su tri dugmeta.
        Prvo dugme je "Idi na početak".
        Ono vraća na sam početak dokumenta.
        Drugo dugme je "Idi na stranicu".
        Ono otvara polje gde upišeš broj stranice.
        Treće dugme je "Idi na Minut".
        Ono otvara polje gde upišeš broj minuta od početka, pa te odvede tamo.
        Ova dugmad su tu, jer aplikacija pamti gde staneš, pa tako lako osvežiš čitanje.

        Prvi red ima tri dugmeta.
        Prvo dugme je "Prethodno poglavlje".
        Ono vraća na početak prethodnog poglavlja.
        Drugo dugme je "Tajmer".
        Svaki dodir menja tajmer: petnaest minuta, trideset minuta, četrdeset pet minuta, šezdeset minuta, devedeset minuta, pa opet isključeno.
        Kad tajmer istekne, čitanje se pauzira samo.
        Treće dugme je "Sledeće poglavlje".
        Ono ide na početak sledećeg poglavlja.
        Poglavlja rade samo ako ih program prepozna u dokumentu.

        Drugi red ima četiri dugmeta.
        Prvo dugme je "Jezik".
        Ono menja jezik samo za ovaj dokument.
        Drugo dugme smanjuje jačinu zvuka.
        Treće dugme je "Glas".
        Ono menja glas samo za ovaj dokument.
        Četvrto dugme pojačava jačinu zvuka.

        Treći red ima tri dugmeta.
        Prvo dugme smanjuje brzinu čitanja.
        Drugo dugme pušta ili pauzira čitanje.
        Treće dugme povećava brzinu čitanja.
        Brzina se menja bez promene visine glasa.

        Četvrti red ima dva dugmeta.
        Prvo dugme pomera unazad.
        Drugo dugme pomera unapred.
        Šta znači "unazad" i "unapred", zavisi od podešavanja "Navigacije".

        Ispod tastature je malo veći klizač, radi lakšeg dodira.
        On pokazuje gde si trenutno u knjizi.
        Možeš ga i prevući prstom do drugog mesta.

        Pri vrhu ekrana piše: koliko ukupno ima stranica, koliko je vremena prošlo i koliko je vremena preostalo do kraja knjige.

        Gore desno u dokumentu je opet dugme "Opcije".
        Tu su "Podešavanja" i "Pomoć".

        Postoji i gest sa dva prsta.
        Dodirni ekran sa dva prsta odjednom, kratko.
        Taj gest pauzira ili nastavlja čitanje.
        Ovo pouzdano radi dok je aplikacija otvorena.
        Sa nekim čitačima ekrana radi i kad je aplikacija u pozadini, ali to nije garancija za svaki telefon.

        Sva podešavanja se pamte za ceo program.
        Mesto gde staneš, brzina, jačina i glas ostaju zapamćeni za svaki dokument.
        Naravno, ako promeniš ta podešavanja.

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
