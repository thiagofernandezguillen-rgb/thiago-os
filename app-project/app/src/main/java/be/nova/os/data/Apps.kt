package be.nova.os.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Immutable
import be.nova.os.ui.Lang
import be.nova.os.ui.tr
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

/** English categories and descriptions. */
private val META_EN = mapOf(
    "com.mediatek.wwtv.tvcenter" to ("Live TV" to "Watch TV channels and switch between your HDMI inputs."),
    "com.google.android.youtube.tv" to ("Video" to "Videos, music and creators from all over the world."),
    "com.google.android.youtube.tvmusic" to ("Music" to "Music, albums and playlists from YouTube Music."),
    "com.netflix.ninja" to ("Movies & series" to "Series, films and documentaries whenever you want."),
    "com.amazon.amazonvideo.livingroom" to ("Movies & series" to "Movies, series and sports from Amazon."),
    "com.disney.disneyplus" to ("Movies & series" to "Disney, Pixar, Marvel, Star Wars and National Geographic."),
    "be.dpgmedia.streamz" to ("Flemish series" to "Flemish and international series and films."),
    "be.vrt.vrtnu" to ("Flemish TV" to "Shows from Eén, Canvas and Ketnet, live and on demand."),
    "be.vrt.vrtmax" to ("Flemish TV" to "Shows from Eén, Canvas and Ketnet, live and on demand."),
    "nl.streamgroup.vlaanderen" to ("TV channels" to "Live TV, replay and recordings with TV Vlaanderen."),
    "nl.streamgroup.telesat" to ("TV channels" to "Live TV, replay and recordings with Telesat."),
    "com.spotify.tv.android" to ("Music" to "Music and podcasts on your big screen."),
    "tunein.player" to ("Radio" to "Radio stations and podcasts from around the world."),
    "com.radio.fmradio" to ("Radio" to "Listen to radio stations over the internet."),
    "com.tiktok.tv" to ("Video" to "Short videos from creators worldwide."),
    "org.xbmc.kodi" to ("Media center" to "Open-source media center for all your files."),
    "org.videolan.vlc" to ("Media player" to "Plays every video, also from USB or your network."),
    "com.plexapp.android" to ("Media server" to "Your own movies and series from your PC or server."),
    "tv.twitch.android.app" to ("Live" to "Live gaming and streams."),
    "tv.pluto.android" to ("Free TV" to "Free live channels and movies."),
    "be.goplay.goplay" to ("Flemish TV" to "Shows from VTM, Play4 and more."),
    "com.android.vending" to ("Apps & games" to "Download new apps and games for your TV."),
    "com.google.android.play.games" to ("Games" to "Discover and play games."),
    "com.google.android.videos" to ("Movies & series" to "Your purchased and rented movies and series."),
    "com.google.android.apps.tachyon" to ("Video calls" to "Video calls with family and friends."),
    "com.mediatek.wwtv.mediaplayer" to ("Media player" to "Photos, music and videos from a USB stick."),
    "com.logitech.circle" to ("Camera" to "View your Logitech Circle cameras."),
    "com.neulion.smartphone.ufc.android" to ("Sports" to "UFC fights, live and on demand."),
    "com.vestel.tv.eib" to ("Manual" to "Your TV's electronic manual."),
    "com.vestel.tv.speedup" to ("Utility" to "Utility from the TV manufacturer."),
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
            ApplicationInfo.CATEGORY_AUDIO -> tr("Muziek & audio", "Music & audio")
            ApplicationInfo.CATEGORY_VIDEO -> "Video"
            ApplicationInfo.CATEGORY_IMAGE -> tr("Foto’s", "Photos")
            ApplicationInfo.CATEGORY_SOCIAL -> tr("Sociaal", "Social")
            ApplicationInfo.CATEGORY_NEWS -> tr("Nieuws", "News")
            ApplicationInfo.CATEGORY_MAPS -> tr("Kaarten", "Maps")
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> tr("Productiviteit", "Productivity")
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
                val meta = if (Lang.en) META_EN[pkg] else META[pkg]
                val desc = meta?.second ?: ai.applicationInfo.loadDescription(pm)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: tr("Open $label op je tv.", "Open $label on your TV.")
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
