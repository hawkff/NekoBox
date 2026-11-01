package xyz.nekobyte.nekobox.ui

import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.addCallback
import androidx.annotation.IdRes
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.doOnPreDraw
import androidx.core.view.isNotEmpty
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceDataStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import xyz.nekobyte.nekobox.BuildConfig
import xyz.nekobyte.nekobox.GroupType
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.aidl.INekoBoxService
import xyz.nekobyte.nekobox.aidl.SpeedDisplayData
import xyz.nekobyte.nekobox.aidl.TrafficData
import xyz.nekobyte.nekobox.api.ApiUi
import xyz.nekobyte.nekobox.api.LocalApiAccess
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.bg.NekoBoxConnection
import xyz.nekobyte.nekobox.bg.NetworkAutomationService
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.GroupManager
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProfileManager
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.database.RoutingProfiles
import xyz.nekobyte.nekobox.database.SubscriptionBean
import xyz.nekobyte.nekobox.database.preference.OnPreferenceDataStoreChangeListener
import xyz.nekobyte.nekobox.databinding.LayoutDontShowAgainBinding
import xyz.nekobyte.nekobox.databinding.LayoutMainBinding
import xyz.nekobyte.nekobox.fmt.AbstractBean
import xyz.nekobyte.nekobox.fmt.KryoConverters
import xyz.nekobyte.nekobox.fmt.PluginEntry
import xyz.nekobyte.nekobox.group.GroupInterfaceAdapter
import xyz.nekobyte.nekobox.group.GroupUpdater
import xyz.nekobyte.nekobox.ktx.FabContainer
import xyz.nekobyte.nekobox.ktx.Logs
import xyz.nekobyte.nekobox.ktx.alert
import xyz.nekobyte.nekobox.ktx.isPreview
import xyz.nekobyte.nekobox.ktx.onMainDispatcher
import xyz.nekobyte.nekobox.ktx.parseProxies
import xyz.nekobyte.nekobox.ktx.readableMessage
import xyz.nekobyte.nekobox.ktx.runOnDefaultDispatcher
import xyz.nekobyte.nekobox.ktx.runOnMainDispatcher
import xyz.nekobyte.nekobox.ktx.whenStill
import xyz.nekobyte.nekobox.plugin.PluginTrust
import xyz.nekobyte.nekobox.plugin.Plugins
import xyz.nekobyte.nekobox.utils.Util
import xyz.nekobyte.nekobox.widget.glass.Glass
import xyz.nekobyte.nekobox.widget.glass.GlassBackdrop
import xyz.nekobyte.nekobox.widget.glass.GlassBackdropFrameLayout
import xyz.nekobyte.nekobox.widget.glass.GlassBand
import xyz.nekobyte.nekobox.widget.glass.GlassDrawable
import xyz.nekobyte.nekobox.widget.glass.WarmUp
import xyz.nekobyte.nekobox.widget.glass.followSwell
import java.util.Locale

class MainActivity :
    ThemedActivity(),
    NekoBoxConnection.Callback,
    OnPreferenceDataStoreChangeListener,
    FabContainer,
    NavigationView.OnNavigationItemSelectedListener {

    lateinit var binding: LayoutMainBinding
    lateinit var navigation: NavigationView

    /** Home's first rows, inflated while the activity starts. */
    val rowWarmup = RowWarmup(this)

    // FabContainer: lets ktx layout managers drive the FAB without importing ui (cycle break).
    override val fabView get() = binding.fab
    override fun showFab() = binding.fab.show()
    override fun hideFab() = binding.fab.hide()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The render thread loads the first frame's GPU programs while the screen is being built.
        WarmUp.start(this)
        // The first plural the process shows loads the locale's plural rules (~80 ms on a slow phone); Home's section
        // headers would pay it in the frame that fills the list.
        runOnDefaultDispatcher { resources.getQuantityString(R.plurals.group_profiles, 2, 2) }
        MessageStore.setCurrentActivity(this)
        backgroundUseAsked = savedInstanceState?.getBoolean(BACKGROUND_USE_ASKED) == true

        binding = LayoutMainBinding.inflate(layoutInflater)
        // Home's rows are made off the main thread from the first frame on, before the profiles come; started sooner,
        // they would hold up that frame, which inflates on the main thread from the same resources.
        binding.root.doOnPreDraw { binding.root.post { rowWarmup.start(lifecycleScope) } }
        // One drawer for every theme. Black used a second one whose solid item fill hid the checked item's name.
        navigation = binding.navView
        navigation.setNavigationItemSelectedListener(this)
        // The drawer's items are built after start-up (see buildAhead), or as the drawer starts to open if sooner.
        // A recreated activity builds them now, so the drawer restores its checked item, and opens with items.
        if (savedInstanceState != null) buildDrawerMenu()
        binding.drawerLayout.addDrawerListener(
            object : DrawerLayout.SimpleDrawerListener() {
                override fun onDrawerStateChanged(newState: Int) {
                    if (newState != DrawerLayout.STATE_IDLE) buildDrawerMenu()
                }
            },
        )
        if (Glass.enabled) decorateGlass()

        binding.root.postDelayed(::buildAhead, 2000)
        if (savedInstanceState == null) {
            displayFragmentWithId(R.id.nav_configuration)
        } else {
            // A recreated activity, after a theme or glass change, restores its screen without displayFragment.
            currentScreen()?.let(::fitBottomBar)
        }
        onBackPressedDispatcher.addCallback {
            val current = currentScreen()
            // An open drawer goes first: Back closes it, and leaves the screen as it was.
            when {
                binding.drawerLayout.isOpen -> binding.drawerLayout.close()
                current?.onBackPressed() == true -> Unit
                current is ConfigurationFragment && !current.proxies -> moveTaskToBack(true)
                else -> displayFragmentWithId(R.id.nav_configuration)
            }
        }

        binding.fab.setOnClickListener {
            if (DataStore.serviceState.canStop) {
                NekoBox.stopService()
            } else {
                connect.launch(
                    null,
                )
            }
        }
        binding.stats.setOnClickListener { if (DataStore.serviceState.connected) binding.stats.testConnection() }

        setContentView(binding.root)
        // A recreated activity asks again until the user answers; the dialog itself is not restored.
        askHelpToImprove()
        changeState(BaseService.State.Idle)
        connection.connect(this, this)
        DataStore.configurationStore.registerChangeListener(this)
        GroupManager.userInterface = GroupInterfaceAdapter(this)

        if (intent?.action == Intent.ACTION_VIEW) {
            onNewIntent(intent)
        }

        // sdk 33 notification
        if (Build.VERSION.SDK_INT >= 33) {
            val checkPermission =
                ContextCompat.checkSelfPermission(this@MainActivity, POST_NOTIFICATIONS)
            if (checkPermission != PackageManager.PERMISSION_GRANTED) {
                // request dynamically
                ActivityCompat.requestPermissions(
                    this@MainActivity,
                    arrayOf(POST_NOTIFICATIONS),
                    0,
                )
            }
        }

        if (isPreview) {
            MaterialAlertDialogBuilder(this)
                .setTitle(BuildConfig.PRE_VERSION_NAME)
                .setMessage(R.string.preview_version_hint)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (ahead?.isNotEmpty() == true) buildAhead()
        MessageStore.setCurrentActivity(this)
        ApiUi.attach(this)
        LocalApiAccess.sync(this)

        applyHideFromRecentApps(DataStore.hideFromRecentApps)
        // A connect that finished while the app was away asks now. Lifecycle reports RESUMED only
        // after onResume returns, which is why the prompt needs no more than STARTED.
        if (DataStore.serviceState.connected) askForBackgroundUse()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) LocalApiAccess.clearCopiedToken(this)
    }

    // Liquid glass: the connect button and the stats bar float as glass over the profiles; the
    // drawer is a sheet of glass over the screen. Glass sets itself off by its edge, not a shadow.
    private fun decorateGlass() {
        val content = binding.fragmentHolder.backdrop
        val tint = Glass.tint(this)
        val fab = binding.fab
        fab.compatElevation = 0f
        fab.compatPressedTranslationZ = 0f
        // The glass at the bottom shows the screen's own backdrop, which the screen's bars frost already.
        fab.useGlass(
            GlassDrawable(fab, content, tint, source = ::shownBackdrop) { bounds ->
                pane().apply {
                    rect.set(bounds)
                    radius = rect.width() / 2
                    anchor = fab
                }
            },
        )
        val stats = binding.stats
        val statsContent = stats.findViewById<View>(R.id.stats_content)
        val inset = resources.getDimension(R.dimen.glass_capsule_inset)
        stats.elevation = 0f
        stats.useGlass(
            GlassDrawable(stats, content, tint, source = ::shownBackdrop) { bounds ->
                val capsule = pane()
                capsule.rect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - stats.paddingBottom - inset)
                capsule.radius = 2 * inset
                capsule.anchor = statsContent
                capsule.followSwell()
                // The profiles fade out under the capsule, so its status reads over any of them.
                band.set(GlassBand.BOTTOM, capsule.rect.top, inset)
            },
        )
        val reach = 64 * resources.displayMetrics.density
        navigation.background = GlassDrawable(navigation, binding.coordinator.backdrop, Glass.surfaceTint(this), sweeps = true) { bounds ->
            // Open at the screen's edges: only the inner edge shows a rim, the left one when the drawer opens from the right.
            val rtl = navigation.layoutDirection == View.LAYOUT_DIRECTION_RTL
            pane().rect.set(
                if (rtl) bounds.left.toFloat() else bounds.left - reach,
                bounds.top - reach,
                if (rtl) bounds.right + reach else bounds.right.toFloat(),
                bounds.bottom + reach,
            )
        }
        // The drawer slides without redrawing; its glass follows the screen behind it.
        binding.drawerLayout.addDrawerListener(
            object : DrawerLayout.SimpleDrawerListener() {
                override fun onDrawerSlide(drawerView: View, slideOffset: Float) = drawerView.invalidate()

                // While the drawer is out the screen behind it stays still, so its content is drawn once, into a
                // layer the glass reuses.
                override fun onDrawerStateChanged(newState: Int) {
                    val out = newState != DrawerLayout.STATE_IDLE || binding.drawerLayout.isOpen
                    if (out) {
                        if (stillBackdrop == null) stillBackdrop = shownBackdrop()?.also { it.still = true }
                    } else {
                        stillBackdrop?.still = false
                        stillBackdrop = null
                    }
                }
            },
        )
    }

    // Reports stay off until the user answers once; builds without reporting keys never ask.
    private fun askHelpToImprove() {
        if (DataStore.helpToImproveAnswered || (BuildConfig.POSTHOG_PROJECT_TOKEN.isBlank() && BuildConfig.SENTRY_DSN.isBlank())) return
        helpToImproveDialog()
            .setPositiveButton(R.string.help_to_improve_prompt_allow) { _, _ -> DataStore.helpToImprove = true }
            .setNegativeButton(R.string.help_to_improve_prompt_deny) { _, _ -> DataStore.helpToImprove = false }
            .setCancelable(false)
            .show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        LocalApiAccess.sync(this)

        val uri = intent.data ?: return

        runOnDefaultDispatcher {
            when {
                (uri.scheme == "sn" && uri.host == "subscription") || uri.scheme == "clash" -> importSubscription(uri)
                uri.scheme == "sn" && uri.host == "routing" -> importRoutingProfile(uri)
                else -> importProfile(uri)
            }
        }
    }

    private suspend fun importRoutingProfile(uri: Uri) {
        val candidate = RoutingProfiles.parse(uri.toString())
        onMainDispatcher {
            if (candidate == null) {
                alert(getString(R.string.routing_profile_import_invalid)).show()
                return@onMainDispatcher
            }
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.routing_profiles)
                .setMessage(getString(R.string.routing_profile_import_message, candidate.name, candidate.ruleCount))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        RoutingProfiles.store(candidate)
                        onMainDispatcher { startActivity(Intent(this@MainActivity, RoutingProfilesActivity::class.java)) }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    fun urlTest(): Int {
        if (!DataStore.serviceState.connected || connection.service == null) {
            error("not started")
        }
        return connection.service!!.urlTest()
    }

    suspend fun importSubscription(uri: Uri) {
        val group: ProxyGroup

        val url = uri.getQueryParameter("url")
        if (!url.isNullOrBlank()) {
            group = ProxyGroup(type = GroupType.SUBSCRIPTION)
            val subscription = SubscriptionBean()
            group.subscription = subscription

            // cleartext format
            subscription.link = url
            group.name = uri.getQueryParameter("name")
        } else {
            val data = uri.encodedQuery.takeIf { !it.isNullOrBlank() } ?: return
            try {
                group = KryoConverters.deserialize(
                    ProxyGroup().apply { export = true },
                    Util.zlibDecompress(Util.b64Decode(data)),
                ).apply {
                    export = false
                }
            } catch (e: Exception) {
                onMainDispatcher {
                    alert(e.readableMessage).show()
                }
                return
            }
        }

        val name = group.name.takeIf { !it.isNullOrBlank() } ?: group.subscription?.link
            ?: group.subscription?.token
        if (name.isNullOrBlank()) return

        group.name = group.name.takeIf { !it.isNullOrBlank() }
            ?: ("Subscription #" + System.currentTimeMillis())

        onMainDispatcher {
            displayFragmentWithId(R.id.nav_group)

            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.subscription_import)
                .setMessage(getString(R.string.subscription_import_message, name))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportSubscription(group)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private suspend fun finishImportSubscription(subscription: ProxyGroup) {
        GroupManager.createGroup(subscription)
        GroupUpdater.startUpdate(subscription, true)
    }

    suspend fun importProfile(uri: Uri) {
        val profile = try {
            parseProxies(uri.toString()).getOrNull(0) ?: error(getString(R.string.no_proxies_found))
        } catch (e: Exception) {
            onMainDispatcher {
                alert(e.readableMessage).show()
            }
            return
        }

        onMainDispatcher {
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.profile_import)
                .setMessage(getString(R.string.profile_import_message, profile.displayName()))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportProfile(profile)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private suspend fun finishImportProfile(profile: AbstractBean) {
        val targetId = DataStore.selectedGroupForImport()

        ProfileManager.createProfile(targetId, profile)
        val group = ProfileDatabase.groupDao.getById(targetId)?.displayName().orEmpty()

        onMainDispatcher {
            // Home opens on the group that took the profile, which the message names.
            DataStore.selectedGroup = targetId
            DataStore.editingGroup = targetId
            displayFragmentWithId(R.id.nav_configuration)

            snackbar(resources.getQuantityString(R.plurals.added_to, 1, 1, group)).show()
        }
    }

    override fun missingPlugin(profileName: String, pluginName: String) {
        val pluginEntity = PluginEntry.find(pluginName)

        // unknown exe or neko plugin
        if (pluginEntity == null) {
            snackbar(getString(R.string.plugin_unknown, pluginName)).show()
            return
        }

        runOnDefaultDispatcher {
            val rejected = try {
                Plugins.inspectRejectedPlugins(pluginName)
            } catch (e: Exception) {
                Logs.w(e)
                emptyList()
            }
            onMainDispatcher {
                if (isFinishing || isDestroyed) return@onMainDispatcher
                when {
                    rejected.size > 1 -> showPluginApprovalConflict(rejected)

                    rejected.singleOrNull()?.currentFingerprints?.isNotEmpty() == true -> {
                        showPluginApprovalDialog(pluginName, rejected.single())
                    }

                    else -> showMissingPluginDialog(profileName, pluginEntity)
                }
            }
        }
    }

    private fun showMissingPluginDialog(profileName: String, pluginEntity: PluginEntry) {
        MaterialAlertDialogBuilder(this).setTitle(R.string.missing_plugin)
            .setMessage(
                getString(
                    R.string.profile_requiring_plugin,
                    profileName,
                    pluginEntity.displayName,
                ) + "\n\n" + getString(R.string.plugin_contract_required),
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showPluginApprovalDialog(pluginName: String, rejection: PluginTrust.RejectedPlugin) {
        val currentFingerprints = rejection.currentFingerprints ?: return
        val fingerprintText = currentFingerprints.sorted().joinToString("\n") {
            it.chunked(2).joinToString(":").uppercase(Locale.ROOT)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.plugin_approval_title)
            .setMessage(
                getString(
                    R.string.plugin_approval_message,
                    rejection.packageName,
                    fingerprintText,
                ),
            )
            .setPositiveButton(R.string.plugin_approval_action) { _, _ ->
                approvePluginSigner(pluginName, rejection)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun approvePluginSigner(pluginName: String, displayed: PluginTrust.RejectedPlugin) {
        runOnDefaultDispatcher {
            val approved = try {
                val current = Plugins.inspectRejectedPlugins(pluginName).singleOrNull()
                if (current == null || current != displayed) {
                    false
                } else {
                    val fingerprints = current.currentFingerprints
                    val identity = if (fingerprints.isNullOrEmpty()) {
                        null
                    } else {
                        PluginTrust.approvalIdentity(current.packageName, fingerprints)
                    }
                    if (identity == null) {
                        false
                    } else {
                        DataStore.approvePluginSigner(identity)
                        true
                    }
                }
            } catch (e: Exception) {
                Logs.w(e)
                false
            }
            showPluginApprovalResult(approved)
        }
    }

    private fun showPluginApprovalResult(approved: Boolean) {
        runOnMainDispatcher {
            if (isFinishing || isDestroyed) return@runOnMainDispatcher
            snackbar(
                if (approved) {
                    getString(R.string.plugin_approval_success)
                } else {
                    getString(R.string.plugin_approval_failed)
                },
            ).show()
        }
    }

    private fun showPluginApprovalConflict(rejected: List<PluginTrust.RejectedPlugin>) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.plugin_approval_conflict_title)
            .setMessage(
                getString(
                    R.string.plugin_approval_conflict_message,
                    rejected.map { it.packageName }.distinct().sorted().joinToString("\n"),
                ),
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        if (item.isChecked) {
            binding.drawerLayout.closeDrawers()
        } else {
            return displayFragmentWithId(item.itemId)
        }
        return true
    }

    @SuppressLint("CommitTransaction")
    fun displayFragment(fragment: ToolbarFragment) {
        fitBottomBar(fragment)
        val manager = supportFragmentManager
        // A request made before the last one ran would not see the screen it added.
        manager.executePendingTransactions()
        val transaction = manager.beginTransaction()
        // The drawer's destinations are peers: one fades through to the next, as Material moves between them.
        // The first screen just appears.
        if (currentScreen() != null) {
            transaction.setCustomAnimations(R.animator.fade_through_in, R.animator.fade_through_out)
        }
        // Screens that keep stay under the others, hidden and paused, so going back shows them as they were
        // instead of building them again; one built before a setting it reads changed is built anew.
        val tag = fragment.javaClass.name
        val kept = (manager.findFragmentByTag(tag) as? ToolbarFragment)?.takeIf { it.keepsAlive && it.current() }
        for (other in manager.fragments) {
            if (other.id != R.id.fragment_holder || other === kept) continue
            if (other is ToolbarFragment && other.keepsAlive && other.current()) {
                if (!other.isHidden) transaction.hide(other).setMaxLifecycle(other, Lifecycle.State.STARTED)
            } else {
                transaction.remove(other)
            }
        }
        if (kept != null) {
            transaction.show(kept).setMaxLifecycle(kept, Lifecycle.State.RESUMED)
        } else {
            transaction.add(R.id.fragment_holder, fragment, tag)
        }
        transaction.commitAllowingStateLoss()
        binding.drawerLayout.closeDrawers()
    }

    /**
     * Once start-up settles, builds the drawer's items and then the screens visited most, hidden and paused, one at
     * a time while the window is still, so the drawer and each first visit show as they would the second time.
     * A screen opened before its turn is built as usual.
     */
    private fun buildAhead() {
        val steps = ahead ?: ArrayDeque<() -> Unit>().also { steps ->
            steps += ::buildDrawerMenu
            for (screen in listOf(::GroupFragment, ::RouteFragment, ::ToolsFragment)) steps += { buildHidden(screen()) }
            ahead = steps
        }
        if (buildingAhead) return
        buildingAhead = true
        binding.root.whenStill {
            // A stopped activity cannot add screens: onResume goes on from here.
            if (isFinishing || isDestroyed || supportFragmentManager.isStateSaved) {
                buildingAhead = false
                return@whenStill false
            }
            steps.removeFirstOrNull()?.invoke()
            steps.isNotEmpty().also { buildingAhead = it }
        }
    }

    /** The steps of [buildAhead] still to run, once it has started. */
    private var ahead: ArrayDeque<() -> Unit>? = null
    private var buildingAhead = false

    private fun buildHidden(screen: ToolbarFragment) {
        val tag = screen.javaClass.name
        if (supportFragmentManager.findFragmentByTag(tag) != null) return
        supportFragmentManager.beginTransaction().add(R.id.fragment_holder, screen, tag).hide(screen)
            .setMaxLifecycle(screen, Lifecycle.State.STARTED).commitNow()
    }

    /** The screen on show; screens that keep stay added under it, hidden. */
    fun currentScreen() = supportFragmentManager.fragments.lastOrNull { it.id == R.id.fragment_holder && !it.isHidden } as? ToolbarFragment

    /** The connect button and the stats bar show on Home, and on every screen while the user wants them there. */
    private fun fitBottomBar(screen: ToolbarFragment) {
        if (screen is ConfigurationFragment) {
            binding.stats.allowShow = true
            // Another screen may have slid the bar away; while connected it belongs on Home.
            if (DataStore.serviceState.connected) binding.stats.performShow()
            binding.fab.show()
        } else if (!DataStore.showBottomBar) {
            binding.stats.allowShow = false
            // The bar slides away by its own height, which it has only once laid out.
            binding.stats.post { binding.stats.performHide() }
            binding.fab.hide()
        }
    }

    /** The drawer item of the screen on show, checked once the drawer's items are built. */
    private var checkedScreen = R.id.nav_configuration

    private fun buildDrawerMenu() {
        if (navigation.menu.isNotEmpty()) return
        navigation.inflateMenu(R.menu.main_drawer_menu)
        navigation.menu.findItem(checkedScreen)?.isChecked = true
    }

    private var backdropScreen: View? = null
    private var screenBackdrop: GlassBackdrop? = null

    /** The backdrop of the screen on show, under its bars, if it has one. */
    private fun shownBackdrop(): GlassBackdrop? {
        val view = currentScreen()?.view
        if (view !== backdropScreen) {
            backdropScreen = view
            screenBackdrop = view?.findViewById<GlassBackdropFrameLayout>(R.id.glass_content)?.backdrop
        }
        return screenBackdrop
    }

    /** The backdrop drawn into a layer while the drawer is out. */
    private var stillBackdrop: GlassBackdrop? = null

    // Home follows the connection while hidden too, so it shows the right state when it comes back.
    private fun refreshConfigurationProfileState() {
        for (screen in listOf(ConfigurationFragment::class.java, ProxiesFragment::class.java)) {
            (supportFragmentManager.findFragmentByTag(screen.name) as? ConfigurationFragment)?.refreshProfileState()
        }
    }

    fun displayFragmentWithId(@IdRes id: Int): Boolean {
        when (id) {
            R.id.nav_configuration -> {
                displayFragment(ConfigurationFragment())
            }

            R.id.nav_group -> displayFragment(GroupFragment())

            R.id.nav_proxies -> displayFragment(ProxiesFragment())

            R.id.nav_route -> displayFragment(RouteFragment())

            R.id.nav_tailscale -> displayFragment(TailscaleFragment())

            R.id.nav_nebula -> displayFragment(NebulaFragment())

            R.id.nav_settings -> displayFragment(SettingsFragment())

            R.id.nav_connections -> displayFragment(ConnectionsFragment())

            R.id.nav_local_api -> displayFragment(LocalApiFragment())

            R.id.nav_tools -> displayFragment(ToolsFragment())

            R.id.nav_logcat -> displayFragment(LogcatFragment())

            R.id.nav_about -> displayFragment(AboutFragment())

            else -> return false
        }
        checkedScreen = id
        navigation.menu.findItem(id)?.isChecked = true
        return true
    }

    private fun changeState(state: BaseService.State, msg: String? = null, animate: Boolean = false) {
        DataStore.serviceState = state
        refreshConfigurationProfileState()

        binding.fab.changeState(state, DataStore.serviceState, animate)
        binding.stats.changeState(state)
        if (msg != null) snackbar(getString(R.string.vpn_error, msg)).show()
        if (state == BaseService.State.Connected) askForBackgroundUse()
    }

    override fun snackbarInternal(text: CharSequence): Snackbar = Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG).apply {
        if (binding.fab.isShown) {
            anchorView = binding.fab
        }
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        changeState(state, msg, true)
    }

    // Android's battery limits can stop a connection that is up: while connected with the app on
    // screen, ask once per launch until background use is allowed or the user opts out.
    @SuppressLint("BatteryLife")
    private fun askForBackgroundUse() {
        if (backgroundUseAsked || backgroundUsePrompt?.isShowing == true) return
        if (!DataStore.askForBackgroundUse || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (NekoBox.power.isIgnoringBatteryOptimizations(packageName)) return
        val dialog = MaterialAlertDialogBuilder(this)
        // The dialog's own theme, so the box and its label take the dialog's colors.
        val prompt = LayoutDontShowAgainBinding.inflate(LayoutInflater.from(dialog.context))
        backgroundUsePrompt = dialog
            .setTitle(R.string.background_use_prompt_title)
            .setMessage(R.string.background_use_prompt_message)
            .setView(prompt.root)
            .setPositiveButton(R.string.background_use_prompt_allow) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri()))
                } catch (_: ActivityNotFoundException) {
                    // Some builds lack the request screen but keep the list of apps; a few have neither.
                    runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }
            }
            .setNegativeButton(R.string.background_use_prompt_later, null)
            // A dialog that goes with a recreated activity is not dismissed, so the new one asks again.
            .setOnDismissListener {
                backgroundUseAsked = true
                if (prompt.dontShowAgain.isChecked) DataStore.askForBackgroundUse = false
            }
            .show()
    }

    // Set once the prompt is answered and kept across recreation: reconnects and server switches in
    // this launch don't ask again.
    private var backgroundUseAsked = false
    private var backgroundUsePrompt: AlertDialog? = null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(BACKGROUND_USE_ASKED, backgroundUseAsked)
    }

    val connection = NekoBoxConnection(NekoBoxConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)
    override fun onServiceConnected(service: INekoBoxService) = changeState(
        try {
            BaseService.State.values()[service.state]
        } catch (_: RemoteException) {
            BaseService.State.Idle
        },
    )

    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)
    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    /** Starts the service for the selected profile, asking for the VPN permission first where needed. */
    fun startConnecting() = connect.launch(null)

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) snackbar(R.string.vpn_permission_denied).show()
    }

    // may NOT called when app is in background
    // ONLY do UI update here, write DB in bg process
    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        binding.stats.updateSpeed(stats.txRateProxy, stats.rxRateProxy)
    }

    override fun cbTrafficUpdate(data: TrafficData) {
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(data)
        }
    }

    override fun cbTrafficUpdateList(data: List<TrafficData>) {
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(data)
        }
    }

    override fun cbSelectorUpdate(id: Long) {
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = id
        DataStore.currentProfile = id
        refreshConfigurationProfileState()
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(id, true)
        }
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        when (key) {
            Key.SERVICE_MODE -> onBinderDied()

            Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL -> {
                if (DataStore.serviceState.canStop) {
                    snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
                        // Await the cached store's async write-through before reload so :bg reads
                        // the new proxy-apps/bypass/individual values from a durable DB.
                        runOnDefaultDispatcher {
                            try {
                                DataStore.configurationStore.awaitWrites()
                                NekoBox.reloadService()
                            } catch (e: Exception) {
                                Logs.w(e)
                                onMainDispatcher {
                                    // The coroutine can outlive the activity; guard fragment/UI APIs.
                                    if (!isFinishing && !isDestroyed) {
                                        snackbar(getString(R.string.service_failed)).show()
                                    }
                                }
                            }
                        }
                    }.show()
                }
            }
        }
    }

    override fun onStart() {
        connection.updateConnectionId(NekoBoxConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        super.onStart()
        // A visible app may start the watcher, which brings it back after a force stop or a
        // refused background restart.
        NetworkAutomationService.sync(applicationContext)
    }

    override fun onStop() {
        connection.updateConnectionId(NekoBoxConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        super.onStop()
    }

    private fun home() = currentScreen() as? ConfigurationFragment

    override fun canSwipeBack() = home().let { wideSwipeAllowed(binding.drawerLayout.isOpen, it?.searchOpen, it?.atFirstGroup == true) }

    // On the first group nothing lies to the left, so a swipe from anywhere on the list opens the drawer.
    override fun wideSwipeZone() = if (home()?.let { it.atFirstGroup && !it.searchOpen } == true) 1f else super.wideSwipeZone()

    override fun onWideSwipe() {
        val home = home() ?: return super.onWideSwipe()
        // Home is the root: the swipe closes an open search, else opens the drawer.
        if (!home.closeSearch()) binding.drawerLayout.open()
    }

    override fun onDestroy() {
        super.onDestroy()
        GroupManager.userInterface = null
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (super.onKeyDown(keyCode, event)) return true
                binding.drawerLayout.open()
                navigation.requestFocus()
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (binding.drawerLayout.isOpen) {
                    binding.drawerLayout.close()
                    return true
                }
            }
        }

        if (super.onKeyDown(keyCode, event)) return true
        if (binding.drawerLayout.isOpen) return false

        val fragment = currentScreen()
        return fragment != null && fragment.onKeyDown(keyCode, event)
    }
}

private const val BACKGROUND_USE_ASKED = "backgroundUseAsked"

/**
 * Whether the wide swipe may start: never over an open drawer. On Home ([searchOpen] not null) a
 * sideways swipe moves between the groups, so the wide swipe only closes an open search, or opens
 * the drawer from the [firstGroup], where nothing lies to the left.
 */
internal fun wideSwipeAllowed(drawerOpen: Boolean, searchOpen: Boolean?, firstGroup: Boolean) = !drawerOpen && (searchOpen != false || firstGroup)
