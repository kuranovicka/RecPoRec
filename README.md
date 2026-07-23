# RečPoReč

Android aplikacija za čitanje knjiga uz TTS (tekst-u-govor), namenjena slepim i slabovidim korisnicima.

## Status
U razvoju. Svaki push na ovaj repozitorijum automatski pokreće build (GitHub Actions) i pravi debug APK, dostupan u sekciji **Releases** ili **Actions → poslednji run → Artifacts**.

## Podržani formati
TXT, EPUB, PDF, DOCX

## Kontrole (u čitaču — raspored kao numerička tastatura, 12 dugmadi)

```
[Prethodno poglavlje]  [Tajmer]           [Sledeće poglavlje]
[Smanji jačinu]        [Izbor glasa]      [Pojačaj jačinu]
[Smanji brzinu]        [Pusti / Pauziraj] [Povećaj brzinu]
[-5% pozicije]         [Idi na stranicu]  [+5% pozicije]
            [Klizač napretka kroz knjigu]
```

## Build lokalno
Potreban je Android SDK (compileSdk 34, minSdk 26) i JDK 17.

```
./gradlew assembleDebug
```
