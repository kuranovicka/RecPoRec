package com.recporec.app.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.UUID

/** Jedan glas u kombinovanom nizu - motor + ime glasa. Motor može biti primarni ili
 * neki od dodatnih (drugi TTS proizvod nego onaj kojim dokument inače čita). */
data class CombinedVoiceRef(val enginePackage: String, val voiceName: String)

/**
 * Obmotava Android TextToSpeech. Čita tekst u delovima (rečenicama) da bi
 * mogli precizno da pratimo poziciju (offset) radi pamćenja napretka i
 * pomeranja po stranicama/procentima.
 *
 * Za kombinovane glasove iz RAZLIČITIH TTS motora (ne samo različitih glasova istog
 * motora), umesto da se jedna veza gasi i pali (sporo, nepouzdano usred čitanja), drže se
 * DVE (ili više) već upaljene, spremne veze istovremeno - jedna po motoru - i samo se bira
 * koja od njih izgovara sledeću rečenicu. Nema gašenja/paljenja usred čitanja.
 */
class TtsManager(private val appContext: Context) {

    var onPositionChanged: ((Int) -> Unit)? = null
    var onFinished: (() -> Unit)? = null
    var onReady: (() -> Unit)? = null

    private var pronunciationDictionary: PronunciationDictionary = PronunciationDictionary.EMPTY
    /** Pozvano kad čitanje automatski pauzira zbog telefonskog poziva ili nekog drugog
     * zvuka koji preuzme prednost (audio fokus) - ne zbog korisnikovog dodira na dugme. */
    var onAutoPaused: (() -> Unit)? = null
    /** Pozvano kad se čitanje automatski nastavi posle takvog prekida. */
    var onAutoResumed: (() -> Unit)? = null

    private var tts: TextToSpeech? = null
    private var ready = false
    var currentEnginePackage: String? = null
        private set

    /** Da li je trenutni TTS motor zaista zavrsio inicijalizaciju (spreman za setVoice/speak). */
    val isEngineReady: Boolean get() = ready

    // Dodatni (sekundarni) motori za kombinovane glasove van primarnog motora - drže se
    // upaljeni i spremni unapred, da ne bude čekanja usred čitanja.
    private val secondaryEngines: MutableMap<String, TextToSpeech> = mutableMapOf()
    private val secondaryEnginesReady: MutableMap<String, Boolean> = mutableMapOf()

    // Veza koja TRENUTNO izgovara tekst (primarna ili neka od sekundarnih).
    private var activeInstance: TextToSpeech? = null

    private var lastSpeechRate: Float = 1.0f
    private var lastPitch: Float = 1.0f
    /** Jačina (0.0-1.0), primenjuje se preko TTS parametra za svaku izgovorenu rečenicu -
     * NIJE vezano za sistemsku jačinu telefona, vezano je za ovaj dokument/opšte podešavanje. */
    private var volume: Float = 1.0f

    // Automatska pauza kad neko drugi (npr. telefonski poziv) preuzme audio - standardan
    // Android mehanizam, ne zahteva nikakvu posebnu dozvolu. Kad poziv zavrsi, citanje se
    // samo nastavi - ali SAMO ako je pauzirano zbog ovoga, ne ako je korisnica sama pauzirala.
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    private var pausedDueToFocusLoss = false

    // Rezervni mehanizam za pozive, odvojen od audio fokusa (koji se na nekim uredjajima
    // pokazao nepouzdanim - fokus se dobije, ali se povratni poziv o gubljenju nikad ne
    // pojavi). Ovaj mehanizam direktno prati stanje telefona (zahteva READ_PHONE_STATE),
    // pa radi nezavisno od toga da li audio fokus ispravno funkcionise na datom uredjaju.
    private var pausedDueToCall = false

    fun pauseForCall() {
        if (isSpeaking) {
            pausedDueToCall = true
            pause()
            onAutoPaused?.invoke()
        }
    }

    fun resumeForCall() {
        if (pausedDueToCall) {
            pausedDueToCall = false
            resume()
            onAutoResumed?.invoke()
        }
    }

    private val focusChangeListener = android.media.AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            android.media.AudioManager.AUDIOFOCUS_LOSS,
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // OVO su prave, znacajne prekide (poziv, druga app preuzima fokus) - vredi
                // pauzirati i kasnije automatski nastaviti.
                audioFocusGranted = false
                if (isSpeaking) {
                    pausedDueToFocusLoss = true
                    pause(userInitiated = false)
                    onAutoPaused?.invoke()
                }
            }
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // NAMERNO se NE pauzira ovde - po Android dokumentaciji, "CAN_DUCK" znaci
                // "mozes da nastavis da svira, samo utisaj", NE "prekini i kasnije nastavi".
                // Ranije smo ovo tretirale isto kao pravi prekid (puna pauza + automatski
                // nastavak) - sumnja je da bas TAJ pogresan tretman izaziva prijavljen problem
                // sa Bluetooth slusalicama (dupli dodir na slusalici izaziva kratak "duck"
                // signal, koji smo pogresno tumacile kao "pauziraj pa nastavi" - ceo ciklus se
                // desi u deliću sekunde, izgleda kao da se nista nije desilo osim kratkog
                // zvucnog "skoka" koji je i sama pauza, momentalno ponistena nasim automatskim
                // nastavkom). Ne diramo jacinu zvuka (nemamo ni implementirano prigušivanje) -
                // prosto ignorisemo ovaj signal, citanje nastavlja normalno.
                audioFocusGranted = false
            }
            android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                audioFocusGranted = true
                if (pausedDueToFocusLoss) {
                    pausedDueToFocusLoss = false
                    resume()
                    onAutoResumed?.invoke()
                }
            }
        }
    }

    private var audioFocusGranted = false

    private fun requestAudioFocus() {
        // Ako vec drzimo fokus, ne traziti ga ponovo - ponovljeno trazenje (bez otpustanja
        // izmedju) registruje app VISE PUTA u sistemskom "stogu" fokusa, sto pravi
        // nepredvidivo ponasanje.
        if (audioFocusGranted) return
        val am = audioManager ?: return
        if (audioFocusRequest == null) {
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            audioFocusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                // Eksplicitno na glavnoj niti - da izbegnemo mogucnost da povratni poziv
                // stigne na niti gde nesto (npr. pozivanje pause()) ne radi kako treba.
                .setOnAudioFocusChangeListener(focusChangeListener, Handler(Looper.getMainLooper()))
                .build()
        }
        try {
            val result = am.requestAudioFocus(audioFocusRequest!!)
            audioFocusGranted = result == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (_: Exception) {
            audioFocusGranted = false
            // Bezopasno ako ne uspe - citanje samo nastavlja bez ove zastite.
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        audioFocusRequest?.let {
            try {
                am.abandonAudioFocusRequest(it)
            } catch (_: Exception) {
            }
        }
        audioFocusGranted = false
    }

    private var chunks: List<String> = emptyList()
    private var chunkOffsets: List<Int> = emptyList()
    /** chunkParagraphAfter[i] = da li je kraj recenice chunks[i] ujedno i kraj pasusa
     * (prazan red posle) - velicina je chunks.size - 1 (poslednja recenica nema "posle"). */
    private var chunkParagraphAfter: List<Boolean> = emptyList()
    private var currentChunkIndex = 0

    var isSpeaking = false
        private set

    /** Pauza između rečenica u milisekundama (0 = isključeno, čita se odmah dalje). */
    var sentencePauseMs: Long = 0

    /** Pauza između pasusa (paragrafa) u milisekundama - primenjuje se UMESTO obične pauze
     * između rečenica kad je granica ujedno i kraj pasusa (0 = isključeno). */
    var paragraphPauseMs: Long = 0

    private val pauseHandler = Handler(Looper.getMainLooper())
    private var pendingNextChunk: Runnable? = null

    // Kombinovani glasovi (2+) koji se smenjuju na svakih N rečenica. Prazna/jednočlana lista
    // isključuje kombinovane glasove (čita se normalno, jednim glasom).
    private var combinedVoices: List<CombinedVoiceRef> = emptyList()
    /** Poslednji "redovni" (ne-kombinovani) glas postavljen kroz setVoiceByName ili
     * applyIndependentDefaultVoice - pamti se da bi se moglo VRATITI na njega kad se
     * kombinacija ugasi (voices.size < 2 u setCombinedVoices). Bez ovoga, applyCombinedVoice
     * direktno menja glas na samom TTS motoru (instance.voice = voice), i ništa ga ne vraća
     * nazad pri gašenju kombinacije - glas ostaje "zaglavljen" na poslednjem iz kombinacije. */
    private var regularVoiceName: String? = null
    private var combinedSentencesPerVoice: Int = 1
    private var combinedVoiceIndex = 0
    private var sentencesReadInCurrentVoice = 0

    /** Postavlja listu glasova (svaki sa svojim motorom) koji se smenjuju svakih
     * [sentencesPerVoice] rečenica. Unapred priprema (pali) svaki motor koji nije primarni,
     * da bude spreman pre nego što čitanje uopšte stigne do njega. */
    fun setCombinedVoices(voices: List<CombinedVoiceRef>, sentencesPerVoice: Int) {
        combinedVoices = voices
        combinedSentencesPerVoice = sentencesPerVoice.coerceAtLeast(1)
        combinedVoiceIndex = 0
        sentencesReadInCurrentVoice = 0

        val distinctEngines = voices.map { it.enginePackage }.toSet()
        for (engine in distinctEngines) {
            if (engine != currentEnginePackage && !secondaryEngines.containsKey(engine)) {
                prepareSecondaryEngine(engine)
            }
        }

        if (voices.size >= 2) {
            applyCombinedVoice(voices[0])
        } else {
            activeInstance = tts
            // KLJUČNO: applyCombinedVoice ranije direktno menja glas na motoru
            // (instance.voice = voice) - bez ovoga bi taj glas ostao "zaglavljen" i posle
            // gašenja kombinacije, jer ništa drugo ga ne vraća nazad na redovni glas.
            regularVoiceName?.let { setVoiceByName(it) }
        }
    }

    private fun prepareSecondaryEngine(enginePackage: String) {
        secondaryEnginesReady[enginePackage] = false
        val instance = TextToSpeech(appContext, { status ->
            val success = status == TextToSpeech.SUCCESS
            secondaryEnginesReady[enginePackage] = success
            if (success) {
                secondaryEngines[enginePackage]?.setOnUtteranceProgressListener(makeSharedListener())
                secondaryEngines[enginePackage]?.setSpeechRate(lastSpeechRate)
                secondaryEngines[enginePackage]?.setPitch(lastPitch)
                // Isti razlog kao za primarni motor - vidi initEngine().
                secondaryEngines[enginePackage]?.setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            }
        }, enginePackage)
        secondaryEngines[enginePackage] = instance
    }

    private fun instanceForEngine(enginePackage: String): TextToSpeech? {
        if (enginePackage == currentEnginePackage) return tts
        if (secondaryEnginesReady[enginePackage] == true) return secondaryEngines[enginePackage]
        return null
    }

    private fun applyCombinedVoice(ref: CombinedVoiceRef) {
        val instance = instanceForEngine(ref.enginePackage) ?: run {
            // Sekundarni motor još nije stigao da se upali (retko - samo ako se čitanje
            // pokrene odmah nakon što su kombinovani glasovi upravo postavljeni). Ostaje se
            // na trenutnom glasu za ovaj krug, probaće se ponovo na sledećoj smeni.
            return
        }
        activeInstance = instance
        val voice = instance.voices?.firstOrNull { it.name == ref.voiceName } ?: return
        // setLanguage() se poziva SAMO kad se jezik stvarno menja - kod nekih motora, poziv
        // sa VEĆ AKTIVNIM jezikom (npr. prelazak sa jednog na drugi srpski glas) zna tiho da
        // vrati podrazumevani glas tog jezika, poništavajući baš izabrani glas.
        val languageChanged = instance.voice?.locale?.language != voice.locale.language
        instance.voice = voice
        if (languageChanged) {
            try {
                instance.setLanguage(voice.locale)
            } catch (_: Exception) {
                // Neki motori/lokali mogu odbiti setLanguage - glas je već postavljen gore.
            }
        }
    }

    private fun advanceCombinedVoiceIfNeeded() {
        if (combinedVoices.size < 2) return
        sentencesReadInCurrentVoice++
        if (sentencesReadInCurrentVoice >= combinedSentencesPerVoice) {
            sentencesReadInCurrentVoice = 0
            combinedVoiceIndex = (combinedVoiceIndex + 1) % combinedVoices.size
            applyCombinedVoice(combinedVoices[combinedVoiceIndex])
        }
    }

    init {
        initEngine(null)
    }

    private fun makeSharedListener(): UtteranceProgressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) {
            handleUtteranceDone()
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            isSpeaking = false
        }
    }

    private fun handleUtteranceDone() {
        val finishedIndex = currentChunkIndex
        currentChunkIndex++
        if (currentChunkIndex < chunks.size) {
            advanceCombinedVoiceIfNeeded()
            val isParagraphBoundary = chunkParagraphAfter.getOrNull(finishedIndex) == true
            val pauseMs = when {
                isParagraphBoundary && paragraphPauseMs > 0 -> paragraphPauseMs
                sentencePauseMs > 0 -> sentencePauseMs
                else -> 0L
            }
            if (pauseMs > 0) {
                val runnable = Runnable { speakCurrentChunk() }
                pendingNextChunk = runnable
                pauseHandler.postDelayed(runnable, pauseMs)
            } else {
                speakCurrentChunk()
            }
        } else {
            isSpeaking = false
            onFinished?.invoke()
        }
    }

    private fun attachListener() {
        tts?.setOnUtteranceProgressListener(makeSharedListener())
    }

    private fun initEngine(enginePackage: String?) {
        val listener = TextToSpeech.OnInitListener { status ->
            ready = status == TextToSpeech.SUCCESS
            currentEnginePackage = enginePackage
            if (ready) {
                attachListener()
                // KRITICNO za Bluetooth slusalice sa fizickim dodirom (dupli tap): sam TTS
                // MOTOR mora da ima AudioAttributes.USAGE_MEDIA, ne samo AudioFocusRequest
                // (koji vec ima ispravne atribute, vidi requestAudioFocus). Bluetooth AVRCP
                // (dodir na slusalicama) odlucuje KOME da posalje komandu pauze/nastavka na
                // osnovu toga koji STREAM trenutno aktivno svira sa USAGE_MEDIA oznakom - bez
                // ovoga, sintetizovan glas moze da svira ispravno (cuje se), ali AVRCP ga ne
                // prepoznaje kao "medijsku" reprodukciju, pa dodir na slusalicama ne uradi
                // nista (samo lokalni "klik" zvuk slusalice, bez efekta u app-i). Prijava
                // korisnice: "radi na pozivu (HFP, drugi profil), ne radi ovde".
                tts?.setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                if (activeInstance == null) activeInstance = tts
                onReady?.invoke()
            }
        }
        tts = if (enginePackage != null) {
            TextToSpeech(appContext, listener, enginePackage)
        } else {
            TextToSpeech(appContext, listener)
        }
    }

    /** Prebacuje PRIMARNI motor (npr. sa Google-ovog na Samsung-ov) i ponovo primenjuje glas.
     * Ovo je i dalje sporije/asinhrono - koristi se samo za običan (nekombinovan) glas
     * dokumenta, ne za smenjivanje unutar kombinovanih glasova (za to postoje sekundarne,
     * unapred upaljene veze). */
    fun switchEngine(enginePackage: String?, voiceName: String?, rate: Float, onSwitched: () -> Unit) {
        tts?.stop()
        tts?.shutdown()
        val previousOnReady = onReady
        onReady = {
            voiceName?.let { setVoiceByName(it) }
            setSpeechRate(rate)
            onReady = previousOnReady
            onSwitched()
        }
        initEngine(enginePackage)
    }

    fun setVoiceByName(name: String) {
        val voice = tts?.voices?.firstOrNull { it.name == name } ?: return
        regularVoiceName = name
        // setLanguage() se poziva SAMO kad se jezik stvarno menja - kod nekih motora, poziv
        // sa VEĆ AKTIVNIM jezikom (npr. prelazak sa jednog na drugi glas istog jezika) zna
        // tiho da vrati podrazumevani glas tog jezika, poništavajući baš izabrani glas.
        val languageChanged = tts?.voice?.locale?.language != voice.locale.language
        tts?.voice = voice
        if (languageChanged) {
            try {
                tts?.setLanguage(voice.locale)
            } catch (_: Exception) {
                // Neki motori/lokali mogu odbiti setLanguage - glas je vec postavljen gore,
                // pa nastavljamo bez prekida.
            }
        }
    }

    /**
     * Kada nije eksplicitno izabran nijedan glas (ni za dokument ni globalno), TextToSpeech
     * bi inače mogao da koristi glas koji se poklapa sa onim kojim čita ekranski čitač
     * (jer oba mogu da koriste isti podrazumevani sistemski glas). Da bi čitanje knjiga bilo
     * potpuno nezavisno, ovde SAMI biramo određen, dosledan podrazumevani glas.
     */
    fun applyIndependentDefaultVoice(): Voice? {
        val allEngineVoices = tts?.voices?.toList() ?: return null
        if (allEngineVoices.isEmpty()) return null
        val deviceLanguage = Locale.getDefault().language
        val candidates = allEngineVoices.filter { it.locale.language == deviceLanguage }
            .ifEmpty { allEngineVoices }
        val chosen = candidates.sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.name })
            .firstOrNull()
        chosen?.let { tts?.voice = it; regularVoiceName = it.name }
        return chosen
    }

    fun setSpeechRate(rate: Float) {
        lastSpeechRate = rate
        tts?.setSpeechRate(rate)
        secondaryEngines.values.forEach { it.setSpeechRate(rate) }
    }

    fun setPitch(pitch: Float) {
        lastPitch = pitch
        tts?.setPitch(pitch)
        secondaryEngines.values.forEach { it.setPitch(pitch) }
    }

    /** Jačina 0.0-1.0. Za razliku od brzine/visine (koje se postavljaju jednom na motor),
     * jačina se šalje uz SVAKI izgovor (TTS parametar), pa ne treba posebno primenjivati na
     * sekundarne motore - primenjuje se automatski čim god koji od njih izgovori sledeći deo. */
    fun setVolume(vol: Float) {
        volume = vol.coerceIn(0f, 1f)
    }

    /** Učitava puni tekst i deli ga na rečenice radi praćenja pozicije.
     *
     * Kraj rečenice prepoznaje tačku, upitnik i uzvičnik, uz dozvoljene zatvarajuće
     * navodnike ili zagradu odmah posle (npr. rečenica koja se završava sa ."  ili  !').
     * Tačka NEPOSREDNO iza broja (npr. godina "1958." ili redni broj) se namerno NE
     * tretira kao kraj rečenice, da se npr. u kombinovanim glasovima ne bi jedan glas
     * "zaglavio" samo na broju godine, a drugi nastavio ostatak rečenice.
     *
     * Paragraf (pasus) se prepoznaje kad posle kraja rečenice sledi prazan red (dva ili
     * više preloma reda zaredom) - koristi se za posebnu, obično dužu pauzu.
     */
    suspend fun loadText(fullText: String) {
        // (?<!\d)[.!?]["'’”)]{0,2}(\s+) - kraj recenice (uz izuzetak brojeva ispred tacke)
        // ILI (\n{2,}) - goli prazan red bez interpunkcije ispred (npr. naslov, lista)
        val pattern = Regex("(?<!\\d)[.!?][\"'\u2019\u201d)]{0,2}(\\s+)|(\\n{2,})")

        val cleaned = mutableListOf<String>()
        val offsets = mutableListOf<Int>()
        val paragraphAfter = mutableListOf<Boolean>()

        var pos = 0
        for (match in pattern.findAll(fullText)) {
            val g1 = match.groups[1]
            val g2 = match.groups[2]
            val wsRange = g1?.range ?: g2?.range ?: continue
            val isParagraph = if (g1 != null) g1.value.count { it == '\n' } >= 2 else true

            val rawPiece = fullText.substring(pos, wsRange.first)
            val trimmed = rawPiece.trimStart()
            val leadingWs = rawPiece.length - trimmed.length
            val piece = trimmed.trimEnd()
            if (piece.isNotEmpty()) {
                cleaned.add(piece)
                offsets.add(pos + leadingWs)
                paragraphAfter.add(isParagraph)
            }
            pos = wsRange.last + 1
        }
        if (pos < fullText.length) {
            val rawPiece = fullText.substring(pos)
            val trimmed = rawPiece.trimStart()
            val leadingWs = rawPiece.length - trimmed.length
            val piece = trimmed.trimEnd()
            if (piece.isNotEmpty()) {
                cleaned.add(piece)
                offsets.add(pos + leadingWs)
            }
        }

        chunks = cleaned
        chunkOffsets = offsets
        chunkParagraphAfter = paragraphAfter
        // Ucitava se OVDE (jednom po otvaranju dokumenta), ne po recenici - jeftino je
        // cak i sa par hiljada unosa u recniku, nema potrebe za slozenijim kesiranjem.
        pronunciationDictionary = PronunciationDictionary.load(appContext)
    }

    /** Počni čitanje od zadatog offseta u tekstu (karakter). */
    fun startFromOffset(offset: Int) {
        val idx = java.util.Collections.binarySearch(chunkOffsets, offset)
        currentChunkIndex = (if (idx >= 0) idx else -idx - 1)
            .coerceIn(0, (chunks.size - 1).coerceAtLeast(0))
        if (chunks.isEmpty()) return
        requestAudioFocus()
        pausedDueToFocusLoss = false
        if (combinedVoices.size >= 2) {
            // Svaki novi početak čitanja (posle skoka na stranicu/oznaku/pretragu) počinje
            // od prvog glasa u nizu - dosledno i lako za očekivati, umesto nagađanja koji bi
            // glas "trebalo" da bude na tom mestu.
            combinedVoiceIndex = 0
            sentencesReadInCurrentVoice = 0
            applyCombinedVoice(combinedVoices[0])
        } else {
            activeInstance = tts
        }
        isSpeaking = true
        // Vidi BluetoothKeepAlive - resenje za Bluetooth slusalice sa fizickim dodirom,
        // preuzeto iz istrage tudjeg iskustva (@Voice Aloud Reader).
        BluetoothKeepAlive.start()
        speakCurrentChunk()
    }

    /** Uskladi internu poziciju sa datim offsetom BEZ pokretanja govora (npr. dok je pauzirano). */
    fun syncPositionOnly(offset: Int) {
        val idx = java.util.Collections.binarySearch(chunkOffsets, offset)
        currentChunkIndex = (if (idx >= 0) idx else -idx - 1)
            .coerceIn(0, (chunks.size - 1).coerceAtLeast(0))
    }

    /** "Vrati rečenice" - vraca karakter-offset pocetka recenice N mesta unazad od trenutne.
     * Koristi ISTU podelu na recenice (chunkOffsets) koju TTS vec koristi za citanje - ne
     * duplira logiku deljenja teksta na recenice negde drugde. Vraca null ako dokument jos
     * nije spreman. Ne ide ispod pocetka dokumenta (coerceAtLeast 0). */
    fun offsetGoingBackSentences(n: Int): Int? {
        if (chunkOffsets.isEmpty()) return null
        val targetIndex = (currentChunkIndex - n).coerceIn(0, chunkOffsets.size - 1)
        return chunkOffsets[targetIndex]
    }

    /** Isto kao offsetGoingBackSentences, samo unapred - "Sledeća rečenica" dugme. */
    fun offsetGoingForwardSentences(n: Int): Int? {
        if (chunkOffsets.isEmpty()) return null
        val targetIndex = (currentChunkIndex + n).coerceIn(0, chunkOffsets.size - 1)
        return chunkOffsets[targetIndex]
    }

    /** [userInitiated]=true (podrazumevano) znaci da je OVO namerna, svesna radnja (dugme,
     * medijski taster, drmanje) - takva pauza UVEK "pobedi" nad automatskim nastavkom, cak i
     * ako se slucajno poklopi sa kratkim gubitkom/vracanjem audio fokusa (npr. Bluetooth
     * dodir moze da izazove takav kratak "treptaj" kao sporedni efekat). Interni poziv iz
     * focusChangeListener-a (zbog PRAVOG prekida, npr. poziva) koristi false, da ne bi sam
     * sebe ponistio. */
    fun pause(userInitiated: Boolean = true) {
        cancelPendingChunk()
        tts?.stop()
        secondaryEngines.values.forEach { it.stop() }
        isSpeaking = false
        BluetoothKeepAlive.stop()
        if (userInitiated) pausedDueToFocusLoss = false
    }

    fun resume() {
        if (chunks.isEmpty()) return
        requestAudioFocus()
        isSpeaking = true
        BluetoothKeepAlive.start()
        speakCurrentChunk()
    }

    fun stop() {
        cancelPendingChunk()
        tts?.stop()
        secondaryEngines.values.forEach { it.stop() }
        isSpeaking = false
        BluetoothKeepAlive.stop()
        pausedDueToFocusLoss = false
        abandonAudioFocus()
    }

    private fun cancelPendingChunk() {
        pendingNextChunk?.let { pauseHandler.removeCallbacks(it) }
        pendingNextChunk = null
    }

    /** Indeks rečenice (chunk-a) koji sadrži dati karakter-offset - koristi se za procenu
     * preostalog/proteklog vremena (uključujući pauze), ne za samo čitanje. */
    fun chunkIndexForOffset(offset: Int): Int {
        val idx = java.util.Collections.binarySearch(chunkOffsets, offset)
        return (if (idx >= 0) idx else -idx - 1).coerceIn(0, (chunks.size - 1).coerceAtLeast(0))
    }

    /** Procenjeno dodatno vreme (ms) usled pauza između rečenica/pasusa, za granice u
     * opsegu [fromIndex, toIndexExclusive). Na granici koja je kraj pasusa računa se
     * pauza za pasus (ako je uključena), inače pauza za rečenice (ako je uključena) -
     * isto pravilo kao i pri stvarnom čitanju (vidi handleUtteranceDone). */
    fun estimatedPauseMillis(fromIndex: Int, toIndexExclusive: Int, sentenceMs: Long, paragraphMs: Long): Long {
        if (chunkParagraphAfter.isEmpty() || (sentenceMs <= 0 && paragraphMs <= 0)) return 0L
        var total = 0L
        val end = toIndexExclusive.coerceAtMost(chunkParagraphAfter.size)
        for (i in fromIndex.coerceAtLeast(0) until end) {
            total += if (chunkParagraphAfter[i] && paragraphMs > 0) paragraphMs
                else if (sentenceMs > 0) sentenceMs
                else 0L
        }
        return total
    }

    private fun speakCurrentChunk() {
        val chunk = chunks.getOrNull(currentChunkIndex) ?: run {
            isSpeaking = false
            onFinished?.invoke()
            return
        }
        onPositionChanged?.invoke(chunkOffsets.getOrElse(currentChunkIndex) { 0 })
        val speaker = activeInstance ?: tts
        val params = android.os.Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        }
        speaker?.speak(pronunciationDictionary.apply(chunk), TextToSpeech.QUEUE_FLUSH, params, UUID.randomUUID().toString())
    }

    fun shutdown() {
        cancelPendingChunk()
        abandonAudioFocus()
        tts?.stop()
        tts?.shutdown()
        secondaryEngines.values.forEach {
            it.stop()
            it.shutdown()
        }
        secondaryEngines.clear()
        secondaryEnginesReady.clear()
    }
}
