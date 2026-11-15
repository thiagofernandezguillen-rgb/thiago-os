package be.nova.os.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Taal van Thiago OS: "auto" (volgt de taal van de tv), "nl" of "en".
 * Nederlands als de tv in het Nederlands staat, anders Engels.
 */
object Lang {
    var mode by mutableStateOf("auto")
    val en: Boolean get() = when (mode) {
        "en" -> true
        "nl" -> false
        else -> Locale.getDefault().language != "nl"
    }
    val locale: Locale get() = if (en) Locale.ENGLISH else Locale.forLanguageTag("nl-BE")
}

/** Kies de tekst in de huidige taal. */
fun tr(nl: String, en: String): String = if (Lang.en) en else nl
