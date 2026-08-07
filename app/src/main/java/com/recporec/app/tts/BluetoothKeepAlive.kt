package com.recporec.app.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Bluetooth AVRCP "keep-alive" - REŠENJE preuzeto iz istrage tuđeg iskustva: @Voice Aloud
 * Reader (poznat, dugogodišnji Android TTS čitač) ima imenovanu funkciju za TAČNO ovaj
 * problem ("Special Bluetooth fade workaround - plays a very low background sound to avoid
 * fade-in/fade-out on some Bluetooth devices between sentences") - korisnički opis
 * ("zvuk se ljulja i vrati") se poklapa sa opisom tog problema.
 *
 * Ideja: TTS motor pušta zvuk u KRATKIM komadima (rečenica po rečenica), sa malim pauzama
 * između - neki Bluetooth uređaji/AVRCP implementacije tumače te kratke tišine kao "stream
 * je prestao", pa prestaju da tretiraju telefon kao aktivnog medijskog plejera kome fizički
 * dodir treba da pošalje komandu. Ovaj plejer pušta VEOMA tih, praktično nečujan ton
 * NEPREKIDNO dok je čitanje aktivno (uključujući i kratke pauze između rečenica) - tako
 * Bluetooth vidi NEPREKIDAN tok zvuka, ne isprekidan.
 *
 * NAMERNO potpuno odvojeno od TtsManager-a i samog TTS motora - ne dira ni poziciju, ni
 * brzinu, ni kombinovane glasove, ni bilo šta drugo. Samo se pokreće/zaustavlja uz postojeće
 * start/pauza/nastavak tačke.
 */
object BluetoothKeepAlive {
    private const val SAMPLE_RATE = 16000
    // Vrlo niska amplituda (oko 0.5% od maksimuma) - dovoljno da bude STVARAN, nenulti signal
    // (neki uređaji ignorišu potpunu tišinu kao "nema signala"), ali praktično nečujno.
    private const val AMPLITUDE = 160
    private const val TONE_HZ = 60.0

    private var audioTrack: AudioTrack? = null
    private var playThread: Thread? = null
    @Volatile private var running = false

    @Synchronized
    fun start() {
        if (running) return
        // Odbrambeno - ako je nekim slucajem ostalo nesto od prethodnog (nepotpuno
        // zavrsenog) zaustavljanja, ocisti pre nego sto napravimo novu traku.
        try {
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null
        running = true
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(1024)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track
        track.play()

        val samplesPerCycle = (SAMPLE_RATE / TONE_HZ).toInt().coerceAtLeast(1)
        val buffer = ShortArray(samplesPerCycle) { i ->
            (AMPLITUDE * kotlin.math.sin(2.0 * Math.PI * i / samplesPerCycle)).toInt().toShort()
        }
        playThread = Thread {
            try {
                while (running) {
                    audioTrack?.write(buffer, 0, buffer.size)
                }
            } catch (_: Exception) {
                // Bezbedno - ako pisanje ne uspe (npr. uredjaj oduzeo fokus), samo prestani,
                // ne rusi citanje.
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        val trackToClose = audioTrack
        val threadToJoin = playThread
        audioTrack = null
        playThread = null
        // pause() ODMAH oslobadja blokirani write() poziv (vidi objasnjenje u start()/pisanju
        // niti) - ovo je brzo, ne blokira pozivaoca.
        try {
            trackToClose?.pause()
        } catch (_: Exception) {
        }
        // OSTATAK ciscenja (cekanje da nit stvarno zavrsi, pa release) radi se U POZADINI, NE
        // na pozivaocevoj niti. Ranije je stop() blokirao (join sa čekanjem do 300ms) ONU nit
        // koja je pozvala pauzu (obicno glavna/UI nit, preko MediaSession callback-a) - to je
        // pravilo primetno kasnjenje u odzivu na dodir slusalice/taster. Sad stop() vraca
        // kontrolu odmah, a stvarno zatvaranje trake se zavrsi kratko posle, u pozadini -
        // bezbedno, jer smo vec iznad "otkacile" stare reference (audioTrack/playThread su
        // null), pa naredni start() ne moze da se sudari sa ovim ciscenjem.
        Thread {
            try {
                threadToJoin?.join(300)
            } catch (_: Exception) {
            }
            try {
                trackToClose?.stop()
                trackToClose?.release()
            } catch (_: Exception) {
            }
        }.apply {
            isDaemon = true
            start()
        }
    }
}
