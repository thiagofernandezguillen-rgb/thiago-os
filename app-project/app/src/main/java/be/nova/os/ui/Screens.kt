package be.nova.os.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.paint
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.nova.os.data.AppEntry
import be.nova.os.data.Icons
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private val NL = Locale.forLanguageTag("nl-BE")

/* =============================== root =============================== */

@Composable
fun NovaRoot(st: NovaState, widthPx: Int, animateBg: Boolean) {
    // 1 dp = 1 px van het 1920×1080-ontwerp, zoals transform:scale() in het prototype
    val density = remember(widthPx) { Density(widthPx / 1920f, 1f) }
    CompositionLocalProvider(LocalDensity provides density) {
        Box(Modifier.requiredSize(1920.dp, 1080.dp)) {
            Wallpaper(animateBg && st.resumed)
            HomePage(st)
            SettingsPage(st)
            TopBar(st)
            ControlCenter(st)
            ContextMenu(st)
            Toast(st)
        }
    }
    LaunchedEffect(st.resumed) { if (st.resumed) st.clockLoop() }
}

@Composable
fun Glyph(v: ImageVector, size: Float, color: Color, modifier: Modifier = Modifier) {
    val p = rememberVectorPainter(v)
    val cf = remember(color) { ColorFilter.tint(color) }
    Box(modifier.size(size.dp).paint(p, colorFilter = cf))
}

private fun Modifier.focusRing(on: Boolean, offset: Float, radius: Float) =
    if (!on) this else drawWithContent { drawContent(); ring(offset, radius) }

/* ============================ achtergrond ============================ */

@Composable
private fun Wallpaper(animate: Boolean) {
    val w = Wall.state.value ?: return // tot dan toont het venster hetzelfde kleurverloop
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) { fade.animateTo(1f, tween(220)) }
    if (animate) {
        // Alleen op toestellen met >2 GB RAM: heel trage verschuiving/schaal via graphicsLayer.
        val t = rememberInfiniteTransition(label = "wall")
        val p = t.animateFloat(0f, 1f, infiniteRepeatable(tween(26000, easing = Ez.ease), RepeatMode.Reverse), label = "p")
        Spacer(Modifier.fillMaxSize().graphicsLayer {
            alpha = fade.value
            val v = p.value
            scaleX = 1.07f + 0.04f * v; scaleY = scaleX
            translationX = (-25f + 50f * v) * density; translationY = (12f - 24f * v) * density
        }.drawBehind { drawImage(w.image) })
    } else {
        Spacer(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.drawBehind { drawImage(w.image) })
    }
}

/* ============================== topbalk ============================== */

@Composable
private fun TopBar(st: NovaState) {
    Box(Modifier.offset(80.dp, 34.dp).size(1760.dp, 84.dp)) {
        Row(Modifier.align(Alignment.CenterStart)) {
            BasicText("Thiago", Modifier.alignByBaseline(), style = T.brand)
            // <small> met vertical-align:middle → ±4.3px boven de basislijn
            BasicText("OS", Modifier.alignByBaseline().padding(start = 8.dp).graphicsLayer { translationY = -4.3f * density }, style = T.brandSmall)
        }
        NavPill(st, Modifier.align(Alignment.Center))
        StatusPill(st, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun NavPill(st: NovaState, modifier: Modifier) {
    val s = LocalDensity.current.density
    val origin = remember(s) { { sz: IntSize -> IntOffset(((1920 * s - sz.width) / 2).roundToInt(), (34 * s + (84 * s - sz.height) / 2).roundToInt()) } }
    val navFocus = st.zone == Zone.NAV && !st.cc && st.ctxApp == null
    Row(
        modifier.deco(Decos.glass, origin = origin).padding(9.5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NavBtn(Ic.home, "Home", st.page == Page.HOME, navFocus && st.nav == 0)
        NavBtn(Ic.store, "Store", false, navFocus && st.nav == 1)
        NavBtn(Ic.sliders, "Instellingen", st.page == Page.SETTINGS, navFocus && st.nav == 2)
    }
}

@Composable
private fun NavBtn(icon: ImageVector, label: String, on: Boolean, focus: Boolean) {
    val a = animateFloatAsState(if (on) 1f else 0f, tween(250, easing = Ez.ease), label = "on")
    Row(
        Modifier.deco(Decos.navOn, alpha = { a.value }).focusRing(focus, 2f, -1f).padding(horizontal = 34.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Glyph(icon, 24f, if (on) Nc.ink else Nc.ink2)
        BasicText(label, style = if (on) T.navOn else T.nav)
    }
}

@Composable
private fun StatusPill(st: NovaState, modifier: Modifier) {
    val s = LocalDensity.current.density
    val origin = remember(s) { { sz: IntSize -> IntOffset((1840 * s - sz.width).roundToInt(), (34 * s + (84 * s - sz.height) / 2).roundToInt()) } }
    val focus = st.zone == Zone.STATUS && !st.cc && st.ctxApp == null
    val sys = st.sys
    Row(
        modifier.deco(Decos.glassLite, origin = origin).focusRing(focus, 3f, -1f).padding(horizontal = 31.5.dp, vertical = 17.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        val conn = sys.connected
        Glyph(Ic.wifiStatus, 26f, Nc.ink2, Modifier.graphicsLayer { alpha = if (conn) 1f else 0.3f })
        if (sys.hasBattery) Battery(sys.battery)
        BasicText(st.dateShort, style = T.status)
        BasicText(st.timeText, style = T.statusTime)
    }
}

@Composable
private fun Battery(pct: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Spacer(Modifier.size(44.dp, 18.dp).drawBehind {
            val d = density
            val body = Size(38 * d, 18 * d)
            drawRoundRect(Nc.ink2.copy(alpha = .85f), size = body, cornerRadius = CornerRadius(6 * d), style = Stroke(2.5f * d))
            drawRoundRect(Nc.ink2.copy(alpha = .85f), topLeft = Offset(39.5f * d, 6 * d), size = Size(3 * d, 6 * d), cornerRadius = CornerRadius(2 * d))
            drawRoundRect(Nc.green, topLeft = Offset(4.5f * d, 4.5f * d), size = Size((29 * d) * pct / 100f, 9 * d), cornerRadius = CornerRadius(2 * d))
        })
        BasicText("$pct%", style = T.status)
    }
}

/* ================================ home ================================ */

@Composable
private fun HomePage(st: NovaState) {
    val pageA = animateFloatAsState(if (st.page == Page.HOME) 1f else 0f, tween(350, easing = Ez.ease), label = "page")
    val scroll = animateFloatAsState(st.homeScroll, tween(550, easing = Ez.out), label = "scroll")
    val menu = st.zone == Zone.MENU
    val heroA = animateFloatAsState(if (menu) 0f else 1f, tween(400, easing = Ez.ease), label = "hero")
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = pageA.value; translationY = scroll.value * density }) {
        val d = LocalDensity.current.density
        val dy = remember(d) { { scroll.value * d } }
        Hero(st, { heroA.value }, dy)
        BigIcon(st, Modifier.offset(1320.dp, 150.dp).graphicsLayer { alpha = heroA.value })
        Row(Modifier.offset(90.dp, 640.dp).graphicsLayer { alpha = heroA.value }) {
            BasicText("Apps", Modifier.alignByBaseline(), style = T.rowLabel)
            BasicText("${st.apps.size} geïnstalleerd", Modifier.alignByBaseline().padding(start = 14.dp), style = T.rowLabelSpan)
        }
        AppRow(st, Modifier.offset(90.dp, 710.dp).graphicsLayer { alpha = heroA.value })
        Peek(st, Modifier.offset(90.dp, 978.dp))
    }
}

@Composable
private fun Hero(st: NovaState, alpha: () -> Float, scrollPx: () -> Float) {
    val a = st.focusedApp ?: return
    val fav = a.pkg in st.favs
    val noOverlay = !st.cc && st.ctxApp == null
    Column(Modifier.offset(90.dp, 200.dp).width(900.dp).graphicsLayer { this.alpha = alpha() }) {
        BasicText(((if (fav) "Favoriet · " else "") + a.cat).uppercase(NL), style = T.kicker, maxLines = 1)
        Spacer(Modifier.height(18.dp))
        // line-height:1.05 → regelvak van 109.2px; de glyphs (1.21em) steken er gecentreerd boven/onder uit
        Box(Modifier.fillMaxWidth().height(109.2.dp), contentAlignment = Alignment.CenterStart) {
            BasicText(
                a.label, Modifier.wrapContentHeight(Alignment.CenterVertically, unbounded = true),
                style = T.h1, maxLines = 1, softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 48.sp, maxFontSize = 104.sp, stepSize = 4.sp),
            )
        }
        Spacer(Modifier.height(14.dp))
        BasicText(if (a.tv) "Geïnstalleerd · Android TV-app" else "Geïnstalleerd · Android-app", style = T.meta)
        Spacer(Modifier.height(22.dp))
        BasicText(a.desc, Modifier.widthIn(max = 820.dp), style = T.desc, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(56.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            BtnDark(st.zone == Zone.ACT && st.actI == 0 && noOverlay)
            BtnLight(fav, st.zone == Zone.ACT && st.actI == 1 && noOverlay, scrollPx)
        }
    }
}

@Composable
private fun BtnDark(focus: Boolean) {
    val sc = animateFloatAsState(if (focus) 1.04f else 1f, tween(200, easing = Ez.ease), label = "b1")
    Row(
        Modifier.graphicsLayer { scaleX = sc.value; scaleY = sc.value }.deco(Decos.btnDark).focusRing(focus, 4f, -1f)
            .padding(horizontal = 40.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Glyph(Ic.play, 22f, Color.White)
        BasicText("Openen", style = T.btnDark)
    }
}

@Composable
private fun BtnLight(fav: Boolean, focus: Boolean, scrollPx: () -> Float) {
    val sc = animateFloatAsState(if (focus) 1.04f else 1f, tween(200, easing = Ez.ease), label = "b2")
    Row(
        Modifier.graphicsLayer { scaleX = sc.value; scaleY = sc.value }.deco(Decos.glassLite, dy = scrollPx).focusRing(focus, 4f, -1f)
            .padding(horizontal = 41.5.dp, vertical = 23.5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Glyph(Ic.starLine, 22f, Nc.ink)
        BasicText(if (fav) "Uit favorieten halen" else "Aan favorieten toevoegen", style = T.btnLight)
    }
}

@Composable
private fun BigIcon(st: NovaState, modifier: Modifier) {
    val a = st.focusedApp ?: return
    val ctx = LocalContext.current
    val px = remember { st.bigPx() }
    val img = remember(a.iconKey) { mutableStateOf(Icons.peek(a, px)) }
    if (img.value == null) LaunchedEffect(a.iconKey) { img.value = Icons.load(ctx, a, px, true) }
    val shown = remember { mutableStateOf<ImageBitmap?>(null) }
    val prev = remember { mutableStateOf<ImageBitmap?>(null) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(img.value) {
        val n = img.value ?: return@LaunchedEffect
        if (shown.value !== n) {
            prev.value = shown.value; shown.value = n
            if (prev.value != null) { fade.snapTo(0f); fade.animateTo(1f, tween(400, easing = Ez.ease)) }
            prev.value = null
        }
    }
    Spacer(modifier.size(400.dp).drawBehind {
        drawDeco(Decos.bigIcon)
        val ds = IntSize(size.width.roundToInt(), size.height.roundToInt())
        prev.value?.let { drawImage(it, dstSize = ds, filterQuality = FilterQuality.Low) }
        shown.value?.let { drawImage(it, dstSize = ds, alpha = fade.value, filterQuality = FilterQuality.Low) }
    })
}

@Composable
private fun AppRow(st: NovaState, modifier: Modifier) {
    val list = st.rowApps
    val focusIdx = if (st.zone == Zone.ROW && st.page == Page.HOME && !st.cc) st.i else -1
    val favs = st.favs
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(34.dp)) {
        list.forEachIndexed { k, a -> key(a.pkg) { AppTile(a, k == focusIdx, a.pkg in favs, st.tilePx()) } }
    }
}

@Composable
private fun Peek(st: NovaState, modifier: Modifier) {
    val apps = st.apps
    val focus = if (st.zone == Zone.MENU && st.page == Page.HOME && !st.cc) st.m else -1
    val favs = st.favs
    val px = st.tilePx()
    Column(modifier) {
        Row {
            BasicText("Alle apps", Modifier.alignByBaseline(), style = T.rowLabel)
            BasicText("${apps.size} apps", Modifier.alignByBaseline().padding(start = 14.dp), style = T.rowLabelSpan)
        }
        Spacer(Modifier.height(22.dp))
        Column(verticalArrangement = Arrangement.spacedBy(46.dp)) {
            apps.chunked(GRID_COLS).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(34.dp)) {
                    row.forEachIndexed { c, a -> key(a.pkg) { AppTile(a, r * GRID_COLS + c == focus, a.pkg in favs, px) } }
                }
            }
        }
    }
}

@Composable
private fun AppTile(a: AppEntry, focused: Boolean, fav: Boolean, px: Int) {
    // Vaste hoogte (tegel + 1 regel naam) zodat elke rasterrij 280px hoog is, zoals in het prototype;
    // een tweede regel loopt over in de tussenruimte.
    Column(Modifier.width(186.dp).height(234.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(186.dp)) {
            TileFace(a, focused, px)
            if (fav) {
                Box(Modifier.align(Alignment.TopEnd).offset(8.dp, (-8).dp).size(40.dp).deco(Decos.fav), contentAlignment = Alignment.Center) {
                    Glyph(Ic.starFill, 22f, Nc.gold)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        val tm = rememberTextMeasurer(cacheSize = 0)
        val dens = LocalDensity.current
        val style = remember(a.label) {
            // Een woord dat breder is dan de tegel wordt niet afgebroken maar iets kleiner gezet.
            val longest = a.label.split(' ').maxByOrNull { it.length }.orEmpty()
            val w = tm.measure(longest, T.appName, softWrap = false, maxLines = 1).size.width / dens.density
            if (w > 178f) T.appName.copy(fontSize = (23f * 176f / w).sp) else T.appName
        }
        BasicText(
            a.label, Modifier.wrapContentHeight(Alignment.Top, unbounded = true),
            style = style, maxLines = 2, overflow = TextOverflow.Visible,
        )
    }
}

@Composable
private fun TileFace(a: AppEntry, focused: Boolean, px: Int) {
    val ctx = LocalContext.current
    val img = remember(a.iconKey) { mutableStateOf(Icons.peek(a, px)) }
    if (img.value == null) LaunchedEffect(a.iconKey) { img.value = Icons.load(ctx, a, px, false) }
    val sc = animateFloatAsState(if (focused) 1.12f else 1f, tween(250, easing = Ez.ease), label = "tile")
    Spacer(Modifier.size(186.dp).graphicsLayer { scaleX = sc.value; scaleY = sc.value }.drawBehind {
        val f = ((sc.value - 1f) / 0.12f).coerceIn(0f, 1f)
        drawDeco(Decos.tileShadow, 1f - f)
        drawDeco(Decos.tileFocus, f)
        val b = img.value
        if (b != null) drawImage(b, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low)
        else drawRoundRect(Color.White.copy(alpha = 0.45f), cornerRadius = CornerRadius(44f * density))
    })
}

/* ============================ instellingen ============================ */

@Composable
private fun SettingsPage(st: NovaState) {
    val show = st.page == Page.SETTINGS
    var ever by remember { mutableStateOf(false) }
    if (show) ever = true
    if (!ever) return
    val a = animateFloatAsState(if (show) 1f else 0f, tween(350, easing = Ez.ease), label = "set")
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = a.value }) {
        Column(Modifier.offset(90.dp, 180.dp)) {
            BasicText("Instellingen", style = T.h2)
            Spacer(Modifier.height(8.dp))
            BasicText("Snelkoppelingen naar de Android-instellingen.", style = T.sub)
        }
        SetList(st)
        SetDetail(st)
    }
}

@Composable
private fun SetList(st: NovaState) {
    val s = LocalDensity.current.density
    val origin = remember(s) { { _: IntSize -> IntOffset((90 * s).roundToInt(), (330 * s).roundToInt()) } }
    val scroll = animateFloatAsState(max(0f, (st.set + 1) * 92f - 641f), tween(250, easing = Ez.ease), label = "sl")
    val focusOn = st.zone == Zone.SET && st.page == Page.SETTINGS && !st.cc
    Box(Modifier.offset(90.dp, 330.dp).size(560.dp, 680.dp).deco(Decos.setList, origin = origin)) {
        Box(Modifier.fillMaxSize().padding(1.5.dp).graphicsLayer { shape = RoundedCornerShape(36.5.dp); clip = true }.padding(18.dp)) {
            Column(Modifier.wrapContentHeight(Alignment.Top, unbounded = true).graphicsLayer { translationY = -scroll.value * density }) {
                st.setItems.forEachIndexed { k, it ->
                    SiRow(it.name, it.color, it.icon(), k == st.set, focusOn && k == st.set)
                }
            }
        }
    }
}

@Composable
private fun SiRow(name: String, color: Color, icon: ImageVector, on: Boolean, focus: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().then(if (on) Modifier.deco(Decos.siOn) else Modifier).focusRing(focus, -3f, 24f)
            .padding(horizontal = 22.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Box(Modifier.size(52.dp).background(color, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
            Glyph(icon, 28f, Color.White)
        }
        BasicText(name, style = T.si, maxLines = 1)
    }
}

@Composable
private fun SetDetail(st: NovaState) {
    val s = LocalDensity.current.density
    val origin = remember(s) { { _: IntSize -> IntOffset((684 * s).roundToInt(), (330 * s).roundToInt()) } }
    val item = st.setItems[st.set]
    val rows = st.detailRows(item.key)
    val focusRow = if (st.zone == Zone.DETAIL && st.page == Page.SETTINGS && !st.cc) st.det else -1
    val scroll = animateFloatAsState(if (focusRow >= 0) max(0f, (focusRow + 1) * 106.25f - 509f) else 0f, tween(250, easing = Ez.ease), label = "sd")
    Box(Modifier.offset(684.dp, 330.dp).size(1146.dp, 680.dp).deco(Decos.setDetail, origin = origin)) {
        Box(Modifier.fillMaxSize().padding(1.5.dp).graphicsLayer { shape = RoundedCornerShape(36.5.dp); clip = true }
            .padding(horizontal = 50.dp, vertical = 44.dp)) {
            Column {
                BasicText(item.name, style = T.h3, maxLines = 1)
                Spacer(Modifier.height(26.dp))
                Column(Modifier.wrapContentHeight(Alignment.Top, unbounded = true).graphicsLayer { translationY = -scroll.value * density }) {
                    rows.forEachIndexed { k, o -> OptRow(o, k == focusRow) }
                }
            }
        }
    }
}

private val lineColor = Color(0x14141826)

@Composable
private fun OptRow(o: Opt, focus: Boolean) {
    Row(
        Modifier.fillMaxWidth().drawBehind {
            drawRect(lineColor, topLeft = Offset(0f, size.height - density), size = Size(size.width, density))
        }.padding(bottom = 1.dp).padding(horizontal = 4.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            BasicText(o.title, style = T.opt, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (o.sub != null) {
                Spacer(Modifier.height(4.dp))
                BasicText(o.sub, style = T.optSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(24.dp))
        when {
            o.sw != null -> Switch(o.sw, focus)
            o.pill != null -> Pill(o.pill, focus)
        }
    }
}

@Composable
private fun Pill(text: String, focus: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.deco(if (focus) Decos.pillDark else Decos.pill).focusRing(focus, 3f, -1f).padding(horizontal = 22.dp, vertical = 10.dp)) {
        BasicText(text, style = if (focus) T.pillOn else T.pill, maxLines = 1)
    }
}

private val swOff = Color(0x2E141826)

@Composable
private fun Switch(on: Boolean, focus: Boolean) {
    Box(Modifier.size(76.dp, 44.dp).background(if (on) Nc.green else swOff, CircleShape).focusRing(focus, 3f, -1f)) {
        Spacer(Modifier.offset(if (on) 36.dp else 4.dp, 4.dp).size(36.dp).deco(Decos.swKnob))
    }
}

/* ========================== bedieningspaneel ========================== */

@Composable
private fun ControlCenter(st: NovaState) {
    var ever by remember { mutableStateOf(false) }
    if (st.cc) ever = true
    if (!ever) return
    val p = animateFloatAsState(if (st.cc) 1f else 0f, tween(280, easing = Ez.out), label = "cc")
    val s = LocalDensity.current.density
    val origin = remember(s) { { _: IntSize -> IntOffset((1200 * s).roundToInt(), (130 * s).roundToInt()) } }
    val sys = st.sys
    val focus = if (st.cc) st.ccI else -1
    Column(
        Modifier.offset(1200.dp, 130.dp).width(640.dp)
            .graphicsLayer {
                alpha = p.value
                translationY = (1f - p.value) * -14f * density
                val sc = 0.97f + 0.03f * p.value; scaleX = sc; scaleY = sc
                transformOrigin = TransformOrigin(1f, 0f)
            }
            .deco(Decos.cc, origin = origin)
            .padding(31.5.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Column {
                BasicText(st.timeText, style = T.ccTime)
                Spacer(Modifier.height(6.dp))
                BasicText(st.dateLong, style = T.ccDate)
            }
            Pill(if (sys.connected) "● Verbonden" else if (sys.wifiEnabled) "○ Geen verbinding" else "○ Geen wifi", false)
        }
        Spacer(Modifier.height(24.dp))
        val tiles = st.ccTiles()
        for (r in 0 until 3) {
            if (r > 0) Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (c in 0..1) { val k = r * 2 + c; CcTileView(tiles[k], k == focus, Modifier.weight(1f)) }
            }
        }
        SliderRow(Ic.volume, sys.volPct, if (sys.volFixed) "Vast" else "${sys.volPct}%", focus == 6, null)
        if (sys.canWrite) SliderRow(Ic.sun, sys.briPct, "${sys.briPct}%", focus == 7, null)
        else SliderRow(Ic.sun, 0, "", focus == 7, "Helderheid · Toestemming geven")
        Row(Modifier.fillMaxWidth().padding(top = 18.dp).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText(if (sys.hasBattery) "Batterij ${sys.battery}%" else sys.ip?.let { "IP $it" } ?: "", style = T.foot)
            BasicText("← → kiezen · OK openen · Terug sluiten", style = T.foot)
        }
    }
}

@Composable
private fun CcTileView(t: CcTile, focus: Boolean, modifier: Modifier) {
    Row(
        modifier.fillMaxHeight().deco(Decos.ct).focusRing(focus, 2f, 28f).padding(21.5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(56.dp).background(if (t.on) Nc.accent else Color(0x1A141826), CircleShape), contentAlignment = Alignment.Center) {
            Glyph(t.icon, 28f, if (t.on) Color.White else Nc.ink2)
        }
        Column {
            BasicText(t.name, style = T.ctB, maxLines = 1)
            BasicText(t.sub, style = T.ctS, maxLines = 2)
        }
    }
}

private val trackBg = Color(0x1A141826)
private val trackRing = Color(0x14141826)

@Composable
private fun SliderRow(icon: ImageVector, pct: Int, value: String, focus: Boolean, button: String?) {
    Row(
        Modifier.padding(top = 16.dp).fillMaxWidth().deco(Decos.ct).focusRing(focus, 2f, 28f).padding(horizontal = 25.5.dp, vertical = 21.5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Glyph(icon, 30f, Nc.ink2)
        if (button != null) {
            Box(Modifier.weight(1f)) { Pill(button, focus) }
        } else {
            Spacer(Modifier.weight(1f).height(22.dp).clip(CircleShape).background(trackBg).drawBehind {
                val w = size.width * pct / 100f
                drawRect(Color.White, size = Size(w, size.height))
                drawRect(trackRing, size = Size(w, size.height), style = Stroke(density))
            })
            BasicText(value, Modifier.width(56.dp), style = T.sliderVal, maxLines = 1)
        }
    }
}

/* ============================ contextmenu ============================ */

@Composable
private fun ContextMenu(st: NovaState) {
    val app = st.ctxApp ?: return
    val items = st.ctxItems
    val menu = st.zone == Zone.MENU
    val (tx, ty) = if (menu) {
        val c = st.m % GRID_COLS; val r = st.m / GRID_COLS
        (90f + c * 220f) to (217f + (r - (r - 2).coerceAtLeast(0)) * 280f)
    } else (90f + st.i * 220f) to 710f
    val s = LocalDensity.current.density
    val pos = { w: Int, h: Int ->
        val wd = w / s; val hd = h / s
        val x = if (tx + 186f + 30f + wd <= 1840f) tx + 186f + 30f else tx - 30f - wd
        val y = (ty + 93f - hd / 2f).coerceIn(130f, 1040f - hd)
        IntOffset((x * s).roundToInt(), (y * s).roundToInt())
    }
    val colors = listOf(Nc.gold, Color(0xFF8E8E93), Color(0xFF8E8E93), Color(0xFF007AFF))
    Layout(
        content = {
            Column(Modifier.width(520.dp).deco(Decos.menu, origin = { sz -> pos(sz.width, sz.height) }).padding(19.5.dp)) {
                BasicText(app.label, Modifier.padding(horizontal = 22.dp, vertical = 12.dp), style = T.menuTitle, maxLines = 1)
                items.forEachIndexed { k, (label, icon) ->
                    SiRow(label, colors[k], icon, k == st.ctxI, k == st.ctxI)
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { ms, cons ->
        val p = ms[0].measure(Constraints())
        layout(cons.maxWidth, cons.maxHeight) { val o = pos(p.width, p.height); p.place(o.x, o.y) }
    }
}

/* =============================== toast =============================== */

@Composable
private fun Toast(st: NovaState) {
    val p = animateFloatAsState(if (st.toastOn) 1f else 0f, tween(300, easing = Ez.ease), label = "toast")
    if (!st.toastOn && p.value == 0f) return
    val s = LocalDensity.current.density
    val origin = remember(s) { { sz: IntSize -> IntOffset(((1920 * s - sz.width) / 2).roundToInt(), (1040 * s - sz.height).roundToInt()) } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.padding(bottom = 40.dp)
                .graphicsLayer { alpha = p.value; translationY = (1f - p.value) * 30f * density }
                .deco(Decos.glass, origin = origin)
                .padding(horizontal = 35.5.dp, vertical = 19.5.dp),
        ) { BasicText(st.toastText, style = T.toast, maxLines = 1) }
    }
}
