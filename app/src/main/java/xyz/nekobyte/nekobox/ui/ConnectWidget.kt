package xyz.nekobyte.nekobox.ui

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.text.format.Formatter
import android.util.SizeF
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.VisibleForTesting
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.nekobyte.nekobox.Action
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.aidl.SpeedDisplayData
import xyz.nekobyte.nekobox.aidl.TrafficData
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.bg.NekoBoxConnection
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.GroupManager
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProfileManager
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.database.preference.OnPreferenceDataStoreChangeListener
import xyz.nekobyte.nekobox.ktx.Logs
import xyz.nekobyte.nekobox.ktx.getColorAttr
import xyz.nekobyte.nekobox.utils.Theme
import xyz.nekobyte.nekobox.widget.OrbRing
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** The home-screen widget: the connect button's orb, the profile, its group, the state, live speed and quick-switch chips. */
class ConnectWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ConnectWidgets.ACTION_REFRESH) ConnectWidgets.refresh(context, goAsync()) else super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        ConnectWidgets.refresh(context, goAsync())
    }

    // A new size can take another layout.
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        ConnectWidgets.refresh(context, goAsync())
    }
}

/** Draws the widgets again while they show the service running, in case its process ended unseen. */
class ConnectWidgetCheck(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        ConnectWidgets.refresh(applicationContext).join()
        return Result.success()
    }
}

/**
 * Draws the connect widgets in the app's process, which holds the settings and the selection; the service's process
 * asks for a new drawing when the connection changes and posts the live speed itself. Nothing runs while no widget
 * is placed.
 */
// The contexts it keeps (the watch's and the last drawing's) are the application's: refresh() draws with no other.
@SuppressLint("StaticFieldLeak")
internal object ConnectWidgets : OnPreferenceDataStoreChangeListener {
    const val ACTION_REFRESH = "xyz.nekobyte.nekobox.WIDGET_REFRESH"

    // What the widgets show follows these settings.
    private val keys = setOf(
        Key.PROFILE_ID,
        Key.FAVORITE_PROFILES,
        Key.RECENTS,
        Key.RECENT_PICKS,
        Key.APP_THEME,
        Key.NIGHT_THEME,
        Key.WIDGET_MATERIAL_YOU,
        Key.SPEED_INTERVAL,
    )

    /** What the running [service] answers: its state, the profile it runs (-1 none yet) and its latest speed. */
    internal class ServiceAnswer(val service: ComponentName, val state: BaseService.State, val runningProfileId: Long, val speed: SpeedDisplayData?)

    /** Asks the service that runs, without starting one; null when none runs or none answers in time. */
    @VisibleForTesting
    internal var askService: suspend (Context) -> ServiceAnswer? = { app ->
        NekoBoxConnection.startedServices().firstNotNullOfOrNull { service ->
            NekoBoxConnection.ask(app, service) { ServiceAnswer(service, BaseService.State.entries.getOrNull(it.state) ?: BaseService.State.Idle, it.runningProfileId, it.latestSpeed) }
        }
    }

    // One drawing at a time, across its waits for the service: an older drawing never lands after a newer one. A
    // request that a drawing begun after it covers is dropped.
    private val rendering = Mutex()
    private val requested = AtomicLong()
    private var drawnUpTo = 0L

    @VisibleForTesting
    @Volatile
    internal var lastRender: Job? = null

    // The running service, watched from this process while the widgets show it running: its process can end without a
    // state change (killed, crashed), and the widgets would go on showing it. Bound without creating it, so the
    // binding neither starts nor keeps a service.
    @VisibleForTesting
    internal val watch = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit

        override fun onServiceDisconnected(name: ComponentName?) {
            watchedBy?.let { refresh(it) }
        }
    }

    @Volatile private var watched: ComponentName? = null

    // The context the watch is bound with, which unbinds it.
    @Volatile private var watchedBy: Context? = null

    // Under the render lock.
    private fun watch(app: Context, service: ComponentName?) {
        if (service == watched && app == watchedBy) return
        watchedBy?.let { unbind(it) }
        watched = null
        watchedBy = null
        if (service == null) return
        if (bindWatch(app, Intent(Action.SERVICE).setComponent(service))) {
            watched = service
            watchedBy = app
        } else {
            // A refused binding is released all the same.
            unbind(app)
        }
    }

    private fun unbind(context: Context) {
        try {
            unbindWatch(context)
        } catch (_: IllegalArgumentException) {
        }
    }

    @VisibleForTesting
    internal var bindWatch: (Context, Intent) -> Boolean = { context, intent -> context.bindService(intent, watch, 0) }

    @VisibleForTesting
    internal var unbindWatch: (Context) -> Unit = { it.unbindService(watch) }

    // The profiles and the group the widgets show, as last drawn, and where: edits and removals of them draw again.
    @Volatile private var shownProfiles: Set<Long> = emptySet()

    @Volatile private var shownIn: Context? = null

    @Volatile private var shownGroup = 0L

    /**
     * Follows the profiles and groups in this process: a rename or a removal of what the widgets show, and lists
     * reloaded or removed (their profiles may be gone), draw them again. Traffic counts do not.
     */
    val modelListener = ModelListener()

    class ModelListener :
        ProfileManager.Listener,
        GroupManager.Listener {
        override suspend fun onAdd(profile: ProxyEntity) = Unit

        override suspend fun onUpdated(data: TrafficData) = Unit

        override suspend fun onUpdated(data: List<TrafficData>) = Unit

        override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) {
            if (profile.id in shownProfiles) shownIn?.let { refresh(it) }
        }

        override suspend fun onRemoved(groupId: Long, profileId: Long) {
            if (profileId in shownProfiles) shownIn?.let { refresh(it) }
        }

        override suspend fun groupAdd(group: ProxyGroup) = Unit

        override suspend fun groupUpdated(group: ProxyGroup) {
            if (group.id == shownGroup) shownIn?.let { refresh(it) }
        }

        override suspend fun groupRemoved(groupId: Long) {
            shownIn?.let { refresh(it) }
        }

        override suspend fun groupUpdated(groupId: Long) {
            shownIn?.let { refresh(it) }
        }
    }

    // Both processes can end, Android ending a cached app process first: then nothing is left to watch. While the
    // widgets show the service running, a check every 15 minutes draws them again, from a new process if need be.
    private var checking: Boolean? = null

    // Under the render lock.
    private fun check(app: Context, running: Boolean) {
        if (running == checking) return
        try {
            val work = WorkManager.getInstance(app)
            if (running) {
                work.enqueueUniquePeriodicWork(
                    CHECK_WORK,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequest.Builder(ConnectWidgetCheck::class.java, 15, TimeUnit.MINUTES).build(),
                )
            } else {
                work.cancelUniqueWork(CHECK_WORK)
            }
            checking = running
        } catch (e: IllegalStateException) {
            Logs.w("widget check", e)
        }
    }

    private fun component(context: Context) = ComponentName(context, ConnectWidget::class.java)

    private fun ids(context: Context): IntArray = AppWidgetManager.getInstance(context).getAppWidgetIds(component(context))

    override fun onPreferenceDataStoreChanged(store: androidx.preference.PreferenceDataStore, key: String) {
        if (key in keys) refresh(NekoBox.application)
    }

    /** From the service's process: has the app's process draw the widgets again, when any is placed. */
    @OptIn(DelicateCoroutinesApi::class)
    fun requestRefresh() {
        GlobalScope.launch(Dispatchers.Default) {
            try {
                val context = NekoBox.application
                if (placed(context, now = true).isNotEmpty()) context.sendBroadcast(Intent(ACTION_REFRESH).setComponent(component(context)))
            } catch (e: RuntimeException) {
                Logs.w("widget refresh", e)
            }
        }
    }

    /** Draws every widget again, off the main thread; [pending] finishes once that is done. */
    @OptIn(DelicateCoroutinesApi::class)
    fun refresh(context: Context, pending: BroadcastReceiver.PendingResult? = null): Job {
        val app = context.applicationContext
        val request = requested.incrementAndGet()
        return GlobalScope.launch(Dispatchers.Default) {
            try {
                rendering.withLock {
                    if (drawnUpTo >= request) return@withLock
                    val upTo = requested.get()
                    render(app)
                    drawnUpTo = upTo
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w("widget", e)
            } finally {
                pending?.finish()
            }
        }.also { lastRender = it }
    }

    // The service's process: the speed lines it posted last.
    private val speedPosts = SpeedPosts()

    // The widgets placed, as the service's process last asked Android: each state change asks again, the speed rounds
    // at most every PLACED_CHECK_MS. A widget placed while connected gets the live speed within that time.
    @Volatile private var placedIds = IntArray(0)

    @Volatile private var placedAt = 0L

    private fun placed(context: Context, now: Boolean = false): IntArray {
        val time = SystemClock.elapsedRealtime()
        if (now || time - placedAt >= PLACED_CHECK_MS) {
            placedIds = ids(context)
            placedAt = time
        }
        return placedIds
    }

    /**
     * From the service's process, each speed round while connected with the screen on: the speed lines of the widgets.
     * [force] posts an unchanged speed too, after a redraw that may have shown an older one.
     */
    fun postSpeed(speed: SpeedDisplayData, force: Boolean = false) {
        try {
            val context = NekoBox.application
            val ids = placed(context)
            if (ids.isEmpty()) return
            val (tx, rx) = speedTexts(context, speed)
            if (!speedPosts.shouldPost(tx to rx, force)) return
            val views = RemoteViews(context.packageName, R.layout.widget_connect)
            setSpeed(context, views, tx, rx)
            AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(ids, views)
            speedPosts.posted(tx to rx)
        } catch (e: RuntimeException) {
            Logs.w("widget speed", e)
        }
    }

    private fun speedTexts(context: Context, speed: SpeedDisplayData?) = (speed?.txRateProxy ?: 0L).let { context.getString(R.string.speed, Formatter.formatFileSize(context, it)) } to
        (speed?.rxRateProxy ?: 0L).let { context.getString(R.string.speed, Formatter.formatFileSize(context, it)) }

    private fun setSpeed(context: Context, views: RemoteViews, tx: String, rx: String) {
        views.setTextViewText(R.id.widget_tx, "▲ $tx")
        views.setContentDescription(R.id.widget_tx, context.getString(R.string.speed_upload, tx))
        views.setTextViewText(R.id.widget_rx, "▼ $rx")
        views.setContentDescription(R.id.widget_rx, context.getString(R.string.speed_download, rx))
    }

    // The colors of one night mode, from the app's palette (or the wallpaper's), as the app's screens show them.
    private class Look(app: Context, theme: Int, night: Boolean, materialYou: Boolean) {
        val context: Context = run {
            val config = Configuration(app.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val themed = ContextThemeWrapper(app.createConfigurationContext(config), R.style.Theme_NekoBox)
            themed.theme.apply {
                applyStyle(R.style.Theme_NekoBox, true)
                applyStyle(R.style.ThemeOverlay_NekoBox_Common, true)
                applyStyle(Theme.palette(theme), true)
            }
            if (materialYou) DynamicColors.wrapContextIfAvailable(themed) else themed
        }
        private fun color(attr: Int) = context.getColorAttr(attr)
        val canvas = ColorUtils.setAlphaComponent(color(android.R.attr.colorBackground), 0xFF)
        val surface = ColorUtils.compositeColors(color(com.google.android.material.R.attr.colorSurface), canvas)

        // Tints are opaque: a tint with alpha would mix with the white of the shapes it colors.
        val outline = ColorUtils.compositeColors(color(com.google.android.material.R.attr.colorOutlineVariant), canvas)
        val chipOutline = ColorUtils.compositeColors(color(com.google.android.material.R.attr.colorOutlineVariant), surface)
        val primary = color(androidx.appcompat.R.attr.colorPrimary)
        val selectedChip = ColorUtils.compositeColors(ColorUtils.setAlphaComponent(primary, 26), surface)
        val text = color(android.R.attr.textColorPrimary)
        val secondary = color(android.R.attr.textColorSecondary)

        // "Connected" is small text: an accent too light for the canvas (Pink or Blue by day) gives way to the text color.
        val connected = color(R.attr.primaryOrTextPrimary).takeIf { ColorUtils.calculateContrast(it, canvas) >= 4.5 } ?: text
        val plate = color(com.google.android.material.R.attr.colorSurfaceContainerHigh)
        val key = listOf(canvas, surface, outline, primary, text, secondary, connected, plate).joinToString()
    }

    private class Looks(val day: Look, val night: Look) {
        fun pair(color: (Look) -> Int) = color(day) to color(night)
    }

    // The orb's pictures, drawn once per look and state.
    private val orbs = HashMap<String, Bitmap>()

    private fun orb(look: Look, state: BaseService.State): Bitmap {
        val picture = if (state == BaseService.State.Idle) BaseService.State.Stopped else state
        return orbs.getOrPut("${look.key}/$picture") {
            if (orbs.size >= 16) orbs.clear()
            val size = (ORB_DP * look.context.resources.displayMetrics.density).toInt()
            createBitmap(size, size).also { bitmap ->
                val canvas = Canvas(bitmap)
                val radius = size / 2f
                canvas.drawCircle(radius, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.plate })
                OrbRing(View(look.context)).apply {
                    offColor = look.secondary
                    dark = ColorUtils.calculateLuminance(look.text) > 0.5
                    still(picture)
                    draw(canvas, radius, radius, radius)
                }
            }
        }
    }

    private fun systemNight() = (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun looks(app: Context): Looks {
        val materialYou = Build.VERSION.SDK_INT >= 31 && DataStore.widgetMaterialYou
        val theme = if (materialYou) Theme.DYNAMIC else DataStore.appTheme
        val night = when {
            Theme.darkOnly(theme) -> true

            DataStore.nightTheme == 1 -> true

            DataStore.nightTheme == 2 -> false

            DataStore.nightTheme == 3 -> NekoBox.power.isPowerSaveMode

            // Following the system on Android 12 and later, the widget carries both looks and the home screen picks.
            Build.VERSION.SDK_INT >= 31 -> return Looks(Look(app, theme, false, materialYou), Look(app, theme, true, materialYou))

            else -> systemNight()
        }
        return Look(app, theme, night, materialYou).let { Looks(it, it) }
    }

    private class Chip(val id: Long, val name: String)

    private suspend fun render(app: Context) {
        val manager = AppWidgetManager.getInstance(app)
        val ids = manager.getAppWidgetIds(component(app))
        if (ids.isEmpty()) {
            watch(app, null)
            check(app, false)
            return
        }
        val answer = askService(app)
        val state = answer?.state ?: BaseService.State.Stopped
        watch(app, answer?.service?.takeIf { state.started })
        check(app, state.started)
        // Running, the profile the service runs, also a server its group picked on its own; else the selected one. Its
        // own name and group, not the notification's title.
        val runningId = answer?.runningProfileId?.takeIf { state.started && it > 0L }
        val selected = runningId?.let { ProfileDatabase.proxyDao.getById(it) } ?: ProfileDatabase.proxyDao.getById(DataStore.selectedProxy)
        val group = selected?.let { ProfileDatabase.groupDao.getById(it.groupId) }
        val name = selected?.displayName() ?: app.getString(R.string.widget_no_profile)
        // Pins of deleted profiles stay until the lists reload: every candidate is looked up before the chips are counted.
        val order = widgetChipOrder(DataStore.favoriteProfiles, DataStore.recentPicks, selected?.groupId ?: DataStore.selectedGroup)
        val chips = widgetChipProfiles(order, ProfileDatabase.proxyDao.getEntities(order)).map { Chip(it.id, it.displayName()) }
        shownProfiles = chips.map { it.id }.toSet() + listOfNotNull(selected?.id)
        shownGroup = group?.id ?: 0L
        shownIn = app
        // The speed read here can be older than one the service posted meanwhile: once drawn, the service is told to
        // post its latest (Action.WIDGET_REDRAWN).
        val speed = if (state == BaseService.State.Connected && DataStore.speedInterval > 0) speedTexts(app, answer?.speed) else null
        val looks = looks(app)
        val content = Content(app, state, name, group?.displayName(), selected?.id ?: 0L, chips, speed, looks)
        // One layout a widget, picked by its size, so that the service's speed lines can update it in place: an update
        // that changes only some views reaches no layout of a size map.
        for (id in ids) {
            val options = manager.getAppWidgetOptions(id)
            val sizes = if (Build.VERSION.SDK_INT >= 31) {
                @Suppress("DEPRECATION") // getParcelableArrayList(String, Class) arrived in Android 13.
                options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES).orEmpty().map { it.width to it.height }
            } else {
                emptyList()
            }
            val (width, height) = widgetFitSize(
                sizes,
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT),
            )
            manager.updateAppWidget(id, content.views(widgetSpec(width, height)))
        }
        if (speed != null) app.sendBroadcast(Intent(Action.WIDGET_REDRAWN).setPackage(app.packageName))
    }

    private class Content(
        val app: Context,
        val state: BaseService.State,
        val name: String,
        val group: String?,
        val selectedId: Long,
        val chips: List<Chip>,
        val speed: Pair<String, String>?,
        val looks: Looks,
    ) {
        val stateText: String = app.getString(widgetStateText(state))
        val orbDay = orb(looks.day, state)
        val orbNight = orb(looks.night, state)
        val openApp: PendingIntent = NekoBox.configureIntent(app)
        val toggle: PendingIntent = action(null)

        fun action(profileId: Long?): PendingIntent {
            val intent = Intent(app, ConnectWidgetActivity::class.java)
            // A chip's intent differs by its data: intents that differ only by extras would share one PendingIntent.
            if (profileId != null) intent.setData(Uri.fromParts("profile", profileId.toString(), null)).putExtra(ConnectWidgetActivity.EXTRA_PROFILE, profileId)
            return PendingIntent.getActivity(app, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun RemoteViews.tint(id: Int, method: String, colors: Pair<Int, Int>) {
            if (Build.VERSION.SDK_INT >= 31 && colors.first != colors.second) setColorInt(id, method, colors.first, colors.second) else setInt(id, method, colors.first)
        }

        fun views(spec: WidgetSpec): RemoteViews {
            val views = RemoteViews(app.packageName, if (spec.orbOnly) R.layout.widget_orb_only else R.layout.widget_connect)
            views.setImageViewBitmap(R.id.widget_orb_day, orbDay)
            views.setImageViewBitmap(R.id.widget_orb_night, orbNight)
            val orbAction = app.getString(if (state.canStop) R.string.stop else R.string.connect)
            views.setContentDescription(R.id.widget_orb, app.getString(R.string.widget_orb_description, orbAction, stateText, name))
            views.setOnClickPendingIntent(R.id.widget_orb, toggle)
            views.setOnClickPendingIntent(R.id.widget_root, openApp)
            if (spec.orbOnly) return views
            views.tint(android.R.id.background, "setColorFilter", looks.pair { it.canvas })
            views.tint(R.id.widget_outline, "setColorFilter", looks.pair { it.outline })
            views.setTextViewText(R.id.widget_profile, name)
            views.tint(R.id.widget_profile, "setTextColor", looks.pair { it.text })
            views.setTextViewText(R.id.widget_state, stateText)
            views.tint(R.id.widget_state, "setTextColor", looks.pair { if (state == BaseService.State.Connected) it.connected else it.secondary })
            views.setTextViewText(R.id.widget_group, group?.let { " · $it" }.orEmpty())
            views.tint(R.id.widget_group, "setTextColor", looks.pair { it.secondary })
            // Every view is set each time: the home screen applies a new drawing over the views it shows.
            views.setViewVisibility(R.id.widget_speed, if (speed != null) View.VISIBLE else View.GONE)
            if (speed != null) {
                setSpeed(app, views, speed.first, speed.second)
                views.tint(R.id.widget_tx, "setTextColor", looks.pair { it.secondary })
                views.tint(R.id.widget_rx, "setTextColor", looks.pair { it.secondary })
            }
            listOf(R.id.widget_chips_1, R.id.widget_chips_2).forEachIndexed { row, rowId ->
                views.removeAllViews(rowId)
                val slots = if (row < spec.chipRows) chips.drop(row * spec.chipColumns).take(spec.chipColumns) else emptyList()
                views.setViewVisibility(rowId, if (slots.isEmpty()) View.GONE else View.VISIBLE)
                if (slots.isNotEmpty()) for (column in 0 until spec.chipColumns) views.addView(rowId, chip(slots.getOrNull(column)))
            }
            return views
        }

        // An empty slot keeps the row's columns, so chips line up from one row to the next.
        private fun chip(chip: Chip?): RemoteViews {
            val views = RemoteViews(app.packageName, R.layout.widget_chip)
            if (chip == null) {
                views.setViewVisibility(R.id.widget_chip, View.INVISIBLE)
                return views
            }
            val selected = chip.id == selectedId
            views.setTextViewText(R.id.widget_chip_text, chip.name)
            views.setContentDescription(
                R.id.widget_chip,
                when {
                    selected -> app.getString(R.string.widget_chip_selected, chip.name)
                    state.canStop -> app.getString(R.string.widget_chip_switch, chip.name)
                    else -> app.getString(R.string.widget_chip_connect, chip.name)
                },
            )
            views.tint(R.id.widget_chip_text, "setTextColor", looks.pair { it.text })
            views.tint(R.id.widget_chip_fill, "setColorFilter", looks.pair { if (selected) it.selectedChip else it.surface })
            if (selected) views.setImageViewResource(R.id.widget_chip_outline, R.drawable.widget_chip_outline_selected)
            views.tint(R.id.widget_chip_outline, "setColorFilter", looks.pair { if (selected) it.primary else it.chipOutline })
            views.setOnClickPendingIntent(R.id.widget_chip, action(chip.id))
            return views
        }
    }

    private const val ORB_DP = 72
    private const val CHECK_WORK = "connect-widget-check"
    private const val PLACED_CHECK_MS = 30_000L
}
