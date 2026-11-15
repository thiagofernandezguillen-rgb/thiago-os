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

    val setItems = listOf(
        SetItem("net", "Netwerk & internet", Color(0xFF2F7BF6)) { Ic.wifi },
        SetItem("disp", "Scherm & beeld", Color(0xFFFF9500)) { Ic.display },
        SetItem("snd", "Geluid", Color(0xFFFF3B30)) { Ic.sound },
        SetItem("bt", "Bluetooth & apparaten", Color(0xFF007AFF)) { Ic.bt },
        SetItem("cast", "Scherm casten & spiegelen", Color(0xFF5856D6)) { Ic.cast },
        SetItem("input", "Ingangen / Bron (HDMI)", Color(0xFF00A3A3)) { Ic.input },
        SetItem("apps", "Apps", Color(0xFF34C759)) { Ic.apps },
        SetItem("time", "Datum & tijd", Color(0xFF8E8E93)) { Ic.time },
        SetItem("all", "Alle Android-instellingen", Color(0xFF3A3A3C)) { Ic.cog },
    )

    /* ------------------------------ laden ------------------------------ */

    fun load() {
        scope.launch {
            val prefs = withContext(Dispatchers.IO) { act.novaStore.data.first() }
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

    private val wdShort = arrayOf("zo", "ma", "di", "wo", "do", "vr", "za")
    private val wdLong = arrayOf("zondag", "maandag", "dinsdag", "woensdag", "donderdag", "vrijdag", "zaterdag")
    private val monShort = arrayOf("jan", "feb", "mrt", "apr", "mei", "jun", "jul", "aug", "sep", "okt", "nov", "dec")
    private val monLong = arrayOf("januari", "februari", "maart", "april", "mei", "juni", "juli", "augustus", "september", "oktober", "november", "december")

    private fun cal() = Calendar.getInstance().apply { timeInMillis = minute }
    val timeText: String by derivedStateOf { val c = cal(); "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)) }
    val dateShort: String by derivedStateOf { val c = cal(); "${wdShort[c.get(Calendar.DAY_OF_WEEK) - 1]} ${c.get(Calendar.DAY_OF_MONTH)} ${monShort[c.get(Calendar.MONTH)]}" }
    val dateLong: String by derivedStateOf { val c = cal(); "${wdLong[c.get(Calendar.DAY_OF_WEEK) - 1]} ${c.get(Calendar.DAY_OF_MONTH)} ${monLong[c.get(Calendar.MONTH)]}" }

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
            if (alt == null || !Nav.open(act, listOf(alt))) toast("${a.label} kan niet geopend worden")
        }
    }

    fun toggleFav(a: AppEntry?) {
        a ?: return
        val inRow = zone != Zone.MENU
        if (a.pkg in favs) { favs = favs - a.pkg; toast("${a.label} uit favorieten") }
        else { favs = favs + a.pkg; toast("${a.label} toegevoegd aan favorieten") }
        save()
        val idx = apps.indexOfFirst { it.pkg == a.pkg }
        if (inRow) i = idx.coerceIn(0, ROW_MAX - 1) else m = idx
        clamp()
    }

    fun move(a: AppEntry, dir: Int) {
        val l = apps.toMutableList()
        val from = l.indexOfFirst { it.pkg == a.pkg }
        val to = from + dir
        if (from < 0 || to < 0 || to >= l.size) { toast("Kan niet verder"); return }
        if ((l[to].pkg in favs) != (a.pkg in favs)) { toast("Favorieten staan altijd vooraan"); return }
        l[from] = l[to]; l[to] = a
        order = l.map { it.pkg }
        save()
        if (zone == Zone.MENU) m = to else if (to < ROW_MAX) i = to else { zone = Zone.MENU; m = to }
    }

    fun openStore() { if (!Nav.open(act, Nav.store(act))) toast("Play Store niet gevonden") }

    fun open(list: List<Intent>, fail: String = "Dit scherm bestaat niet op deze tv") {
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
        return when (key) {
            "net" -> listOfNotNull(
                Opt("Wifi", when { s.wifiConnected -> "Verbonden"; s.wifiEnabled -> "Aan · niet verbonden"; else -> "Uit" }, sw = s.wifiEnabled, action = { open(Nav.NETWORK) }),
                Opt("Ethernet", if (s.ethConnected) "Verbonden via kabel" else "Geen kabel", pill = if (s.ethConnected) "✓" else "—"),
                Opt("IP-adres", s.ip ?: "Geen verbinding", pill = if (s.ip != null) "IPv4" else "—"),
                Opt("Privé-DNS", s.privateDns ?: "Automatisch", sw = s.privateDns != null, action = { open(Nav.NETWORK) }),
                Opt("Netwerkinstellingen", "Wifi kiezen, wachtwoord, proxy en IP", pill = "Openen →", action = { open(Nav.NETWORK) }),
            )
            "disp" -> listOf(
                Opt("Resolutie", s.displayMode.ifEmpty { "—" }, pill = s.displayTag.ifEmpty { "—" }),
                Opt("Beeldmodus", "Beeldinstellingen van de tv", pill = "Wijzigen", action = { open(Nav.PICTURE) }),
                Opt("Helderheid", if (s.canWrite) "${s.briPct}%" else "Toestemming nodig", pill = "Via bedieningspaneel"),
                Opt("Scherm & geluid", "Alle beeldinstellingen van Android", pill = "Openen →", action = { open(Nav.DISPLAY) }),
            )
            "snd" -> listOf(
                Opt("Volume", if (s.volFixed) "Geregeld door het aangesloten apparaat" else "${s.volPct}%", pill = "Via bedieningspaneel"),
                Opt("Geluidsuitvoer", s.output, pill = "Kiezen", action = { open(Nav.SOUND) }),
                Opt("Geluidsinstellingen", "Surround, dialoogverbetering, HDMI ARC", pill = "Openen →", action = { open(Nav.SOUND) }),
            )
            "bt" -> listOf(
                Opt("Bluetooth", if (s.btOn) (s.btAudio?.let { "Aan · $it verbonden" } ?: "Aan") else "Uit", sw = s.btOn, action = { open(Nav.BLUETOOTH) }),
                Opt("Apparaat koppelen", "Zoek naar koptelefoon, speaker of controller", pill = "Zoeken", action = { open(Nav.PAIR) }),
                Opt("Afstandsbedieningen & accessoires", "Gekoppelde apparaten beheren", pill = "Openen →", action = { open(Nav.ALL) }),
            )
            "cast" -> listOf(
                Opt("Casten ontvangen", if (s.castAvail) "Chromecast built-in: andere toestellen kunnen naar deze tv casten" else "Niet beschikbaar op deze tv",
                    sw = s.castAvail, action = { open(Nav.CAST) }),
                Opt("Scherm spiegelen", "Toon je gsm- of pc-scherm op de tv", pill = "Uitleg",
                    action = { toast("Kies op je gsm of laptop ‘Casten’ en selecteer deze tv") }),
                Opt("Laptop als (tweede) scherm", "Met een HDMI-kabel: kies daarna de juiste HDMI-ingang", pill = "Bron", action = { open(Nav.INPUTS) }),
            )
            "input" -> s.inputs.map { inp ->
                Opt(inp.label, when (inp.state) { 0 -> "Apparaat aangesloten"; 1 -> "Apparaat in stand-by"; else -> "Niets aangesloten" },
                    pill = "Kiezen", action = { open(Nav.input(inp.id) + Nav.INPUTS) })
            } + Opt("Alle ingangen", "Het bronnenmenu van de tv", pill = "Openen →", action = { open(Nav.INPUTS) })
            "apps" -> listOf(Opt("Apps beheren", "Opslag, rechten en verwijderen", pill = "Openen →", action = { open(Nav.APPS) })) +
                apps.map { a -> Opt(a.label, a.cat, pill = if (a.pkg in favs) "★ Favoriet" else "Geïnstalleerd", action = { open(Nav.appInfo(a.pkg)) }) }
            "time" -> listOf(
                Opt("Automatisch instellen", "Via netwerk", sw = s.autoTime, action = { open(Nav.DATE) }),
                Opt("Tijdzone", s.tz, pill = "Wijzigen", action = { open(Nav.DATE) }),
                Opt("24-uursnotatie", null, sw = s.is24, action = { open(Nav.DATE) }),
            )
            else -> listOf(Opt("Android-instellingen openen", "Opent het volledige instellingenmenu van Android", pill = "Openen →", action = { open(Nav.ALL) }))
        }
    }

    private fun primary(key: String): List<Intent> = when (key) {
        "net" -> Nav.NETWORK; "disp" -> Nav.DISPLAY; "snd" -> Nav.SOUND; "bt" -> Nav.BLUETOOTH; "cast" -> Nav.CAST
        "input" -> Nav.INPUTS; "apps" -> Nav.APPS; "time" -> Nav.DATE; else -> Nav.ALL
    }

    /** Indexen van rijen met een actie (focusbaar in het detailpaneel). */
    fun actionable(rows: List<Opt>) = rows.indices.filter { rows[it].action != null }

    /* -------------------------- bedieningspaneel -------------------------- */

    fun ccTiles(): List<CcTile> {
        val s = sys
        return listOf(
            CcTile("Wifi", when { s.wifiConnected -> "Verbonden"; s.wifiEnabled -> "Niet verbonden"; else -> "Uit" }, s.wifiEnabled, Ic.wifi),
            CcTile("Bluetooth", if (s.btOn) (if (s.btAudio != null) "Speaker verbonden" else "Aan") else "Uit", s.btOn, Ic.bt),
            CcTile("Casten", if (s.castAvail) "Klaar om te ontvangen" else "Uit", s.castAvail, Ic.cast),
            CcTile("Bron", s.inputs.count { it.hdmi }.let { if (it > 0) "$it × HDMI" else "Ingangen" }, false, Ic.display),
            CcTile("Niet storen", if (s.dnd) "Aan" else "Uit", s.dnd, Ic.moon),
            CcTile("Instellingen", "Alle instellingen", false, Ic.cog),
        )
    }

    private fun ccAction() {
        when (ccI) {
            0 -> open(Nav.NETWORK)
            1 -> open(Nav.BLUETOOTH)
            2 -> open(Nav.CAST)
            3 -> open(Nav.INPUTS)
            4 -> open(Nav.DND, "Niet storen staat op deze tv in de Android-instellingen")
            5 -> { cc = false; go(2) }
            6 -> open(Nav.VOLUME_PANEL)
            7 -> if (!sys.canWrite) requestWrite()
        }
    }

    fun requestWrite() {
        if (!Nav.open(act, Nav.writeSettings(act.packageName)))
            toast("Deze tv heeft daar geen scherm voor; zie HERSTEL.md (appops)")
    }

    private fun openCc() { cc = true; ccI = 0; sys.refresh() }

    /* -------------------------- contextmenu (lang OK) -------------------------- */

    val ctxItems: List<Pair<String, ImageVector>> get() {
        val a = ctxApp ?: return emptyList()
        return listOf(
            (if (a.pkg in favs) "Uit favorieten halen" else "Aan favorieten toevoegen") to Ic.starLine,
            "Naar links verplaatsen" to Ic.left,
            "Naar rechts verplaatsen" to Ic.right,
            "App-info" to Ic.info,
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
                "ok" -> open(primary(setItems[set].key))
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
