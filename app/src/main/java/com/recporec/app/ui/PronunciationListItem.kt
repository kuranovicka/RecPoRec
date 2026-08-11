package com.recporec.app.ui

/** Jedan red u spojenoj listi (ugrađeni rečnik + korisnikovi sopstveni unosi). entityId je
 * null za čisto ugrađen unos koji korisnica NIJE prepisala svojom zamenom - takav unos se
 * ne može menjati/brisati, samo čuti (Pusti izgovor) ili preuzeti kao osnova za sopstvenu
 * zamenu. Ako entityId nije null, red predstavlja pravi red iz baze (bilo nova reč, bilo
 * prepisana zamena za reč koja postoji i u ugrađenom rečniku) - potpuno se menja/briše. */
data class PronunciationListItem(
    val originalWord: String,
    val replacement: String,
    val isBuiltIn: Boolean,
    val entityId: Long?
)
