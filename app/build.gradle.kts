plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.recporec.app"
    compileSdk = 35

    // Broj build-a sa GitHub Actions-a (uvek raste) - koristi se za versionCode da bi
    // Android uvek prepoznao noviji APK kao "ažuriranje", umesto da traži deinstalaciju.
    val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()

    defaultConfig {
        applicationId = "com.recporec.app"
        minSdk = 26
        targetSdk = 35
        versionCode = ciRunNumber ?: 1
        versionName = "0.1.${ciRunNumber ?: 0}"
    }

    signingConfigs {
        getByName("debug") {
            // Fiksan, u repo-u sačuvan keystore - da bi svaki CI build imao ISTI potpis.
            // Bez ovoga, svaki build na novom serveru dobija nasumičan debug ključ, pa
            // Android odbija instalaciju "preko" prethodne verzije (traži deinstalaciju).
            storeFile = file("../keystore/recporec-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.media:media:1.7.0")

    // Room for persistence
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // PDF text extraction
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // OCR (citanje teksta sa slika) - potpuno OFFLINE, na samom uredjaju, ne salje sliku
    // nigde preko interneta - bitno za privatnost korisnika. Setup potvrdjen kao standardan
    // (Google/Kodeco dokumentacija) - raniji neuspeli build-ovi su bili zbog NEPOVEZANE
    // greske u drugom fajlu (ShareReceiverActivity), ne zbog ovoga.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Za otvaranje fajlova (uključujući Google Disk kroz sistemski birač)
    implementation("androidx.activity:activity-ktx:1.9.1")

    // Audio knjige - reprodukcija zvučnih fajlova (MP3, WAV, OGG, FLAC, M4A/AAC).
    // Potpuno offline, Apache 2.0 licenca, ne zahteva FFmpeg ekstenziju (native build) -
    // podržani formati su pokriveni ugrađenim ExoPlayer ekstraktorima.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    // Za rad sa folderom (ACTION_OPEN_DOCUMENT_TREE) pri dodavanju audio knjige.
    implementation("androidx.documentfile:documentfile:1.0.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
