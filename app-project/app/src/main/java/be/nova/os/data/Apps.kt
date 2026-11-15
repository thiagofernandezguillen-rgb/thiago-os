package be.nova.os.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Immutable
import java.text.Collator
import java.util.Locale

@Immutable
data class AppEntry(
    val pkg: String,
    val cls: String,
    val label: String,
    val cat: String,
    val desc: String,
    val tv: Boolean,
    val stamp: Long,
) {
    val iconKey get() = "$pkg/$stamp"
}

/** Nederlandse categorie + korte beschrijving voor bekende apps (zoals in het prototype). */
private val META = mapOf(
    "com.mediatek.wwtv.tvcenter" to ("Live tv" to "Tv-zenders kijken en wisselen tussen je HDMI-ingangen."),
    "com.google.android.youtube.tv" to ("Video" to "Video’s, muziek en creators van over de hele wereld."),
    "com.google.android.youtube.tvmusic" to ("Muziek" to "Muziek, albums en playlists van YouTube Music."),
    "com.netflix.ninja" to ("Films & series" to "Series, films en documentaires om te kijken wanneer je wilt."),
    "com.amazon.amazonvideo.livingroom" to ("Films & series" to "Films, series en sport van Amazon."),
    "com.disney.disneyplus" to ("Films & series" to "Disney, Pixar, Marvel, Star Wars en National Geographic."),
    "be.dpgmedia.streamz" to ("Vlaamse series" to "Vlaamse en internationale series en films."),
    "be.vrt.vrtnu" to ("Vlaamse tv" to "Programma’s van Eén, Canvas en Ketnet, live en uitgesteld."),
    "be.vrt.vrtmax" to ("Vlaamse tv" to "Programma’s van Eén, Canvas en Ketnet, live en uitgesteld."),
    "nl.streamgroup.vlaanderen" to ("Tv-zenders" to "Live tv, terugkijken en opnames via TV Vlaanderen."),
    "nl.streamgroup.telesat" to ("Tv-zenders" to "Live tv, terugkijken en opnames via Telesat."),
    "com.spotify.tv.android" to ("Muziek" to "Muziek en podcasts op je grote scherm."),
    "tunein.player" to ("Radio" to "Radiozenders en podcasts van over de hele wereld."),
    "com.radio.fmradio" to ("Radio" to "Luister naar radiozenders via internet."),
    "com.tiktok.tv" to ("Video" to "Korte video’s van creators wereldwijd."),
    "org.xbmc.kodi" to ("Mediacenter" to "Open-source mediacenter voor al je bestanden."),
    "org.videolan.vlc" to ("Mediaspeler" to "Speelt alle video’s af, ook van een USB-stick of je netwerk."),
    "com.plexapp.android" to ("Mediaserver" to "Je eigen films en series van je pc of server."),
    "tv.twitch.android.app" to ("Live" to "Live gaming en streams."),
    "tv.pluto.android" to ("Gratis tv" to "Gratis live-kanalen en films."),
    "be.goplay.goplay" to ("Vlaamse tv" to "Programma’s van VTM, Play4 en meer."),
    "com.android.vending" to ("Apps & games" to "Nieuwe apps en games downloaden voor je tv."),
    "com.google.android.play.games" to ("Games" to "Games ontdekken en spelen."),
    "com.google.android.videos" to ("Films & series" to "Je gekochte en gehuurde films en series."),
    "com.google.android.apps.tachyon" to ("Videobellen" to "Videobellen met familie en vrienden."),
    "com.mediatek.wwtv.mediaplayer" to ("Mediaspeler" to "Foto’s, muziek en video’s van een USB-stick."),
    "com.logitech.circle" to ("Camera" to "Bekijk de beelden van je Logitech Circle-camera’s."),
    "com.neulion.smartphone.ufc.android" to ("Sport" to "UFC-gevechten, live en op aanvraag."),
    "com.vestel.tv.eib" to ("Handleiding" to "De elektronische handleiding van je tv."),
    "com.vestel.tv.speedup" to ("Hulpprogramma" to "Hulpprogramma van de tv-fabrikant."),
)

/** Standaardvolgorde zolang je zelf niets verplaatst hebt. */
private val PRIORITY = listOf(
    "com.mediatek.wwtv.tvcenter", "com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom",
    "com.disney.disneyplus", "be.dpgmedia.streamz", "be.vrt.vrtnu", "be.vrt.vrtmax", "nl.streamgroup.vlaanderen",
    "nl.streamgroup.telesat", "com.spotify.tv.android", "com.google.android.youtube.tvmusic", "tunein.player",
    "com.tiktok.tv", "org.xbmc.kodi",
)
private val LAST = listOf(
    "com.android.vending", "com.google.android.play.games", "com.mediatek.wwtv.mediaplayer",
    "com.vestel.tv.eib", "com.vestel.tv.speedup",
)
private val HIDDEN = setOf("com.mediatek.android.leanbacklauncher.partnercustomizer")

private val collator: Collator = Collator.getInstance(Locale.forLanguageTag("nl-BE"))

val defaultOrder = Comparator<AppEntry> { a, b ->
    fun rank(x: AppEntry): Int {
        val p = PRIORITY.indexOf(x.pkg); if (p >= 0) return p
        val l = LAST.indexOf(x.pkg); if (l >= 0) return 1000 + l
        return 500
    }
    val r = rank(a) - rank(b)
    if (r != 0) r else collator.compare(a.label, b.label)
}

class AppRepo(private val ctx: Context) {
    private val pm = ctx.packageManager

    private fun category(ai: ApplicationInfo): String {
        if (Build.VERSION.SDK_INT < 26) return "App"
        return when (ai.category) {
            ApplicationInfo.CATEGORY_GAME -> "Games"
            ApplicationInfo.CATEGORY_AUDIO -> "Muziek & audio"
            ApplicationInfo.CATEGORY_VIDEO -> "Video"
            ApplicationInfo.CATEGORY_IMAGE -> "Foto’s"
            ApplicationInfo.CATEGORY_SOCIAL -> "Sociaal"
            ApplicationInfo.CATEGORY_NEWS -> "Nieuws"
            ApplicationInfo.CATEGORY_MAPS -> "Kaarten"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productiviteit"
            else -> "App"
        }
    }

    /** Alle startbare apps (eerst tv-apps, daarna gewone Android-apps), zonder Nova OS zelf. */
    fun query(): List<AppEntry> {
        val out = LinkedHashMap<String, AppEntry>()
        fun add(cat: String, tv: Boolean) {
            val ris = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)
            for (ri in ris) {
                val ai = ri.activityInfo ?: continue
                val pkg = ai.packageName
                if (pkg == ctx.packageName || pkg in HIDDEN || out.containsKey(pkg)) continue
                if (!ai.enabled || !ai.applicationInfo.enabled) continue
                val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { pkg }
                val meta = META[pkg]
                val desc = meta?.second ?: ai.applicationInfo.loadDescription(pm)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "Open $label op je tv."
                val stamp = try { pm.getPackageInfo(pkg, 0).lastUpdateTime } catch (_: Exception) { 0L }
                out[pkg] = AppEntry(pkg, ai.name, label, meta?.first ?: category(ai.applicationInfo), desc, tv, stamp)
            }
        }
        add(Intent.CATEGORY_LEANBACK_LAUNCHER, true)
        add(Intent.CATEGORY_LAUNCHER, false)
        return out.values.toList()
    }

    fun serialize(l: List<AppEntry>): String = l.joinToString("\n") {
        listOf(it.pkg, it.cls, it.label, it.cat, it.desc, if (it.tv) "1" else "0", it.stamp.toString())
            .joinToString("\t") { s -> s.replace('\t', ' ').replace('\n', ' ') }
    }

    fun deserialize(s: String?): List<AppEntry> {
        if (s.isNullOrEmpty()) return emptyList()
        return s.split('\n').mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 7) null else AppEntry(f[0], f[1], f[2], f[3], f[4], f[5] == "1", f[6].toLongOrNull() ?: 0L)
        }
    }
}
