package com.recporec.app.tts

import android.content.Context
import com.recporec.app.data.AppDatabase
import com.recporec.app.data.AppSettings
import com.recporec.app.data.DocumentEntity
import com.recporec.app.parser.ParsedDocument
import com.recporec.app.service.WakeAlarmReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drži jedinu instancu TtsManager-a i trenutno otvoren dokument,
 * dostupno i Activity-ju i pozadinskom servisu.
 *
 * VAŽNO: ovaj objekat SAM čuva poziciju u dokumentu (i vreme čitanja) u bazu,
 * nezavisno od toga da li je ReaderActivity trenutno otvorena. Ranije je to
 * radila sama Activity, pa bi se čuvanje pozicije prekinulo čim korisnik
 * ode na listu dokumenata ili potpuno izađe dok se čita u pozadini.
 */
object PlaybackController {

    var ttsManager: TtsManager? = null
        private set

    /** "Audio motor" - ExoPlayer za audio knjige, paralelno sa ttsManager (TTS motor za
     * tekstualne knjige). U svakom trenutku aktivan je NAJVIŠE JEDAN od njih (u zavisnosti
     * od formata trenutno otvorenog dokumenta). Trenutno se samo drži ovde (nullable) - sama
     * reprodukcija (kreiranje, playlist, pozicija) dolazi u sledećem koraku; do tada ostaje
     * uvek null, pa isActive() ispod ostaje potpuno nepromenjenog ponašanja. */
    var audioPlayer: androidx.media3.exoplayer.ExoPlayer? = null
        private set

    private var _currentDocument: DocumentEntity? = null

    /** Kad se ovo polje promeni na DRUGI dokument (razlicit id) DOK nesto trenutno cita,
     * SAMO polje se pobrine da se stara reprodukcija zaustavi - bez obzira odakle je promena
     * dosla (rucno otvaranje, automatsko citanje, auto-prelazak...). Ovo je centralizovana,
     * pouzdanija zastita umesto da se ista provera ponavlja (i eventualno propusti) na svakom
     * pojedinacnom mestu koje otvara dokument. Azuriranja POZICIJE (npr. currentDocument =
     * currentDocument?.copy(currentCharacterOffset = x)) zadrzavaju ISTI id, pa ovo NIKAD ne
     * remeti obicno, cesto azuriranje toka citanja. */
    var currentDocument: DocumentEntity?
        get() = _currentDocument
        set(value) {
            val oldId = _currentDocument?.id
            val newId = value?.id
            if (oldId != null && newId != null && oldId != newId && isActive()) {
                pauseEngine()
            }
            _currentDocument = value
        }

    /** "Generacija" zahteva za otvaranje dokumenta - raste svaki put kad se BILO KOJI
     * dokument POCNE da se otvara (ReaderActivity.onCreate). Koristi se da se sprece "stare",
     * nadmasene ucitane sesije da pobede - ako je npr. Pera otvoren, pa Marko odmah zatim
     * (Pera-in ekran ostaje "ziv" u pozadini dok se otvara Marko), a Perino asinhrono
     * ucitavanje se iz nekog razloga zavrsi POSLE Markovog, Pera NE SME da "pregazi" Markovo
     * vec postavljeno stanje - pobeduje uvek NAJNOVIJI zahtev, bez obzira koji redom zavrsi. */
    private var latestLoadRequestId: Long = 0L

    /** Poziva SVAKI put kad se neki ekran za citanje TEK POCINJE da otvara dokument (pre bilo
     * kakvog asinhronog rada) - vraca "token" koji treba proslediti u commitLoadIfCurrent() na
     * kraju, da se zna da li je taj konkretan zahtev jos uvek najnoviji. */
    fun beginLoadRequest(): Long {
        latestLoadRequestId += 1
        return latestLoadRequestId
    }

    /** Da li je dati token JOS UVEK najnoviji zahtev za otvaranje - false znaci da je u
     * medjuvremenu neki DRUGI (noviji) dokument pocet da se otvara, i da ovaj (stariji)
     * poziv treba tiho da odustane bez upisivanja bilo cega u deljeno stanje. */
    fun isLoadRequestCurrent(token: Long): Boolean = token == latestLoadRequestId
    var parsedDocument: ParsedDocument? = null
    var elapsedSeconds: Long = 0

    /** Preostale sekunde tajmera za automatsku pauzu (0 = nije aktivan). Živi ovde (ne u
     * ReaderActivity) da bi preživeo ako Android uništi i ponovo napravi ekran za čitanje
     * dok app radi u pozadini - inače bi se tajmer "restartovao" na isključeno. */
    var timerRemainingSeconds: Int = 0
        private set

    var uiTimerExpiredListener: (() -> Unit)? = null

    /** ReadingService se prijavi ovde dok radi u pozadini, da bi osvežio notifikaciju kad
     * se stanje čitanja promeni bilo odakle (dugme, tajmer, automatska pauza pri pozivu...). */
    var playbackStateListener: (() -> Unit)? = null

    fun notifyPlaybackStateChanged() {
        playbackStateListener?.invoke()
    }

    fun setTimerMinutes(minutes: Int) {
        timerRemainingSeconds = if (minutes <= 0) 0 else minutes * 60
    }

    /** Produžava VEĆ AKTIVAN tajmer za dati broj minuta, bez resetovanja - ako tajmer nije
     * aktivan (0), ne radi ništa (poziva se samo ako je caller već proverio da je aktivan). */
    fun extendTimerMinutes(minutes: Int) {
        if (timerRemainingSeconds <= 0) return
        timerRemainingSeconds += minutes * 60
    }

    /** "Probudi"/"Zakaži čitanje" - PAUZIRA čitanje ODMAH, i SAMO NASTAVLJA u tačno
     * određeno vreme, bez ikakve akcije korisnice. */
    var restRemainingSeconds: Int = 0
        private set
    private var restAlarmTone: android.media.ToneGenerator? = null
    private var restWakeLock: android.os.PowerManager.WakeLock? = null

    private fun stopRestAlarm() {
        restAlarmTone?.release()
        restAlarmTone = null
    }

    /** Dok knjiga aktivno čita, telefon prirodno ostaje "budan" zbog same reprodukcije zvuka.
     * Ali TOKOM odmora se ništa ne čuje (čitanje je pauzirano) - bez ovog "wake lock-a",
     * telefon bi mogao da utone u dubok san (Doze) čim se ekran zaključa, AKO "Čitanje bez
     * prekida" nije uključeno u Podešavanjima - i tad bi se ceo brojač odmora zaustavio,
     * jer procesor prosto ne bi radio. Ovo drži procesor budnim SAMO tokom odmora, bez obzira
     * na to opšte podešavanje - odmor bi trebalo da radi pouzdano uvek. */
    private fun acquireRestWakeLock() {
        val ctx = appContext ?: return
        try {
            releaseRestWakeLock()
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "RecPoRec:Rest")
            wl.setReferenceCounted(false)
            wl.acquire(25 * 60 * 60 * 1000L) // najviše 25h zaštita - buđenje/zakazivanje moze biti i skoro ceo dan unapred
            restWakeLock = wl
        } catch (_: Exception) { }
    }

    private fun releaseRestWakeLock() {
        try {
            restWakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) { }
        restWakeLock = null
    }

    private var restIsWakeTime = false
    /** "Zakaži čitanje" - kao Probudi, ali BEZ ikakvog alarma/punog ekrana - čitanje prosto
     * tiho krene u zakazano vreme, kao da si sama pritisla Play. */
    private var restSuppressAlarm = false
    /** Razlikuje "Kratak predah" (dug pritisak na Kontrole) od običnog "Zakaži čitanje" -
     * oba dele isti restSuppressAlarm mehanizam, ali se RAZLIČITO ponašaju na ručno
     * nastavljanje (dugme/drmanje/medijski taster): Kratak predah se TIME prekida (to je i
     * poenta kratke pauze - "gotova sam sa predahom"), dok "Zakaži čitanje"/"Probudi me"
     * namerno ostaju aktivni i posle ručnog nastavka (drugi slučaj upotrebe - unapred
     * zakazano vreme koje treba da važi bez obzira na to šta se dešava u međuvremenu). */
    var restIsQuickBreak: Boolean = false
        private set
    /** Apsolutan trenutak (SystemClock.elapsedRealtime) kad odmor treba da se završi -
     * koristi se da se preostalo vreme računa SVAKI PUT iznova prema pravom satu, umesto da
     * se prosto oduzima "jedan" svake sekunde (što bi se s vremenom nakupilo u kašnjenje ako
     * bilo koja petlja potraje i malo duže od tačno jedne sekunde). */
    private var restTargetElapsedMillis: Long = 0L

    const val WAKE_ALARM_REQUEST_CODE = 4271

    /** Zakazuje PRAVI, sistemski alarm (AlarmManager.setAlarmClock) tacno u trenutak kad
     * "Probudi" treba da zazvoni - IZUZET od Doze/uspavljivanja u pozadini, isti
     * mehanizam koji koriste prave budilnik aplikacije. Nas unutrasnji ticker/wake lock i
     * dalje rade normalno, ovo je REZERVNI, pouzdaniji nacin da se garantuje da ce puni
     * ekran stvarno iskociti, cak i ako telefon (npr. pojedini Samsung modeli) agresivno
     * throttle-uje obicne senzore/dugmad u pozadini. */
    private fun scheduleWakeAlarm(targetElapsedMillis: Long) {
        val ctx = appContext ?: return
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val nowElapsed = android.os.SystemClock.elapsedRealtime()
            val triggerWallTime = System.currentTimeMillis() + (targetElapsedMillis - nowElapsed)
            val receiverIntent = android.content.Intent(ctx, WakeAlarmReceiver::class.java)
            val receiverPendingIntent = android.app.PendingIntent.getBroadcast(
                ctx, WAKE_ALARM_REQUEST_CODE, receiverIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val showIntent = android.app.PendingIntent.getActivity(
                ctx, WAKE_ALARM_REQUEST_CODE,
                android.content.Intent(ctx, com.recporec.app.ui.WakeAlarmActivity::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            am.setAlarmClock(
                android.app.AlarmManager.AlarmClockInfo(triggerWallTime, showIntent),
                receiverPendingIntent
            )
        } catch (_: Exception) {
            // Bezbedno ako ne uspe - unutrasnji ticker i dalje pokusava normalno.
        }
    }

    private fun cancelWakeAlarm() {
        val ctx = appContext ?: return
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val receiverIntent = android.content.Intent(ctx, WakeAlarmReceiver::class.java)
            val receiverPendingIntent = android.app.PendingIntent.getBroadcast(
                ctx, WAKE_ALARM_REQUEST_CODE, receiverIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(receiverPendingIntent)
        } catch (_: Exception) {
        }
    }

    /** Poziva ga WakeAlarmReceiver kad sistemski alarm stvarno zazvoni - "budi" nasu
     * unutrasnju logiku ako iz nekog razloga jos nije sama preskocila u fazu zvonjenja. */
    fun onWakeAlarmFired(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        // Ako je proces u medjuvremenu ubijen (npr. budjenje zakazano daleko unapred, pa je
        // Android sam ugasio app dok je cekala) - AlarmManager ce ipak probuditi proces i
        // pozvati OVU funkciju, ali ttsManager (i sve sto uz njega ide) ce biti PRAZNO. Bez
        // ovoga bi budjenje TIHO ne uradilo nista - startFromOffset() na null motoru je no-op.
        ensureInitialized(context)
        if (currentDocument == null) {
            // "Sastavi nazad" iz sacuvanog stanja (prezivelo je gasenje procesa, za razliku
            // od obicnih in-memory polja) - retko se desava (samo ako je Android STVARNO
            // ubio proces dok se cekalo, npr. preko cele noci), pa asinhrona rekonstrukcija
            // ovde ne smeta.
            val settings = com.recporec.app.data.AppSettings(context)
            val docId = settings.pendingWakeDocumentId
            if (docId > 0) {
                restIsWakeTime = settings.pendingWakeIsWakeTime
                restSuppressAlarm = settings.pendingWakeSuppressAlarm
                restRemainingSeconds = 0
                restAlarmActive = false
                scope.launch {
                    val db = AppDatabase.getInstance(context)
                    val entity = withContext(Dispatchers.IO) { db.documentDao().getById(docId) } ?: return@launch
                    val parsedDoc = try {
                        withContext(Dispatchers.IO) {
                            com.recporec.app.parser.DocumentParser.parse(context, android.net.Uri.parse(entity.uri), entity.format)
                        }
                    } catch (_: Exception) {
                        return@launch
                    }
                    parsedDocument = parsedDoc
                    currentDocument = entity
                    val tts = ttsManager ?: return@launch
                    withContext(Dispatchers.Default) { tts.loadText(parsedDoc.fullText) }
                    tts.syncPositionOnly(entity.currentCharacterOffset)
                    // KRITICNO: motor je OVDE tek napravljen (ensureInitialized iznad), pa je
                    // skoro sigurno JOS UVEK asinhrono "u pripremi" (TextToSpeech.onInit jos
                    // nije stigao) - poziv startFromOffset() pre toga bi TIHO ne izgovorio
                    // nista (isti "tihi neuspeh" koji je citav ovaj commit trebalo da resi,
                    // samo prebacen ovde). Isti obrazac kao svugde drugde u kodu: sacekaj
                    // isEngineReady/onReady pre nego sto se nastavi.
                    if (tts.isEngineReady) {
                        onWakeAlarmFired(context) // sad kad je currentDocument popunjen, nastavi normalno ispod
                    } else {
                        tts.onReady = { onWakeAlarmFired(context) }
                    }
                }
                return
            }
        }
        if (restRemainingSeconds <= 0 && !restAlarmActive) {
            if (restSuppressAlarm) {
                // "Zakaži čitanje" - bez alarma, samo tiho krece.
                stopRestAlarm()
                releaseRestWakeLock()
                cancelWakeAlarm()
                // Servis (drzi drmanje aktivnim) se pokrece PRE pocetka citanja, ne posle -
                // pokretanje servisa nije trenutno (par stotina ms do onStartCommand/registracije
                // senzora), pa bi drmanje odmah po pocetku citanja moglo tiho da ne uspe da se
                // registruje na vreme.
                ensureBackgroundServiceRunning()
                val offset = currentDocument?.currentCharacterOffset
                resumeEngine(offset)
                notifyPlaybackStateChanged()
            } else {
                pauseEngine()
                restAlarmActive = true
                restAlarmSecondsLeft = ALARM_RING_SECONDS
                acquireRestWakeLock()
                notifyPlaybackStateChanged()
            }
        }
    }

    /** Redosled: prvo odbrojavanje (restRemainingSeconds), pa kad stigne do nule, alarm
     * ZVONI PUN MINUT (restAlarmActive), a citanje JOS UVEK ne pocinje. Tek kad alarm
     * prestane (istekne minut, ili se rucno prekine drmanjem/dugmetom), citanje krece. Dok
     * alarm zvoni, "Produzi odmor" radi kao "odlozi" (snooze) - dodaje jos 10 minuta umesto
     * da knjiga uopste krene. */
    private var restAlarmActive = false
    private var restAlarmSecondsLeft = 0
    private const val ALARM_RING_SECONDS = 60
    private val SNOOZE_SECONDS = 10 * 60
    /** Koliko puta je odmor ODLOŽEN zaredom (bez novog, ručno postavljenog odmora između) -
     * kad stigne do MAX_SNOOZE_COUNT, dalje odlaganje se ne dozvoljava, čitanje samo
     * nastavlja. Vraća se na nulu čim se odmor iznova postavi, ili čim se ručno prekine
     * (cancelRest, npr. preko dugmeta ili drmanja). */
    private var restSnoozeCount = 0
    private val MAX_SNOOZE_COUNT = 5

    /** "Probudi" - odmor do TAČNO određenog vremena, SA alarmom (ponavlja se ako se ne
     * prekine, do pet puta) - namenjeno za buđenje. */
    fun startRestUntil(totalSeconds: Int) {
        pauseEngine()
        stopRestAlarm()
        restRemainingSeconds = if (totalSeconds <= 0) 0 else totalSeconds
        restIsWakeTime = totalSeconds > 0
        restSuppressAlarm = false
        restIsQuickBreak = false
        restAlarmActive = false
        restSnoozeCount = 0
        if (totalSeconds > 0) {
            restTargetElapsedMillis = android.os.SystemClock.elapsedRealtime() + restRemainingSeconds * 1000L
            acquireRestWakeLock()
            scheduleWakeAlarm(restTargetElapsedMillis)
            persistPendingWake(true, false)
        } else {
            releaseRestWakeLock()
            cancelWakeAlarm()
            appContext?.let { com.recporec.app.data.AppSettings(it).clearPendingWake() }
        }
        notifyPlaybackStateChanged()
    }

    /** "Zakaži čitanje" - kao Probudi, ALI bez ikakvog alarma - čitanje tiho krene u
     * zakazano vreme, kao da si sama pritisla Play. Koristi isti pouzdan sistemski alarm za
     * BUĐENJE PROCESA (da se garantovano pokrene čak i uz Doze/pozadinsko throttlovanje),
     * samo bez zvuka i punog ekrana kad taj trenutak stigne. */
    fun startScheduledReading(totalSeconds: Int, isQuickBreak: Boolean = false) {
        pauseEngine()
        stopRestAlarm()
        restRemainingSeconds = if (totalSeconds <= 0) 0 else totalSeconds
        restIsWakeTime = false
        restSuppressAlarm = totalSeconds > 0
        restIsQuickBreak = totalSeconds > 0 && isQuickBreak
        restAlarmActive = false
        restSnoozeCount = 0
        if (totalSeconds > 0) {
            restTargetElapsedMillis = android.os.SystemClock.elapsedRealtime() + restRemainingSeconds * 1000L
            acquireRestWakeLock()
            scheduleWakeAlarm(restTargetElapsedMillis)
            persistPendingWake(false, true)
        } else {
            releaseRestWakeLock()
            cancelWakeAlarm()
            appContext?.let { com.recporec.app.data.AppSettings(it).clearPendingWake() }
        }
        notifyPlaybackStateChanged()
    }

    /** Produžava VEĆ AKTIVAN predah/zakazano čitanje za DODATNIH N minuta na PREOSTALO vreme -
     * isti obrazac kao extendTimerMinutes() za Tajmer (sabira, ne resetuje na svežih N). Za
     * razliku od restRemainingSeconds (koje se svaki tick IZNOVA računa iz
     * restTargetElapsedMillis), mora se pomeriti sam CILJ u budućnost - inače bi sledeći tick
     * odmah "pojeo" ovo produženje. */
    fun extendScheduledReadingMinutes(minutes: Int) {
        if (restRemainingSeconds <= 0 || !restSuppressAlarm) return
        restTargetElapsedMillis += minutes * 60 * 1000L
        restRemainingSeconds += minutes * 60
        scheduleWakeAlarm(restTargetElapsedMillis)
        persistPendingWake(false, true)
        notifyPlaybackStateChanged()
    }

    /** Cuva dovoljno podataka da se budjenje/zakazano citanje moze "sastaviti nazad" cak i
     * ako Android u medjuvremenu ubije ceo proces dok se ceka (npr. preko noci) - obicna
     * in-memory polja (restRemainingSeconds i slicna) bi se u tom slucaju izgubila zajedno sa
     * procesom, iako AlarmManager i dalje pouzdano budi novi proces na vreme. */
    private fun persistPendingWake(isWakeTime: Boolean, suppressAlarm: Boolean) {
        val ctx = appContext ?: return
        val docId = currentDocument?.id ?: return
        val settings = com.recporec.app.data.AppSettings(ctx)
        settings.pendingWakeDocumentId = docId
        settings.pendingWakeTargetElapsedMillis = restTargetElapsedMillis
        // Wall-clock verzija istog trenutka - jedina koja prezivljava restart telefona
        // (elapsedRealtime se resetuje na 0 posle restarta). Koristi je BootRescheduleReceiver.
        settings.pendingWakeTargetWallMillis = System.currentTimeMillis() +
            (restTargetElapsedMillis - android.os.SystemClock.elapsedRealtime())
        settings.pendingWakeIsWakeTime = isWakeTime
        settings.pendingWakeSuppressAlarm = suppressAlarm
    }

    fun cancelRest() {
        restRemainingSeconds = 0
        restIsWakeTime = false
        restSuppressAlarm = false
        restIsQuickBreak = false
        restAlarmActive = false
        restSnoozeCount = 0
        stopRestAlarm()
        releaseRestWakeLock()
        cancelWakeAlarm()
        appContext?.let { com.recporec.app.data.AppSettings(it).clearPendingWake() }
    }

    /** Nastavlja čitanje, i AKO je odmor (ili alarm koji zvoni posle njega) trenutno aktivan,
     * prvo ga prekida (isključi alarm, oslobodi wake lock) - koristi se SVUDA gde čitanje
     * može ručno da se nastavi (dugme, drmanje...), da bi ponašanje bilo dosledno bez obzira
     * kojim putem korisnica nastavi. */
    fun resumeCancelingRestIfNeeded(): Boolean {
        // Kao i dugme Play/Pauza: JEDINO "Probudi me u" (restIsWakeTime) dok JOS NIJE
        // zazvonio ostaje netaknut - drmanje/medijski taster ovde samo pokrecu citanje odmah,
        // budjenje ostaje aktivno za svoje pravo vreme. Sve ostalo (alarm koji vec zvoni,
        // "Zakazi citanje", "Kratak predah") se OTKAZUJE - i "Zakazi citanje" pretpostavlja
        // da neko vreme neces biti tu da rucno pustis, pa rucno pustanje znaci da ta
        // pretpostavka vise ne vazi.
        val shouldCancel = restAlarmActive || (restSuppressAlarm && restRemainingSeconds > 0)
        if (shouldCancel) cancelRest()
        // Drmanje i medijski taster su TAKODJE svesna, rucna radnja korisnice (kao i dugme
        // Play/Pauza) - resetuje se ista zastavica, da automatsko citanje kasnije ispravno
        // zna da korisnica VISE nije "pauzirala i ostavila to tako".
        appContext?.let { com.recporec.app.data.AppSettings(it).userManuallyPaused = false }
        // Ako motor JOS UVEK nije spreman (npr. drmanje odmah posle PRAVOG hladnog pokretanja,
        // dok se dokument tek "priprema" u pozadini - Pri otvaranju dokumenta rezim) -
        // pokusaj tiho ne bi uradio nista (chunks prazni). Umesto da samo odustanemo,
        // sacekamo kratko i pokusamo ponovo, do par puta - dovoljno da priprema stigne da
        // zavrsi, bez potrebe da korisnica sama shvati da treba da drmne opet.
        val engineReady = if (currentDocument?.format == "audio") audioPlayer != null else ttsManager?.isEngineReady == true
        if (!engineReady || currentDocument == null) {
            // Sprecava da svako naredno drmanje (dok jos nije spremno) pokrene JOS JEDAN,
            // preklapajuci pokusaj cekanja - vise takvih istovremeno moglo je da izazove
            // nepredvidivo ponasanje (npr. dupli pokusaji nastavka u istom trenutku).
            if (!isRetryingShakeResume) {
                isRetryingShakeResume = true
                scope.launch {
                    repeat(10) {
                        delay(300)
                        val readyNow = if (currentDocument?.format == "audio") audioPlayer != null else ttsManager?.isEngineReady == true
                        if (readyNow && currentDocument != null) {
                            isRetryingShakeResume = false
                            resumeCancelingRestIfNeeded()
                            return@launch
                        }
                    }
                    isRetryingShakeResume = false
                }
            }
            return false
        }
        // VAŽNO: servis (drži drmanje i medijski taster aktivnim) se pokreće PRE početka
        // čitanja, ne posle - pokretanje servisa nije trenutno (par stotina ms do
        // onStartCommand/registracije senzora), pa bi drmanje odmah po nastavku čitanja
        // moglo tiho da ne uspe da se registruje na vreme. Radi se OVDE (drmanje, medijski
        // taster, buđenje/odmor) umesto preko dugmeta u čitaču - dugme SAMO takođe (ponovo)
        // pokreće ReadingService. Bez ovoga, ako servis iz bilo kog razloga nije radio,
        // drmanje ostaje "gluvo" dok se ručno ne otvori app i pritisne dugme (što baš to
        // radi) - primećeno posle buđenja/odmora.
        ensureBackgroundServiceRunning()
        // Eksplicitno kreni od SAČUVANE pozicije (kao dugme Play/Pauza u čitaču), umesto da
        // se osloni na unutrašnji indeks rečenice u TtsManager-u - pouzdanije, posebno posle
        // duže pauze (odmor/buđenje) kad taj indeks moze da se razidje sa stvarno sacuvanom
        // pozicijom.
        val offset = currentDocument?.currentCharacterOffset
        resumeEngine(offset)
        // KRITICNO, CENTRALIZOVANO OVDE (ne kod svakog pozivaoca): bez ovoga MediaSession
        // ostaje "zaglavljena" na starom stanju dok neka DRUGA, rucna akcija (npr. dodir na
        // dugme u citacu) to prvi put ne osvezi - objasnjava prijave da dvoprst/slusalice ne
        // rade odmah posle drmanja, budjenja, ili dugmeta "Prekini budjenje", ali PROORADE
        // cim se JEDNOM rucno pauzira/nastavi preko dugmeta (koje ovo vec ispravno radi).
        // Stavljeno OVDE, unutar deljene funkcije, umesto kod svakog pojedinacnog pozivaoca -
        // sada su svi pozivaoci (drmanje, medijski taster, "Prekini budjenje", ponovni
        // pokusaj posle hladnog starta) automatski pokriveni, bez oslanjanja da svaki od njih
        // to setno ne zaboravi.
        notifyPlaybackStateChanged()
        return shouldCancel
    }

    fun isSnoozeAvailable(): Boolean = restAlarmActive && restSnoozeCount < MAX_SNOOZE_COUNT

    fun isRestAlarmRinging(): Boolean = restAlarmActive

    /** Da li je TRENUTNO aktivan odmor bas "buđenje" (Probudi me u, ili odlaganje koje je iz
     * njega nastalo) - koristi ga dugme "Isključi buđenje", da ne bi slučajno prekinulo
     * klasičan odmor postavljen preko klizača umesto buđenja. */
    fun isWakeUpActive(): Boolean = (restRemainingSeconds > 0 || restAlarmActive) && restIsWakeTime

    /** Da li je TRENUTNO aktivno "Zakaži čitanje" (bez alarma) - koristi ga statusna linija
     * da razlikuje ovo od buđenja, koje ima drugačiju poruku. */
    fun isScheduledReadingActive(): Boolean = restRemainingSeconds > 0 && restSuppressAlarm

    /** Dug pritisak na "Kombinovani glasovi": ili produžava VEĆ AKTIVAN odmor za POSLEDNJI
     * korišćen broj minuta (klizač), ILI, ako alarm TRENUTNO zvoni (odmor upravo istekao) i
     * nismo dostigli MAX_SNOOZE_COUNT, "odlaže" (snooze) za tačno 10 minuta, umesto da knjiga
     * uopšte krene. Vraća true ako je nešto urađeno, false ako nema šta (caller prikazuje
     * odgovarajuću poruku prema ovome). */
    /** Deljena logika za "odlaganje" (snooze) - deset minuta tišine pre sledećeg pokušaja,
     * koristi je i ručno "Spavaj još malo" (WakeAlarmActivity) i automatsko ponavljanje kad
     * se alarm ne prekine. */
    private fun snoozeInternal() {
        stopRestAlarm()
        restAlarmActive = false
        restRemainingSeconds = SNOOZE_SECONDS
        restTargetElapsedMillis = android.os.SystemClock.elapsedRealtime() + restRemainingSeconds * 1000L
        restSnoozeCount += 1
        acquireRestWakeLock()
        scheduleWakeAlarm(restTargetElapsedMillis)
        // Azuriraj i sacuvano vreme (koje "prezivljava" gasenje procesa) - u suprotnom bi
        // rekonstrukcija posle eventualnog gasenja procesa TOKOM odlaganja koristila STARO,
        // vec proslo vreme.
        appContext?.let {
            val settings = com.recporec.app.data.AppSettings(it)
            if (settings.pendingWakeDocumentId > 0) {
                settings.pendingWakeTargetElapsedMillis = restTargetElapsedMillis
            }
        }
        notifyPlaybackStateChanged()
    }

    fun extendRest(): Boolean {
        if (!restAlarmActive || !isSnoozeAvailable()) return false
        snoozeInternal()
        return true
    }

    /** UI (ReaderActivity) se ovde prikači dok je vidljiva, da dobija živo ažuriranje. */
    var uiPositionListener: ((Int) -> Unit)? = null
    var uiFinishedListener: (() -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var appContext: Context? = null
    private var tickerStarted = false
    private var isRetryingShakeResume = false

    // Iste vrednosti kao u ReaderActivity - drzi korak navigacije dosledan bez obzira da li
    // je pokrenut sa dugmeta u citacu ili sa medijskog tastera.
    private const val CHARS_PER_PAGE = 1800
    private const val BASE_CHARS_PER_MINUTE = 800f

    fun ensureInitialized(context: Context) {
        appContext = context.applicationContext
        if (ttsManager == null) {
            ttsManager = TtsManager(context.applicationContext)
            wireCallbacks()
        }
        startTickerIfNeeded()
    }

    private fun wireCallbacks() {
        val tts = ttsManager ?: return
        tts.onPositionChanged = { offset ->
            scope.launch {
                currentDocument = currentDocument?.copy(currentCharacterOffset = offset)
                persistCurrentDocument()
                uiPositionListener?.invoke(offset)
            }
        }
        tts.onFinished = {
            // Poslednja recenica ne pomera poziciju do STVARNOG kraja teksta (samo do
            // pocetka te poslednje recenice) - bez ovoga, kartica "Procitane" nikad ne bi
            // prepoznala knjigu kao zavrsenu, jer bi currentCharacterOffset uvek ostajao
            // malo ISPOD totalCharacters.
            val totalLen = parsedDocument?.length
            if (totalLen != null && totalLen > 0) {
                currentDocument = currentDocument?.copy(currentCharacterOffset = totalLen)
                persistCurrentDocument()
            }
            scope.launch { uiFinishedListener?.invoke() }
            handleAutoAdvance()
        }
        tts.onAutoPaused = { notifyPlaybackStateChanged() }
        tts.onAutoResumed = { notifyPlaybackStateChanged() }
    }

    /** Kad se dokument do kraja pročita i uključeno je "Pređi automatski na sledeći",
     * nakon kratke pauze (i zvučnog signala) prelazi na sledeći dokument u listi. Živi ovde
     * (ne u ReaderActivity) da bi radilo i kad je čitanje u pozadini, van otvorenog ekrana. */
    /** Kratak "bip" (isti kao za dodir dugmadi) koji najavljuje automatski prelazak na novi
     * dokument, PRE nego sto citanje pocne - koristi se svugde gde se citanje automatski
     * prebacuje sa jednog dokumenta na drugi (auto-prelazak, automatsko citanje pri
     * otvaranju app-e/dokumenta, nastavak toka pri rucnom prebacivanju). Postuje isti
     * prekidac "Zvuk" iz Podesavanja kao i dugmad. */
    /** Osigurava da pozadinski servis (drzi drmanje i medijski taster aktivnim) radi - koristi
     * se SVUDA gde citanje moze automatski da krene/nastavi (budjenje, zakazano citanje,
     * odmor...), da drmanje ne bi ostalo "gluvo" ako servis iz bilo kog razloga nije vec
     * pokrenut u tom trenutku. */
    private fun ensureBackgroundServiceRunning() {
        val ctx = appContext ?: return
        try {
            val settings = com.recporec.app.data.AppSettings(ctx)
            if (settings.backgroundEnabled) {
                com.recporec.app.service.ReadingService.start(ctx)
            }
        } catch (_: Exception) {
        }
    }

    fun playTransitionSound(context: Context) {
        try {
            if (!com.recporec.app.data.AppSettings(context).soundFeedbackEnabled) return
            val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 70)
            tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 60)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tone.release() }, 200)
        } catch (_: Exception) {
        }
    }

    /** Gasi pozadinski servis (i time wake lock/wifi lock, ako je "Citanje bez prekida"
     * ukljuceno) kad citanje STVARNO nema sta vise da radi - knjiga zavrsena, i nema
     * sledeceg dokumenta (ili je automatski nastavak iskljucen). Bez ovoga bi servis (i
     * eventualni wake lock, do 12h) ostajao aktivan i posle kraja knjige bez ikakve svrhe.
     * Bezbedno je zaustaviti servis ovde - dugme Play, drmanje i medijski taster ga svi
     * ionako sami ponovo pokrecu pre nego sto krenu da citaju (vidi ensureBackgroundServiceRunning). */
    private fun stopBackgroundServiceIfIdle() {
        val ctx = appContext ?: return
        // Bezbednosna provera - ne diramo ako je odmor/budjenje ipak nekako u toku (ne bi
        // trebalo da se desi odmah po zavrsetku knjige, ali sigurnije je proveriti).
        if (restRemainingSeconds > 0 || restAlarmActive) return
        try {
            com.recporec.app.service.ReadingService.stop(ctx)
        } catch (_: Exception) {
        }
    }

    private fun handleAutoAdvance() {
        val ctx = appContext ?: return
        val settings = com.recporec.app.data.AppSettings(ctx)
        if (!settings.autoNextDocumentEnabled) {
            stopBackgroundServiceIfIdle()
            return
        }
        val currentDocId = currentDocument?.id ?: return
        scope.launch {
            delay(1000)
            val list = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(ctx).documentDao().observeAllOnce()
            }
            val currentIndex = list.indexOfFirst { it.id == currentDocId }
            val next = if (currentIndex in 0 until list.size - 1) list[currentIndex + 1] else null
            if (next == null) {
                android.widget.Toast.makeText(
                    ctx, "Ovo je poslednji dokument u listi.", android.widget.Toast.LENGTH_LONG
                ).show()
                stopBackgroundServiceIfIdle()
                return@launch
            }
            playTransitionSound(ctx)

            // Android 10+ NE dozvoljava pokretanje novog ekrana iz pozadine (bez otvorenog
            // ekrana ove app) - taj pokusaj bi tiho promasio kad je citanje u pozadini, van
            // otvorene app. Zato citanje sledeceg dokumenta pokrecemo OVDE, direktno, bez
            // ikakvog ekrana - a ako korisnica kasnije otvori app, ReaderActivity ce prepoznati
            // da je citanje vec u toku i samo se prikaci na njega.
            loadAndPlayDocumentInBackground(ctx, next.id, beginLoadRequest())

            // I dalje pokusavamo da otvorimo ekran, za slucaj da app JESTE u prvom planu kad
            // ovo stigne (npr. korisnica gleda listu dokumenata) - ako sistem to blokira jer
            // je app stvarno u pozadini, ovo se bezbedno preskace (citanje je vec pokrenuto
            // gore, bez obzira na ovo).
            try {
                val intent = android.content.Intent(ctx, com.recporec.app.ui.ReaderActivity::class.java).apply {
                    putExtra(com.recporec.app.ui.ReaderActivity.EXTRA_DOCUMENT_ID, next.id)
                    putExtra(com.recporec.app.ui.ReaderActivity.EXTRA_AUTOPLAY, true)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                ctx.startActivity(intent)
            } catch (_: Exception) {
                // Ocekivano kad je app u pozadini - citanje je vec pokrenuto gore bez obzira.
            }
        }
    }

    /** Ucitava dokument i pokrece citanje BEZ potrebe za ikakvim otvorenim ekranom - koristi
     * se za automatski prelazak na sledeci dokument dok je citanje u pozadini. Namerno je
     * odvojena od ReaderActivity.loadDocument()/setupTts() (ne deli kod sa njima) da bi
     * ostala potpuno nezavisna od Activity zivotnog ciklusa, i da eventualna izmena ovde ne
     * rizikuje da pokvari dobro isprobanu putanju kad korisnica sama otvori knjigu. */
    /** "Automatski čitaj aktivni dokument", opcija "Pri otvaranju aplikacije" - pronalazi
     * poslednji aktivni (već započet, ali nezavršen) dokument i pokreće ga u pozadini, BEZ
     * ulaska u čitač - korisnica ostaje na spisku dokumenata dok čitanje počne da se čuje. */
    /** @param autoPlay Ako je false, dokument se samo UCITA i pozicija uskladi (spreman za
     * drmanje/dugme), ali se NE cuje odmah - koristi se za "Pri otvaranju dokumenta" rezim,
     * gde citanje treba da bude SPREMNO cim se app otvori, ali ne treba samo da pocne da
     * cita (to je posao DRUGOG rezima, "Pri otvaranju aplikacije"). */
    fun autoResumeLastActiveDocument(context: Context, autoPlay: Boolean = true) {
        val loadToken = beginLoadRequest()
        scope.launch {
            if (isActive()) return@launch // vec nesto cita, ne diramo
            // KRITICNO: ako je BUDJENJE ili ZAKAZANO CITANJE trenutno aktivno (odbrojava, ili
            // alarm vec zvoni), NIKAKO ne diramo nista ovde - ta funkcija ima SVOJ, odvojen
            // tok upravljanja (AlarmManager, WakeAlarmActivity...), i ako bismo ovde pripremili
            // ili pustili dokument, to bi prekinulo/pomerilo tu vec aktivnu, casovnikom
            // vodjenu sekvencu (npr. korisnica bi ocekivala da je citanje jos uvek "cekanje na
            // budjenje", a mi bismo ga preranije pokrenuli ili pomerili poziciju).
            if (restRemainingSeconds > 0 || restAlarmActive) return@launch
            // VAZNO: rucna pauza NIKAD ne sme da spreci PRIPREMU (ucitavanje teksta/pozicije u
            // motor) - samo GLASNO automatsko pustanje. Ranije je ovo bio raniji "return@launch"
            // koji je PRESKACAO CELU pripremu kad je autoPlay=true i korisnica rucno pauzirala -
            // posledica je bila da NISTA nije bilo ucitano u motor, pa drmanje NIJE imalo sta
            // da nastavi (ostajalo "gluvo") dok korisnica sama rucno ne otvori neki dokument.
            // Drmanje MORA uvek da radi, bez obzira na rucnu pauzu ili nacin na koji je citanje
            // poslednji put zaustavljeno.
            val effectiveAutoPlay = autoPlay && !com.recporec.app.data.AppSettings(context).userManuallyPaused
            val db = AppDatabase.getInstance(context)
            // Preferiraj dokument koji je STVARNO aktivan u ovoj sesiji (ono sto je
            // korisnica poslednje otvorila/citala) - pouzdanije od pretrage po vremenu u
            // bazi, koje je moglo da "zaostane" na stariji dokument u nekim slucajevima.
            // Tek ako trenutno NEMA nikakvog aktivnog dokumenta (npr. svez pokrenut proces),
            // potrazi u bazi poslednji aktivni.
            val activeId = currentDocument?.id
            val entity = if (activeId != null) {
                withContext(Dispatchers.IO) { db.documentDao().getById(activeId) }
            } else {
                withContext(Dispatchers.IO) { db.documentDao().getLastActiveDocument() }
            } ?: return@launch
            // Ako je taj dokument u medjuvremenu zavrsen, ne pokrecemo ga ponovo automatski.
            if (entity.totalCharacters > 0 && entity.currentCharacterOffset >= entity.totalCharacters) return@launch
            // VAZNO: ako je BAS TAJ dokument VEC pripremljen (motor spreman, isti id) - NE
            // ponavljamo pripremu iznova. Ovaj ekran (DocumentListActivity) se vraca u prvi
            // plan cesto (svaki put kad se korisnica vrati na spisak, ne samo pri pravom
            // otvaranju app-e), i bez ove provere bi svaki takav povratak IZNOVA ucitavao
            // tekst u motor - sto je moglo da prekine/pomeri VEC pripremljeno, pauzirano
            // stanje i ostavi drmanje "gluvim" dok se ponovni pokusaj priprema ne zavrsi.
            if (activeId == entity.id && ttsManager?.isEngineReady == true) {
                ensureBackgroundServiceRunning()
                return@launch
            }
            // Ako je korisnica U MEDJUVREMENU vec rucno otvorila neki dokument (npr. odmah
            // pri otvaranju app-e dodirnula knjigu pre nego sto je ovo stiglo da zavrsi),
            // tiho odustajemo - njen rucni izbor uvek pobedjuje.
            if (!isLoadRequestCurrent(loadToken)) return@launch
            if (effectiveAutoPlay) playTransitionSound(context)
            ensureInitialized(context)
            // VAZNO: servis (drzi drmanje i medijski taster aktivnim) se pokrece PRE ucitavanja
            // i pocetka citanja, ne posle - bez ovoga bi citanje moglo da se gasi posle SVEGA
            // JEDNE recenice (Android nema razlog da drzi pozadinski rad zivim bez foreground
            // servisa), a i drmanje bi moglo tiho da ne stigne da se registruje na vreme.
            // Servis se pokrece UVEK (ne samo kad je effectiveAutoPlay) - drmanje treba da
            // bude spremno u svakom slucaju.
            ensureBackgroundServiceRunning()
            loadAndPlayDocumentInBackground(context, entity.id, loadToken, effectiveAutoPlay)
        }
    }

    private suspend fun loadAndPlayDocumentInBackground(
        context: Context, documentId: Long, loadToken: Long? = null, autoPlay: Boolean = true
    ) {
        val db = AppDatabase.getInstance(context)
        val entity = withContext(Dispatchers.IO) { db.documentDao().getById(documentId) } ?: return
        val settings = com.recporec.app.data.AppSettings(context)

        val parsedDoc = try {
            withContext(Dispatchers.IO) {
                com.recporec.app.parser.DocumentParser.parse(context, android.net.Uri.parse(entity.uri), entity.format)
            }
        } catch (_: Exception) {
            return // ne mozemo da ucitamo - citanje ostaje na starom dokumentu, korisnica ce videti kad otvori app
        }

        val charsPerPage = 1800
        val totalPages = kotlin.math.max(1, (parsedDoc.length + charsPerPage - 1) / charsPerPage)
        // Azuriramo i lastOpenedTimestamp OVDE, i to VEC "upeceno" u finalEntity (ne samo u
        // bazi) - isti razlog kao u ReaderActivity.loadDocument(): kasnije periodicno cuvanje
        // pozicije upisuje CEO entitet, i bez ovoga bi vratilo staru vrednost nazad.
        val now = System.currentTimeMillis()
        val finalEntity = (if (entity.totalPages != totalPages || entity.totalCharacters != parsedDoc.length) {
            entity.copy(totalPages = totalPages, totalCharacters = parsedDoc.length)
        } else entity).copy(lastOpenedTimestamp = now)
        withContext(Dispatchers.IO) {
            db.documentDao().update(finalEntity)
        }

        // Ako je u medjuvremenu neki NOVIJI zahtev za otvaranje pocet (npr. korisnica je
        // rucno otvorila drugu knjigu dok je ovo jos trajalo), odustajemo PRE upisa u
        // deljeno stanje - noviji zahtev uvek pobedjuje.
        if (loadToken != null && !isLoadRequestCurrent(loadToken)) return

        parsedDocument = parsedDoc
        currentDocument = finalEntity
        elapsedSeconds = finalEntity.elapsedSeconds

        val tts = ttsManager ?: return

        val combined = resolveCombinedVoiceConfigInBackground(
            db, finalEntity.id,
            finalEntity.voiceName ?: settings.globalVoiceName,
            finalEntity.voiceEngine ?: settings.globalVoiceEngine,
            settings
        )
        val effectiveVoiceName = combined?.voices?.first()?.voiceName ?: (finalEntity.voiceName ?: settings.globalVoiceName)
        val effectiveEngine = combined?.voices?.first()?.enginePackage ?: (finalEntity.voiceEngine ?: settings.globalVoiceEngine)
        val effRate = finalEntity.speechRate.let { if (it > 0f) it else settings.globalSpeechRate }
        val effPitch = finalEntity.pitch.let { if (it > 0f) it else settings.globalPitch }
        val effVolume = finalEntity.volumePercent.let { if (it >= 0) it else settings.globalVolumePercent }

        suspend fun applyVoiceTextAndPlay() {
            withContext(Dispatchers.Default) { tts.loadText(parsedDoc.fullText) }
            tts.setSpeechRate(effRate)
            tts.setPitch(effPitch)
            tts.setVolume(effVolume / 100f)
            tts.sentencePauseMs = if (settings.sentencePauseEnabled) settings.sentencePauseMs.toLong() else 0L
            tts.paragraphPauseMs = if (settings.paragraphPauseEnabled) settings.paragraphPauseMs.toLong() else 0L
            if (effectiveVoiceName != null) tts.setVoiceByName(effectiveVoiceName) else tts.applyIndependentDefaultVoice()
            if (combined != null) tts.setCombinedVoices(combined.voices, combined.sentencesPerVoice) else tts.setCombinedVoices(emptyList(), 1)
            if (autoPlay) {
                tts.startFromOffset(finalEntity.currentCharacterOffset)
                // KRITICNO: ovaj put (automatski nastavak "Pri otvaranju aplikacije") je do
                // sad pokretao citanje BEZ da ikad obavesti MediaSession o novom stanju -
                // isti tip propusta koji smo vec resile za dodir na dugme/medijske tastere,
                // samo se ovde provukao neopazen (dodir na ekranu radio je odmah, jer je
                // togglePlayPause() vec imao ovaj poziv - ali AUTOMATSKI pokrenuto citanje
                // pri otvaranju app-e nikad nije proslo kroz taj kod). Bez ovoga, MediaSession
                // ostaje "zaglavljena" na starom (npr. pauzirano/nepoznato) stanju sve dok
                // neka DRUGA, rucna akcija to prvi put ne osvezi - objasnjava korisnicku
                // prijavu: "dvoprst ne radi posle automatskog pokretanja, ali radi cim rucno
                // pauziram/nastavim".
                notifyPlaybackStateChanged()
            } else {
                // Samo "pripremi" dokument (ucitaj tekst/glas, uskladi poziciju) BEZ da
                // pocne da cita - koristi se kad "Pri otvaranju dokumenta" nema da automatski
                // pusti zvuk, ali app i dalje treba da bude SPREMNA da nastavi na drmanje bez
                // potrebe da korisnica prvo rucno otvori taj dokument.
                tts.syncPositionOnly(finalEntity.currentCharacterOffset)
            }
        }

        if (effectiveEngine != null && effectiveEngine != tts.currentEnginePackage) {
            val done = kotlinx.coroutines.CompletableDeferred<Unit>()
            tts.switchEngine(effectiveEngine, effectiveVoiceName, effRate) {
                scope.launch { applyVoiceTextAndPlay(); done.complete(Unit) }
            }
            done.await()
        } else if (tts.isEngineReady) {
            applyVoiceTextAndPlay()
        } else {
            val done = kotlinx.coroutines.CompletableDeferred<Unit>()
            tts.onReady = { scope.launch { applyVoiceTextAndPlay(); done.complete(Unit) } }
            done.await()
        }
    }

    private data class BgCombinedVoiceConfig(val voices: List<com.recporec.app.tts.CombinedVoiceRef>, val sentencesPerVoice: Int)

    /** Isto kao ReaderActivity.resolveCombinedVoiceConfig, samo bez zavisnosti od Activity-ja -
     * kombinovani glasovi za dokument imaju prednost nad opštim. */
    private suspend fun resolveCombinedVoiceConfigInBackground(
        db: AppDatabase, docId: Long, docRegularVoiceName: String?, docRegularEngine: String?,
        settings: com.recporec.app.data.AppSettings
    ): BgCombinedVoiceConfig? {
        val dao = db.combinedVoiceDao()
        suspend fun resolveForScope(scopeId: Long, mergeVoiceName: String?, mergeEngine: String?): BgCombinedVoiceConfig? {
            val explicit = dao.getVoices(scopeId)
            if (explicit.isEmpty()) return null
            val refs = mutableListOf<com.recporec.app.tts.CombinedVoiceRef>()
            if (mergeVoiceName != null && mergeEngine != null && explicit.none { it.voiceName == mergeVoiceName }) {
                refs.add(com.recporec.app.tts.CombinedVoiceRef(mergeEngine, mergeVoiceName))
            }
            refs.addAll(explicit.map { com.recporec.app.tts.CombinedVoiceRef(it.voiceEngine, it.voiceName) })
            if (refs.size < 2) return null
            val count = dao.getSettings(scopeId)?.sentencesPerVoice ?: 1
            return BgCombinedVoiceConfig(refs, count)
        }
        // Ako je dokument SVESNO diran (postoji red u combined_voice_settings, cak i ako je
        // trenutno prazan) - ne prelazi na opste, postuje se ono sto dokument kaze (moze biti
        // "nema kombinacije za ovaj dokument", namerno). Samo NIKAD-DIRAN dokument nasledjuje
        // opste - PRAVO nasledjivanje: koristi se OPSTI redovni glas za spajanje (ne dokumentov
        // eventualno drugaciji glas), da nasledjena kombinacija bude verna opstoj, ne mesavina.
        val docTouched = dao.getSettings(docId) != null
        return if (docTouched) {
            resolveForScope(docId, docRegularVoiceName, docRegularEngine)
        } else {
            resolveForScope(0L, settings.globalVoiceName, settings.globalVoiceEngine)
        }
    }

    private fun startTickerIfNeeded() {
        if (tickerStarted) return
        tickerStarted = true
        scope.launch {
            var tick = 0
            while (true) {
                delay(1000)

                // "Odmori" broji STVARNO proteklo vreme, ne vreme dok knjiga aktivno cita -
                // suprotno je od tajmera po prirodi, jer knjiga bas TOKOM odmora ne cita.
                // Racuna se iz APSOLUTNOG cilja (ne oduzimanjem "jedan" svake sekunde) - da se
                // ne bi nakupilo kasnjenje ako neka petlja potraje i malo duze od tacno jedne
                // sekunde (primetljivo tek posle vise sati, ali baš to je i prijavljeno).
                if (restRemainingSeconds > 0) {
                    val remainingMillis = restTargetElapsedMillis - android.os.SystemClock.elapsedRealtime()
                    val previousRemaining = restRemainingSeconds
                    restRemainingSeconds = (remainingMillis / 1000).toInt().coerceAtLeast(0)
                    // Obavestenje (notifikacija) se NE osvezava svake sekunde sama od sebe -
                    // samo na promenu stanja. Bez ovoga bi "Citanje pauzirano na X minuta." u
                    // notifikaciji ostalo zamrznuto na pocetnoj vrednosti ceo predah, umesto da
                    // prati isti odbrojavajuci broj kao statusna linija u citacu. Osvezava se
                    // SAMO kad predjeni broj CELIH minuta stvarno promeni (ne svake sekunde) -
                    // dovoljno cesto koliko se i prikazuje (minuti, ne sekunde).
                    if (restSuppressAlarm && (previousRemaining + 59) / 60 != (restRemainingSeconds + 59) / 60) {
                        notifyPlaybackStateChanged()
                    }
                    // Minut pre isteka - isto kratko DVOSTRUKO upozorenje kao kod Tajmera, ali
                    // SAMO za "Zakazi citanje"/"Kratak predah" (restSuppressAlarm) - kod
                    // "Probudi me" ne treba, jer je sam alarm vec dovoljno upozorenje.
                    if (restRemainingSeconds == 60 && restSuppressAlarm) {
                        val warnTone = android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100)
                        warnTone.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
                        delay(400)
                        warnTone.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
                        delay(300)
                        warnTone.release()
                    }
                    if (restRemainingSeconds <= 0) {
                        restRemainingSeconds = 0
                        if (restSuppressAlarm) {
                            // "Zakaži čitanje" - bez alarma, tiho krece. AKO korisnica u
                            // medjuvremenu VEC rucno cita (dozvoljeno otkad Play vise ne
                            // otkazuje zakazano citanje), NE diramo je - vec cita, nema potrebe
                            // da se ponovo (re)pokrene od sacuvane pozicije.
                            stopRestAlarm()
                            releaseRestWakeLock()
                            cancelWakeAlarm()
                            if (!isActive()) {
                                ensureBackgroundServiceRunning()
                                val offset = currentDocument?.currentCharacterOffset
                                resumeEngine(offset)
                            }
                            notifyPlaybackStateChanged()
                        } else {
                            // Odbrojavanje je isteklo - NE nastavlja citanje odmah, prvo pun
                            // minut zvoni alarm (kao pravi budilnik), a tek posle toga knjiga
                            // krece. AKO korisnica u medjuvremenu VEC rucno cita (dozvoljeno
                            // otkad Play vise ne otkazuje zakazano budjenje), pauziramo je PRE
                            // nego sto alarm pocne da zvoni - inace bi se glas i alarm culi
                            // istovremeno.
                            pauseEngine()
                            restAlarmActive = true
                            restAlarmSecondsLeft = ALARM_RING_SECONDS
                            acquireRestWakeLock()
                            notifyPlaybackStateChanged()
                        }
                    }
                } else if (restAlarmActive) {
                    if (restAlarmTone == null) {
                        restAlarmTone = android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100)
                    }
                    restAlarmTone?.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600)
                    restAlarmSecondsLeft -= 1
                    if (restAlarmSecondsLeft <= 0) {
                        if (restSnoozeCount < MAX_SNOOZE_COUNT) {
                            // Bez reakcije - program pretpostavlja da se jos spava, i SAM
                            // odlaze za deset minuta (isto kao rucno "Produzi odmor"), do
                            // najvise MAX_SNOOZE_COUNT puta - isti budzet, isto ponasanje,
                            // bilo automatski ili rucno.
                            snoozeInternal()
                        } else {
                            restAlarmActive = false
                            stopRestAlarm()
                            releaseRestWakeLock()
                            cancelWakeAlarm()
                            // Ovde je bilo zaboravljeno (za razliku od svih ostalih mesta gde se
                            // odmor/budjenje zavrsava) - bez ovoga bi sacuvano stanje budjenja
                            // ostalo "na cekanju" u podesavanjima i posle automatskog odustajanja.
                            appContext?.let { com.recporec.app.data.AppSettings(it).clearPendingWake() }
                            ensureBackgroundServiceRunning()
                            resumeEngine()
                            notifyPlaybackStateChanged()
                        }
                    }
                }

                if (isActive()) {
                    elapsedSeconds += 1
                    currentDocument = currentDocument?.copy(elapsedSeconds = elapsedSeconds)
                    tick++
                    if (tick % 5 == 0) persistCurrentDocument()

                    if (timerRemainingSeconds > 0) {
                        timerRemainingSeconds -= 1
                        if (timerRemainingSeconds == 60) {
                            // Minut pre isteka - kratko, DVOSTRUKO upozorenje (ne razvuceno kao
                            // pravi alarm kod Odmori, samo dovoljno da se primeti dok se cita).
                            val warnTone = android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100)
                            warnTone.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
                            delay(400)
                            warnTone.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
                            delay(300)
                            warnTone.release()
                        }
                        if (timerRemainingSeconds <= 0) {
                            timerRemainingSeconds = 0
                            pauseEngine()
                            notifyPlaybackStateChanged()
                            // Kad istekne, tajmer se prosto iskljuci - jednostavno, kao pre.
                            currentDocument = currentDocument?.copy(timerMinutes = 0)
                            persistCurrentDocument()
                            uiTimerExpiredListener?.invoke()
                        }
                    }
                }
            }
        }
    }

    private fun persistCurrentDocument() {
        val ctx = appContext ?: return
        val doc = currentDocument ?: return
        scope.launch(Dispatchers.IO) {
            try {
                AppDatabase.getInstance(ctx).documentDao().update(doc)
            } catch (_: Exception) { }
        }
    }

    /** Ručno čuvanje (npr. posle navigacije dugmadima/klizačem) kroz isti nezavisan mehanizam,
     * da upis ne zavisi od toga da li je ReaderActivity još živa u tom trenutku. */
    fun persistDocumentNow(entity: DocumentEntity) {
        currentDocument = entity
        persistCurrentDocument()
    }

    /** Raspakuje audio zip (ako već nije raspakovan) i priprema ExoPlayer sa playlistom -
     * NE pokreće automatski reprodukciju (playWhenReady = false), samo priprema motor na
     * sačuvanoj poziciji. Poziva se pri otvaranju audio knjige (format == "audio"),
     * analogno setupTts() za tekstualne knjige. Trenutno se NIODAKLE ne poziva - dodaje se
     * u sledećem koraku, kad se poveže sa otvaranjem dokumenta i dugmetom Play/Pauza. */
    suspend fun setupAudioEngine(context: Context, doc: DocumentEntity) {
        withContext(Dispatchers.IO) {
            val extractDir = java.io.File(context.cacheDir, "audio_extracted/${doc.id}")
            if (!extractDir.exists() || extractDir.listFiles().isNullOrEmpty()) {
                extractDir.mkdirs()
                val zipPath = android.net.Uri.parse(doc.uri).path ?: return@withContext
                val zipFile = java.io.File(zipPath)
                java.util.zip.ZipInputStream(zipFile.inputStream()).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        val outFile = java.io.File(extractDir, entry.name)
                        outFile.outputStream().use { out -> zipIn.copyTo(out) }
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                }
            }
            val files = extractDir.listFiles()?.sortedBy { it.name } ?: emptyList()
            if (files.isEmpty()) return@withContext

            withContext(Dispatchers.Main) {
                releaseAudioEngine()
                val player = androidx.media3.exoplayer.ExoPlayer.Builder(context).build()
                val mediaItems = files.map { androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(it)) }
                player.setMediaItems(mediaItems)

                val settings = AppSettings(context)
                val speed = if (doc.speechRate > 0f) doc.speechRate else settings.globalSpeechRate
                val pitch = if (doc.pitch > 0f) doc.pitch else settings.globalPitch
                val volumePercent = if (doc.volumePercent >= 0) doc.volumePercent else settings.globalVolumePercent
                player.playbackParameters = androidx.media3.common.PlaybackParameters(speed, pitch)
                player.volume = volumePercent / 100f

                player.prepare()
                val startIndex = doc.audioFileIndex.coerceIn(0, mediaItems.size - 1)
                player.seekTo(startIndex, doc.audioPositionMs)
                player.playWhenReady = false
                audioPlayer = player
            }
        }
    }

    /** Oslobađa audio motor (ExoPlayer) - poziva se pri zatvaranju audio knjige ili prelasku
     * na drugi dokument, da ne ostane da drži fajlove/resurse otvorene u pozadini. */
    fun releaseAudioEngine() {
        audioPlayer?.release()
        audioPlayer = null
    }

    /** Pauzira TRENUTNO AKTIVAN motor (TTS ili audio) - koristi JEDINO isActive()-u odgovarajuća
     * grana, da tajmer/Odmor/buđenje rade identično za oba tipa knjige bez ponavljanja iste
     * provere na svakom pojedinačnom mestu. */
    private fun pauseEngine() {
        if (currentDocument?.format == "audio") {
            audioPlayer?.pause()
        } else {
            ttsManager?.pause()
        }
    }

    /** Nastavlja TRENUTNO otvoren dokument - za audio prosto pusti (ExoPlayer već zna svoju
     * poziciju), za tekst ili počinje OD DATOG offseta ili nastavlja odakle je stalo. */
    private fun resumeEngine(offset: Int? = null) {
        if (currentDocument?.format == "audio") {
            audioPlayer?.play()
        } else {
            if (offset != null) ttsManager?.startFromOffset(offset) else ttsManager?.resume()
        }
    }

    /** JEDINO mesto koje odgovara na pitanje "da li NEŠTO trenutno čita/svira" - grana na
     * TTS motor ili audio motor, u zavisnosti od toga koji je trenutno aktivan (audioPlayer
     * je non-null SAMO kad je otvorena audio knjiga - videti sledeći korak). Svi ostali
     * delovi koda (PlaybackController, ReadingService, ReaderActivity) pitaju OVU funkciju,
     * ne diraju ttsManager/audioPlayer direktno za "da li svira" pitanja. */
    fun isActive(): Boolean = ttsManager?.isSpeaking == true || audioPlayer?.isPlaying == true

    /** Isto što i dugmad "Prethodna/Sledeća" u čitaču (zavisno od podešavanja Navigacije) -
     * ovde postoji posebno da bi radilo i pozvano iz servisa (npr. sa tastera za premotavanje
     * na slušalicama/spoljnoj tastaturi), nezavisno od toga da li je ekran otvoren. */
    fun stepNavigate(forward: Boolean, context: Context) {
        val entity = currentDocument ?: return
        if (entity.format == "audio") {
            // Audio: "Prethodni/Sledeći zapis" menja FAJL u folderu (kao poglavlje), ne
            // premotava po minutima - to radi dugme Prethodna/Sledeća u čitaču, odvojeno.
            val player = audioPlayer ?: return
            if (forward) player.seekToNextMediaItem() else player.seekToPreviousMediaItem()
            return
        }
        val settings = com.recporec.app.data.AppSettings(context)
        val mode = settings.navigationMode
        val length = parsedDocument?.length ?: return

        if (mode == "bookmark") {
            scope.launch {
                val bookmarks = withContext(Dispatchers.IO) {
                    AppDatabase.getInstance(context).bookmarkDao().getForDocument(entity.id)
                }.sortedBy { it.characterOffset }
                if (bookmarks.isEmpty()) return@launch
                val current = currentDocument?.currentCharacterOffset ?: 0
                val target = if (forward) {
                    bookmarks.firstOrNull { it.characterOffset > current } ?: bookmarks.first()
                } else {
                    bookmarks.lastOrNull { it.characterOffset < current } ?: bookmarks.last()
                }
                applyOffset(target.characterOffset)
            }
            return
        }

        val delta = when (mode) {
            "min1" -> minutesToChars(1, entity.speechRate)
            "min5" -> minutesToChars(5, entity.speechRate)
            "min10" -> minutesToChars(10, entity.speechRate)
            else -> CHARS_PER_PAGE
        }
        val signedDelta = if (forward) delta else -delta
        val current = entity.currentCharacterOffset
        val newOffset = (current + signedDelta).coerceIn(0, (length - 1).coerceAtLeast(0))
        applyOffset(newOffset)
    }

    private fun minutesToChars(minutes: Int, rate: Float): Int =
        (minutes * BASE_CHARS_PER_MINUTE * rate.coerceAtLeast(0.3f)).toInt()

    private fun applyOffset(offset: Int) {
        currentDocument = currentDocument?.copy(currentCharacterOffset = offset)
        persistCurrentDocument()
        val tts = ttsManager
        if (tts?.isSpeaking == true) tts.startFromOffset(offset) else tts?.syncPositionOnly(offset)
        uiPositionListener?.invoke(offset)
    }

    fun release() {
        ttsManager?.shutdown()
        ttsManager = null
        releaseAudioEngine()
        currentDocument = null
        parsedDocument = null
        elapsedSeconds = 0
        // Ako se ostavi "zaglavljena" na true (npr. korisnica pauzirala pa NIKAD nije uspesno
        // nastavila drmanjem/dugmetom pre Izlaza), ova zastavica bi TRAJNO (SharedPreferences
        // preziveljava restart) blokirala "Pri otvaranju aplikacije" u SVIM buducim sesijama,
        // cak i danima kasnije. Svez pokusaj (nova sesija posle Izlaza) zasluzuje svez pokusaj
        // automatskog nastavka.
        appContext?.let { com.recporec.app.data.AppSettings(it).userManuallyPaused = false }
        timerRemainingSeconds = 0
        restRemainingSeconds = 0
        restIsWakeTime = false
        restSuppressAlarm = false
        restAlarmActive = false
        restSnoozeCount = 0
        stopRestAlarm()
        releaseRestWakeLock()
        // Ako se app potpuno zatvori (dugme Izlaz) dok je budjenje/zakazano citanje aktivno,
        // otkazujemo i sistemski alarm - bez ovoga bi ostao "zaboravljen" alarm koji bi
        // kasnije zazvonio u prazno (procesor bi se probudio, ali app vise ne postoji da
        // bilo sta uradi sa tim). Iskreno: ovo znaci da BUDJENJE NE PREZIVLJAVA potpuno
        // zatvaranje app-e - namerna, bezbednija odluka, umesto rizicnog pokusaja da se cela
        // sesija ponovo sastavi iz niceg kad telefon sam probudi vec ugasenu app.
        cancelWakeAlarm()
        appContext?.let { com.recporec.app.data.AppSettings(it).clearPendingWake() }
        uiPositionListener = null
        uiFinishedListener = null
        uiTimerExpiredListener = null
        playbackStateListener = null
        scope.coroutineContext.cancelChildren()
        tickerStarted = false
    }
}
