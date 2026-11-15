package be.nova.os.ui

import android.content.res.AssetManager
import android.graphics.Typeface
import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/** Kleuren uit :root van het prototype. */
object Nc {
    val ink = Color(0xFF0E1220)
    val ink2 = Color(0xFF4A5268)
    val accent = Color(0xFF4F5BD5)
    val dark = Color(0xFF141826)
    val green = Color(0xFF34C759)
    val gold = Color(0xFFF5B301)
}

/** CSS-easings. */
object Ez {
    val ease = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
    val out = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
}

/**
 * Inter (variabel lettertype met wght- en opsz-as), net als Google Fonts in de browser:
 * Chrome zet opsz automatisch gelijk aan de lettergrootte (14..32).
 */
object Inter {
    private lateinit var assets: AssetManager
    private val cache = ConcurrentHashMap<Int, FontFamily>()

    fun init(a: AssetManager) { assets = a }

    private fun opsz(size: Float) = size.roundToInt().coerceIn(14, 32)

    fun family(weight: Int, size: Float): FontFamily {
        val o = opsz(size)
        return cache.getOrPut(weight * 100 + o) { FontFamily(create(weight, o)) }
    }

    private fun create(w: Int, o: Int): Typeface =
        if (Build.VERSION.SDK_INT >= 26) {
            Typeface.Builder(assets, "fonts/inter.ttf")
                .setFontVariationSettings("'wght' $w, 'opsz' $o")
                .build()
        } else {
            val base = Typeface.createFromAsset(assets, "fonts/inter.ttf")
            if (w >= 600) Typeface.create(base, Typeface.BOLD) else base
        }
}

fun ts(size: Float, weight: Int = 400, color: Color = Nc.ink, ls: Float = 0f, lh: Float = 0f) = TextStyle(
    color = color,
    fontSize = size.sp,
    fontFamily = Inter.family(weight, size),
    fontSynthesis = FontSynthesis.None,
    letterSpacing = ls.sp,
    lineHeight = if (lh > 0f) (size * lh).sp else TextUnit.Unspecified,
    lineHeightStyle = if (lh > 0f) LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None) else null,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/** Alle tekststijlen van het prototype (px = dp in onze 1920×1080-ontwerpruimte). */
object T {
    val brand by lazy { ts(40f, 800, ls = -1.2f) }
    val brandSmall by lazy { ts(18f, 600, Nc.accent, ls = 2f) }
    val nav by lazy { ts(24f, 600, Nc.ink2) }
    val navOn by lazy { ts(24f, 600, Nc.ink) }
    val status by lazy { ts(23f, 600, Nc.ink2) }
    val statusTime by lazy { ts(27f, 700, Nc.ink) }
    val kicker by lazy { ts(21f, 700, Nc.accent, ls = 4f) }
    val h1 by lazy { ts(104f, 800, ls = -3.5f, lh = 1.05f) }
    val meta by lazy { ts(26f, 400, Nc.ink2) }
    val desc by lazy { ts(27f, 400, Nc.ink2, lh = 1.4f) }
    val btnDark by lazy { ts(25f, 700, Color.White) }
    val btnLight by lazy { ts(25f, 700, Nc.ink) }
    val rowLabel by lazy { ts(31f, 700) }
    val rowLabelSpan by lazy { ts(21f, 500, Nc.ink2) }
    val appName by lazy { ts(23f, 600).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
    val h2 by lazy { ts(64f, 800, ls = -2f) }
    val sub by lazy { ts(24f, 400, Nc.ink2) }
    val si by lazy { ts(25f, 600) }
    val h3 by lazy { ts(44f, 800, ls = -1f) }
    val opt by lazy { ts(25f, 400) }
    val optSmall by lazy { ts(19f, 400, Nc.ink2) }
    val pill by lazy { ts(20f, 600, Nc.ink2) }
    val pillOn by lazy { ts(20f, 600, Color.White) }
    val ccTime by lazy { ts(62f, 800, ls = -2f, lh = 1f) }
    val ccDate by lazy { ts(22f, 400, Nc.ink2) }
    val ctB by lazy { ts(22f, 700) }
    val ctS by lazy { ts(18f, 400, Nc.ink2) }
    val sliderVal by lazy { ts(20f, 700, Nc.ink2).copy(textAlign = androidx.compose.ui.text.style.TextAlign.End) }
    val foot by lazy { ts(19f, 400, Nc.ink2) }
    val toast by lazy { ts(22f, 600) }
    val menuTitle by lazy { ts(27f, 800, ls = -0.5f) }

    /** Laadt de lettertypes vooraf op een achtergrondthread. */
    fun prewarm() {
        listOf(brand, brandSmall, nav, navOn, status, statusTime, kicker, h1, meta, desc, btnDark, btnLight,
            rowLabel, rowLabelSpan, appName)
    }
}

/* ------------------------------ iconen ------------------------------ */

private enum class Mode { S, F, SF }

@Immutable
private class Part(val d: String, val mode: Mode = Mode.S)

private fun circle(cx: Float, cy: Float, r: Float) =
    "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0z"

private fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float): String {
    val iw = w - 2 * rx; val ih = h - 2 * rx
    return "M${x + rx},${y}h${iw}a$rx,$rx 0 0 1 $rx,$rx v${ih}a$rx,$rx 0 0 1 ${-rx},$rx h${-iw}" +
        "a$rx,$rx 0 0 1 ${-rx},${-rx}v${-ih}a$rx,$rx 0 0 1 $rx,${-rx}z"
}

private fun vec(
    name: String, vararg parts: Part,
    stroke: Float = 2.2f, cap: StrokeCap = StrokeCap.Round, join: StrokeJoin = StrokeJoin.Round,
): ImageVector {
    val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    val black = SolidColor(Color.Black)
    for (p in parts) {
        val nodes = PathParser().parsePathString(p.d).toNodes()
        b.addPath(
            pathData = nodes,
            fill = if (p.mode != Mode.S) black else null,
            stroke = if (p.mode != Mode.F) black else null,
            strokeLineWidth = stroke,
            strokeLineCap = cap,
            strokeLineJoin = join,
        )
    }
    return b.build()
}

object Ic {
    private const val WIFI = "M2.5 9a14 14 0 0 1 19 0M5.5 12.5a9.5 9.5 0 0 1 13 0M8.7 16a5 5 0 0 1 6.6 0"
    private const val STAR = "M12 3l2.8 5.8 6.2.9-4.5 4.4 1.1 6.2L12 17.4 6.4 20.3l1.1-6.2L3 9.7l6.2-.9z"
    private val COG = "M12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M5 19l2-2M17 7l2-2"

    val home by lazy { vec("home", Part("M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z")) }
    val store by lazy { vec("store", Part("M5 7h14l-1 13H6z"), Part("M9 7a3 3 0 0 1 6 0")) }
    val sliders by lazy {
        vec("sliders", Part("M4 7h10M18 7h2M4 17h4M12 17h8"), Part(circle(16f, 7f, 2.2f)), Part(circle(10f, 17f, 2.2f)),
            join = StrokeJoin.Miter)
    }
    val wifiStatus by lazy {
        vec("wifi", Part("M2.5 9a14 14 0 0 1 19 0"), Part("M5.5 12.5a9.5 9.5 0 0 1 13 0"), Part("M8.7 16a5 5 0 0 1 6.6 0"),
            Part(circle(12f, 19.3f, 1.2f), Mode.SF), stroke = 2.4f, join = StrokeJoin.Miter)
    }
    val play by lazy { vec("play", Part("M7 4l13 8-13 8z", Mode.F)) }
    val starLine by lazy { vec("starLine", Part(STAR), cap = StrokeCap.Butt) }
    val starFill by lazy { vec("starFill", Part(STAR, Mode.F)) }

    // Instellingen-lijst (SET) en bedieningspaneel (CT)
    val wifi by lazy { vec("w", Part(WIFI), Part(circle(12f, 19.3f, 1f), Mode.SF)) }
    val display by lazy { vec("d", Part(rect(3f, 5f, 18f, 12f, 2f)), Part("M9 21h6")) }
    val sound by lazy { vec("s", Part("M4 9h4l5-4v14l-5-4H4z"), Part("M17 9a4 4 0 0 1 0 6")) }
    val bt by lazy { vec("b", Part("M7 7l10 10-5 4V3l5 4L7 17")) }
    val cast by lazy { vec("c", Part("M3 18a3 3 0 0 1 3 3M3 14a7 7 0 0 1 7 7"), Part("M5 9V5h16v14h-5")) }
    val apps by lazy {
        vec("a", Part(rect(4f, 4f, 7f, 7f, 2f)), Part(rect(13f, 4f, 7f, 7f, 2f)), Part(rect(4f, 13f, 7f, 7f, 2f)),
            Part(rect(13f, 13f, 7f, 7f, 2f)))
    }
    val time by lazy { vec("t", Part(circle(12f, 12f, 9f)), Part("M12 7v5l3 2")) }
    val cog by lazy { vec("g", Part(circle(12f, 12f, 3f)), Part(COG)) }
    val moon by lazy { vec("m", Part("M20 14A8 8 0 1 1 10 4a6.5 6.5 0 0 0 10 10z")) }
    val input by lazy { vec("i", Part(rect(3f, 6f, 18f, 12f, 2f)), Part("M10 9.5l3.5 2.5-3.5 2.5z", Mode.SF), Part("M7 21h10")) }
    val volume by lazy { vec("v", Part("M4 9h4l5-4v14l-5-4H4z"), Part("M17 9a4 4 0 0 1 0 6")) }
    val sun by lazy {
        vec("sun", Part(circle(12f, 12f, 4f)),
            Part("M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"),
            join = StrokeJoin.Miter)
    }
    val left by lazy { vec("l", Part("M15 5l-7 7 7 7")) }
    val right by lazy { vec("r", Part("M9 5l7 7-7 7")) }
    val info by lazy { vec("n", Part(circle(12f, 12f, 9f)), Part("M12 11v6"), Part(circle(12f, 7.6f, 0.6f), Mode.SF)) }
}
