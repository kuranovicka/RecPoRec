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
        Novi dokument se dodaje na kraj liste.

        Ispod tog dugmeta je dugme "Izlaz".
        Dodirni ga da potpuno zatvoriš aplikaciju.
        Tada se prekida i čitanje u pozadini.

        Pored svakog dokumenta je dugme "Radnje".
        Meni radnje ti omogućava da premeštaš i brišeš dokumente.
        Unutra su tri stavke: "Premesti nagore", "Premesti nadole" i "Obriši".
        "Obriši" trajno briše taj dokument i sav napredak čitanja.

        Gore desno je dugme "Opcije".
        Tu se nalaze tri stavke.

        Prva stavka je "Opšta podešavanja glasa".
        Tu biraš jezik, glas, brzinu, jačinu i visinu za sve nove dokumente.
        Prvo izaberi jezik.
        Onda izaberi glas.
        Dodirom na stavku na listi odmah se i bira i potvrđuje.
        Nema posebnog dugmeta za potvrdu.
        Ako dodirneš glas na listi, čućeš kratak primer teksta izgovoren tim glasom.
        Tako lakše prepoznaš koji glas ti se sviđa.

        Posle glasa je dugme "Kombinovani glasovi".
        Tu kombinuješ glasove za naizmenično čitanje.
        Dugme "Dodaj jezik" dodaje jezik za dodatne glasove.
        Može se dodati samo jedan dodatni jezik.
        Dugme "Dodaj glas" dodaje glas iz dodatih ili već odabranog jezika.
        Može se dodati samo jedan dodatni glas.
        Dugme "Ukloni jezik" i dugme "Ukloni glas," uklanjaju dodatni glas i jezik.
        Dugme "Broj rečenica po glasu" bira posle koliko rečenica se menja glas.
        Ako polje ostane prazno, svaki glas čita po jednu rečenicu.
        Isto dugme postoji i u svakom dokumentu, samo za taj dokument.
        Glasovi mogu biti i iz različitih govornih mehanizama, na primer Google i AlfaNum.
        Na dnu ovog ekrana je dugme "Vrati na zadano".
        Ono vraća jezik, glas, brzinu, jačinu, visinu i kombinovane glasove na podrazumevano stanje.
        Ne dira podešavanja pojedinačnih dokumenata.

        Druga stavka je "Podešavanja".
        Tu su prekidači i jedno dugme.
        Prekidač "Rad u pozadini" znači da čitanje ne prestaje kad umanjiš aplikaciju na početni ekran.
        Prekidač "Čitanje bez prekida" znači da čitanje ne prestaje ni kad se ekran ugasi.
        Prekidač "Prodrmaj telefon za pauzu i nastavak" znači da drmanje telefona pauzira ili nastavlja čitanje.
        Kad ga uključiš, biraš jačinu drmanja: blago, srednje ili jako.
        Čitanje se samo pauzira kad stigne telefonski poziv, i samo nastavi kad se poziv završi.
        Prvi put kad otvoriš dokument, aplikacija može da zatraži dodatnu dozvolu za stanje telefona, radi pouzdanije pauze pri pozivu.
        Nije obavezno, ne moraš prihvatiti.
        Program će i dalje pokušati da pauzira čitanje na uobičajen način.
        Prekidač "Zvuk" znači da čuješ tihi beep kad dodirneš dugmad.
        Sada možeš napraviti pauzu između dve rečenice.
        Prekidač "Pauza između rečenica" dodaje kratak predah između svake rečenice dok čita.
        Kad ga uključiš, pojavljuje se klizač.
        Biraš trajanje od 0 do 1000 milisekundi.
        Prekidač "Pauza između pasusa" dodaje duži predah na kraju svakog pasusa odlomka .
        Kad ga uključiš, pojavljuje se klizač.
        Biraš trajanje od 0 do 1000 milisekundi.
        Ako je uključena i pauza između rečenica i pauza između pasusa, na kraju pasusa se čuje samo pauza za pasus, ne obe zaredom.
        Obe pauze prepoznaju kraj rečenice pametno - tačka posle godine, na primer 1958., se ne računa kao kraj rečenice.
        Opcija "Pređi automatski na čitanje sledećeg dokumenta" ti omogućava da bez dodatnog klika pokreneš čitanje nove knjige ili dokumenta, čim čitanje prethodnog dokumenta bude završeno.
        Pre nego što novo čitanje počne, sačekaš kratko i čuješ zvučni signal.
        Dugme "Navigacija" bira šta rade dugmad za pomeranje unazad i unapred u dokumentu.
        Možeš izabrati: stranicu, jedan minut, pet minuta, deset minuta ili oznaku.
        Sva podešavanja se pamte za ceo program.
        Mesto gde staneš, brzina, jačina i glas ostaju zapamćeni za svaki dokument.
        Na dnu ovog ekrana je dugme "Vrati na zadano".
        Ono vraća sve prekidače i navigaciju na ovom ekranu na podrazumevano stanje.

        Treća stavka je "Pomoć".

        Sada idemo u sam dokument.
        Tastatura ima devetnaest dugmića.
        Podseća na numeričku tastaturu na računaru.

        Prvi red čine tri dugmeta.
        Prvo dugme je "Oznake".
        Ono otvara meni sa tri stavke.
        Prva stavka je "Dodaj oznaku".
        Ona postavlja oznaku na mesto na kome se trenutno nalaziš.
        Možeš upisati naziv oznake.
        Ako ne upišeš naziv, oznaka dobija broj, počevši od jedan.
        Druga stavka je "Ukloni oznaku".
        Ona prikazuje listu tvojih oznaka za ovaj dokument.
        Dodirneš oznaku sa liste, pa potvrdiš da je želiš obrisati.
        Ako nemaš nijednu oznaku, piše "Nema oznaka".
        Treća stavka je "Ukloni sve oznake".
        Tu samo potvrdiš, nema polja za unos.
        Drugo dugme je "Idi na".
        Ono otvara meni sa tri stavke.
        Prva stavka je "Idi na stranicu".
        Upišeš broj stranice.
        Druga stavka je "Idi na minut".
        Upišeš broj minuta od početka dokumenta.
        Treća stavka je "Idi na oznaku".
        Ona prikazuje listu tvojih oznaka za ovaj dokument.
        Dodirneš oznaku sa liste da odeš na nju.
        Ako nemaš nijednu oznaku, piše "Nema oznaka".
        Ova dugmad su tu, jer aplikacija pamti gde staneš, pa tako lako osvežiš čitanje.
        Pored njih je i dugme "Pretraga".
        Pretraga teksta ti omogućava da pronađeš neki pojam u dokumentu.
        Upišeš pojam koji tražiš.
        Dobijaš listu rezultata, svaki sa malo teksta oko pronađene reči.
        Dodirneš rezultat da odeš tačno na to mesto.
        Ako ništa nije pronađeno, piše "Nema rezultata".

        Drugi red ima pet dugmadi.
        Prvo dugme smanjuje visinu glasa.
        Drugo dugme je "Prethodno poglavlje".
        Ono vraća na početak prethodnog poglavlja.
        Dug pritisak na prethodno poglavlje, ponavlja poslednje poglavlje.
        Treće dugme je "Tajmer".
        Otvara klizač od pet do sto dvadeset minuta, po pet minuta.
        Dugme "Postavi" postavlja tajmer na izabran broj minuta.
        Dugme "Isključi" zaustavlja tajmer.
        Kad tajmer istekne, čitanje se pauzira samo, i tajmer se isključi.
        Pri vrhu ekrana uvek piše koliko je tajmeru ostalo, ili da nije aktivan.
        Četvrto dugme je "Sledeće poglavlje".
        Ono ide na početak sledećeg poglavlja.
        Poglavlja rade samo ako ih program prepozna u dokumentu.
        Peto dugme povećava visinu glasa.
        Dug pritisak na bilo koje od ova dva dugmeta vraća visinu ovog dokumenta na zadano.

        Treći red ima pet dugmadi.
        Prvo dugme smanjuje jačinu zvuka.
        Drugo dugme je "Jezik".
        Ono menja jezik samo za ovaj dokument.
        U toj listi je i stavka "Koristi opšti jezik", koja uklanja poseban izbor za ovaj dokument.
        Treće dugme je "Glas".
        Ono menja glas samo za ovaj dokument.
        U toj listi je i stavka "Koristi opšti glas", koja uklanja poseban izbor za ovaj dokument.
        Ta stavka uklanja i kombinovane glasove ovog dokumenta, ako ih ima.
        Dokument se posle toga u potpunosti oslanja na opšta podešavanja.
        Četvrto dugme je "Kombinovani glasovi", isto kao u Opštim podešavanjima, samo za ovaj dokument.
        Peto dugme pojačava jačinu zvuka.
        Jačina je vezana samo za trenutni dokument.
        Ne menja stvarnu jačinu zvuka telefona.
        Dug pritisak na bilo koje od ova dva dugmeta vraća jačinu ovog dokumenta na zadano.

        Četvrti red ima tri dugmeta.
        Prvo dugme smanjuje brzinu čitanja.
        Drugo dugme pušta ili pauzira čitanje.
        Dug pritisak na play dugme, ponavlja poslednju rečenicu.
        Treće dugme povećava brzinu čitanja.
        Brzina se menja bez promene visine glasa.
        Dug pritisak na bilo koje od dugmadi za brzinu vraća brzinu ovog dokumenta na zadano.
        Ako imaš slušalice ili tastaturu sa medijskim tasterima plej, pauza, premotavanje , oni takođe rade.
        Premotavanje radi isto kao dugmad za prethodnu/sledeću stavku iz petog reda.

        Peti red ima tri dugmeta.
        Prvo dugme pomera unazad.
        Šta tačno radi, zavisi od podešavanja "Navigacije".
        Ako je izabrana stranica, ideš na prethodnu stranicu.
        Ako je izabran minut, ideš unazad za jedan, pet ili deset minuta.
        Ako je izabrana oznaka, ideš tačno na prethodnu oznaku, ne na fiksnu udaljenost.
        Dug pritisak na navigaciono dugme za prethodni element, ponavlja poslednju stranicu.
        Drugo dugme je "Podseti me".
        Vraća vreme unazad za odabrani broj minuta.
        Otvara klizač od pet do sto dvadeset minuta, po pet minuta.
        Izabereš broj minuta i potvrdiš.
        Čitanje se odmah vrati unazad na odabrani broj minuta.
        Ovo je korisno kada se uspavaš.
        Treće dugme pomera dokument unapred, u zavisnosti od tipa navigacije: stranica, minuti, oznake.

        Ispod tastature je malo veći klizač, radi lakšeg dodira.
        On pokazuje gde si trenutno u knjizi.
        Možeš ga i prevući prstom do drugog mesta.

        Pri vrhu ekrana piše: koliko ukupno ima stranica, koliko je vremena prošlo i koliko je vremena preostalo do kraja knjige ili tajmera.

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
