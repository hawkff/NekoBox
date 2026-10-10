package xyz.nekobyte.nekobox.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import xyz.nekobyte.nekobox.utils.SendLog

class BlankActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // process crash log
        intent?.getStringExtra("sendLog")?.apply {
            SendLog.sendLog(this@BlankActivity, this)
        }

        finish()
    }
}
