package xyz.nekobyte.nekobox.ui

import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import androidx.activity.addCallback
import androidx.annotation.IdRes
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
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
import xyz.nekobyte.nekobox.database.ProfileManager
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.database.RoutingProfiles
import xyz.nekobyte.nekobox.database.SubscriptionBean
import xyz.nekobyte.nekobox.database.preference.OnPreferenceDataStoreChangeListener
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
import xyz.nekobyte.nekobox.plugin.PluginTrust
import xyz.nekobyte.nekobox.plugin.Plugins
import xyz.nekobyte.nekobox.utils.Theme
import xyz.nekobyte.nekobox.utils.Util
import xyz.nekobyte.nekobox.widget.glass.Glass
import xyz.nekobyte.nekobox.widget.glass.GlassDrawable
import xyz.nekobyte.nekobox.widget.glass.GlassShape
import java.util.Locale

class MainActivity :
    ThemedActivity(),
    NekoBoxConnection.Callback,
    OnPreferenceDataStoreChangeListener,
    FabContainer,
    NavigationView.OnNavigationItemSelectedListener {

    lateinit var binding: LayoutMainBinding
    lateinit var navigation: NavigationView

    // FabContainer: lets ktx layout managers drive the FAB without importing ui (cycle break).
    override val fabView get() = binding.fab
    override fun showFab() = binding.fab.show()
    override fun hideFab() = binding.fab.hide()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MessageStore.setCurrentActivity(this)

        binding = LayoutMainBinding.inflate(layoutInflater)
        binding.fab.initProgress(binding.fabProgress)
        if (DataStore.appTheme != Theme.BLACK) {
            navigation = binding.navView
            binding.drawerLayout.removeView(binding.navViewBlack)
        } else {
            navigation = binding.navViewBlack
            binding.drawerLayout.removeView(binding.navView)
        }
        navigation.setNavigationItemSelectedListener(this)
        if (Glass.enabled) decorateGlass()

        if (savedInstanceState == null) {
            displayFragmentWithId(R.id.nav_configuration)
        }
        onBackPressedDispatcher.addCallback {
            val current = supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
            when {
                current?.onBackPressed() == true -> Unit
                current is ConfigurationFragment -> moveTaskToBack(true)
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

        refreshNavMenu(DataStore.enableClashAPI)

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
        MessageStore.setCurrentActivity(this)
        ApiUi.attach(this)
        LocalApiAccess.sync(this)

        applyHideFromRecentApps(DataStore.hideFromRecentApps)
    }

    // Liquid glass: the connect button, the stats bar and the drawer show what lies behind them.
    private fun decorateGlass() {
        val content = binding.fragmentHolder.backdrop
        binding.fab.useGlass(GlassDrawable(binding.fab, content, Glass.orbTint(this), GlassShape.CIRCLE))
        val inset = resources.getDimension(R.dimen.glass_capsule_inset)
        binding.stats.useGlass(GlassDrawable(binding.stats, content, Glass.barTint(this), GlassShape.capsule(inset, 2 * inset)))
        navigation.background = GlassDrawable(navigation, binding.coordinator.backdrop, Glass.surfaceTint(this), GlassShape.DRAWER)
        // The drawer slides without redrawing; its glass follows the screen behind it.
        binding.drawerLayout.addDrawerListener(
            object : DrawerLayout.SimpleDrawerListener() {
                override fun onDrawerSlide(drawerView: View, slideOffset: Float) = drawerView.invalidate()
            },
        )
    }

    fun refreshNavMenu(clashApi: Boolean) {
        if (::navigation.isInitialized) {
            navigation.menu.findItem(R.id.nav_traffic)?.isVisible = clashApi
        }
    }

    // Reports stay off until the user answers once; builds without reporting keys never ask.
    private fun askHelpToImprove() {
        if (DataStore.helpToImproveAnswered || (BuildConfig.POSTHOG_PROJECT_TOKEN.isBlank() && BuildConfig.SENTRY_DSN.isBlank())) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.help_to_improve_prompt_title)
            .setMessage(R.string.help_to_improve_prompt_message)
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

        onMainDispatcher {
            displayFragmentWithId(R.id.nav_configuration)

            snackbar(resources.getQuantityString(R.plurals.added, 1, 1)).show()
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
        if (fragment is ConfigurationFragment) {
            binding.stats.allowShow = true
            binding.fab.show()
        } else if (!DataStore.showBottomBar) {
            binding.stats.allowShow = false
            binding.stats.performHide()
            binding.fab.hide()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, fragment)
            .commitAllowingStateLoss()
        binding.drawerLayout.closeDrawers()
    }

    private fun refreshConfigurationProfileState() {
        (supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ConfigurationFragment)
            ?.refreshProfileState()
    }

    fun displayFragmentWithId(@IdRes id: Int): Boolean {
        when (id) {
            R.id.nav_configuration -> {
                displayFragment(ConfigurationFragment())
            }

            R.id.nav_group -> displayFragment(GroupFragment())

            R.id.nav_route -> displayFragment(RouteFragment())

            R.id.nav_tailscale -> displayFragment(TailscaleFragment())

            R.id.nav_settings -> displayFragment(SettingsFragment())

            R.id.nav_connections -> displayFragment(ConnectionsFragment())

            R.id.nav_traffic -> displayFragment(WebviewFragment())

            R.id.nav_tools -> displayFragment(ToolsFragment())

            R.id.nav_logcat -> displayFragment(LogcatFragment())

            R.id.nav_about -> displayFragment(AboutFragment())

            else -> return false
        }
        navigation.menu.findItem(id).isChecked = true
        return true
    }

    private fun changeState(state: BaseService.State, msg: String? = null, animate: Boolean = false) {
        DataStore.serviceState = state
        refreshConfigurationProfileState()

        binding.fab.changeState(state, DataStore.serviceState, animate)
        binding.stats.changeState(state)
        if (msg != null) snackbar(getString(R.string.vpn_error, msg)).show()
    }

    override fun snackbarInternal(text: CharSequence): Snackbar = Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG).apply {
        if (binding.fab.isShown) {
            anchorView = binding.fab
        }
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        changeState(state, msg, true)
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

        val fragment =
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        return fragment != null && fragment.onKeyDown(keyCode, event)
    }
}
