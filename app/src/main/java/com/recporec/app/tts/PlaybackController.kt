package com.recporec.app.tts

import android.content.Context
import com.recporec.app.data.DocumentEntity
import com.recporec.app.parser.ParsedDocument

/**
 * Drži jedinu instancu TtsManager-a i trenutno otvoren dokument,
 * dostupno i Activity-ju i pozadinskom servisu.
 */
object PlaybackController {

    var ttsManager: TtsManager? = null
        private set

    var currentDocument: DocumentEntity? = null
    var parsedDocument: ParsedDocument? = null
    var elapsedSeconds: Long = 0

    fun ensureInitialized(context: Context) {
        if (ttsManager == null) {
            ttsManager = TtsManager(context)
        }
    }

    fun isActive(): Boolean = ttsManager?.isSpeaking == true

    fun release() {
        ttsManager?.shutdown()
        ttsManager = null
        currentDocument = null
        parsedDocument = null
        elapsedSeconds = 0
    }
}
