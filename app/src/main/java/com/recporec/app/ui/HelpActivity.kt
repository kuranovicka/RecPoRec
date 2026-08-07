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
        Na dnu ekrana su tri kartice: sve, započete i pročitane knjige.
        Biraš koje knjige se prikazuju na listi.
        Dugme Dodaj dokument je desno, na sredini ekrana.
        Dodaje knjigu sa telefona, ili sa Google diska, OneDrive-a, Dropboxa i slično.
        Podržani formati su: txt, epub, pdf, docx, html, fb2, rtf i mobi.
        Dug pritisak proverava sve dozvole potrebne aplikaciji, i javlja ako neka nije data, kao pri prvom pokretanju.
        Ispod njega je dugme Opšte radnje.
        Pojavljuje se samo kada ima dokumenata.
        Otvara opcije: odaberi sve, obriši odabrano, ili opozovi izbor.
        Dugme Izlaz je ispod toga.
        Zatvara aplikaciju potpuno, i prekida čitanje u pozadini.
        Dug pritisak otkriva spoljni uređaj: Bluetooth slušalice, tastaturu, ili neki drugi fizički uređaj.
        Korisno ako nije uspelo automatsko povezivanje.
        Dug pritisak na dokument otvara njegove radnje: pomeri gore, pomeri dole, ili obriši.
        Gore desno je dugme Opcije.
        Otvara tri stavke: Opšta podešavanja glasa, Podešavanja i Pomoć.
        Dug pritisak izgovara statistiku čitanja, bez otvaranja novog ekrana: koliko si ukupno slušao-la, koliko knjiga si pročitao-la do kraja, i koliko ih je u toku.
        Prva stavka je Opšta podešavanja glasa.
        Tu biraš jezik i glas za sve nove dokumente.
        Čuješ kratak primer svakog glasa kad ga dodirneš.
        Tu je i dugme Kombinovani glasovi.
        Njime biraš dva glasa koja se smenjuju dok čitaš.
        Na dnu tog ekrana je dugme Vrati na zadano.
        Vraća jezik, glas, brzinu, jačinu i visinu na početne vrednosti.
        Druga stavka je Podešavanja.
        Tu su prekidači za: rad u pozadini, čitanje bez prekida, drmanje telefona za pauzu i nastavak, zvuk dugmadi, pauze između rečenica i pasusa, i automatski prelazak na sledeći dokument.
        Tu je i prekidač Automatski čitaj aktivni dokument.
        Kad ga uključiš, biraš jednu od dve mogućnosti: Pri otvaranju aplikacije, ili Pri otvaranju dokumenta.
        Prva opcija nastavlja čitanje poslednjeg dokumenta, čim otvoriš aplikaciju.
        Druga opcija počinje čitanje čim otvoriš bilo koji dokument.
        Dugme Navigacija bira šta rade dugmad za pomeranje: stranicu, minute, ili oznaku.
        Tu su i dugmad Izvezi podešavanja u fajl, i Uvezi podešavanja iz fajla.
        Čuvaju glas, brzinu i sve prekidače u fajl, ili ih vraćaju iz ranije sačuvanog fajla.
        Ne čuvaju samu listu knjiga.
        Korisno pred promenu telefona.
        Na dnu ekrana je dugme Vrati na zadano.
        Vraća sva ova podešavanja na početak.
        Treća stavka je Pomoć.
        Sada idemo u sam dokument.
        Dug pritisak na bilo koji dokument otvara opcije: premesti nagore, premesti nadole, preimenuj i obriši.
        Tastatura ima dvadeset dugmića, raspoređenih u pet redova.
        Pored toga ima i dve dodatne kontrole: Nazad i Prikaži-sakrij kontrole.
        U prvom redu odozgo, prvo dugme je Oznake.
        Dodaje, uklanja ili briše sve oznake u dokumentu, a dug pritisak odmah dodaje oznaku na trenutno mesto.
        Drugo dugme je Idi na.
        Vodi na tačnu stranicu, minut ili oznaku, a dug pritisak vraća na sam početak dokumenta.
        Treće dugme je Probudi me u tačno vreme.
        Upišeš tačno vreme, na primer 5:20, i knjiga te budi u to vreme.
        Statusna linija pri vrhu ekrana tada pokazuje koliko je u satima i minutima ostalo do buđenja.
        Kad dođe vreme za buđenje, alarm zvoni jedan minut.
        Na zaključanom ekranu se tada pojave dva dugmeta.
        Knjiga se nastavlja samo ako aktiviraš dugme Prekini buđenje.
        Ako ne reaguješ, ili aktiviraš dugme Spavaj još malo, alarm se ponovo oglašava posle deset minuta tišine, najviše pet puta.
        Ako u međuvremenu drmneš telefon, ili pritisneš dugme za nastavak, buđenje se odmah završava i knjiga kreće.
        Dug pritisak isključuje buđenje.
        Četvrto dugme je Pretraga.
        Pronalazi reč u dokumentu, a dug pritisak ponavlja poslednju pretragu.
        U drugom redu, prvo dugme smanjuje visinu glasa, a peto je povećava.
        Dug pritisak na bilo koje od njih vraća visinu na opštu vrednost.
        Drugo dugme je Prethodno poglavlje.
        Vodi na početak prethodnog poglavlja, a dug pritisak ponavlja trenutno poglavlje od početka.
        Treće dugme je Tajmer.
        Otvara klizač od pet do sto dvadeset minuta za automatski prekid čitanja, a dug pritisak produžava već aktivan tajmer.
        Minut pre isteka tajmera, dobijaš kratko zvučno upozorenje.
        Četvrto dugme je Sledeće poglavlje.
        Vodi na sledeće poglavlje, a dug pritisak otvara spisak svih poglavlja za brz izbor.
        U trećem redu, prvo dugme smanjuje jačinu zvuka, a peto je povećava.
        Jačina, kao i dug pritisak koji je vraća na opštu vrednost, važe samo za ovaj dokument, ne za sam telefon.
        Drugo dugme je Jezik.
        Menja jezik za ovaj dokument, a dug pritisak poništava sve tvoje radnje: na primer, vraćanje unazad, ili odlazak na stranicu.
        Treće dugme je Glas.
        Menja glas za ovaj dokument, a dug pritisak otvara Zakaži čitanje.
        Upišeš tačno vreme, na primer 5:20.
        Čitanje tada tiho krene, bez alarma i bez punog ekrana, kao da ručno pritisneš Play.
        Statusna linija tada pokazuje za koliko sati i minuta je čitanje zakazano.
        Ako pustiš knjigu ranije, zakazano čitanje se prekida.
        Četvrto dugme je Kombinovani glasovi, isto kao u Opštim podešavanjima, samo za ovaj dokument.
        Dug pritisak isključuje tajmer.
        Funkcija buđenja i zakazivanja radi pouzdanije ako u Podešavanjima uključiš čitanje bez prekida.
        Buđenje i zakazano čitanje su aktivni samo ako aplikacija radi u pozadini.
        Ako pritisneš dugme Izlaz, ove dve funkcije neće raditi.
        Ponovno pokretanje pamti samo gde nastavljaš knjigu.
        U četvrtom redu, prvo dugme smanjuje brzinu čitanja, a treće je povećava.
        Dug pritisak na bilo koje od njih vraća brzinu na opštu vrednost.
        Drugo dugme pušta ili pauzira čitanje.
        Dug pritisak kaže naglas trenutni status: stranicu, poglavlje, proteklo i preostalo vreme, tajmer, i preostalo vreme do buđenja ili do zakazanog čitanja.
        U petom redu, prvo dugme ide na prethodni element: stranicu, minut ili oznaku, zavisno od Navigacije.
        Dug pritisak ponavlja trenutnu stranicu od početka.
        Drugo dugme je Podseti me.
        Vraća čitanje unazad za broj minuta koji izabereš na klizaču, a dug pritisak ponavlja poslednje korišćeno vreme.
        Treće dugme ide na sledeći element.
        Dug pritisak poništava tvoju poslednju radnju, koja god da je bila.
        Ispod tastature je veći klizač za lakši dodir.
        Pokazuje gde si u knjizi, i možeš ga prstom prevući na drugo mesto.
        Desno od klizača je dugme Prikaži-sakrij kontrole.
        Sakriva svu dugmad i statusne redove, osim sebe, dugmeta Pokreni/Pauziraj čitanje, i dugmeta Nazad.
        Ono ostaje prisutno u svim slučajevima.
        Dug pritisak pauzira čitanje na 15 minuta.
        Minut pre kraja pauze ćeš čuti kratak zvučni signal.
        Ako ponovo dugo pritisneš ovo dugme, predah će trajati još 15 minuta.
        Za razliku od opcije Zakaži čitanje, ako pustiš knjigu ranije (dugmetom, drmanjem, ili tasterom na slušalicama), Kratak predah se prekida.
        Ispod klizača je dugme Nazad.
        Zatvara ovaj dokument i vraća te na listu dokumenata, bez prekidanja čitanja ako je aktivno u pozadini.
        Dug pritisak šalje aplikaciju u pozadinu, kao dugme Home na telefonu.
        Tada dokument ostaje otvoren.
        Kada ponovo pokreneš aplikaciju, vraćaš se tačno na taj dokument sa svim dugmadima, umesto da kreneš od liste dokumenata.
        Pri vrhu ekrana piše broj stranica, proteklo i preostalo vreme, i informacije o buđenju i zakazanom čitanju.
        Dug pritisak na prečicu programa na početnom ekranu, pored uobičajenih sistemskih mogućnosti, nudi ti nastavak i pauziranje čitanja.
        Pauza-nastavak direktno otvara poslednji dokument, i omogućava ti da ga direktno pustiš ili pauziraš.
        Program podržava i rad sa fizičkim Bluetooth tastaturama, kao i sa slušalicama na dodir ili dugme.
        Dvostruki pritisak sa dva prsta na glavnom ekranu, takođe pauzira i nastavlja čitanje.
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
