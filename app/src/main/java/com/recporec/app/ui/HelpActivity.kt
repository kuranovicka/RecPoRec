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
        Na dnu ekrana su četiri kartice: sve, novo, započeto i pročitano.
        Biraš koje knjige se prikazuju na listi.
        Dugme Dodaj dokument je desno, na sredini ekrana.
        Dodaje knjigu sa telefona, ili sa Google diska, OneDrive-a, Dropboxa i slično.
        Podržani formati su: txt, epub, pdf, docx, html, fb2, rtf, mobi, slikovni formati, i zvučni formati mp3, wav, ogg, flac i m4a.
        Dug pritisak izgovara statistiku čitanja, bez otvaranja novog ekrana:
        koliko si ukupno slušao-la, koliko knjiga si pročitao-la do kraja i koliko ih je u toku.
        Ispod njega je dugme Dodaj folder.
        Dodaje ceo folder sa zvučnim fajlovima kao jednu audio knjigu.
        Program ga pakuje u zip arhivu i prikazuje ga u listi kao i sve ostale knjige.
        Ispod njega je dugme Opšte radnje.
        Pojavljuje se samo kada ima dokumenata.
        Otvara opcije: odaberi sve, obriši odabrano, ili opozovi izbor.
        Dugme Napravi rezervnu kopiju, pravi kopiju svih dokumenata koje si dodao-la u program: sve fajlove, poziciju čitanja, i oznake.
        Dug pritisak vraća rezervnu kopiju iz fajla.
        Npr, izvezeš ih na Google disk, ili u telefon, pa ih nakon promene telefona vratiš.
        Nakon vraćanja, zipovana rezervna kopija se automatski briše.
        Dugme Izlaz je ispod toga.
        Zatvara aplikaciju potpuno i prekida čitanje u pozadini.
        Dug pritisak otkriva spoljni uređaj: Bluetooth slušalice, tastaturu, ili neki drugi fizički uređaj.
        Korisno ako nije uspelo automatsko povezivanje.
        Dug pritisak na dokument otvara njegove radnje: pomeri gore, pomeri dole, preimenuj, podeli, obriši.
        Ako slučajno obrišeš dokument, ili klikneš na Obriši sve, imaš nekoliko trenutaka da poništiš brisanje.
        Gore desno je dugme Opcije.
        Otvara tri stavke: Opšta podešavanja glasa, Podešavanja programa i Pomoć.
        Dug pritisak proverava sve dozvole potrebne aplikaciji i javlja ako neka nije data, kao pri prvom pokretanju.
        Prva stavka je Opšta podešavanja glasa.
        Ovde biraš jezik i glas, važi za sve dokumente.
        Čuješ kratak primer svakog glasa kad ga dodirneš.
        Tu je i dugme Kombinovani glasovi.
        Njime biraš dva glasa koja se smenjuju dok čitaš.
        Na dnu tog ekrana je dugme Vrati na zadano.
        Vraća baš sva podešavanja - glas, jezik, brzinu, jačinu, visinu i sve prekidače - na početne vrednosti.
        Druga stavka je Podešavanja programa.
        Tu su prekidači za: rad u pozadini, čitanje bez prekida, drmanje telefona za produžetak tajmera, zvuk dugmadi, pauze između rečenica i pasusa i automatski prelazak na sledeći dokument.
        Tu je i prekidač Automatski čitaj aktivni dokument.
        Kad ga uključiš, biraš jednu od dve mogućnosti:
        Automatsko čitanje pri otvaranju aplikacije, ili Pri otvaranju dokumenta.
        Tu je i prekidač za uključivanje ugrađenog rečnika.
        Za sad podržava samo srpski i hrvatski jezik.
        Dugme Rečnik izgovora, otvara upravljanje rečnikom.
        Aktiviranjem svake od ponuđene reči u listi, tu reč možeš izmeniti, ukloniti ili čuti kako zvuči njen izgovor.
        Takođe imaš mogućnost da neku reč pronađeš u pretrazi tako što upišeš stranu, a ne našu reč.
        Dugme Navigacija bira šta rade dugmad za pomeranje: stranicu, minute, oznaku ili rečenice.
        Ako biraš minute, biraš: 1, 5, 10.
        Ako izabereš rečenice, biraš: jednu, tri, pet ili deset.
        Tu su i dugmad Izvezi podešavanja u fajl, i Uvezi podešavanja iz fajla.
        Čuvaju glas, brzinu i sve prekidače u fajl, ili ih vraćaju iz ranije sačuvanog fajla.
        Ne čuvaju samu listu knjiga.
        Korisno pred promenu telefona.
        Na dnu ekrana je dugme Vrati na zadano.
        Isto kao i na prethodnom ekranu, vraća baš sva podešavanja na početak.
        Treća stavka je Pomoć.
        Sada idemo u sam dokument.
        Gore desno je opet dugme Opcije, isto kao na prvom ekranu.
        Pre svih podešavanja se nalaze podešavanja jezika, glasa, kombinovanih glasova, brzine, visine i jačine samo za taj dokument.
        Na kraju je dugme vrati na zadano, gde sve vraćaš na zadano, opet samo za taj, trenutno otvoreni dokument.
        Tastatura ima trinaest dugmadi, raspoređenih u četiri reda.
        Pored toga ima i tri dodatne kontrole koje ostaju uvek prisutne, čak i kad se ostala dugmad sakriju: Prikaži-sakrij kontrole, Pusti-pauziraj, i Nazad.
        U prvom redu odozgo, prvo dugme je Oznake.
        Dodaje, uklanja ili briše sve oznake u dokumentu, a dug pritisak odmah dodaje oznaku na trenutno mesto.
        Drugo dugme je Idi na.
        Vodi na tačnu stranicu, minut ili oznaku, a dug pritisak vraća na sam početak dokumenta.
        Treće dugme je Odaberi sve, a dug pritisak kopira odabrani tekst u privremenu memoriju.
        Četvrto dugme je Pretraga.
        Pronalazi reč u dokumentu, a dug pritisak ponavlja poslednju pretragu.
        U drugom redu, prvo dugme je Prethodno poglavlje.
        Vodi na početak prethodnog poglavlja, a dug pritisak ponavlja trenutno poglavlje od početka.
        Drugo dugme je Automatski listaj dokument.
        Biraš između stranice, oznaka, poglavlja ili minuta.
        Zatim odabereš da li dokument listaš unapred ili unazad.
        Tada kreće automatsko listanje.
        Čitač ekrana te obaveštava gde se trenutno nalaziš.
        Dugmetom play-pauza zaustavljaš listanje i čitaš od mesta na kome je listanje zaustavljeno.
        Treće dugme je Sledeće poglavlje.
        Vodi na sledeće poglavlje, a dug pritisak otvara spisak svih poglavlja za brz izbor.
        U trećem redu, prvo dugme je Tajmer.
        Otvara klizač od pet do sto dvadeset minuta, za automatski prekid čitanja.
        Minut pre isteka tajmera, dobijaš kratko zvučno upozorenje.
        Dug pritisak isključuje tajmer.
        Drugo dugme je Probudi me u tačno vreme.
        Upišeš tačno vreme, na primer 5:20, i knjiga te budi u to vreme.
        Kad dođe vreme za buđenje, alarm zvoni jedan minut.
        Na zaključanom ekranu se tada pojave dva dugmeta.
        Knjiga se nastavlja samo ako aktiviraš dugme Prekini buđenje.
        Ako ne reaguješ, ili aktiviraš dugme Spavaj još malo, alarm se ponovo oglašava posle deset minuta tišine, najviše pet puta.
        Ako u međuvremenu pritisneš dugme za nastavak, buđenje se odmah završava i knjiga kreće.
        Dug pritisak isključuje buđenje.
        Buđenje i zakazano čitanje su aktivni samo ako aplikacija radi u pozadini.
        Ako pritisneš dugme Izlaz, ove dve funkcije neće raditi.
        Ponovno pokretanje pamti samo gde nastavljaš knjigu.
        Treće dugme je Zakaži čitanje.
        Upišeš tačno vreme, na primer 5:20.
        Čitanje tada tiho krene, bez alarma i bez punog ekrana.
        Ako pustiš knjigu ranije, zakazano čitanje se prekida.
        U četvrtom redu, prvo dugme ide na prethodni element: stranicu, minut, oznaku ili rečenice, zavisno od Navigacije.
        Dug pritisak ponavlja trenutnu stranicu od početka, ili prethodne rečenice ako je Navigacija podešena na rečenice.
        Drugo dugme je Podseti me.
        Vraća čitanje unazad za broj minuta koji izabereš na klizaču, a dug pritisak ponavlja poslednje korišćeno vreme.
        Treće dugme ide na sledeći element.
        Dug pritisak poništava tvoju poslednju radnju, koja god da je bila.
        Ispod tastature su tri dugmeta koja ostaju prisutna u svim slučajevima, čak i kad se ostala dugmad sakriju.
        Prvo, samo, u svom redu, je dugme koje pokreće i pauzira čitanje.
        Dug pritisak kaže naglas trenutni status: stranicu, poglavlje, proteklo i preostalo vreme, info o tajmeru, preostalo vreme do buđenja ili do zakazanog čitanja.
        Ispod njega, jedno pored drugog, su Prikaži-sakrij kontrole levo i Nazad desno.
        Prikaži-sakrij kontrole sakriva skoro svu dugmad i statusne redove.
        Dug pritisak pauzira čitanje na 15 minuta.
        Minut pre kraja pauze ćeš čuti kratak zvučni signal.
        Ako ponovo dugo pritisneš ovo dugme, predah će trajati još 15 minuta.
        Nazad zatvara ovaj dokument i vraća te na listu dokumenata, bez prekidanja čitanja ako je aktivno u pozadini.
        Dug pritisak šalje aplikaciju u pozadinu, kao dugme Home na telefonu.
        Tada dokument ostaje otvoren.
        Na samom kraju, na dnu ekrana, je veći klizač za lakši dodir, preko celog ekrana.
        Pokazuje gde si u knjizi, i možeš ga prstom prevući na drugo mesto.
        Pri vrhu ekrana piše broj stranica, proteklo i preostalo vreme, informacije o buđenju i zakazanom čitanju.
        Dug pritisak na prečicu Reč po reč na početnom ekranu, pored uobičajenih sistemskih mogućnosti, nudi ti nastavak i pauziranje čitanja.
        Pauza-nastavak direktno otvara poslednji dokument i omogućava ti da ga pustiš ili pauziraš.
        Program podržava i rad sa fizičkim Bluetooth tastaturama, kao i sa slušalicama na dodir ili dugme.
        Dvostruki pritisak sa dva prsta na glavnom ekranu, takođe pauzira i nastavlja čitanje.
        Kada neki dokument ili knjigu želiš da podeliš, u opcijama će ti biti ponuđena i aplikacija Reč po reč.
        Program sada podržava i čitanje slikovnih datoteka.
        Radi tako što izvuče tekst iz slike i automatski ga izveze u txt.
        Aplikacija ponekad traži dodatne dozvole: za upravljanje pozivima, za neprekidno čitanje u pozadini, i za prikaz preko celog ekrana kod buđenja.
        Odobri sve dozvole pri prvom korišćenju, da kasnije program ne bi imao problem sa radom.
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
        binding.btnBack.setOnClickListener { finish() }
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
