package be.nova.os.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import be.nova.os.data.AppEntry
import be.nova.os.data.AppRepo
import be.nova.os.data.Icons
import be.nova.os.data.Nav
import be.nova.os.data.Sys
import be.nova.os.data.defaultOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

val Context.novaStore by preferencesDataStore("nova")
private val K_FAVS = stringSetPreferencesKey("favs")
private val K_ORDER = stringPreferencesKey("order")
private val K_CACHE = stringPreferencesKey("appcache")
private val K_LANG = stringPreferencesKey("lang")

enum class Page { HOME, SETTINGS }
enum class Zone { NAV, STATUS, ACT, ROW, MENU, SET, DETAIL }

@Immutable
class SetItem(val key: String, val name: String, val color: Color, val icon: () -> ImageVector)

/** Eén regel in het detailpaneel van Instellingen (.opt). */
@Immutable
class Opt(
    val title: String,
    val sub: String? = null,
    val pill: String? = null,
    val sw: Boolean? = null,
    val action: (() -> Unit)? = null,
)

/** Tegel in het bedieningspaneel (.ct). */
@Immutable
class CcTile(val name: String, val sub: String, val on: Boolean, val icon: ImageVector)

const val ROW_MAX = 8
const val GRID_COLS = 8

@Stable
class NovaState(
    private val act: Activity,
    val sys: Sys,
    private val repo: AppRepo,
    private val scope: CoroutineScope,
) {
    var page by mutableStateOf(Page.HOME); private set
    var zone by mutableStateOf(Zone.ROW); private set
    var i by mutableIntStateOf(0); private set
    var m by mutableIntStateOf(0); private set
    var nav by mutableIntStateOf(0); private set
    var actI by mutableIntStateOf(0); private set
    var cc by mutableStateOf(false); private set
    var ccI by mutableIntStateOf(0); private set
    var set by mutableIntStateOf(0); private set
    var det by mutableIntStateOf(0); private set
    var ctxApp by mutableStateOf<AppEntry?>(null); private set
    var ctxI by mutableIntStateOf(0); private set
    var toastText by mutableStateOf(""); private set
    var toastOn by mutableStateOf(false); private set
    var minute by mutableLongStateOf(System.currentTimeMillis()); private set
    var resumed by mutableStateOf(true)
    var loaded by mutableStateOf(false); private set

    private var installed by mutableStateOf<List<AppEntry>>(emptyList())
    var favs by mutableStateOf<Set<String>>(emptySet()); private set
    private var order by mutableStateOf<List<String>>(emptyList())

    /** Zoals apps() in het prototype: favorieten eerst, verder in de opgeslagen volgorde. */
    val apps: List<AppEntry> by derivedStateOf {
        val byPkg = installed.associateBy { it.pkg }
        val known = order.toHashSet()
        val ordered = order.mapNotNull { byPkg[it] } + installed.filter { it.pkg !in known }.sortedWith(defaultOrder)
        val f = favs
        ordered.filter { it.pkg in f } + ordered.filter { it.pkg !in f }
    }
    val rowApps: List<AppEntry> by derivedStateOf { apps.take(ROW_MAX) }
    val focusedApp: AppEntry? by derivedStateOf { rowApps.getOrNull(i.coerceAtMost(rowApps.size - 1)) }

    /** Grid-rij die in beeld moet zijn; scrollt het raster mee als je verder dan 3 rijen naar beneden gaat. */
    val homeScroll: Float by derivedStateOf {
        if (zone == Zone.MENU) -820f - (m / GRID_COLS - 2).coerceAtLeast(0) * 280f else 0f
    }

    val setItems: List<SetItem> get() = listOf(
        SetItem("net", tr("Netwerk & internet", "Network & internet"), Color(0xFF2F7BF6)) { Ic.wifi },
        SetItem("disp", tr("Scherm & beeld", "Display & picture"), Color(0xFFFF9500)) { Ic.display },
        SetItem("snd", tr("Geluid", "Sound"), Color(0xFFFF3B30)) { Ic.sound },
        SetItem("bt", tr("Bluetooth & apparaten", "Bluetooth & devices"), Color(0xFF007AFF)) { Ic.bt },
        SetItem("cast", tr("Scherm casten & spiegelen", "Cast & screen mirroring"), Color(0xFF5856D6)) { Ic.cast },
        SetItem("input", tr("Ingangen / Bron (HDMI)", "Inputs / Source (HDMI)"), Color(0xFF00A3A3)) { Ic.input },
        SetItem("apps", "Apps", Color(0xFF34C759)) { Ic.apps },
        SetItem("time", tr("Datum & tijd", "Date & time"), Color(0xFF8E8E93)) { Ic.time },
        SetItem("lang", tr("Taal", "Language"), Color(0xFFAF52DE)) { Ic.globe },
        SetItem("all", tr("Alle Android-instellingen", "All Android settings"), Color(0xFF3A3A3C)) { Ic.cog },
    )

    /* ------------------------------ laden ------------------------------ */

    fun load() {
        scope.launch {
            val prefs = withContext(Dispatchers.IO) { act.novaStore.data.first() }
            Lang.mode = prefs[K_LANG] ?: "auto"
            favs = prefs[K_FAVS] ?: emptySet()
            order = prefs[K_ORDER]?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
            val cached = repo.deserialize(prefs[K_CACHE])
            if (cached.isNotEmpty()) { installed = cached; loaded = true }
            refreshApps()
        }
    }

    private var refreshJob: Job? = null
    fun refreshApps() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val l = withContext(Dispatchers.Default) { repo.query() }
            if (l != installed) {
                installed = l
                val s = repo.serialize(l)
                withContext(Dispatchers.IO) { act.novaStore.edit { it[K_CACHE] = s } }
            }
            loaded = true
            clamp()
            // iconen alvast renderen: eerst de rij, dan de rest
            val tile = tilePx(); val big = bigPx()
            apps.forEachIndexed { k, a -> if (k < ROW_MAX) Icons.load(act, a, tile, false) }
            focusedApp?.let { Icons.load(act, it, big, true) }
            apps.forEach { Icons.load(act, it, tile, false) }
        }
    }

    fun tilePx() = Math.round(186f * 1.12f * Wall.deviceW / 1920f)
    fun bigPx() = Math.round(400f * Wall.deviceW / 1920f)

    private fun save() {
        val f = favs; val o = apps.map { it.pkg }
        order = o
        scope.launch(Dispatchers.IO) { act.novaStore.edit { it[K_FAVS] = f; it[K_ORDER] = o.joinToString("\n") } }
    }

    private fun clamp() {
        val n = apps.size
        i = i.coerceIn(0, (minOf(n, ROW_MAX) - 1).coerceAtLeast(0))
        m = m.coerceIn(0, (n - 1).coerceAtLeast(0))
    }

    /* ------------------------------ klok ------------------------------ */

    suspend fun clockLoop() {
        while (true) {
            minute = System.currentTimeMillis()
            val now = System.currentTimeMillis()
            delay(60_000L - now % 60_000L + 50L)
        }
    }

    private val wdShortNl = arrayOf("zo", "ma", "di", "wo", "do", "vr", "za")
    private val wdLongNl = arrayOf("zondag", "maandag", "dinsdag", "woensdag", "donderdag", "vrijdag", "zaterdag")
    private val monShortNl = arrayOf("jan", "feb", "mrt", "apr", "mei", "jun", "jul", "aug", "sep", "okt", "nov", "dec")
    private val monLongNl = arrayOf("januari", "februari", "maart", "april", "mei", "juni", "juli", "augustus", "september", "oktober", "november", "december")
    private val wdShortEn = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    private val wdLongEn = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    private val monShortEn = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val monLongEn = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    private fun cal() = Calendar.getInstance().apply { timeInMillis = minute }
    val timeText: String by derivedStateOf { val c = cal(); "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)) }
    val dateShort: String by derivedStateOf {
        val c = cal(); val d = c.get(Calendar.DAY_OF_WEEK) - 1; val day = c.get(Calendar.DAY_OF_MONTH); val mo = c.get(Calendar.MONTH)
        if (Lang.en) "${wdShortEn[d]} ${monShortEn[mo]} $day" else "${wdShortNl[d]} $day ${monShortNl[mo]}"
    }
    val dateLong: String by derivedStateOf {
        val c = cal(); val d = c.get(Calendar.DAY_OF_WEEK) - 1; val day = c.get(Calendar.DAY_OF_MONTH); val mo = c.get(Calendar.MONTH)
        if (Lang.en) "${wdLongEn[d]}, ${monLongEn[mo]} $day" else "${wdLongNl[d]} $day ${monLongNl[mo]}"
    }

    /* ------------------------------ toasts ------------------------------ */

    private var toastJob: Job? = null
    fun toast(t: String) {
        toastText = t; toastOn = true
        toastJob?.cancel()
        toastJob = scope.launch { delay(1800); toastOn = false }
    }

    /* ------------------------------ acties ------------------------------ */

    fun openApp(a: AppEntry?) {
        a ?: return
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(if (a.tv) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(a.pkg, a.cls))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try { act.startActivity(intent) } catch (_: Exception) {
            val alt = act.packageManager.getLeanbackLaunchIntentForPackage(a.pkg) ?: act.packageManager.getLaunchIntentForPackage(a.pkg)
            if (alt == null || !Nav.open(act, listOf(alt))) toast(tr("${a.label} kan niet geopend worden", "Can't open ${a.label}"))
        }
    }

    fun toggleFav(a: AppEntry?) {
        a ?: return
        val inRow = zone != Zone.MENU
        if (a.pkg in favs) { favs = favs - a.pkg; toast(tr("${a.label} uit favorieten", "${a.label} removed from favourites")) }
        else { favs = favs + a.pkg; toast(tr("${a.label} toegevoegd aan favorieten", "${a.label} added to favourites")) }
        save()
        val idx = apps.indexOfFirst { it.pkg == a.pkg }
        if (inRow) i = idx.coerceIn(0, ROW_MAX - 1) else m = idx
        clamp()
    }

    fun move(a: AppEntry, dir: Int) {
        val l = apps.toMutableList()
        val from = l.indexOfFirst { it.pkg == a.pkg }
        val to = from + dir
        if (from < 0 || to < 0 || to >= l.size) { toast(tr("Kan niet verder", "Can't move further")); return }
        if ((l[to].pkg in favs) != (a.pkg in favs)) { toast(tr("Favorieten staan altijd vooraan", "Favourites always come first")); return }
        l[from] = l[to]; l[to] = a
        order = l.map { it.pkg }
        save()
        if (zone == Zone.MENU) m = to else if (to < ROW_MAX) i = to else { zone = Zone.MENU; m = to }
    }

    fun openStore() { if (!Nav.open(act, Nav.store(act))) toast(tr("Play Store niet gevonden", "Play Store not found")) }

    fun open(list: List<Intent>, fail: String = tr("Dit scherm bestaat niet op deze tv", "This screen isn't available on this TV")) {
        if (!Nav.open(act, list)) toast(fail)
    }

    fun onHome() {
        cc = false; ctxApp = null
        if (page != Page.HOME) page = Page.HOME
        zone = Zone.ROW
    }

    private fun go(p: Int) {
        when (p) {
            0 -> { page = Page.HOME; nav = 0; zone = Zone.ROW }
            1 -> { nav = 1; openStore() }
            2 -> { page = Page.SETTINGS; nav = 2; zone = Zone.SET; sys.refresh() }
        }
    }

    private fun pageZone() = if (page == Page.HOME) Zone.ACT else Zone.SET

    /* -------------------------- Instellingen: details -------------------------- */

    fun detailRows(key: String): List<Opt> {
        val s = sys
        val open = tr("Openen →", "Open →")
        return when (key) {
            "net" -> listOfNotNull(
                Opt("Wifi", when { s.wifiConnected -> tr("Verbonden", "Connected"); s.wifiEnabled -> tr("Aan · niet verbonden", "On · not connected"); else -> tr("Uit", "Off") }, sw = s.wifiEnabled, action = { open(Nav.NETWORK) }),
                Opt("Ethernet", if (s.ethConnected) tr("Verbonden via kabel", "Connected by cable") else tr("Geen kabel", "No cable"), pill = if (s.ethConnected) "✓" else "—"),
                Opt(tr("IP-adres", "IP address"), s.ip ?: tr("Geen verbinding", "Not connected"), pill = if (s.ip != null) "IPv4" else "—"),
                Opt(tr("Privé-DNS", "Private DNS"), s.privateDns ?: tr("Automatisch", "Automatic"), sw = s.privateDns != null, action = { open(Nav.NETWORK) }),
                Opt(tr("Netwerkinstellingen", "Network settings"), tr("Wifi kiezen, wachtwoord, proxy en IP", "Choose Wi-Fi, password, proxy and IP"), pill = open, action = { open(Nav.NETWORK) }),
            )
            "disp" -> listOf(
                Opt(tr("Resolutie", "Resolution"), s.displayMode.ifEmpty { "—" }, pill = s.displayTag.ifEmpty { "—" }),
                Opt(tr("Beeldmodus", "Picture mode"), tr("Beeldinstellingen van de tv", "The TV's picture settings"), pill = tr("Wijzigen", "Change"), action = { open(Nav.PICTURE) }),
                Opt(tr("Helderheid", "Brightness"), if (s.canWrite) "${s.briPct}%" else tr("Toestemming nodig", "Permission needed"), pill = tr("Via bedieningspaneel", "In control panel")),
                Opt(tr("Scherm & geluid", "Display & sound"), tr("Alle beeldinstellingen van Android", "All Android display settings"), pill = open, action = { open(Nav.DISPLAY) }),
            )
            "snd" -> listOf(
                Opt("Volume", if (s.volFixed) tr("Geregeld door het aangesloten apparaat", "Controlled by the connected device") else "${s.volPct}%", pill = tr("Via bedieningspaneel", "In control panel")),
                Opt(tr("Geluidsuitvoer", "Sound output"), s.output, pill = tr("Kiezen", "Choose"), action = { open(Nav.SOUND) }),
                Opt(tr("Geluidsinstellingen", "Sound settings"), tr("Surround, dialoogverbetering, HDMI ARC", "Surround, dialogue enhancement, HDMI ARC"), pill = open, action = { open(Nav.SOUND) }),
            )
            "bt" -> listOf(
                Opt("Bluetooth", if (s.btOn) (s.btAudio?.let { tr("Aan · $it verbonden", "On · $it connected") } ?: tr("Aan", "On")) else tr("Uit", "Off"), sw = s.btOn, action = { open(Nav.BLUETOOTH) }),
                Opt(tr("Apparaat koppelen", "Pair a device"), tr("Zoek naar koptelefoon, speaker of controller", "Find headphones, a speaker or a controller"), pill = tr("Zoeken", "Search"), action = { open(Nav.PAIR) }),
                Opt(tr("Afstandsbedieningen & accessoires", "Remotes & accessories"), tr("Gekoppelde apparaten beheren", "Manage paired devices"), pill = open, action = { open(Nav.ALL) }),
            )
            "cast" -> listOf(
                Opt(tr("Casten ontvangen", "Receive casts"), if (s.castAvail) tr("Chromecast built-in: andere toestellen kunnen naar deze tv casten", "Chromecast built-in: other devices can cast to this TV") else tr("Niet beschikbaar op deze tv", "Not available on this TV"),
                    sw = s.castAvail, action = { open(Nav.CAST) }),
                Opt(tr("Scherm spiegelen", "Screen mirroring"), tr("Toon je gsm- of pc-scherm op de tv", "Show your phone or PC screen on the TV"), pill = tr("Uitleg", "How to"),
                    action = { toast(tr("Kies op je gsm of laptop ‘Casten’ en selecteer deze tv", "On your phone or laptop choose ‘Cast’ and select this TV")) }),
                Opt(tr("Laptop als (tweede) scherm", "Laptop as (second) screen"), tr("Met een HDMI-kabel: kies daarna de juiste HDMI-ingang", "With an HDMI cable: then choose the right HDMI input"), pill = tr("Bron", "Source"), action = { open(Nav.INPUTS) }),
            )
            "input" -> s.inputs.map { inp ->
                Opt(inp.label, when (inp.state) { 0 -> tr("Apparaat aangesloten", "Device connected"); 1 -> tr("Apparaat in stand-by", "Device on standby"); else -> tr("Niets aangesloten", "Nothing connected") },
                    pill = tr("Kiezen", "Choose"), action = { open(Nav.input(inp.id) + Nav.INPUTS) })
            } + Opt(tr("Alle ingangen", "All inputs"), tr("Het bronnenmenu van de tv", "The TV's source menu"), pill = open, action = { open(Nav.INPUTS) })
            "apps" -> listOf(Opt(tr("Apps beheren", "Manage apps"), tr("Opslag, rechten en verwijderen", "Storage, permissions and uninstall"), pill = open, action = { open(Nav.APPS) })) +
                apps.map { a -> Opt(a.label, a.cat, pill = if (a.pkg in favs) tr("★ Favoriet", "★ Favourite") else tr("Geïnstalleerd", "Installed"), action = { open(Nav.appInfo(a.pkg)) }) }
            "time" -> listOf(
                Opt(tr("Automatisch instellen", "Set automatically"), tr("Via netwerk", "From the network"), sw = s.autoTime, action = { open(Nav.DATE) }),
                Opt(tr("Tijdzone", "Time zone"), s.tz, pill = tr("Wijzigen", "Change"), action = { open(Nav.DATE) }),
                Opt(tr("24-uursnotatie", "24-hour clock"), null, sw = s.is24, action = { open(Nav.DATE) }),
            )
            "lang" -> listOf(
                Opt(tr("Automatisch", "Automatic"), tr("Volgt de taal van je tv", "Follows your TV's language"), pill = if (Lang.mode == "auto") "✓" else tr("Kiezen", "Choose"), action = { setLang("auto") }),
                Opt("Nederlands", "Nederlands", pill = if (Lang.mode == "nl") "✓" else tr("Kiezen", "Choose"), action = { setLang("nl") }),
                Opt("English", "English", pill = if (Lang.mode == "en") "✓" else tr("Kiezen", "Choose"), action = { setLang("en") }),
            )
            else -> listOf(Opt(tr("Android-instellingen openen", "Open Android settings"), tr("Opent het volledige instellingenmenu van Android", "Opens Android's full settings menu"), pill = open, action = { open(Nav.ALL) }))
        }
    }

    /** Taal wijzigen: meteen zichtbaar, bewaard en app-beschrijvingen opnieuw geladen. */
    fun setLang(mode: String) {
        Lang.mode = mode
        scope.launch(Dispatchers.IO) { act.novaStore.edit { it[K_LANG] = mode } }
        sys.refresh()
        refreshApps()
        toast(tr("Taal: Nederlands", "Language: English"))
    }
    private fun primary(key: String): List<Intent> = when (key) {
        "net" -> Nav.NETWORK; "disp" -> Nav.DISPLAY; "snd" -> Nav.SOUND; "bt" -> Nav.BLUETOOTH; "cast" -> Nav.CAST
        "input" -> Nav.INPUTS; "apps" -> Nav.APPS; "time" -> Nav.DATE; "lang" -> emptyList(); else -> Nav.ALL
    }

    /** Indexen van rijen met een actie (focusbaar in het detailpaneel). */
    fun actionable(rows: List<Opt>) = rows.indices.filter { rows[it].action != null }

    /* -------------------------- bedieningspaneel -------------------------- */

    fun ccTiles(): List<CcTile> {
        val s = sys
        return listOf(
            CcTile("Wifi", when { s.wifiConnected -> tr("Verbonden", "Connected"); s.wifiEnabled -> tr("Niet verbonden", "Not connected"); else -> tr("Uit", "Off") }, s.wifiEnabled, Ic.wifi),
            CcTile("Bluetooth", if (s.btOn) (if (s.btAudio != null) tr("Speaker verbonden", "Speaker connected") else tr("Aan", "On")) else tr("Uit", "Off"), s.btOn, Ic.bt),
            CcTile(tr("Casten", "Cast"), if (s.castAvail) tr("Klaar om te ontvangen", "Ready to receive") else tr("Uit", "Off"), s.castAvail, Ic.cast),
            CcTile(tr("Bron", "Source"), s.inputs.count { it.hdmi }.let { if (it > 0) "$it × HDMI" else tr("Ingangen", "Inputs") }, false, Ic.display),
            CcTile(tr("Niet storen", "Do not disturb"), if (s.dnd) tr("Aan", "On") else tr("Uit", "Off"), s.dnd, Ic.moon),
            CcTile(tr("Instellingen", "Settings"), tr("Alle instellingen", "All settings"), false, Ic.cog),
        )
    }

    private fun ccAction() {
        when (ccI) {
            0 -> open(Nav.NETWORK)
            1 -> open(Nav.BLUETOOTH)
            2 -> open(Nav.CAST)
            3 -> open(Nav.INPUTS)
            4 -> open(Nav.DND, tr("Niet storen staat op deze tv in de Android-instellingen", "Do not disturb is in the Android settings on this TV"))
            5 -> { cc = false; go(2) }
            6 -> open(Nav.VOLUME_PANEL)
            7 -> if (!sys.canWrite) requestWrite()
        }
    }

    fun requestWrite() {
        if (!Nav.open(act, Nav.writeSettings(act.packageName)))
            toast(tr("Deze tv heeft daar geen scherm voor (zie FAQ op GitHub)", "This TV has no screen for that (see the FAQ on GitHub)"))
    }

    private fun openCc() { cc = true; ccI = 0; sys.refresh() }

    /* -------------------------- contextmenu (lang OK) -------------------------- */

    val ctxItems: List<Pair<String, ImageVector>> get() {
        val a = ctxApp ?: return emptyList()
        return listOf(
            (if (a.pkg in favs) tr("Uit favorieten halen", "Remove from favourites") else tr("Aan favorieten toevoegen", "Add to favourites")) to Ic.starLine,
            tr("Naar links verplaatsen", "Move left") to Ic.left,
            tr("Naar rechts verplaatsen", "Move right") to Ic.right,
            "App info" to Ic.info,
        )
    }

    private fun openCtx() {
        val a = if (zone == Zone.MENU) apps.getOrNull(m) else if (zone == Zone.ROW || zone == Zone.ACT) focusedApp else null
        a ?: return
        ctxApp = a; ctxI = 0
    }

    private fun ctxAction() {
        val a = ctxApp ?: return
        when (ctxI) {
            0 -> { toggleFav(a); ctxApp = null }
            1 -> move(a, -1)
            2 -> move(a, +1)
            3 -> { ctxApp = null; open(Nav.appInfo(a.pkg)) }
        }
    }

    /* ------------------------------ toetsen ------------------------------ */

    private var okDown = false
    private var okLong = false

    /** Afstandsbediening → zelfde logica als de keydown-handler van het prototype. */
    fun onKey(e: KeyEvent): Boolean {
        val k = e.keyCode
        val isOk = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A
        val isBack = k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE || k == KeyEvent.KEYCODE_BUTTON_B
        val isMenu = k == KeyEvent.KEYCODE_MENU
        val isArrow = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
        if (!isOk && !isBack && !isArrow && !isMenu) return false

        if (isOk) {
            if (e.action == KeyEvent.ACTION_DOWN) {
                if (e.repeatCount == 0) { okDown = true; okLong = false }
                else if (okDown && !okLong) { okLong = true; key("long") }
            } else if (e.action == KeyEvent.ACTION_UP) {
                if (okDown && !okLong) key("ok")
                okDown = false; okLong = false
            }
            return true
        }
        if (isBack) { if (e.action == KeyEvent.ACTION_UP) key("back"); return true }
        if (isMenu) { if (e.action == KeyEvent.ACTION_UP) key("long"); return true }
        if (e.action == KeyEvent.ACTION_DOWN) key(when (k) {
            KeyEvent.KEYCODE_DPAD_UP -> "up"; KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"; else -> "right"
        })
        return true
    }

    fun key(k: String) {
        // contextmenu
        ctxApp?.let {
            when (k) {
                "up" -> ctxI = (ctxI - 1).coerceAtLeast(0)
                "down" -> ctxI = (ctxI + 1).coerceAtMost(3)
                "ok" -> ctxAction()
                "back", "long" -> ctxApp = null
            }
            return
        }
        // bedieningspaneel
        if (cc) {
            val tiles = 6
            val n = tiles + 2
            when (k) {
                "back" -> { cc = false; zone = Zone.STATUS }
                "right" -> if (ccI < tiles) ccI = (ccI + 1).coerceAtMost(n - 1) else slider(ccI - tiles, true)
                "left" -> if (ccI < tiles) ccI = (ccI - 1).coerceAtLeast(0) else slider(ccI - tiles, false)
                "down" -> ccI = (if (ccI < tiles) ccI + 2 else ccI + 1).coerceAtMost(n - 1)
                "up" -> ccI = (if (ccI < tiles) ccI - 2 else ccI - 1).coerceAtLeast(0)
                "ok" -> ccAction()
            }
            return
        }
        val L = apps.size
        when (zone) {
            Zone.NAV -> when (k) {
                "left" -> nav = (nav - 1).coerceAtLeast(0)
                "right" -> if (nav < 2) nav++ else zone = Zone.STATUS
                "ok" -> go(nav)
                "down" -> zone = pageZone()
                "back" -> if (page == Page.SETTINGS) go(0) else zone = Zone.ROW
            }
            Zone.STATUS -> when (k) {
                "left" -> { zone = Zone.NAV; nav = 2 }
                "ok" -> openCc()
                "down" -> zone = pageZone()
                "back" -> if (page == Page.SETTINGS) go(0) else zone = Zone.ROW
            }
            Zone.ACT -> when (k) {
                "left" -> actI = 0
                "right" -> actI = 1
                "down" -> zone = Zone.ROW
                "up" -> zone = Zone.NAV
                "ok" -> if (actI == 0) openApp(focusedApp) else toggleFav(focusedApp)
                "long" -> openCtx()
                "back" -> zone = Zone.ROW
            }
            Zone.ROW -> when (k) {
                "left" -> i = (i - 1).coerceAtLeast(0)
                "right" -> i = (i + 1).coerceAtMost(minOf(L, ROW_MAX) - 1).coerceAtLeast(0)
                "up" -> zone = Zone.ACT
                "down" -> if (L > 0) { zone = Zone.MENU; m = i.coerceAtMost(L - 1) }
                "ok" -> openApp(focusedApp)
                "long" -> openCtx()
            }
            Zone.MENU -> when (k) {
                "left" -> m = (m - 1).coerceAtLeast(0)
                "right" -> m = (m + 1).coerceAtMost(L - 1)
                "down" -> m = (m + GRID_COLS).coerceAtMost(L - 1)
                "up" -> if (m < GRID_COLS) { zone = Zone.ROW; i = m.coerceAtMost(ROW_MAX - 1).coerceAtMost(minOf(L, ROW_MAX) - 1) } else m -= GRID_COLS
                "ok" -> openApp(apps.getOrNull(m))
                "long" -> openCtx()
                "back" -> zone = Zone.ROW
            }
            Zone.SET -> when (k) {
                "down" -> { set = (set + 1).coerceAtMost(setItems.size - 1); det = 0 }
                "up" -> if (set == 0) zone = Zone.NAV else { set--; det = 0 }
                "right" -> { val a = actionable(detailRows(setItems[set].key)); if (a.isNotEmpty()) { zone = Zone.DETAIL; det = a.first() } }
                "ok" -> { val p = primary(setItems[set].key); if (p.isEmpty()) key("right") else open(p) }
                "back" -> go(0)
            }
            Zone.DETAIL -> {
                val rows = detailRows(setItems[set].key)
                val a = actionable(rows)
                val pos = a.indexOf(det)
                when (k) {
                    "down" -> if (pos >= 0 && pos < a.size - 1) det = a[pos + 1]
                    "up" -> if (pos > 0) det = a[pos - 1]
                    "left", "back" -> zone = Zone.SET
                    "ok" -> rows.getOrNull(det)?.action?.invoke()
                }
            }
        }
    }

    private fun slider(idx: Int, up: Boolean) {
        if (idx == 0) sys.stepVolume(up) else if (sys.canWrite) sys.stepBrightness(up)
    }
}
