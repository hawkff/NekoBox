package xyz.nekobyte.nekobox.ui

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import xyz.nekobyte.nekobox.GroupLayout
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.ktx.Logs
import xyz.nekobyte.nekobox.ktx.PlainInflater
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Home's first rows, inflated off the main thread while the activity starts, a second before the profiles come: the
 * list that shows first takes them, so the frame that fills it binds rows instead of inflating each one, and its first
 * scroll finds the next screenful made too. What no list takes goes with the activity.
 */
class RowWarmup(private val context: Context) {
    private val made = ConcurrentHashMap<Int, ConcurrentLinkedQueue<View>>()

    fun start(scope: LifecycleCoroutineScope) {
        // About two screenfuls of rows.
        val layout = when (DataStore.groupLayoutMode) {
            GroupLayout.COMPACT -> R.layout.layout_profile_compact to 24
            GroupLayout.TWO_COLUMNS -> R.layout.layout_profile to 20
            else -> R.layout.layout_profile to 10
        }
        val rows = listOfNotNull(R.layout.layout_profile_section.takeIf { DataStore.profileSections }?.let { it to 4 }, layout)
        scope.launch(Dispatchers.Default) {
            val inflater = PlainInflater(context)
            // A parent for the rows' layout params, margins included; the list converts them to its own.
            val parent = FrameLayout(context)
            try {
                // Sections first: the list starts with one, and a row of cards follows.
                for ((resource, count) in rows) {
                    val queue = made.getOrPut(resource) { ConcurrentLinkedQueue() }
                    repeat(count) {
                        // A destroyed activity stops here instead of inflating for a screen that is gone.
                        ensureActive()
                        queue.add(inflater.inflate(resource, parent, false))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
    }

    /** A row of [layout] made ahead, on the main thread only, so a list making rows off it cannot take them. */
    fun take(layout: Int): View? = if (Looper.myLooper() == Looper.getMainLooper()) made[layout]?.poll() else null
}
