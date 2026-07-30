package com.recporec.app.util

import android.view.View
import android.view.accessibility.AccessibilityEvent

/**
 * Traži da ekranski čitač (TalkBack) odmah postavi fokus na ovaj view, čim se pojavi na
 * ekranu - korisno za klizače koje želimo da budu PRVI u fokusu na ekranu ili u dijalogu,
 * umesto da korisnik mora ručno da ih pronađe prevlačenjem prsta.
 *
 * `post { ... }` je bitan - view mora prvo da bude izmeren/postavljen na ekran pre nego
 * što fokus ima efekta, inače se tiho ignoriše.
 */
fun View.requestAccessibilityFocusNow() {
    post {
        isFocusableInTouchMode = true
        requestFocus()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
    }
}
