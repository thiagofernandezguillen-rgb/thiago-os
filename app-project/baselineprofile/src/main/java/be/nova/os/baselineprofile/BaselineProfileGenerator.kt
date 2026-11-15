package be.nova.os.baselineprofile

import android.content.Intent
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Genereert het Baseline Profile: opstarten, door de app-rij en het raster bewegen,
 * Instellingen en het bedieningspaneel openen.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = "be.nova.os", includeInStartupProfile = true) {
        startActivityAndWait(Intent(Intent.ACTION_MAIN).setClassName("be.nova.os", "be.nova.os.MainActivity"))
        val d: UiDevice = device
        fun press(k: Int, n: Int = 1) = repeat(n) { d.pressKeyCode(k); Thread.sleep(250) }
        val right = 22; val left = 21; val up = 19; val down = 20; val enter = 23
        press(right, 7); press(left, 7)
        press(down); press(right, 6); press(down); press(up, 2); Thread.sleep(600)
        press(up); press(right); press(left)
        press(up); press(right, 2); press(enter); Thread.sleep(600)
        press(down, 8); press(right); press(down, 2); press(left); press(up, 8)
        press(right); press(enter); Thread.sleep(500)
        press(right, 3); press(down, 3); press(4); Thread.sleep(400)
        press(left, 2); press(enter); Thread.sleep(500)
    }
}
