package xyz.nekobyte.nekobox.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import xyz.nekobyte.nekobox.utils.SendLog

class BlankActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A crash report is shared only after the user has read it.
        val title = intent?.getStringExtra("sendLog")
        if (title == null) finish() else SendLog.review(this, title) { finish() }
    }
}
