package xyz.nekobyte.nekobox.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.SystemClock
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.widget.NeonQrView
import xyz.nekobyte.nekobox.widget.SubscriptionQrDialog

/** A subscription's card draws its link as a code that reads back, and says who can use it. */
@RunWith(AndroidJUnit4::class)
class SubscriptionQrDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val link = "https://sub.example.com/api/v1/client/subscribe?token=0123456789abcdef0123456789abcdef"

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(1_200)
        instrumentation.waitForIdleSync()
    }

    @Test
    fun theCardDrawsTheLinkAsACodeThatReadsBack() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle()
            scenario.onActivity { SubscriptionQrDialog(link, "Night city").show(it.supportFragmentManager, "qr") }
            settle()
            var read = ""
            var warning = ""
            var description = ""
            scenario.onActivity { activity ->
                val root = (activity.supportFragmentManager.findFragmentByTag("qr") as SubscriptionQrDialog).requireView()
                val code = root.findViewById<NeonQrView>(R.id.code)
                // The view as it draws on screen, read the way a scanner reads it.
                val bitmap = Bitmap.createBitmap(code.width, code.height, Bitmap.Config.ARGB_8888)
                code.draw(Canvas(bitmap))
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                read = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))).text
                warning = root.findViewById<TextView>(R.id.warning).text.toString()
                description = code.contentDescription.toString()
            }
            assertEquals(link, read)
            assertEquals(context.getString(R.string.subscription_qr_warning), warning)
            assertTrue("a screen reader hears whose link it is: $description", "Night city" in description)
        }
    }
}
