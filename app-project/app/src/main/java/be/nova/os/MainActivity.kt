package be.nova.os

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import be.nova.os.data.AppRepo
import be.nova.os.data.Sys
import be.nova.os.ui.Inter
import be.nova.os.ui.NovaRoot
import be.nova.os.ui.NovaState
import be.nova.os.ui.T
import be.nova.os.ui.Wall
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    private lateinit var st: NovaState
    private lateinit var sys: Sys

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (::st.isInitialized) st.refreshApps() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Inter.init(assets)
        val dm = resources.displayMetrics
        val w = max(dm.widthPixels, dm.heightPixels)
        val h = min(dm.widthPixels, dm.heightPixels)
        // Achtergrond en lettertypes parallel voorbereiden terwijl Compose de eerste frame opbouwt.
        Wall.renderAsync(w, h)
        Thread({ T.prewarm() }, "nova-fonts").start()

        val am = getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val animateBg = !am.isLowRamDevice && mi.totalMem > 2_200_000_000L

        sys = Sys(this)
        st = NovaState(this, sys, AppRepo(this), lifecycleScope)
        st.load()
        setContent { NovaRoot(st, w, animateBg) }

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, pkgReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        (::st.isInitialized && st.onKey(event)) || super.dispatchKeyEvent(event)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME) && ::st.isInitialized) st.onHome()
    }

    override fun onResume() {
        super.onResume()
        st.resumed = true
        sys.start()
    }

    override fun onPause() {
        super.onPause()
        st.resumed = false
        sys.stop()
    }

    override fun onDestroy() {
        try { unregisterReceiver(pkgReceiver) } catch (_: Exception) { }
        super.onDestroy()
    }
}
