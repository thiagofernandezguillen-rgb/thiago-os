package be.nova.os.data

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.Inet4Address
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

@Immutable
data class TvIn(val id: String, val label: String, val state: Int, val hdmi: Boolean)

/** Alleen-lezen status van de tv. Nova OS verandert zelf niets aan het systeem (behalve volume/helderheid via de schuiven). */
@Stable
class Sys(private val ctx: Context) {
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    var wifiEnabled by mutableStateOf(false); private set
    var wifiConnected by mutableStateOf(false); private set
    var ethConnected by mutableStateOf(false); private set
    var ip by mutableStateOf<String?>(null); private set
    var volPct by mutableIntStateOf(0); private set
    var volFixed by mutableStateOf(false); private set
    var btOn by mutableStateOf(false); private set
    var btAudio by mutableStateOf<String?>(null); private set
    var castAvail by mutableStateOf(false); private set
    var dnd by mutableStateOf(false); private set
    var briPct by mutableIntStateOf(0); private set
    var canWrite by mutableStateOf(false); private set
    var autoTime by mutableStateOf(true); private set
    var is24 by mutableStateOf(true); private set
    var tz by mutableStateOf(""); private set
    var privateDns by mutableStateOf<String?>(null); private set
    var displayMode by mutableStateOf(""); private set
    var displayTag by mutableStateOf(""); private set
    var output by mutableStateOf(""); private set
    var inputs by mutableStateOf<List<TvIn>>(emptyList()); private set
    var hasBattery by mutableStateOf(false); private set
    var battery by mutableIntStateOf(0); private set

    val connected get() = wifiConnected || ethConnected

    private val netCb = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { main.post { readNet() } }
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) { main.post { readNet() } }
        override fun onLost(network: Network) { main.post { readNet() } }
        override fun onAvailable(network: Network) { main.post { readNet() } }
    }
    private var netRegistered = false

    fun start() {
        if (!netRegistered) {
            try { cm?.registerDefaultNetworkCallback(netCb); netRegistered = true } catch (_: Exception) { }
        }
        refresh()
    }

    fun stop() {
        if (netRegistered) { try { cm?.unregisterNetworkCallback(netCb) } catch (_: Exception) { }; netRegistered = false }
    }

    fun refresh() {
        readNet(); readAudio(); readMisc()
    }

    private fun readNet() {
        wifiEnabled = try { wm?.isWifiEnabled == true } catch (_: Exception) { false }
        val n = cm?.activeNetwork
        val caps = n?.let { cm.getNetworkCapabilities(it) }
        wifiConnected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        ethConnected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        ip = n?.let { cm.getLinkProperties(it) }?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }?.hostAddress
        val mode = Settings.Global.getString(ctx.contentResolver, "private_dns_mode")
        privateDns = if (mode == "hostname") Settings.Global.getString(ctx.contentResolver, "private_dns_specifier") else null
    }

    fun readAudio() {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        volPct = (am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt()
        volFixed = am.isVolumeFixed
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val bt = outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        btAudio = bt?.productName?.toString()?.takeIf { it.isNotBlank() } ?: bt?.let { "Bluetooth-luidspreker" }
        val arc = outs.any { it.type == AudioDeviceInfo.TYPE_HDMI_ARC || (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_HDMI_EARC) }
        val usb = outs.any { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
        val wired = outs.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
        output = when {
            btAudio != null -> btAudio!!
            arc -> "HDMI ARC (soundbar of versterker)"
            usb -> "USB-audio"
            wired -> "Koptelefoon"
            else -> "Tv-luidsprekers"
        }
    }

    private fun readMisc() {
        val cr = ctx.contentResolver
        btOn = Settings.Global.getInt(cr, "bluetooth_on", 0) == 1
        castAvail = installed("com.google.android.apps.mediashell")
        dnd = try { nm.currentInterruptionFilter.let { it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN } } catch (_: Exception) { false }
        briPct = (Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128) * 100f / 255f).roundToInt()
        canWrite = Settings.System.canWrite(ctx)
        autoTime = Settings.Global.getInt(cr, Settings.Global.AUTO_TIME, 1) == 1
        is24 = DateFormat.is24HourFormat(ctx)
        val z = TimeZone.getDefault()
        val off = z.getOffset(System.currentTimeMillis()) / 60000
        val city = when (z.id) {
            "Europe/Brussels" -> "Brussel"; "Europe/Amsterdam" -> "Amsterdam"; "Europe/Paris" -> "Parijs"
            "Europe/London" -> "Londen"; "Europe/Berlin" -> "Berlijn"; "Europe/Madrid" -> "Madrid"
            else -> z.id.substringAfterLast('/').replace('_', ' ')
        }
        tz = "%s (GMT%s%02d:%02d)".format(city, if (off >= 0) "+" else "-", abs(off) / 60, abs(off) % 60)
        try {
            @Suppress("DEPRECATION") val d = (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
            val m = d.mode
            val w = maxOf(m.physicalWidth, m.physicalHeight); val h = minOf(m.physicalWidth, m.physicalHeight)
            displayMode = "$w × $h · ${m.refreshRate.roundToInt()} Hz"
            displayTag = when { w >= 3840 -> "4K"; w >= 1920 -> "Full HD"; w >= 1280 -> "HD"; else -> "SD" }
        } catch (_: Exception) { }
        val bat = ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        hasBattery = bat?.getBooleanExtra("present", false) == true
        if (hasBattery) {
            val lvl = bat!!.getIntExtra("level", 0); val sc = bat.getIntExtra("scale", 100).coerceAtLeast(1)
            battery = lvl * 100 / sc
        }
        inputs = readInputs()
    }

    private fun readInputs(): List<TvIn> {
        val tim = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return emptyList()
        return try {
            tim.tvInputList.filter { it.parentId == null && it.type != TvInputInfo.TYPE_TUNER && it.isPassthroughInput }
                .map { TvIn(it.id, it.loadLabel(ctx).toString(), tim.getInputState(it.id), it.type == TvInputInfo.TYPE_HDMI) }
                .sortedBy { it.label }
        } catch (_: Exception) { emptyList() }
    }

    fun installed(pkg: String) = try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }

    /* ------------------------------ acties ------------------------------ */

    fun stepVolume(up: Boolean) {
        if (volFixed) return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = ((volPct + if (up) 5 else -5).coerceIn(0, 100) * max / 100f).roundToInt()
        val next = (if (target == cur) cur + if (up) 1 else -1 else target).coerceIn(0, max)
        try { am.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0) } catch (_: Exception) { }
        volPct = if (next == cur) volPct else (next * 100f / max).roundToInt()
    }

    fun stepBrightness(up: Boolean) {
        if (!Settings.System.canWrite(ctx)) { canWrite = false; return }
        val v = (briPct + if (up) 5 else -5).coerceIn(0, 100)
        try {
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (v * 255f / 100f).roundToInt())
            briPct = v
        } catch (_: Exception) { }
    }
}

/* ---------------------- Android-instellingen openen ---------------------- */

object Nav {
    private const val TVS = "com.android.tv.settings"
    private fun act(a: String) = Intent(a)
    private fun comp(pkg: String, cls: String, action: String? = null) =
        Intent(action ?: Intent.ACTION_MAIN).setComponent(ComponentName(pkg, cls))

    val SETTINGS get() = act(Settings.ACTION_SETTINGS)
    val NETWORK get() = listOf(act(Settings.ACTION_WIFI_SETTINGS), act("android.settings.NETWORK_SETTINGS_TV_OEM_LINK"), SETTINGS)
    val DISPLAY get() = listOf(comp(TVS, "$TVS.device.displaysound.DisplaySoundActivity"), act(Settings.ACTION_DISPLAY_SETTINGS), SETTINGS)
    val PICTURE get() = listOf(act("android.settings.PQAQ.SETTINGS"), comp(TVS, "$TVS.device.displaysound.DisplaySoundActivity"), SETTINGS)
    val SOUND get() = listOf(act(Settings.ACTION_SOUND_SETTINGS), SETTINGS)
    val BLUETOOTH get() = listOf(act(Settings.ACTION_BLUETOOTH_SETTINGS), comp(TVS, "$TVS.accessories.AddAccessoryActivity"), SETTINGS)
    val PAIR get() = listOf(comp(TVS, "$TVS.accessories.AddAccessoryActivity"), act(Settings.ACTION_BLUETOOTH_SETTINGS), SETTINGS)
    val CAST get() = listOf(act("com.google.android.settings.CAST_RECEIVER_SETTINGS"), act(Settings.ACTION_CAST_SETTINGS), SETTINGS)
    val INPUTS get() = listOf(
        comp("com.mediatek.wwtv.tvcenter", "com.mediatek.wwtv.tvcenter.nav.inputpanel.InputsPanelActivity", "com.android.tv.action.VIEW_INPUTS"),
        act("com.android.tv.action.VIEW_INPUTS"),
        comp("com.google.android.tvlauncher", "com.google.android.tvlauncher.inputs.InputsPanelActivity", "com.android.tv.action.VIEW_INPUTS"),
    )
    val APPS get() = listOf(act(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS), act(Settings.ACTION_APPLICATION_SETTINGS), SETTINGS)
    val DATE get() = listOf(comp(TVS, "$TVS.system.DateTimeActivity"), act(Settings.ACTION_DATE_SETTINGS), SETTINGS)
    val DND get() = listOf(act("android.settings.ZEN_MODE_SETTINGS"), act("android.settings.NOTIFICATION_SETTINGS"), SETTINGS)
    val ALL get() = listOf(SETTINGS)
    val VOLUME_PANEL get() = listOf(act("android.settings.panel.action.VOLUME"), act(Settings.ACTION_SOUND_SETTINGS))

    fun appInfo(pkg: String) = listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
    fun writeSettings(pkg: String) = listOf(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$pkg")))
    fun input(id: String) = listOf(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id)))

    fun store(ctx: Context): List<Intent> {
        val pm = ctx.packageManager
        return listOfNotNull(
            pm.getLeanbackLaunchIntentForPackage("com.android.vending"),
            pm.getLaunchIntentForPackage("com.android.vending"),
            Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=")),
        )
    }

    /** Probeert de intents op volgorde; true als er een scherm geopend is. */
    fun open(ctx: Context, list: List<Intent>): Boolean {
        for (i in list) {
            val intent = Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(ctx.packageManager) == null) continue
            try { ctx.startActivity(intent); return true } catch (_: ActivityNotFoundException) { } catch (_: SecurityException) { }
        }
        return false
    }
}
