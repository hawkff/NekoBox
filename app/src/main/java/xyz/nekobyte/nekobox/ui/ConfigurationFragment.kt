package xyz.nekobyte.nekobox.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.appcompat.widget.TooltipCompat
import androidx.core.net.toUri
import androidx.core.view.MenuCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.size
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceDataStore
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libcore.Libcore
import xyz.nekobyte.nekobox.GroupType
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.Protocols
import xyz.nekobyte.nekobox.Protocols.getProtocolColor
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.aidl.TrafficData
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.bg.proto.UrlTest
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.GroupManager
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProfileManager
import xyz.nekobyte.nekobox.database.ProfileTestResult
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.database.SubscriptionBean
import xyz.nekobyte.nekobox.database.preference.OnPreferenceDataStoreChangeListener
import xyz.nekobyte.nekobox.databinding.LayoutGroupListBinding
import xyz.nekobyte.nekobox.databinding.LayoutGroupNameDialogBinding
import xyz.nekobyte.nekobox.databinding.LayoutPasteProxiesBinding
import xyz.nekobyte.nekobox.databinding.LayoutProgressListBinding
import xyz.nekobyte.nekobox.fmt.AbstractBean
import xyz.nekobyte.nekobox.fmt.ProxyLines
import xyz.nekobyte.nekobox.fmt.internal.ChainBean
import xyz.nekobyte.nekobox.fmt.internal.chainContains
import xyz.nekobyte.nekobox.fmt.internal.chainHops
import xyz.nekobyte.nekobox.group.GroupUpdater
import xyz.nekobyte.nekobox.group.ImportPreview
import xyz.nekobyte.nekobox.group.RawUpdater
import xyz.nekobyte.nekobox.ktx.Logs
import xyz.nekobyte.nekobox.ktx.MAX_IMPORT_BYTES
import xyz.nekobyte.nekobox.ktx.SubscriptionFoundException
import xyz.nekobyte.nekobox.ktx.app
import xyz.nekobyte.nekobox.ktx.dp2px
import xyz.nekobyte.nekobox.ktx.getColorAttr
import xyz.nekobyte.nekobox.ktx.isIpAddress
import xyz.nekobyte.nekobox.ktx.onMainDispatcher
import xyz.nekobyte.nekobox.ktx.readBytesBounded
import xyz.nekobyte.nekobox.ktx.readTextBounded
import xyz.nekobyte.nekobox.ktx.readableMessage
import xyz.nekobyte.nekobox.ktx.runOnDefaultDispatcher
import xyz.nekobyte.nekobox.ktx.runOnLifecycleDispatcher
import xyz.nekobyte.nekobox.ktx.runOnMainDispatcher
import xyz.nekobyte.nekobox.ktx.scrollTo
import xyz.nekobyte.nekobox.net.ConnectionProbes
import xyz.nekobyte.nekobox.plugin.PluginManager
import xyz.nekobyte.nekobox.proxy.anytls.AnyTLSSettingsActivity
import xyz.nekobyte.nekobox.proxy.config.ConfigSettingActivity
import xyz.nekobyte.nekobox.proxy.shadowtls.ShadowTLSSettingsActivity
import xyz.nekobyte.nekobox.ui.profile.AmneziaWGSettingsActivity
import xyz.nekobyte.nekobox.ui.profile.ChainSettingsActivity
import xyz.nekobyte.nekobox.ui.profile.HttpSettingsActivity
import xyz.nekobyte.nekobox.ui.profile.HysteriaSettingsActivity
import xyz.nekobyte.nekobox.ui.profile.JuicitySettingsActivity
import xyz.nekobyte.nekobox.ui.profile.MieruSettingsActivity
import xyz.nekobyte.nekobox.ui.profile.NaiveSettingsActivity
import xyz.nekobyte.nekobox.ui.profile.ProfileSettingsActivity
import xyz.nekobyte.nekobox.ui.profile.SSHSettingsActivity
import xyz.nekobyte.nekobox.ui.profile.ShadowsocksRSettingsActivity
import xyz.nekobyte.nekobox.ui.profile.ShadowsocksSettingsActivity
import xyz.nekobyte.nekobox.ui.profile.SnellSettingsActivity
import xyz.nekobyte.nekobox.ui.profile.SocksSettingsActivity
import xyz.nekobyte.nekobox.ui.profile.TailscaleSettingsActivity
import xyz.nekobyte.nekobox.ui.profile.TrojanSettingsActivity
import xyz.nekobyte.nekobox.ui.profile.TuicSettingsActivity
import xyz.nekobyte.nekobox.ui.profile.VMessSettingsActivity
import xyz.nekobyte.nekobox.ui.profile.WireGuardSettingsActivity
import xyz.nekobyte.nekobox.widget.glass.Glass
import xyz.nekobyte.nekobox.widget.glass.GlassLayout
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlin.collections.set

/**
 * Home: the profiles of every group, a tab each. As a picker ([select]) it returns the profile tapped; as the
 * Proxies screen ([proxies], see ProxiesFragment) it shows only the lists of proxies, which Home leaves out.
 */
open class ConfigurationFragment @JvmOverloads constructor(
    val select: Boolean = false,
    val selectedItem: ProxyEntity? = null,
    val titleRes: Int = 0,
    val proxies: Boolean = false,
) : ToolbarFragment(R.layout.layout_group_list),
    PopupMenu.OnMenuItemClickListener,
    Toolbar.OnMenuItemClickListener,
    SearchView.OnQueryTextListener,
    OnPreferenceDataStoreChangeListener {

    interface SelectCallback {
        fun returnProfile(profileId: Long)
    }

    private companion object {
        const val KEY_EXIT_PROXY_BASE = "exitProxyBase"
        const val KEY_THROUGH_LIST = "throughList"

        // What a checked proxy's connection reports about itself, its address among it.
        const val EXIT_TRACE = "https://www.cloudflare.com/cdn-cgi/trace"
        const val MAX_SEARCH_RESULTS = 500

        // Actions on the open group; Favorites is no group, so they hide there. Update subscription
        // is decided as the menu opens.
        val GROUP_ACTIONS = listOf(
            R.id.action_clear_traffic_statistics,
            R.id.action_remove_duplicate,
            R.id.action_connection_tcp_ping,
            R.id.action_connection_icmp_ping,
            R.id.action_connection_url_test,
            R.id.action_connection_test_clear_results,
            R.id.action_connection_test_delete_unavailable,
            R.id.action_order_layout,
        )
    }

    lateinit var adapter: GroupPagerAdapter
    lateinit var tabLayout: TabLayout
    lateinit var groupPager: ViewPager2
    private var tabLayoutMediator: TabLayoutMediator? = null
    private lateinit var tabBar: View

    // Lists every group; hidden while there is only one.
    private lateinit var groupPicker: View
    private lateinit var searchResults: RecyclerView
    private lateinit var searchEmpty: View

    private var searchEmptyMargin = 0
    private var searchView: SearchView? = null
    private var searchJob: Job? = null
    private val searchResultAdapter = SearchResultAdapter()
    private var favoritesShown = false

    val alwaysShowAddress by lazy { DataStore.alwaysShowAddress }

    override val keepsAlive get() = true

    override fun current() = alwaysShowAddress == DataStore.alwaysShowAddress

    /** Whether this screen has a tab for [group]: Home the servers, the Proxies screen the proxy lists, a picker both. */
    internal fun shows(group: ProxyGroup) = select || (group.type == GroupType.PROXIES) == proxies

    // The tab this screen opens on: Home's group, the Proxies screen's list.
    private var openGroupId: Long
        get() = if (proxies) DataStore.selectedProxyList else DataStore.selectedGroup
        set(value) {
            if (proxies) DataStore.selectedProxyList = value else DataStore.selectedGroup = value
        }

    /** The group on the open tab; read off the main thread. */
    private fun openGroup(): ProxyGroup? = if (proxies) ProfileDatabase.groupDao.getById(DataStore.selectedProxyList) else DataStore.currentGroup()

    private var selectedProfileSnapshot = 0L
    private var currentProfileSnapshot = 0L
    private var serviceStartedSnapshot = false
    private var connectingSnapshot = false

    private fun syncProfileState() {
        val selectedProfile = selectedItem?.id ?: DataStore.selectedProxy
        val currentProfile = DataStore.currentProfile
        val serviceStarted = DataStore.serviceState.started
        val connecting = DataStore.serviceState == BaseService.State.Connecting
        if (
            selectedProfileSnapshot == selectedProfile &&
            currentProfileSnapshot == currentProfile &&
            serviceStartedSnapshot == serviceStarted &&
            connectingSnapshot == connecting
        ) {
            return
        }

        val changedIds = setOf(
            selectedProfileSnapshot,
            currentProfileSnapshot,
            selectedProfile,
            currentProfile,
        ).filterTo(mutableSetOf()) { it > 0L }

        selectedProfileSnapshot = selectedProfile
        currentProfileSnapshot = currentProfile
        serviceStartedSnapshot = serviceStarted
        connectingSnapshot = connecting

        if (!::adapter.isInitialized || changedIds.isEmpty()) return
        adapter.groupFragments.values.forEach { fragment ->
            fragment.adapter?.refreshProfileState(changedIds)
        }
    }

    internal fun refreshProfileState() {
        runOnMainDispatcher { syncProfileState() }
    }

    internal fun isSelectedProfile(profileId: Long) = selectedProfileSnapshot == profileId

    internal fun isRunningProfile(profileId: Long) = serviceStartedSnapshot && currentProfileSnapshot == profileId

    /**
     * Whether the service is connecting to [profileId] right now: it starts the selected profile, and names it
     * current once up. A picker's selection is its own choice, so a picker shows no connection.
     */
    internal fun isConnectingProfile(profileId: Long) = !select && connectingSnapshot && selectedProfileSnapshot == profileId

    fun getCurrentGroupFragment(): ConfigurationGroupFragment? = try {
        childFragmentManager.findFragmentByTag("f" + openGroupId) as ConfigurationGroupFragment?
    } catch (e: Exception) {
        Logs.e(e)
        null
    }

    fun switchAllGroupFragmentsLayout() {
        adapter.groupFragments.values.forEach { fragment ->
            if (fragment.isAdded && fragment.view != null) {
                fragment.switchLayoutMode()
            }
        }
    }

    // A page selects its group once it settles: a swipe that springs back changes nothing. A layout
    // also reports the settled page, which keeps the selection right after the groups change.
    val updateSelectedCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) = selectGroupAt(position)

        override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
            if (positionOffset == 0f) selectGroupAt(position)
        }

        private fun selectGroupAt(position: Int) {
            if (adapter.groupList.size > position) {
                val groupId = adapter.groupList[position].id
                setFavoritesShown(groupId == FAVORITES_GROUP_ID)
                if (groupId != FAVORITES_GROUP_ID) openGroupId = groupId
            }
        }
    }

    // The groups scroll beneath the floating controls (ToolbarFragment floats them): the tabs ride
    // with the toolbar as a glass capsule.
    private fun styleGlassTabs() {
        tabBar.background = null
        tabLayout.background = null
        Glass.styleTabs(tabLayout, tabBar)
        searchResults.background = null
    }

    override fun GlassLayout.glassPanes(bars: ViewGroup) = paneOver(bars, tabBar, 0f, 0f)

    override fun onGlassInset(content: View, inset: Int) {
        super.onGlassInset(content, inset)
        searchEmpty.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = searchEmptyMargin + inset }
    }

    private fun setFavoritesShown(shown: Boolean) {
        if (favoritesShown == shown) return
        favoritesShown = shown
        for (id in GROUP_ACTIONS) toolbar.menu.findItem(id)?.isVisible = !shown
    }

    override fun onQueryTextChange(query: String): Boolean {
        searchAllGroups(query)
        return false
    }

    // Search covers every group; a result opens its group and scrolls to the profile.
    private fun searchAllGroups(query: String) {
        searchJob?.cancel()
        if (view == null) return
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) {
            showSearchResults(null)
            return
        }
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(150)
            val results = withContext(Dispatchers.IO) {
                val groups = ProfileDatabase.groupDao.allGroups().filter(::shows).associate { it.id to it.displayName() }
                ProfileDatabase.proxyDao.getAll().asSequence()
                    .filter { it.groupId in groups }
                    .filter { profile -> listOf(profile.displayName(), profile.displayType(), profile.displayAddress()).any { it.lowercase().contains(needle) } }
                    .take(MAX_SEARCH_RESULTS)
                    .map { SearchResult(it.id, it.displayName(), "${groups[it.groupId].orEmpty()} · ${it.displayType()}") }
                    .toList()
            }
            showSearchResults(results)
        }
    }

    private fun showSearchResults(results: List<SearchResult>?) {
        if (view == null) return
        searchResults.isGone = results == null
        searchEmpty.isGone = results?.isEmpty() != true
        groupPager.isInvisible = results != null
        searchResultAdapter.submitList(results.orEmpty())
    }

    // Opens the group that holds the profile and scrolls to it.
    private fun jumpToProfile(profileId: Long) {
        runOnDefaultDispatcher {
            val groupId = ProfileDatabase.proxyDao.getById(profileId)?.groupId ?: return@runOnDefaultDispatcher
            onMainDispatcher {
                if (view == null || !::adapter.isInitialized) return@onMainDispatcher
                val index = adapter.groupList.indexOfFirst { it.id == groupId }
                if (index < 0) return@onMainDispatcher
                groupPager.setCurrentItem(index, false)
                // The page attaches on the next frame; its list scrolls once it has loaded.
                groupPager.post { adapter.groupFragments[groupId]?.scrollToProfile(profileId) }
            }
        }
    }

    private fun showGroupMenu(anchor: View, position: Int) {
        val group = adapter.groupList.getOrNull(position)?.takeIf { it.id != FAVORITES_GROUP_ID } ?: return
        PopupMenu(requireContext(), anchor).apply {
            if (group.type == GroupType.PROXIES) {
                menu.add(0, R.string.proxies_connect_through, 0, R.string.proxies_connect_through)
                if (group.frontProxy > 0L) menu.add(0, R.string.proxies_connect_directly, 1, R.string.proxies_connect_directly)
            } else {
                menu.add(0, R.string.group_edit, 0, R.string.group_edit)
            }
            if (group.type == GroupType.SUBSCRIPTION) menu.add(0, R.string.group_update, 1, R.string.group_update)
            // The default group keeps its name and stays, as on the Groups screen. An update under way
            // writes the group back when it ends, over a new name, so neither is offered meanwhile.
            if (!group.ungrouped && group.id !in GroupUpdater.updating) {
                menu.add(0, R.string.group_rename, 2, R.string.group_rename)
                // Removing comes last, apart from the rest.
                menu.add(1, R.string.group_delete, 3, R.string.group_delete)
            }
            MenuCompat.setGroupDividerEnabled(menu, true)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.string.group_edit -> startActivity(Intent(requireContext(), GroupSettingsActivity::class.java).putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id))

                    R.string.proxies_connect_through -> {
                        throughListId = group.id
                        selectThrough.launch(Intent(requireActivity(), ProfileSelectActivity::class.java))
                    }

                    R.string.proxies_connect_directly -> setThrough(group.id, -1L)

                    R.string.group_update -> runOnDefaultDispatcher { ProfileDatabase.groupDao.getById(group.id)?.let { GroupUpdater.startUpdate(it, true) } }

                    R.string.group_rename -> renameGroup(group)

                    R.string.group_delete -> confirmDeleteGroup(group)
                }
                true
            }
        }.show()
    }

    // A provider's title replaces only a placeholder name, so a name set here stays.
    private fun renameGroup(group: ProxyGroup) {
        val binding = LayoutGroupNameDialogBinding.inflate(layoutInflater)
        binding.name.setText(group.name)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.group_rename)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = binding.name.text?.toString()?.trim().orEmpty()
                if (name.isEmpty() || name == group.name) return@setPositiveButton
                // An update that started while the dialog was open would write its own copy back.
                if (group.id in GroupUpdater.updating) {
                    snackbar(R.string.group_editor_wait_update).show()
                    return@setPositiveButton
                }
                // The stored group, which an update may have changed since the tabs loaded.
                runOnDefaultDispatcher {
                    ProfileDatabase.groupDao.getById(group.id)?.let { GroupManager.updateGroup(it.apply { this.name = name }) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        // The keyboard opens on the whole name, so typing replaces it.
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        binding.name.requestFocus()
        binding.name.selectAll()
        // OK stays off while the name is blank, from the start.
        val ok = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        ok.isEnabled = !binding.name.text.isNullOrBlank()
        binding.name.doAfterTextChanged { ok.isEnabled = !it.isNullOrBlank() }
    }

    // As on the Groups screen: names the group and how many profiles go with it, and asks first.
    private fun confirmDeleteGroup(group: ProxyGroup) {
        runOnDefaultDispatcher {
            val count = ProfileDatabase.proxyDao.countByGroup(group.id).toInt()
            onMainDispatcher {
                if (!isAdded) return@onMainDispatcher
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(resources.getQuantityString(R.plurals.group_remove, 1, 1))
                    .setMessage("${group.displayName()} · ${resources.getQuantityString(R.plurals.group_profiles, count, count)}")
                    .setPositiveButton(R.string.delete) { _, _ ->
                        // An update may have started since the menu opened; it would write the group back.
                        if (group.id in GroupUpdater.updating) {
                            snackbar(R.string.group_wait_update).show()
                        } else {
                            runOnDefaultDispatcher { GroupManager.deleteGroup(listOf(group)) }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    // Each group with the facts its Groups row gives: profiles, traffic and when it ends.
    private fun showGroupPicker() {
        val groups = adapter.groupList.toList()
        val context = requireContext()
        runOnDefaultDispatcher {
            val now = System.currentTimeMillis()
            val rows = groups.map { group ->
                val count = if (group.id == FAVORITES_GROUP_ID) DataStore.favoriteProfiles.size else ProfileDatabase.proxyDao.countByGroup(group.id).toInt()
                val quota = GroupWords.quota(group.subscription.takeIf { group.type == GroupType.SUBSCRIPTION })
                val facts = listOfNotNull(
                    context.resources.getQuantityString(R.plurals.group_profiles, count, count),
                    GroupWords.traffic(context, quota),
                    GroupWords.ends(context, quota, now),
                ).joinToString(" · ")
                group.displayName() to facts
            }
            onMainDispatcher {
                if (!isAdded) return@onMainDispatcher
                showChoiceDialog(context, getString(R.string.all_groups), rows, groupPager.currentItem) { groupPager.setCurrentItem(it, false) }
            }
        }
    }

    private fun updateGroupPicker() {
        groupPicker.isVisible = adapter.groupList.size > 1
    }

    override fun onQueryTextSubmit(query: String): Boolean = false

    @SuppressLint("DetachAndAttachSameFragment")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)

        if (savedInstanceState != null) {
            exitProxyBaseId = savedInstanceState.getLong(KEY_EXIT_PROXY_BASE, 0L)
            throughListId = savedInstanceState.getLong(KEY_THROUGH_LIST, 0L)
            parentFragmentManager.beginTransaction()
                .setReorderingAllowed(false)
                .detach(this)
                .attach(this)
                .commit()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_EXIT_PROXY_BASE, exitProxyBaseId)
        outState.putLong(KEY_THROUGH_LIST, throughListId)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        releaseViewListeners()

        if (!select) {
            toolbar.inflateMenu(R.menu.add_profile_menu)
            if (proxies) fitProxiesMenu(toolbar.menu)
            MenuCompat.setGroupDividerEnabled(toolbar.menu, true)
            toolbar.menu.findItem(R.id.action_add)?.subMenu?.let { MenuCompat.setGroupDividerEnabled(it, true) }
            toolbar.menu.findItem(R.id.action_global_mode)?.isChecked = DataStore.globalMode
            toolbar.setOnMenuItemClickListener(this)
        } else {
            toolbar.setTitle(titleRes)
            toolbar.setNavigationIcon(R.drawable.ic_close)
            toolbar.setNavigationContentDescription(R.string.close)
            toolbar.setNavigationOnClickListener {
                requireActivity().finish()
            }
        }

        // findViewById (not ViewBinding): action_search is a menu action view inflated from the
        // toolbar menu, not a view in this fragment's layout binding.
        val searchView = toolbar.findViewById<SearchView>(R.id.action_search)
        this.searchView = searchView
        if (searchView != null) {
            searchView.setOnQueryTextListener(this)
            searchView.maxWidth = Int.MAX_VALUE

            searchView.setOnQueryTextFocusChangeListener { _, hasFocus ->
                // Results stay while the keyboard hides; an empty search closes.
                if (!hasFocus && searchView.query.isNullOrEmpty()) {
                    cancelSearch(searchView)
                }
            }
            searchView.setOnSearchClickListener { updatePaging() }
            // Its close button collapses it without passing through cancelSearch; look again once it has.
            searchView.setOnCloseListener {
                searchView.post(::updatePaging)
                false
            }
        }

        val groupListBinding = LayoutGroupListBinding.bind(view)
        groupPager = groupListBinding.groupPager
        tabLayout = groupListBinding.groupTab
        tabBar = groupListBinding.groupTabBar
        // The tabs name the group on show, the only one too.
        toolbar.elevation = dp2px(4).toFloat()
        searchResults = groupListBinding.groupSearchResults
        searchEmpty = groupListBinding.groupSearchEmpty
        searchEmptyMargin = (searchEmpty.layoutParams as FrameLayout.LayoutParams).topMargin
        if (Glass.enabled) styleGlassTabs()
        searchResults.layoutManager = LinearLayoutManager(requireContext())
        searchResults.adapter = searchResultAdapter
        groupPicker = groupListBinding.groupPicker
        groupPicker.setOnClickListener { showGroupPicker() }
        emptyState = groupListBinding.groupEmpty
        groupListBinding.groupEmptyAction.setOnClickListener { pasteProxies() }
        if (proxies) toolbar.setTitle(R.string.menu_proxies)
        TooltipCompat.setTooltipText(groupPicker, getString(R.string.all_groups))
        syncProfileState()
        adapter = GroupPagerAdapter()
        ProfileManager.addListener(adapter)
        GroupManager.addListener(adapter)

        groupPager.adapter = adapter
        // The open group's page is built first, and its neighbors once it has drawn, ready for a swipe.
        groupPager.doOnPreDraw { groupPager.post { groupPager.offscreenPageLimit = 2 } }
        updatePaging()

        tabLayoutMediator = TabLayoutMediator(tabLayout, groupPager) { tab, position ->
            if (adapter.groupList.size > position) {
                tab.text = adapter.groupList[position].displayName()
            }
            tab.view.setOnLongClickListener {
                if (!select) showGroupMenu(it, position)
                true
            }
        }.also { it.attach() }

        toolbar.setOnClickListener {
            val fragment = getCurrentGroupFragment()
            val selectedProfile = selectedItem?.id ?: DataStore.selectedProxy
            // The active profile sits in another group, or Favorites is open: open its group.
            if (selectedProfile > 0L && (favoritesShown || fragment?.adapter?.configurationIdList?.contains(selectedProfile) != true)) {
                jumpToProfile(selectedProfile)
                return@setOnClickListener
            }

            if (fragment != null) {
                val fragmentAdapter = fragment.adapter ?: return@setOnClickListener
                val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
                val selectedProfileIndex =
                    fragmentAdapter.configurationIdList.indexOf(selectedProxy)
                if (selectedProfileIndex != -1) {
                    val layoutManager = fragment.layoutManager
                    if (layoutManager is LinearLayoutManager) {
                        val first = layoutManager.findFirstVisibleItemPosition()
                        val last = layoutManager.findLastVisibleItemPosition()

                        if (selectedProfileIndex !in first..last) {
                            fragment.configurationListView.scrollTo(selectedProfileIndex, true)
                            return@setOnClickListener
                        }
                    } else {
                        fragment.configurationListView.scrollTo(selectedProfileIndex, true)
                        return@setOnClickListener
                    }
                }

                fragment.configurationListView.scrollTo(0)
            }
        }

        DataStore.profileCacheStore.registerChangeListener(this)
    }

    // Showing again: global mode, profiles and groups can change on other screens while this one stays hidden.
    override fun onResume() {
        super.onResume()
        if (!select && view != null) toolbar.menu.findItem(R.id.action_global_mode)?.isChecked = DataStore.globalMode
        searchView?.query?.toString()?.takeIf { searchOpen && it.isNotBlank() }?.let(::searchAllGroups)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.findItem(R.id.action_global_mode)?.isChecked = DataStore.globalMode
        super.onPrepareOptionsMenu(menu)
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        runOnMainDispatcher {
            if (view == null || !::adapter.isInitialized) return@runOnMainDispatcher
            // editingGroup
            if (key == Key.PROFILE_GROUP) {
                val targetId = DataStore.editingGroup
                // Home leaves the Proxies screen's lists to it, and the other way round.
                val target = if (targetId > 0) onDefaultDispatcher { ProfileDatabase.groupDao.getById(targetId) } else null
                if (target == null || !shows(target) || view == null) return@runOnMainDispatcher
                if (targetId != openGroupId) openGroupId = targetId
                // Compared with the tab on screen: Home lives on under other screens, and whoever asks for
                // a group may have set selectedGroup already.
                if (targetId > 0 && targetId != adapter.groupList.getOrNull(groupPager.currentItem)?.id) {
                    val targetIndex = adapter.groupList.indexOfFirst { it.id == targetId }
                    if (targetIndex >= 0) {
                        groupPager.setCurrentItem(targetIndex, false)
                    } else {
                        adapter.reload()
                    }
                }
            }
        }
    }

    private fun releaseViewListeners() {
        DataStore.profileCacheStore.unregisterChangeListener(this)
        tabLayoutMediator?.detach()
        tabLayoutMediator = null
        if (::adapter.isInitialized) {
            adapter.dispose()
            GroupManager.removeListener(adapter)
            ProfileManager.removeListener(adapter)
        }
        if (::groupPager.isInitialized) {
            groupPager.unregisterOnPageChangeCallback(updateSelectedCallback)
            groupPager.adapter = null
        }
    }

    override fun onDestroyView() {
        releaseViewListeners()
        super.onDestroyView()
    }

    override fun onDestroy() {
        releaseViewListeners()
        super.onDestroy()
    }

    override fun onKeyDown(ketCode: Int, event: KeyEvent): Boolean {
        val fragment = getCurrentGroupFragment()
        fragment?.configurationListView?.apply {
            if (!hasFocus()) requestFocus()
        }
        return super.onKeyDown(ketCode, event)
    }

    private val importFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
            if (file != null) {
                runOnDefaultDispatcher {
                    try {
                        val fileName =
                            requireContext().contentResolver.query(file, null, null, null, null)
                                ?.use { cursor ->
                                    cursor.moveToFirst()
                                    cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                                        .let(cursor::getString)
                                }
                        val proxies = mutableListOf<AbstractBean>()
                        var preview: ImportPreview? = null
                        val skippedEntries = mutableListOf<String>()
                        if (fileName != null && fileName.endsWith(".zip")) {
                            // try parse wireguard zip (bounded per-entry + cumulative to stop
                            // a decompression bomb from exhausting memory)
                            ZipInputStream(
                                requireContext().contentResolver.openInputStream(file)!!,
                            ).use { zip ->
                                var remaining = MAX_IMPORT_BYTES
                                while (true) {
                                    val entry = zip.nextEntry ?: break
                                    if (entry.isDirectory) continue
                                    // Cap each entry at the REMAINING budget so cumulative
                                    // decompressed bytes across all entries can never exceed
                                    // MAX_IMPORT_BYTES (defeats a many-entry zip bomb).
                                    val bytes = zip.readBytesBounded(remaining)
                                    remaining -= bytes.size
                                    try {
                                        RawUpdater.parseRaw(bytes.toString(Charsets.UTF_8), entry.name)
                                            ?.let { pl -> proxies.addAll(pl) }
                                    } catch (e: SubscriptionFoundException) {
                                        throw e
                                    } catch (e: Exception) {
                                        // One config that cannot be imported does not stop the others; it is named.
                                        skippedEntries += getString(R.string.import_entry_failed, entry.name, e.readableMessage)
                                    }
                                    zip.closeEntry()
                                }
                            }
                        } else {
                            val fileText =
                                requireContext().contentResolver.openInputStream(file)!!.use {
                                    it.readTextBounded()
                                }
                            RawUpdater.parseImport(fileText, fileName ?: "")?.let { parsed ->
                                proxies.addAll(parsed.proxies)
                                preview = ImportPreview.of(parsed)
                            }
                        }
                        if (skippedEntries.isNotEmpty()) {
                            onMainDispatcher {
                                if (isAdded) {
                                    MaterialAlertDialogBuilder(requireContext())
                                        .setMessage(skippedEntries.joinToString("\n"))
                                        .setPositiveButton(android.R.string.ok, null)
                                        .show()
                                }
                            }
                        }
                        if (proxies.isEmpty()) {
                            onMainDispatcher {
                                snackbar(getString(R.string.no_proxies_found_in_file)).show()
                            }
                        } else {
                            confirmImport(preview, proxies)
                        }
                    } catch (e: SubscriptionFoundException) {
                        (requireActivity() as MainActivity).importSubscription(e.link.toUri())
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }
                }
            }
        }

    /** Show what the import keeps and drops before creating profiles. Sources without a preview (zip) import directly. */
    private suspend fun confirmImport(preview: ImportPreview?, proxies: List<AbstractBean>) {
        if (preview == null) return import(proxies)
        onMainDispatcher {
            if (!isAdded) return@onMainDispatcher
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.import_preview_title)
                .setMessage(describe(preview))
                .setPositiveButton(R.string.import_preview_confirm) { _, _ -> runOnDefaultDispatcher { import(proxies) } }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun describe(preview: ImportPreview): String = buildString {
        append(getString(R.string.import_preview_accepted, preview.acceptedCount))
        if (preview.accepted.isNotEmpty()) {
            append(preview.accepted.entries.joinToString(", ", " (", ")") { "${it.key} ${it.value}" })
        }
        for ((reason, count) in preview.skipped) {
            append('\n')
            append(
                getString(
                    when (reason) {
                        ImportPreview.Reason.BUILTIN -> R.string.import_preview_skipped_builtin
                        ImportPreview.Reason.UNPARSED -> R.string.import_preview_skipped_unparsed
                    },
                    count,
                ),
            )
        }
        if (preview.reasons.isNotEmpty()) append('\n').append(getString(R.string.subscription_import_reasons, preview.reasons.joinToString(", ")))
        if (preview.behaviorDiffers) {
            val sections = preview.dropped.joinToString(", ") { getString(it.title) }
            append("\n\n").append(getString(R.string.import_preview_dropped, sections))
        }
    }

    // A subscription's group cannot take them, so the message names the group that did; Home opens its tab.
    suspend fun import(proxies: List<AbstractBean>) {
        val targetId = DataStore.selectedGroupForImport()
        ProfileManager.createProfiles(targetId, proxies)
        val group = ProfileDatabase.groupDao.getById(targetId)?.displayName().orEmpty()
        onMainDispatcher {
            DataStore.editingGroup = targetId
            snackbar(resources.getQuantityString(R.plurals.added_to, proxies.size, proxies.size, group)).show()
        }
    }

    // The profile the exit is added to, captured when the picker opens: a selector update
    // while the picker is up must not redirect the new chain to another profile.
    private var exitProxyBaseId = 0L

    private val selectExitProxy =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { (resultCode, data) ->
            if (resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val exitId = data?.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0L) ?: 0L
            val baseId = exitProxyBaseId.takeIf { it > 0L } ?: DataStore.selectedProxy
            runOnDefaultDispatcher {
                try {
                    val chain = addExitProxy(baseId, exitId)
                    onMainDispatcher { snackbar(getString(R.string.exit_proxy_added, chain.displayName())).show() }
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher { snackbar(e.readableMessage).show() }
                }
            }
        }

    private lateinit var emptyState: View

    // The Proxies screen without a list says what goes there and starts one; the tabs wait for the first list.
    private fun showEmpty() {
        if (!proxies || view == null) return
        val empty = adapter.groupList.isEmpty()
        emptyState.isVisible = empty
        tabBar.isVisible = !empty
    }

    // Home's menu on the Proxies screen: its own ways to add, a check that also reads where each proxy leaves
    // from, and none of what belongs to Home (subscriptions, the exit picker, global mode).
    private fun fitProxiesMenu(menu: Menu) {
        menu.findItem(R.id.action_add)?.subMenu?.apply {
            removeGroup(R.id.group_add_profile)
            removeGroup(R.id.group_add_group)
            add(R.id.group_add_profile, R.id.action_paste_proxies, 0, R.string.proxies_paste)
            add(R.id.group_add_profile, R.id.action_new_socks, 1, R.string.proxies_new_socks)
            add(R.id.group_add_profile, R.id.action_new_http, 2, R.string.proxies_new_http)
            add(R.id.group_add_group, R.id.action_new_proxy_list, 3, R.string.proxies_new_list)
        }
        menu.findItem(R.id.action_misc)?.subMenu?.apply {
            findItem(R.id.action_connection_url_test)?.setTitle(R.string.proxies_check)
            removeItem(R.id.action_update_subscription)
            removeGroup(R.id.group_connection)
        }
    }

    private suspend fun createProxyList(name: String) = GroupManager.createGroup(ProxyGroup(name = name, type = GroupType.PROXIES))

    // A new profile goes where this screen adds: the open list on the Proxies screen (a first list when there is
    // none), Home's import target otherwise.
    private fun newProfile(editor: Class<out Activity>) {
        if (!proxies) {
            startActivity(Intent(requireActivity(), editor))
            return
        }
        val open = adapter.groupList.getOrNull(groupPager.currentItem)?.id
        val name = getString(R.string.proxies_default_list)
        runOnDefaultDispatcher {
            val listId = open ?: createProxyList(name).id
            onMainDispatcher {
                if (isAdded) startActivity(Intent(requireActivity(), editor).putExtra(ProfileSettingsActivity.EXTRA_GROUP_ID, listId))
            }
        }
    }

    // A name for a new list of proxies, as a group's name is asked for.
    private fun newProxyList() {
        val binding = LayoutGroupNameDialogBinding.inflate(layoutInflater)
        binding.nameLayout.hint = getString(R.string.proxies_list_name)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.proxies_new_list)
            .setView(binding.root)
            .setPositiveButton(R.string.add) { _, _ ->
                val name = binding.name.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) runOnDefaultDispatcher { createProxyList(name) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        binding.name.requestFocus()
        val ok = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        ok.isEnabled = false
        binding.name.doAfterTextChanged { ok.isEnabled = !it.isNullOrBlank() }
    }

    /**
     * Proxies as a provider lists them, into the open list; the first paste also names the first list. A list on
     * the clipboard fills the field, and the count of proxies and skipped lines follows the text.
     */
    private fun pasteProxies() {
        val binding = LayoutPasteProxiesBinding.inflate(layoutInflater)
        val open = adapter.groupList.getOrNull(groupPager.currentItem)
        binding.listLayout.isVisible = open == null
        binding.listName.setText(R.string.proxies_default_list)
        NekoBox.getClipboardText().takeIf { ProxyLines.parse(it, ProxyLines.Plain.SOCKS5).proxies.isNotEmpty() }?.let(binding.proxies::setText)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.proxies_paste)
            .setView(binding.root)
            .setPositiveButton(R.string.add, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        fun parsed() = ProxyLines.parse(
            binding.proxies.text?.toString().orEmpty(),
            if (binding.kind.checkedChipId == R.id.kind_http) ProxyLines.Plain.HTTP else ProxyLines.Plain.SOCKS5,
        )
        fun refresh() {
            val result = parsed()
            val count = result.proxies.size
            binding.proxiesLayout.helperText = listOfNotNull(
                resources.getQuantityString(R.plurals.proxies_found, count, count),
                result.skipped.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.proxies_skipped, it, it) },
            ).joinToString(" · ")
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled = count > 0 && (open != null || !binding.listName.text.isNullOrBlank())
        }
        dialog.setOnShowListener {
            refresh()
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val found = parsed().proxies
                val name = binding.listName.text?.toString()?.trim().orEmpty()
                dialog.dismiss()
                runOnDefaultDispatcher {
                    val list = open ?: createProxyList(name)
                    ProfileManager.createProfiles(list.id, found)
                    onMainDispatcher {
                        snackbar(resources.getQuantityString(R.plurals.added_to, found.size, found.size, list.displayName())).show()
                    }
                }
            }
        }
        binding.proxies.doAfterTextChanged { refresh() }
        binding.listName.doAfterTextChanged { refresh() }
        binding.kind.setOnCheckedStateChangeListener { _, _ -> refresh() }
        dialog.show()
    }

    // The list whose first hop the picker is choosing, kept while it is open.
    private var throughListId = 0L

    private val selectThrough =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { (resultCode, data) ->
            if (resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val id = data?.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0L) ?: 0L
            if (id > 0L && throughListId > 0L) setThrough(throughListId, id)
        }

    /**
     * Makes the proxies of [listId] connect through [profileId] first, or directly for a negative id: Mullvad's
     * SOCKS5 proxies answer only inside its WireGuard tunnel, and a residential exit can sit behind the user's own
     * server. The message offers to undo it.
     */
    private fun setThrough(listId: Long, profileId: Long) {
        runOnDefaultDispatcher {
            val list = ProfileDatabase.groupDao.getById(listId) ?: return@runOnDefaultDispatcher
            val through = if (profileId > 0L) ProfileManager.getProfile(profileId) else null
            if (through != null && through.groupId == listId) {
                onMainDispatcher { snackbar(R.string.proxies_through_own_list).show() }
                return@runOnDefaultDispatcher
            }
            val previous = list.frontProxy
            GroupManager.updateGroup(list.apply { frontProxy = through?.id ?: -1L })
            reloadIfRunningIn(listId)
            onMainDispatcher {
                val message = if (through == null) {
                    getString(R.string.proxies_now_direct, list.displayName())
                } else {
                    getString(R.string.proxies_now_through, list.displayName(), through.displayName())
                }
                snackbar(message).setAction(R.string.undo) { setThrough(listId, previous) }.show()
            }
        }
    }

    /** Makes the servers of a group the user picks leave through [profileId], so sites see the proxy's address. */
    internal fun useAsExit(profileId: Long) {
        val context = requireContext()
        runOnDefaultDispatcher {
            val proxy = ProfileManager.getProfile(profileId) ?: return@runOnDefaultDispatcher
            val groups = ProfileDatabase.groupDao.allGroups().filter { it.type != GroupType.PROXIES }
            onMainDispatcher {
                if (!isAdded) return@onMainDispatcher
                // A group that leaves through it already says so; picking it again stops that.
                val rows = groups.map { it.displayName() to getString(R.string.proxies_exit_now).takeIf { _ -> it.landingProxy == profileId } }
                showChoiceDialog(context, getString(R.string.proxies_exit_title, proxy.displayName()), rows, -1) { index ->
                    val group = groups[index]
                    setExit(group.id, if (group.landingProxy == profileId) -1L else profileId)
                }
            }
        }
    }

    private fun setExit(groupId: Long, profileId: Long) {
        runOnDefaultDispatcher {
            val group = ProfileDatabase.groupDao.getById(groupId) ?: return@runOnDefaultDispatcher
            val previous = group.landingProxy
            val proxy = if (profileId > 0L) ProfileManager.getProfile(profileId) else null
            GroupManager.updateGroup(group.apply { landingProxy = proxy?.id ?: -1L })
            reloadIfRunningIn(groupId)
            onMainDispatcher {
                val message = if (proxy == null) {
                    getString(R.string.proxies_exit_cleared, group.displayName())
                } else {
                    getString(R.string.proxies_exit_set, group.displayName(), proxy.displayName())
                }
                snackbar(message).setAction(R.string.undo) { setExit(groupId, previous) }.show()
            }
        }
    }

    // A new route for the running profile's group applies at once.
    private suspend fun reloadIfRunningIn(groupId: Long) {
        if (!DataStore.serviceState.canStop) return
        if (ProfileDatabase.proxyDao.getById(DataStore.selectedProxy)?.groupId == groupId) NekoBox.reloadService()
    }

    // A running connection cannot grow a hop: build a chain profile that ends at the exit,
    // select it and reconnect. The hop order is the profile name. Both sides stay references,
    // so later edits to either chain apply to this one as well.
    private suspend fun addExitProxy(baseId: Long, exitId: Long): ProxyEntity {
        val base = requireNotNull(ProfileManager.getProfile(baseId)) { getString(R.string.profile_empty) }
        val exit = requireNotNull(ProfileManager.getProfile(exitId)) { getString(R.string.profile_empty) }
        require(!chainContains(exit, base.id)) { getString(R.string.circular_reference_sum) }
        // Resolve both sides now, so a chain without hops or with a deleted hop fails here
        // with its message instead of stopping the service on reload.
        chainHops(base)
        chainHops(exit)
        val bean = ChainBean().apply {
            name = "${base.displayName()} \u2192 ${exit.displayName()}"
            proxies = listOf(base.id, exit.id)
            initializeDefaultValues()
        }

        // Subscription groups are overwritten on update, and a group that picks its member by
        // URL test would treat the chain as one more candidate: keep it where the selection is
        // what gets dialed.
        fun ProxyGroup.usesSelection() = type == GroupType.BASIC && !(isSelector && autoSelect)
        val groupId = ProfileDatabase.groupDao.getById(base.groupId)?.takeIf { it.usesSelection() }?.id
            ?: ProfileDatabase.groupDao.allGroups().firstOrNull { it.usesSelection() }?.id
            ?: ProfileDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
        val chain = ProfileManager.createProfile(groupId, bean)
        val previous = DataStore.selectedProxy
        DataStore.selectedProxy = chain.id
        refreshProfileState()
        ProfileManager.postUpdate(previous, noTraffic = true)
        if (DataStore.serviceState.canStop) NekoBox.reloadService(chain.id)
        return chain
    }

    // Names up to 20 of the profiles and asks before removing them; says so when there are none.
    private fun confirmRemove(profiles: List<ProxyEntity>, noneText: Int) {
        if (!isAdded) return
        if (profiles.isEmpty()) {
            snackbar(noneText).show()
            return
        }
        val names = profiles.take(20).joinToString("\n") { it.displayName() }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(resources.getQuantityString(R.plurals.remove_profiles, profiles.size, profiles.size))
            .setMessage(if (profiles.size > 20) "$names\n…" else names)
            .setPositiveButton(R.string.delete) { _, _ -> deleteProfilesFromGroup(profiles.first().groupId, profiles) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteProfilesFromGroup(groupId: Long, profiles: List<ProxyEntity>) {
        val profileIds = profiles.map { it.id }
        runOnDefaultDispatcher {
            try {
                ProfileManager.deleteProfiles(profiles)
                val groupEmptied = ProfileDatabase.proxyDao.countByGroup(groupId) == 0L &&
                    ProfileDatabase.groupDao.getById(groupId)?.ungrouped == true
                val fallbackGroupId = if (groupEmptied) {
                    ProfileDatabase.groupDao.allGroups().firstOrNull { it.id != groupId }?.id
                } else {
                    null
                }
                onMainDispatcher {
                    if (view != null && ::adapter.isInitialized) {
                        adapter.groupFragments[groupId]?.adapter?.removeProfiles(profileIds)
                        if (groupEmptied) {
                            if (fallbackGroupId != null && openGroupId == groupId) {
                                openGroupId = fallbackGroupId
                            }
                            adapter.reload()
                        }
                    }
                }
            } catch (e: Exception) {
                Logs.w(e)
                GroupManager.postReload(groupId)
                onMainDispatcher {
                    if (view != null) snackbar(e.readableMessage).show()
                }
            }
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_create_group -> showNewGroupChoices(requireContext())

            R.id.action_scan_qr_code -> {
                startActivity(Intent(context, ScannerActivity::class.java))
            }

            R.id.action_import_clipboard -> {
                val text = NekoBox.getClipboardText()
                if (text.isBlank()) {
                    snackbar(getString(R.string.clipboard_empty)).show()
                } else {
                    runOnDefaultDispatcher {
                        try {
                            val parsed = RawUpdater.parseImport(text)
                            if (parsed == null) {
                                onMainDispatcher {
                                    snackbar(getString(R.string.no_proxies_found_in_clipboard)).show()
                                }
                            } else {
                                confirmImport(ImportPreview.of(parsed), parsed.proxies)
                            }
                        } catch (e: SubscriptionFoundException) {
                            onMainDispatcher {
                                if (e.link.startsWith("sn://")) {
                                    (requireActivity() as MainActivity).importSubscription(e.link.toUri())
                                } else {
                                    val subscriptionLink = Uri.parse(e.link).getQueryParameter("url") ?: e.link

                                    val group = ProxyGroup(type = GroupType.SUBSCRIPTION)
                                    val subscription = SubscriptionBean()
                                    group.subscription = subscription
                                    subscription.link = subscriptionLink
                                    subscription.autoUpdate = false
                                    group.name = ""
                                    startActivity(
                                        Intent(requireContext(), GroupSettingsActivity::class.java).apply {
                                            putExtra(
                                                GroupSettingsActivity.EXTRA_GROUP_SUBSCRIPTION_LINK,
                                                subscriptionLink,
                                            )
                                        },
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            Logs.w(e)
                            onMainDispatcher {
                                snackbar(e.readableMessage).show()
                            }
                        }
                    }
                }
            }

            R.id.action_import_file -> {
                startFilesForResult(importFile, "*/*")
            }

            R.id.action_new_socks -> newProfile(SocksSettingsActivity::class.java)

            R.id.action_new_http -> newProfile(HttpSettingsActivity::class.java)

            R.id.action_paste_proxies -> pasteProxies()

            R.id.action_new_proxy_list -> newProxyList()

            R.id.action_new_ss -> {
                startActivity(Intent(requireActivity(), ShadowsocksSettingsActivity::class.java))
            }

            R.id.action_new_ssr -> {
                startActivity(Intent(requireActivity(), ShadowsocksRSettingsActivity::class.java))
            }

            R.id.action_new_vmess -> {
                startActivity(Intent(requireActivity(), VMessSettingsActivity::class.java))
            }

            R.id.action_new_vless -> {
                startActivity(
                    Intent(requireActivity(), VMessSettingsActivity::class.java).apply {
                        putExtra("vless", true)
                    },
                )
            }

            R.id.action_new_trojan -> {
                startActivity(Intent(requireActivity(), TrojanSettingsActivity::class.java))
            }

            R.id.action_new_mieru -> {
                startActivity(Intent(requireActivity(), MieruSettingsActivity::class.java))
            }

            R.id.action_new_naive -> {
                startActivity(Intent(requireActivity(), NaiveSettingsActivity::class.java))
            }

            R.id.action_new_hysteria -> {
                startActivity(Intent(requireActivity(), HysteriaSettingsActivity::class.java))
            }

            R.id.action_new_tuic -> {
                startActivity(Intent(requireActivity(), TuicSettingsActivity::class.java))
            }

            R.id.action_new_juicity -> {
                startActivity(Intent(requireActivity(), JuicitySettingsActivity::class.java))
            }

            R.id.action_new_ssh -> {
                startActivity(Intent(requireActivity(), SSHSettingsActivity::class.java))
            }

            R.id.action_new_snell -> {
                startActivity(Intent(requireActivity(), SnellSettingsActivity::class.java))
            }

            R.id.action_new_wg -> {
                startActivity(Intent(requireActivity(), WireGuardSettingsActivity::class.java))
            }

            R.id.action_new_awg -> {
                startActivity(Intent(requireActivity(), AmneziaWGSettingsActivity::class.java))
            }

            R.id.action_new_tailscale -> {
                startActivity(Intent(requireActivity(), TailscaleSettingsActivity::class.java))
            }

            R.id.action_new_shadowtls -> {
                startActivity(Intent(requireActivity(), ShadowTLSSettingsActivity::class.java))
            }

            R.id.action_new_anytls -> {
                startActivity(Intent(requireActivity(), AnyTLSSettingsActivity::class.java))
            }

            R.id.action_new_config -> {
                startActivity(Intent(requireActivity(), ConfigSettingActivity::class.java))
            }

            R.id.action_new_chain -> {
                startActivity(Intent(requireActivity(), ChainSettingsActivity::class.java))
            }

            R.id.action_add_exit_proxy -> {
                exitProxyBaseId = DataStore.selectedProxy
                if (exitProxyBaseId <= 0L) {
                    snackbar(R.string.profile_empty).show()
                } else {
                    selectExitProxy.launch(Intent(requireActivity(), ProfileSelectActivity::class.java))
                }
            }

            R.id.action_share_connection -> {
                startActivity(Intent(requireActivity(), ShareConnectionActivity::class.java))
            }

            R.id.action_update_subscription -> {
                // An unset selection uses the default group; never follow a later tab switch.
                val groupId = DataStore.configurationStore.getLong(Key.PROFILE_GROUP)?.takeIf { it > 0L }
                runOnLifecycleDispatcher {
                    val group = if (groupId == null) {
                        ProfileDatabase.groupDao.allGroups().firstOrNull()
                    } else {
                        ProfileDatabase.groupDao.getById(groupId)
                    }
                    if (group?.type != GroupType.SUBSCRIPTION) {
                        onMainDispatcher { snackbar(R.string.group_not_subscription).show() }
                    } else {
                        GroupUpdater.startUpdate(group, true)
                    }
                }
            }

            R.id.action_clear_traffic_statistics -> {
                runOnDefaultDispatcher {
                    val profiles = ProfileDatabase.proxyDao.getByGroup(openGroupId)
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) {
                        for (profile in profiles) {
                            if (profile.tx != 0L || profile.rx != 0L) {
                                profile.tx = 0
                                profile.rx = 0
                                toClear.add(profile)
                            }
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        ProfileManager.updateProfile(toClear)
                    }
                }
            }

            R.id.action_connection_test_clear_results -> {
                runOnDefaultDispatcher {
                    val profiles = ProfileDatabase.proxyDao.getByGroup(openGroupId)
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) {
                        for (profile in profiles) {
                            if (profile.status != 0) {
                                profile.status = 0
                                profile.ping = 0
                                profile.error = null
                                toClear.add(profile)
                            }
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        ProfileManager.updateProfile(toClear)
                    }
                }
            }

            R.id.action_connection_test_delete_unavailable -> {
                runOnDefaultDispatcher {
                    val profiles = ProfileDatabase.proxyDao.getByGroup(openGroupId)
                    // Status 0 is untested, 1 available; the rest failed their last test.
                    val failed = profiles.filter { it.status != 0 && it.status != 1 }
                    onMainDispatcher { confirmRemove(failed, R.string.no_unavailable_profiles) }
                }
            }

            R.id.action_remove_duplicate -> {
                runOnDefaultDispatcher {
                    val profiles = ProfileDatabase.proxyDao.getByGroup(openGroupId)
                    val seen = HashSet<Protocols.Deduplication>()
                    val duplicates = profiles.filterNot { seen.add(Protocols.Deduplication(it.requireBean())) }
                    onMainDispatcher { confirmRemove(duplicates, R.string.no_duplicate_profiles) }
                }
            }

            R.id.action_connection_tcp_ping -> {
                pingTest(false)
            }

            R.id.action_connection_icmp_ping -> {
                pingTest(true)
            }

            R.id.action_connection_url_test -> {
                urlTest()
            }

            R.id.action_misc -> {
                // The menu is opening: only an open subscription group can update.
                val open = adapter.groupList.getOrNull(groupPager.currentItem)
                val update = item.subMenu?.findItem(R.id.action_update_subscription)
                update?.isVisible = open?.type == GroupType.SUBSCRIPTION
            }

            R.id.action_global_mode -> {
                item.isChecked = !item.isChecked
                DataStore.globalMode = item.isChecked
                if (DataStore.serviceState.canStop) {
                    runOnDefaultDispatcher {
                        try {
                            // ensure the globalMode write-through has committed before offering
                            // reload (the :bg reload re-reads globalMode from the DB)
                            DataStore.configurationStore.awaitWrites()
                            snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
                                runOnDefaultDispatcher {
                                    try {
                                        DataStore.configurationStore.awaitWrites()
                                        NekoBox.reloadService()
                                    } catch (e: Exception) {
                                        Logs.w(e)
                                        onMainDispatcher {
                                            snackbar(getString(R.string.service_failed)).show()
                                        }
                                    }
                                }
                            }.show()
                        } catch (e: Exception) {
                            Logs.w(e)
                            onMainDispatcher {
                                snackbar(getString(R.string.service_failed)).show()
                            }
                        }
                    }
                }
                return true
            }
        }
        return false
    }

    private inner class TestDialog {
        val binding = LayoutProgressListBinding.inflate(layoutInflater)
        val builder = MaterialAlertDialogBuilder(requireContext()).setView(binding.root)
            .setPositiveButton(R.string.minimize) { _, _ ->
                minimize()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                cancel()
            }
            .setCancelable(false)

        lateinit var cancel: () -> Unit
        lateinit var minimize: () -> Unit

        val dialogStatus = AtomicInteger(0) // 1: hidden 2: canceled
        var notification: ConnectionTestNotification? = null

        val results: MutableSet<ProfileTestResult> = ConcurrentHashMap.newKeySet()
        var proxyN = 0
        val finishedN = AtomicInteger(0)

        fun update(result: ProfileTestResult) {
            val profile = result.profile
            if (dialogStatus.get() != 2) {
                results.add(result)
            }
            runOnMainDispatcher {
                val context = context ?: return@runOnMainDispatcher
                val progress = finishedN.addAndGet(1)
                val status = dialogStatus.get()
                notification?.updateNotification(
                    progress,
                    proxyN,
                    progress >= proxyN || status == 2,
                )
                if (status >= 1) return@runOnMainDispatcher
                if (!isAdded) return@runOnMainDispatcher

                // refresh dialog

                var profileStatusText: String? = null
                var profileStatusColor = 0

                when (profile.status) {
                    -1 -> {
                        profileStatusText = profile.error
                        profileStatusColor = context.getColorAttr(android.R.attr.textColorSecondary)
                    }

                    0 -> {
                        profileStatusText = getString(R.string.connection_test_testing)
                        profileStatusColor = context.getColorAttr(android.R.attr.textColorSecondary)
                    }

                    1 -> {
                        profileStatusText = getString(R.string.available, profile.ping)
                        profileStatusColor = context.getColorAttr(R.attr.testAvailableColor)
                    }

                    2 -> {
                        profileStatusText = profile.error
                        profileStatusColor = context.getColorAttr(R.attr.testFailColor)
                    }

                    3 -> {
                        val err = profile.error ?: ""
                        val msg = Protocols.genFriendlyMsg(err)
                        profileStatusText = if (msg != err) msg else getString(R.string.unavailable)
                        profileStatusColor = context.getColorAttr(R.attr.testFailColor)
                    }
                }

                val text = SpannableStringBuilder().apply {
                    append("\n" + profile.displayName())
                    append("\n")
                    append(
                        profile.displayType(),
                        ForegroundColorSpan(context.getProtocolColor(profile.type)),
                        SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    append(" ")
                    append(
                        profileStatusText,
                        ForegroundColorSpan(profileStatusColor),
                        SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    append("\n")
                }

                binding.nowTesting.text = text
                binding.progress.text = "$progress / $proxyN"
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Suppress("EXPERIMENTAL_API_USAGE")
    fun pingTest(icmpPing: Boolean) {
        if (DataStore.runningTest) return else DataStore.runningTest = true
        val test = TestDialog()
        val dialog = test.builder.show()
        val testJobs = mutableListOf<Job>()
        // Group display name for the minimize->notification callback. The DAO read must stay off
        // the main thread, so it is fetched inside the worker below and cached here for the
        // callback (which fires later, on user tap).
        var groupName = ""

        val mainJob = runOnDefaultDispatcher {
            val group = openGroup() ?: return@runOnDefaultDispatcher
            groupName = group.displayName()
            val profilesList = ProfileDatabase.proxyDao.getByGroup(group.id).filter {
                if (icmpPing) {
                    if (it.requireBean().canICMPing()) {
                        return@filter true
                    }
                } else {
                    if (it.requireBean().canTCPing()) {
                        return@filter true
                    }
                }
                return@filter false
            }
            test.proxyN = profilesList.size
            val profiles = ConcurrentLinkedQueue(profilesList.map(::ProfileTestResult))
            repeat(DataStore.connectionTestConcurrent) {
                testJobs.add(
                    launch(Dispatchers.IO) {
                        while (isActive) {
                            val result = profiles.poll() ?: break
                            val profile = result.profile

                            profile.status = 0
                            val address = resolvePingAddress(profile)
                            if (!isActive) break
                            if (address == null) {
                                profile.status = 2
                                profile.error = app.getString(R.string.connection_test_domain_not_found)
                                test.update(result)
                                continue
                            }
                            try {
                                if (icmpPing) {
                                    // libcore protects the socket so the echo bypasses a running VPN.
                                    val latency = Libcore.icmpPing(address, DataStore.connectionTestTimeout)
                                    if (!isActive) break
                                    profile.status = 1
                                    profile.ping = latency
                                    profile.error = null
                                    test.update(result)
                                } else {
                                    val socket =
                                        NekoBox.underlyingNetwork?.socketFactory?.createSocket()
                                            ?: Socket()
                                    try {
                                        socket.soTimeout = 3000
                                        socket.bind(InetSocketAddress(0))
                                        val start = SystemClock.elapsedRealtime()
                                        socket.connect(
                                            InetSocketAddress(
                                                address,
                                                profile.requireBean().serverPort!!,
                                            ),
                                            3000,
                                        )
                                        if (!isActive) break
                                        profile.status = 1
                                        profile.ping = (SystemClock.elapsedRealtime() - start).toInt()
                                        // Clear any stale error from a previous failed test.
                                        profile.error = null
                                        test.update(result)
                                    } finally {
                                        socket.closeQuietly()
                                    }
                                }
                            } catch (e: Exception) {
                                if (!isActive) break
                                val message = e.readableMessage

                                if (icmpPing) {
                                    profile.status = 2
                                    profile.error = getString(
                                        if (message.contains("timeout")) R.string.connection_test_timeout_error else R.string.connection_test_unreachable,
                                    )
                                } else {
                                    profile.status = 2
                                    when {
                                        !message.contains("failed:") ->
                                            profile.error =
                                                getString(R.string.connection_test_timeout_error)

                                        else -> when {
                                            message.contains("ECONNREFUSED") -> {
                                                profile.error =
                                                    getString(R.string.connection_test_refused)
                                            }

                                            message.contains("ENETUNREACH") -> {
                                                profile.error =
                                                    getString(R.string.connection_test_unreachable)
                                            }

                                            else -> {
                                                profile.status = 3
                                                profile.error = message
                                            }
                                        }
                                    }
                                }
                                test.update(result)
                            }
                        }
                    },
                )
            }

            testJobs.joinAll()

            runOnMainDispatcher {
                test.cancel()
            }
        }
        test.cancel = {
            if (test.dialogStatus.getAndSet(2) != 2) {
                try {
                    dialog.dismiss()
                } catch (e: IllegalStateException) {
                    Logs.w(e)
                }
                runOnDefaultDispatcher {
                    try {
                        finishConnectionTests(mainJob, test.results)
                    } catch (e: Exception) {
                        Logs.w(e)
                    }
                }
            }
        }
        mainJob.invokeOnCompletion { runOnMainDispatcher { test.cancel() } }
        test.minimize = {
            test.dialogStatus.set(1)
            test.notification = ConnectionTestNotification(
                dialog.context,
                "[$groupName] ${getString(R.string.connection_test)}",
            )
            dialog.hide()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun urlTest() {
        if (DataStore.runningTest) return else DataStore.runningTest = true
        val test = TestDialog()
        val dialog = test.builder.show()
        val testJobs = mutableListOf<Job>()
        // See pingTest(): cache the group name off-thread for the minimize callback.
        var groupName = ""

        // Profiles whose Tailscale node the running service drives are measured through it.
        val service = { (activity as? MainActivity)?.connection?.service }
        val mainJob = runOnDefaultDispatcher {
            val group = openGroup() ?: return@runOnDefaultDispatcher
            groupName = group.displayName()
            val profilesList = ProfileDatabase.proxyDao.getByGroup(group.id)
            test.proxyN = profilesList.size
            val profiles = ConcurrentLinkedQueue(profilesList.map(::ProfileTestResult))
            repeat(DataStore.connectionTestConcurrent) {
                testJobs.add(
                    launch(Dispatchers.IO) {
                        val urlTest = UrlTest(service) // note: this is NOT in bg process
                        while (isActive) {
                            val result = profiles.poll() ?: break
                            val profile = result.profile
                            profile.status = 0

                            try {
                                val latency = if (proxies) {
                                    val (latency, trace) = urlTest.check(profile, EXIT_TRACE, result.prepareUrlTest())
                                    ConnectionProbes.traceAddress(trace)?.let { exit ->
                                        ServerCountries.lookUp(listOf(exit))
                                        DataStore.rememberProxyExits(mapOf(profile.id to exit))
                                    }
                                    latency
                                } else {
                                    urlTest.doTest(profile, result.prepareUrlTest())
                                }
                                profile.status = 1
                                profile.ping = latency
                                // Clear any stale error from a previous failed test so a now-passing
                                // profile doesn't keep showing an old failure message.
                                profile.error = null
                            } catch (e: PluginManager.PluginNotFoundException) {
                                if (!isActive) break
                                profile.status = 2
                                profile.error = e.readableMessage
                            } catch (e: Exception) {
                                // A canceled test (dialog cancel / teardown) kills the sidecar
                                // mid-handshake and throws here. Don't record that as a profile
                                // failure - it isn't one. The pingTest path guards the same way.
                                if (!isActive) break
                                profile.status = 3
                                profile.error = e.readableMessage
                            }

                            if (!isActive) break
                            test.update(result)
                        }
                    },
                )
            }

            testJobs.joinAll()

            runOnMainDispatcher {
                test.cancel()
            }
        }
        test.cancel = {
            if (test.dialogStatus.getAndSet(2) != 2) {
                try {
                    dialog.dismiss()
                } catch (e: IllegalStateException) {
                    Logs.w(e)
                }
                runOnDefaultDispatcher {
                    try {
                        finishConnectionTests(mainJob, test.results)
                    } catch (e: Exception) {
                        Logs.w(e)
                    }
                }
            }
        }
        mainJob.invokeOnCompletion { runOnMainDispatcher { test.cancel() } }
        test.minimize = {
            test.dialogStatus.set(1)
            test.notification = ConnectionTestNotification(
                dialog.context,
                "[$groupName] ${getString(R.string.connection_test)}",
            )
            dialog.hide()
        }
    }

    inner class GroupPagerAdapter :
        FragmentStateAdapter(this),
        ProfileManager.Listener,
        GroupManager.Listener {

        var selectedGroupIndex = 0
        var groupList: ArrayList<ProxyGroup> = ArrayList()
        var groupFragments: HashMap<Long, ConfigurationGroupFragment> = HashMap()
        private var disposed = false

        fun dispose() {
            disposed = true
        }

        fun reload(now: Boolean = false) {
            if (disposed) return
            if (!select) {
                groupPager.unregisterOnPageChangeCallback(updateSelectedCallback)
            }

            runOnDefaultDispatcher {
                var newGroupList = ArrayList(ProfileDatabase.groupDao.allGroups().filter(::shows))
                if (newGroupList.isEmpty() && !proxies) {
                    ProfileDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
                    newGroupList = ArrayList(ProfileDatabase.groupDao.allGroups().filter(::shows))
                }
                newGroupList.find { it.ungrouped }?.let {
                    if (newGroupList.size > 1 && ProfileDatabase.proxyDao.countByGroup(it.id) == 0L) {
                        newGroupList.remove(it)
                    }
                }
                // Favorites leads the tabs while it holds a profile; deleted profiles drop out of it.
                val favorites = DataStore.favoriteProfiles
                val liveFavorites = ProfileDatabase.proxyDao.getEntities(favorites).mapTo(HashSet()) { it.id }
                // Only pins known to be gone are dropped; one added meanwhile stays.
                val gone = favorites.toSet() - liveFavorites
                if (gone.isNotEmpty()) DataStore.updateFavoriteProfiles { it - gone }
                if (liveFavorites.isNotEmpty() && !proxies) newGroupList.add(0, ProxyGroup(id = FAVORITES_GROUP_ID, name = app.getString(R.string.favorites)))

                var selectedGroup = selectedItem?.groupId ?: if (proxies) DataStore.selectedProxyList else DataStore.currentGroupId()
                var set = false
                if (selectedGroup > 0L) {
                    val index = newGroupList.indexOfFirst { it.id == selectedGroup }
                    if (index >= 0) {
                        selectedGroupIndex = index
                        set = true
                    }
                }
                val firstGroup = newGroupList.indexOfFirst { it.id != FAVORITES_GROUP_ID }
                if (!set && firstGroup >= 0) {
                    selectedGroupIndex = firstGroup
                    selectedGroup = newGroupList[firstGroup].id
                    if (openGroupId != selectedGroup) {
                        openGroupId = selectedGroup
                    }
                    set = true
                }
                // An open Favorites tab stays open when its list changes.
                if (favoritesShown && liveFavorites.isNotEmpty()) selectedGroupIndex = 0

                val runFunc = if (now) activity?.let { it::runOnUiThread } else groupPager::post
                if (runFunc != null) {
                    runFunc {
                        if (!disposed) {
                            val liveIds = newGroupList.mapTo(HashSet()) { it.id }
                            groupFragments.keys.retainAll(liveIds)
                            groupList = newGroupList
                            notifyDataSetChanged()
                            updateGroupPicker()
                            showEmpty()
                            if (set) groupPager.setCurrentItem(selectedGroupIndex, false)
                            if (!select) {
                                groupPager.registerOnPageChangeCallback(updateSelectedCallback)
                            }
                        }
                    }
                }
            }
        }

        init {
            reload(true)
        }

        override fun getItemCount(): Int = groupList.size

        override fun createFragment(position: Int): Fragment = ConfigurationGroupFragment().apply {
            proxyGroup = groupList[position]
            groupFragments[proxyGroup.id] = this
            if (position == selectedGroupIndex) {
                selected = true
            }
        }

        override fun getItemId(position: Int): Long = groupList[position].id

        override fun containsItem(itemId: Long): Boolean = groupList.any { it.id == itemId }

        override suspend fun groupAdd(group: ProxyGroup) {
            if (disposed || !shows(group)) return
            tabLayout.post {
                if (disposed) return@post
                groupList.add(group)
                notifyItemInserted(groupList.size - 1)
                updateGroupPicker()
                showEmpty()
                tabLayout.getTabAt(groupList.size - 1)?.select()
            }
        }

        override suspend fun groupRemoved(groupId: Long) {
            if (disposed) return
            tabLayout.post {
                if (disposed) return@post
                val index = groupList.indexOfFirst { it.id == groupId }
                if (index == -1) return@post
                groupFragments.remove(groupId)
                groupList.removeAt(index)
                notifyItemRemoved(index)
                updateGroupPicker()
                showEmpty()
            }
        }

        override suspend fun groupUpdated(group: ProxyGroup) {
            if (disposed) return
            tabLayout.post {
                if (disposed) return@post
                val index = groupList.indexOfFirst { it.id == group.id }
                if (index == -1) return@post
                // Keep the type current too: the menu reads it.
                groupList[index] = group
                tabLayout.getTabAt(index)?.text = group.displayName()
            }
        }

        override suspend fun groupUpdated(groupId: Long) {
            onMainDispatcher {
                if (!disposed && (groupId == FAVORITES_GROUP_ID || groupList.none { it.id == groupId })) reload()
            }
        }

        override suspend fun onAdd(profile: ProxyEntity) {
            if (disposed) return
            if (groupList.find { it.id == profile.groupId } == null) {
                if (ProfileDatabase.groupDao.getById(profile.groupId)?.let(::shows) != true) return
                openGroupId = profile.groupId
                reload()
            }
        }

        override suspend fun onUpdated(data: TrafficData) = Unit

        override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) = Unit

        override suspend fun onRemoved(groupId: Long, profileId: Long) {
            if (disposed) return
            val group = groupList.find { it.id == groupId } ?: return
            if (group.ungrouped && ProfileDatabase.proxyDao.countByGroup(groupId) == 0L) {
                reload()
            }
        }
    }

    // internal (not private) so the extracted ConfigurationGroupFragment can pass this launcher
    // to startFilesForResult via `parentFragment as ConfigurationFragment`.
    internal val exportConfig =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
            if (data != null) {
                runOnDefaultDispatcher {
                    try {
                        (requireActivity() as MainActivity).contentResolver.openOutputStream(data)!!
                            .bufferedWriter()
                            .use {
                                it.write(DataStore.serverConfig)
                            }
                        onMainDispatcher {
                            snackbar(getString(R.string.action_export_msg)).show()
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }
                }
            }
        }

    private fun cancelSearch(searchView: SearchView) {
        searchView.onActionViewCollapsed()
        searchView.clearFocus()
        updatePaging()
    }

    /** Whether the search field is open. */
    val searchOpen get() = searchView?.isIconified == false

    /** Whether the first group is on screen, with no group to its left. */
    val atFirstGroup get() = ::groupPager.isInitialized && groupPager.currentItem == 0

    // A sideways swipe moves between the groups, except in the profile picker and while searching.
    private fun updatePaging() {
        if (::groupPager.isInitialized) groupPager.isUserInputEnabled = !select && !searchOpen
    }

    /** Closes the search if it is open; false when it was not. */
    fun closeSearch(): Boolean {
        val searchView = searchView?.takeIf { !it.isIconified } ?: return false
        cancelSearch(searchView)
        return true
    }

    private inner class SearchResultAdapter : ListAdapter<SearchResult, SearchResultHolder>(SearchResultDiff) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = SearchResultHolder(
            LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false),
        )

        override fun onBindViewHolder(holder: SearchResultHolder, position: Int) = holder.bind(getItem(position))
    }

    private inner class SearchResultHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(android.R.id.text1)
        private val detail: TextView = view.findViewById(android.R.id.text2)

        fun bind(result: SearchResult) {
            name.text = result.name
            detail.text = result.detail
            itemView.setOnClickListener {
                searchView?.let(::cancelSearch)
                jumpToProfile(result.id)
            }
        }
    }
}

private data class SearchResult(val id: Long, val name: String, val detail: String)

private object SearchResultDiff : DiffUtil.ItemCallback<SearchResult>() {
    override fun areItemsTheSame(oldItem: SearchResult, newItem: SearchResult) = oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: SearchResult, newItem: SearchResult) = oldItem == newItem
}

/**
 * Closes this resource, ignoring any exception. Replacement for OkHttp's internal
 * `closeQuietly()` extension so we don't depend on an unstable `okhttp3.internal` API.
 */
private fun Closeable.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
    }
}
