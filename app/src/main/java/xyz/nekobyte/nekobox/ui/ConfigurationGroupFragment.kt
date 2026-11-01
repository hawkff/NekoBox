package xyz.nekobyte.nekobox.ui

import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.TooltipCompat
import androidx.core.os.BundleCompat
import androidx.core.view.MenuCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.size
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import xyz.nekobyte.nekobox.GroupLayout
import xyz.nekobyte.nekobox.GroupOrder
import xyz.nekobyte.nekobox.GroupType
import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.Protocols
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.aidl.TrafficData
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.GroupManager
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProfileManager
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.databinding.LayoutProfileActionsBinding
import xyz.nekobyte.nekobox.databinding.LayoutProfileBinding
import xyz.nekobyte.nekobox.databinding.LayoutProfileListBinding
import xyz.nekobyte.nekobox.databinding.LayoutProfileSectionBinding
import xyz.nekobyte.nekobox.fmt.toUniversalLink
import xyz.nekobyte.nekobox.group.MullvadRelays
import xyz.nekobyte.nekobox.ktx.EaseOut
import xyz.nekobyte.nekobox.ktx.FixedGridLayoutManager
import xyz.nekobyte.nekobox.ktx.FixedLinearLayoutManager
import xyz.nekobyte.nekobox.ktx.Logs
import xyz.nekobyte.nekobox.ktx.alert
import xyz.nekobyte.nekobox.ktx.app
import xyz.nekobyte.nekobox.ktx.byteCount
import xyz.nekobyte.nekobox.ktx.getColorAttr
import xyz.nekobyte.nekobox.ktx.itemMoveIndices
import xyz.nekobyte.nekobox.ktx.onDefaultDispatcher
import xyz.nekobyte.nekobox.ktx.onMainDispatcher
import xyz.nekobyte.nekobox.ktx.readableMessage
import xyz.nekobyte.nekobox.ktx.resolveStyle
import xyz.nekobyte.nekobox.ktx.runOnDefaultDispatcher
import xyz.nekobyte.nekobox.ktx.scrollTo
import xyz.nekobyte.nekobox.ktx.showAllowingStateLoss
import xyz.nekobyte.nekobox.ktx.tryToShow
import xyz.nekobyte.nekobox.widget.CardHighlight
import xyz.nekobyte.nekobox.widget.QRCodeDialog
import xyz.nekobyte.nekobox.widget.UndoSnackbarManager
import xyz.nekobyte.nekobox.widget.attachItemTouch
import java.util.Objects
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.collections.set

private data class ProfileListSubmission(
    val ids: List<Long>,
    val profiles: Map<Long, ProxyEntity>,
    val stamps: Map<Long, ProfileRowStamp>,
    val scrollIndex: Int = -1,
    // The section headers among [ids], and the section of every profile, shown or folded away.
    val sections: Map<Long, SectionRow> = emptyMap(),
    val sectionKeys: Map<Long, String> = emptyMap(),
    // The master order the list was built from.
    val master: List<Long> = emptyList(),
    // The servers of Mullvad's tree on show, each with its label: one-line rows across the list.
    val labels: Map<Long, String> = emptyMap(),
)

/**
 * A header in the list: a section of profiles whose names differ by a number, or a country ([level] 0) or city (1) of
 * Mullvad's list ([tree]), which also says how many of its proxies [answered] a check (-1 before one) and the [best]
 * latency, and whether it [holdsSelected] the selected proxy.
 */
private class SectionRow(
    val key: String,
    val title: String,
    val count: Int,
    val folded: Boolean,
    val tree: Boolean = false,
    val level: Int = 0,
    val spoken: String = title,
    val answered: Int = -1,
    val best: Int = 0,
    val holdsSelected: Boolean = false,
) {
    fun stamp() = Objects.hash(title, count, folded, tree, level, answered, best, holdsSelected)
}

// Header IDs count down from here, below every profile ID and RecyclerView's NO_ID.
private const val SECTION_ID_BASE = -100L
private const val SECTION_VIEW = 100
private const val LINE_VIEW = 101

// A row scrolls to the top of the list; the first of a section takes its header along.
private fun withHeader(ids: List<Long>, index: Int) = if (index > 0 && ids[index - 1] < 0) index - 1 else index

/** The pager tab of profiles pinned from any group. It is not a group: it never becomes the selected group. */
internal const val FAVORITES_GROUP_ID = -2L

class ConfigurationGroupFragment : Fragment() {

    lateinit var proxyGroup: ProxyGroup
    var selected = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = LayoutProfileListBinding.inflate(inflater).root

    var undoManager: UndoSnackbarManager<ProxyEntity>? = null
    var adapter: ConfigurationAdapter? = null

    private val isFavorites get() = proxyGroup.id == FAVORITES_GROUP_ID

    // Favorites mixes groups, so each profile opens for editing as its own group allows.
    @Volatile
    private var subscriptionGroupIds = emptySet<Long>()

    // A profile to show once the list has loaded, such as a search result in a group opened for it.
    private var pendingScrollId = 0L

    fun scrollToProfile(profileId: Long) {
        val index = adapter?.configurationIdList?.indexOf(profileId) ?: -1
        if (index >= 0) {
            configurationListView.scrollTo(withHeader(adapter!!.configurationIdList, index), true)
        } else {
            pendingScrollId = profileId
            adapter?.reveal(profileId)
        }
    }

    // Folds are kept per group, one per line.
    private fun foldKey(sectionKey: String) = "${proxyGroup.id}\t${sectionKey.replace('\n', ' ')}"

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        if (::proxyGroup.isInitialized) {
            outState.putParcelable("proxyGroup", proxyGroup)
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)

        savedInstanceState?.let { BundleCompat.getParcelable(it, "proxyGroup", ProxyGroup::class.java) }?.also {
            proxyGroup = it
            onViewCreated(requireView(), null)
        }
    }

    private val isEnabled: Boolean
        get() {
            return DataStore.serviceState.let { it.canStop || it == BaseService.State.Stopped }
        }

    lateinit var layoutManager: RecyclerView.LayoutManager
    private lateinit var itemTouchHelper: ItemTouchHelper

    private fun setupItemTouchHelper() {
        if (select) return

        if (::itemTouchHelper.isInitialized) {
            itemTouchHelper.attachToRecyclerView(null)
        }

        itemTouchHelper = configurationListView.attachItemTouch(object : ItemTouchHelper.SimpleCallback(0, 0) {
            override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int = if (viewHolder is ConfigurationHolder && isEnabled && adapter?.canDrag() == true) {
                if (DataStore.groupLayoutMode == GroupLayout.TWO_COLUMNS) {
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                } else {
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN
                }
            } else {
                0
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            }

            override fun canDropOver(recyclerView: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = target is ConfigurationHolder && adapter?.sameSection(current.bindingAdapterPosition, target.bindingAdapterPosition) == true

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                val fromPosition = viewHolder.bindingAdapterPosition
                val toPosition = target.bindingAdapterPosition

                if (fromPosition == RecyclerView.NO_POSITION || toPosition == RecyclerView.NO_POSITION) {
                    return false
                }

                adapter?.move(fromPosition, toPosition)
                return true
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) adapter?.beginDrag()
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                adapter?.commitMove()
            }
        })
    }
    lateinit var configurationListView: RecyclerView
    private lateinit var emptyHint: TextView

    val select by lazy {
        try {
            (parentFragment as ConfigurationFragment).select
        } catch (e: Exception) {
            Logs.e(e)
            false
        }
    }
    val selectedItem by lazy {
        try {
            (parentFragment as ConfigurationFragment).selectedItem
        } catch (e: Exception) {
            Logs.e(e)
            null
        }
    }

    override fun onResume() {
        super.onResume()

        if (::configurationListView.isInitialized && configurationListView.size == 0) {
            configurationListView.adapter = adapter
            runOnDefaultDispatcher {
                adapter?.reloadProfiles()
            }
        } else if (!::configurationListView.isInitialized) {
            onViewCreated(requireView(), null)
        }
        checkOrderMenu()
        configurationListView.requestFocus()
    }

    fun checkOrderMenu() {
        if (select) return

        val pf = requireParentFragment() as? ToolbarFragment ?: return
        val menu = pf.toolbar.menu
        val origin = menu.findItem(R.id.action_order_origin)
        val byName = menu.findItem(R.id.action_order_by_name)
        val byDelay = menu.findItem(R.id.action_order_by_delay)
        when (proxyGroup.order) {
            GroupOrder.ORIGIN -> {
                origin.isChecked = true
            }

            GroupOrder.BY_NAME -> {
                byName.isChecked = true
            }

            GroupOrder.BY_DELAY -> {
                byDelay.isChecked = true
            }
        }

        fun updateTo(order: Int) {
            if (proxyGroup.order == order) return
            runOnDefaultDispatcher {
                proxyGroup.order = order
                // Onto the stored row: a page restored from saved state lacks a list's first hop, exit and server list.
                // Favorites has no row.
                GroupManager.updateGroup(ProfileDatabase.groupDao.getById(proxyGroup.id)?.apply { this.order = order } ?: proxyGroup)
            }
        }

        origin.setOnMenuItemClickListener {
            it.isChecked = true
            updateTo(GroupOrder.ORIGIN)
            true
        }
        byName.setOnMenuItemClickListener {
            it.isChecked = true
            updateTo(GroupOrder.BY_NAME)
            true
        }
        byDelay.setOnMenuItemClickListener {
            it.isChecked = true
            updateTo(GroupOrder.BY_DELAY)
            true
        }

        val layouts = mapOf(
            R.id.action_layout_compact to GroupLayout.COMPACT,
            R.id.action_layout_single to GroupLayout.CARDS,
            R.id.action_layout_double to GroupLayout.TWO_COLUMNS,
        )
        val current = DataStore.groupLayoutMode
        for ((id, layout) in layouts) {
            val item = menu.findItem(id)
            item.isChecked = layout == current
            item.setOnMenuItemClickListener {
                it.isChecked = true
                if (DataStore.groupLayoutMode != layout) {
                    DataStore.groupLayoutMode = layout
                    (parentFragment as? ConfigurationFragment)?.switchAllGroupFragmentsLayout()
                }
                true
            }
        }
        menu.findItem(R.id.action_sections).apply {
            isChecked = DataStore.profileSections
            setOnMenuItemClickListener {
                DataStore.profileSections = !DataStore.profileSections
                it.isChecked = DataStore.profileSections
                (parentFragment as? ConfigurationFragment)?.switchAllGroupFragmentsLayout()
                true
            }
        }
    }

    private fun setupLayoutManager() {
        layoutManager = if (DataStore.groupLayoutMode == GroupLayout.TWO_COLUMNS) {
            FixedGridLayoutManager(configurationListView, 2).apply {
                // A section header spans both columns, and so does each row of Mullvad's tree.
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int) = if (adapter?.isFullSpan(position) == true) spanCount else 1
                }
            }
        } else {
            FixedLinearLayoutManager(configurationListView)
        }
    }

    fun switchLayoutMode() {
        setupLayoutManager()
        configurationListView.layoutManager = layoutManager

        setupItemTouchHelper()

        adapter?.onLayoutModeChanged()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!::proxyGroup.isInitialized) return

        val binding = LayoutProfileListBinding.bind(view)
        configurationListView = binding.configurationList
        emptyHint = binding.emptyHint
        setupLayoutManager()
        configurationListView.layoutManager = layoutManager
        adapter?.let {
            ProfileManager.removeListener(it)
            GroupManager.removeListener(it)
            it.dispose()
        }
        adapter = ConfigurationAdapter()
        // Mullvad's list opens on country headers and its cities on one-line rows, made in the background while it loads.
        if (MullvadRelays.isRelayList(proxyGroup)) {
            (activity as? MainActivity)?.let { it.rowWarmup.fill(it.lifecycleScope, R.layout.layout_profile_section to 16, R.layout.layout_profile_compact to 8) }
        }
        ProfileManager.addListener(adapter!!)
        GroupManager.addListener(adapter!!)
        configurationListView.adapter = adapter
        configurationListView.setItemViewCacheSize(20)
        FastScrollerBuilder(configurationListView).useMd2Style().build()

        // Hide the docked FAB while scrolling down (so it never overlaps the
        // bottom card) and bring it back on upward scroll or when the list
        // settles. Mirrors the stats bar's hideOnScroll behavior. Only act in
        // stable states (Stopped/Connected) so we don't fight the FAB's own
        // show/hide animation during Connecting/Stopping.
        configurationListView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            private fun stableState(): Boolean = DataStore.serviceState.let {
                it == BaseService.State.Stopped || it == BaseService.State.Connected
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!stableState()) return
                val fab = (activity as? MainActivity)?.binding?.fab ?: return
                if (dy > 4) {
                    fab.hide()
                } else if (dy < -4) {
                    fab.show()
                }
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE && stableState()) {
                    (activity as? MainActivity)?.binding?.fab?.show()
                }
            }
        })

        if (!select) {
            undoManager = UndoSnackbarManager(activity as MainActivity, adapter!!)
            setupItemTouchHelper()
        }
    }

    override fun onDestroyView() {
        undoManager?.flush()
        undoManager = null
        adapter?.let {
            ProfileManager.removeListener(it)
            GroupManager.removeListener(it)
            it.dispose()
        }
        adapter = null
        if (::itemTouchHelper.isInitialized) itemTouchHelper.attachToRecyclerView(null)
        super.onDestroyView()
    }

    override fun onDestroy() {
        adapter?.let {
            ProfileManager.removeListener(it)
            GroupManager.removeListener(it)
        }

        super.onDestroy()

        undoManager?.flush()
    }

    inner class ConfigurationAdapter :
        RecyclerView.Adapter<RecyclerView.ViewHolder>(),
        ProfileManager.Listener,
        GroupManager.Listener,
        UndoSnackbarManager.Interface<ProxyEntity> {

        init {
            setHasStableIds(true)
        }

        // Rows of the previous layout are not reused in another.
        private var layoutMode = DataStore.groupLayoutMode

        var configurationIdList: MutableList<Long> = mutableListOf()
        val configurationList = HashMap<Long, ProxyEntity>()
        private val configurationStamps = HashMap<Long, ProfileRowStamp>()
        private val sectionRows = HashMap<Long, SectionRow>()
        private val sectionKeys = HashMap<Long, String>()
        private val lineLabels = HashMap<Long, String>()

        // A header keeps its ID for its key, so a fold that opens rows above it does not make it a new item.
        private val headerIds = HashMap<String, Long>()
        private var nextHeaderId = SECTION_ID_BASE

        // Where each host's server stands in Mullvad's list, read once per host.
        private val places = HashMap<String, MullvadRelays.Place?>()

        // Mullvad's list sorted into its tree, and the IDs and kept stamps it was sorted from: a fold shows it again.
        private var tree: RelayTree.Tree? = null
        private var treeKey = LongArray(0)
        private var displayedMaster: List<Long> = emptyList()
        private val displayPositions = HashMap<Long, Int>()
        private val masterIds = ArrayList<Long>()
        private val masterProfiles = HashMap<Long, ProxyEntity>()
        private val masterStamps = HashMap<Long, Int>()
        private val hostView = configurationListView
        private val reloadGeneration = AtomicLong()
        private var displayGeneration = 0L
        private var diffJob: Job? = null
        private var filterQuery = ""
        private var filterPending = false
        private var masterUpdatePending = false
        private var displayPending = false
        private var dragInProgress = false
        private var disposed = false
        private val filterRunnable = Runnable {
            if (!disposed) {
                filterPending = false
                submitMasterList()
            }
        }
        private val masterUpdateRunnable = Runnable {
            if (!disposed) {
                masterUpdatePending = false
                sortMasterProfiles()
                submitMasterList()
            }
        }

        // Keep this aligned with ConfigurationHolder.bind. TrafficData updates both the entities
        // and these cached stamps directly, without scheduling a list diff for every traffic tick.
        // A server of Mullvad's tree shows its label and its check alone.
        private fun contentStamp(profile: ProxyEntity) = if (showsTree() && MullvadRelays.place(profile.requireBean().serverAddress) != null) {
            Objects.hash(profile.displayName(), profile.requireBean().serverAddress, profile.ping, profile.status, profile.error)
        } else {
            fullStamp(profile)
        }

        private fun fullStamp(profile: ProxyEntity) = Objects.hash(
            profile.displayName(),
            profile.displayType(),
            profile.displayAddress(),
            profile.requireBean().name,
            profile.ping,
            profile.status,
            profile.error,
            profile.tx,
            profile.rx,
            profile.lifetimeTx,
            profile.lifetimeRx,
            ProfileWords.connection(profile),
            rowCountry(profile),
            rowAddress(profile),
        )

        private fun profileStateStamp(contentStamp: Int, profileId: Long): Int {
            val host = parentFragment as? ConfigurationFragment
            return Objects.hash(
                contentStamp,
                host?.isSelectedProfile(profileId) == true,
                host?.isRunningProfile(profileId) == true,
                host?.isConnectingProfile(profileId) == true,
            )
        }

        private fun profileStateStamp(profile: ProxyEntity) = profileStateStamp(contentStamp(profile), profile.id)

        private fun matchesFilter(profile: ProxyEntity): Boolean {
            if (filterQuery.isEmpty()) return true
            val lower = filterQuery.lowercase()
            return profile.displayName().lowercase().contains(lower) ||
                profile.displayType().lowercase().contains(lower) ||
                profile.displayAddress().lowercase().contains(lower)
        }

        private fun rebuildDisplayPositions() {
            displayPositions.clear()
            configurationIdList.forEachIndexed { index, id -> displayPositions[id] = index }
        }

        private fun applySubmission(submission: ProfileListSubmission) {
            configurationIdList.clear()
            configurationIdList.addAll(submission.ids)
            configurationList.clear()
            configurationList.putAll(submission.profiles)
            configurationStamps.clear()
            configurationStamps.putAll(submission.stamps)
            sectionRows.clear()
            sectionRows.putAll(submission.sections)
            sectionKeys.clear()
            sectionKeys.putAll(submission.sectionKeys)
            lineLabels.clear()
            lineLabels.putAll(submission.labels)
            displayedMaster = submission.master
            pendingRemovalIds.removeAll { id -> id !in configurationList && id !in masterProfiles }
            rebuildDisplayPositions()
            updateEmptyHint()
        }

        private fun updateEmptyHint() {
            emptyHint.isVisible = configurationIdList.isEmpty()
            if (configurationIdList.isNotEmpty()) return
            emptyHint.text = when {
                filterQuery.isNotEmpty() -> getString(R.string.profile_list_no_matches)
                select -> getString(R.string.profile_list_empty)
                else -> getString(R.string.profile_list_empty) + "\n\n" + getString(R.string.profile_list_empty_hint)
            }
        }

        private fun submit(submission: ProfileListSubmission) {
            if (disposed || dragInProgress) return
            val generation = ++displayGeneration
            displayPending = true
            val oldIds = configurationIdList.toList()
            val oldStamps = configurationStamps.toMap()
            if (oldIds.isEmpty()) {
                applySubmission(submission)
                displayPending = false
                notifyDataSetChanged()
                scrollAfterSubmission(submission)
                return
            }
            diffJob?.cancel()
            diffJob = runOnDefaultDispatcher {
                val diff = try {
                    DiffUtil.calculateDiff(
                        ProfileDiffCallback(oldIds, submission.ids, oldStamps, submission.stamps),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        if (disposed || dragInProgress || generation != displayGeneration) {
                            return@onMainDispatcher
                        }
                        applySubmission(submission)
                        displayPending = false
                        // Exceptional fallback only: keep RecyclerView state consistent if DiffUtil
                        // rejects an otherwise valid immutable snapshot.
                        notifyDataSetChanged()
                        scrollAfterSubmission(submission)
                    }
                    return@runOnDefaultDispatcher
                }
                onMainDispatcher {
                    if (disposed || dragInProgress || generation != displayGeneration) return@onMainDispatcher
                    applySubmission(submission)
                    displayPending = false
                    diff.dispatchUpdatesTo(this@ConfigurationAdapter)
                    scrollAfterSubmission(submission)
                }
            }
        }

        private fun scrollAfterSubmission(submission: ProfileListSubmission) {
            val pending = submission.ids.indexOf(pendingScrollId).takeIf { pendingScrollId != 0L && it >= 0 }?.let { withHeader(submission.ids, it) }
            if (pending != null) {
                pendingScrollId = 0L
                configurationListView.scrollTo(pending, true)
            } else if (submission.scrollIndex >= 0) {
                configurationListView.scrollTo(submission.scrollIndex, true)
            }
        }

        // Sections follow the order on show; a search lists its matches without them.
        private fun showsSections() = DataStore.profileSections && !isFavorites && filterQuery.isEmpty()

        // Mullvad's list shows its servers by country and city, the way its servers stand, whatever the sections setting.
        private fun showsTree() = !isFavorites && filterQuery.isEmpty() && MullvadRelays.isRelayList(proxyGroup)

        /** Whether the row at [position] spans the grid's columns: a header, or a server of Mullvad's tree. */
        fun isFullSpan(position: Int) = configurationIdList.getOrNull(position)?.let { it < 0 || it in lineLabels } == true

        private fun headerId(key: String) = headerIds.getOrPut(key) { nextHeaderId-- }

        private fun placeOf(profile: ProxyEntity): MullvadRelays.Place? {
            val host = profile.requireBean().serverAddress.orEmpty()
            return if (host in places) places[host] else MullvadRelays.place(host).also { places[host] = it }
        }

        /**
         * [reloaded]: the list a reload just read, whose stamps came off the main thread a moment ago. Without [restamp]
         * (a fold), rows already on show keep their stamps: only rows come and go.
         */
        private fun submitMasterList(scrollIndex: Int = -1, scrollToId: Long = 0L, reloaded: Boolean = false, restamp: Boolean = true) {
            if (disposed || dragInProgress) return
            val visible = masterIds.filter { id -> masterProfiles[id]?.let(::matchesFilter) == true }
            val tree = showsTree()
            val sections = if (!tree && showsSections()) ProfileSections.of(visible.map { masterProfiles.getValue(it).displayName() }) else emptyList()
            val rows = HashMap<Long, SectionRow>()
            val keys = HashMap<Long, String>()
            val labels = HashMap<Long, String>()
            val folded = if (sections.isEmpty()) emptySet() else DataStore.foldedSections
            val ids = if (tree) {
                treeIds(visible, rows, labels)
            } else if (sections.isEmpty()) {
                visible
            } else {
                buildList {
                    sections.forEach { section ->
                        val headerId = headerId(section.key)
                        val members = section.members.map(visible::get)
                        var isFolded = foldKey(section.key) in folded
                        // A jump to a profile opens its section, also when the jump came before the list loaded.
                        if (isFolded && pendingScrollId in members) {
                            DataStore.updateFoldedSections { it - foldKey(section.key) }
                            isFolded = false
                        }
                        members.forEach { keys[it] = section.key }
                        val title = section.title ?: getString(if (section.allFlags) R.string.profile_section_countries else R.string.profile_section_other)
                        rows[headerId] = SectionRow(section.key, title, members.size, isFolded)
                        add(headerId)
                        if (!isFolded) addAll(members)
                    }
                }
            }
            // The profiles on show: a tree folded to its countries shows a few of hundreds.
            val profiles = HashMap<Long, ProxyEntity>(ids.size)
            for (id in ids) if (id > 0) masterProfiles[id]?.let { profiles[id] = it }
            val stamps = buildDisplayStamps(ids, profiles, reuseKept = reloaded, restamp = restamp, labels = labels) + rows.mapValues { (_, row) ->
                ProfileRowStamp(row.stamp(), reserveMiddleRow = false, doubleColumn = false)
            }
            submit(
                ProfileListSubmission(
                    ids = ids,
                    profiles = profiles,
                    stamps = stamps,
                    scrollIndex = ids.indexOf(scrollToId).takeIf { scrollToId != 0L && it >= 0 }?.let { withHeader(ids, it) } ?: scrollIndex.coerceAtMost(ids.lastIndex),
                    sections = rows,
                    sectionKeys = keys,
                    master = masterIds.toList(),
                    labels = labels,
                ),
            )
        }

        /**
         * Mullvad's list as countries and cities: the headers into [rows], each server's label into [labels]. A fold the
         * user made is kept as "key" and an opening as "+key"; without either, a header takes its default.
         */
        private fun treeIds(visible: List<Long>, rows: MutableMap<Long, SectionRow>, labels: MutableMap<Long, String>): List<Long> {
            // A jump to a proxy opens its country and city for good, or Other for one added by hand, also when it came
            // before the list loaded.
            masterProfiles[pendingScrollId]?.takeIf { pendingScrollId in visible }?.let { target ->
                val opened = placeOf(target)?.let { listOf(it.country, "${it.country}-${it.city}") } ?: listOf(RelayTree.REST)
                DataStore.updateFoldedSections { folds -> folds - opened.map(::foldKey).toSet() + opened.map { foldKey("+$it") } }
            }
            val folds = DataStore.foldedSections
            // The tree follows the list's order and each proxy's name, place, check and selection, all in its kept stamp.
            val key = LongArray(visible.size * 2)
            visible.forEachIndexed { index, id ->
                key[2 * index] = id
                key[2 * index + 1] = (masterStamps[id] ?: 0).toLong()
            }
            val sorted = tree?.takeIf { key.contentEquals(treeKey) } ?: run {
                val host = parentFragment as? ConfigurationFragment
                val selected = visible.firstOrNull { host?.isSelectedProfile(it) == true } ?: 0L
                val servers = visible.map { id -> masterProfiles.getValue(id).let { RelayTree.Server(id, placeOf(it), it.displayName(), it.status, it.ping) } }
                RelayTree.group(servers, selected, { MullvadRelays.countryName(it) }, getString(R.string.profile_section_other)).also {
                    tree = it
                    treeKey = key
                }
            }
            val entries = RelayTree.show(sorted) { key, byDefault ->
                when {
                    foldKey("+$key") in folds -> true
                    foldKey(key) in folds -> false
                    else -> byDefault
                }
            }
            return entries.map { entry ->
                when (entry) {
                    is RelayTree.Header -> headerId(entry.key).also { id ->
                        rows[id] = SectionRow(
                            entry.key, entry.title, entry.count, !entry.open,
                            tree = true, level = entry.level, spoken = entry.spoken,
                            answered = entry.answered, best = entry.best, holdsSelected = entry.holdsSelected,
                        )
                    }

                    is RelayTree.Row -> entry.id.also { id -> entry.label?.let { labels[id] = it } }
                }
            }
        }

        fun toggleSection(headerId: Long) {
            val row = sectionRows[headerId] ?: return
            val key = foldKey(row.key)
            DataStore.updateFoldedSections { folds ->
                if (row.tree) {
                    // A header of the tree keeps the user's choice either way, as its default differs.
                    val opened = foldKey("+${row.key}")
                    folds - key - opened + if (row.folded) opened else key
                } else if (key in folds) {
                    folds - key
                } else {
                    folds + key
                }
            }
            submitMasterList(restamp = false)
        }

        /** Shows the pending jump target: building the list opens its folded section. */
        fun reveal(profileId: Long) {
            // Not loaded yet: the load's own list opens it.
            if (profileId in masterProfiles) submitMasterList()
        }

        /** Whether the rows at two positions are profiles of one section, where a drag may drop. */
        fun sameSection(from: Int, to: Int): Boolean {
            val first = configurationIdList.getOrNull(from) ?: return false
            val second = configurationIdList.getOrNull(to) ?: return false
            return first > 0 && second > 0 && sectionKeys[first] == sectionKeys[second]
        }

        private fun sortMasterProfiles() {
            val sorted = when (proxyGroup.order) {
                GroupOrder.BY_NAME -> masterProfiles.values.sortedBy { it.displayName() }

                GroupOrder.BY_DELAY -> masterProfiles.values.sortedBy {
                    if (it.status == 1) it.ping else 114514
                }

                else -> masterProfiles.values.sortedBy { it.userOrder }
            }
            masterIds.clear()
            masterIds.addAll(sorted.map { it.id })
        }

        private fun scheduleMasterUpdate() {
            ++displayGeneration
            diffJob?.cancel()
            displayPending = false
            masterUpdatePending = true
            hostView.removeCallbacks(masterUpdateRunnable)
            hostView.postDelayed(masterUpdateRunnable, 50L)
        }

        fun canDrag() = !disposed &&
            !isFavorites &&
            filterQuery.isEmpty() &&
            !filterPending &&
            !masterUpdatePending &&
            !displayPending &&
            displayedMaster == masterIds &&
            // Mullvad's list keeps the server list's order: each update puts it back.
            !showsTree() &&
            // Sections stand in the master order, so only that order can be dragged into shape.
            (sectionRows.isEmpty() || proxyGroup.order == GroupOrder.ORIGIN)

        fun beginDrag() {
            if (!canDrag()) return
            reloadGeneration.incrementAndGet()
            hostView.removeCallbacks(filterRunnable)
            hostView.removeCallbacks(masterUpdateRunnable)
            filterPending = false
            masterUpdatePending = false
            displayPending = false
            dragInProgress = true
            ++displayGeneration
            diffJob?.cancel()
        }

        fun dispose() {
            disposed = true
            dragInProgress = false
            reloadGeneration.incrementAndGet()
            ++displayGeneration
            diffJob?.cancel()
            filterPending = false
            masterUpdatePending = false
            displayPending = false
            hostView.removeCallbacks(filterRunnable)
            hostView.removeCallbacks(masterUpdateRunnable)
        }

        private fun getItem(profileId: Long): ProxyEntity = configurationList[profileId]!!

        private fun getItemAt(index: Int) = getItem(configurationIdList[index])

        // Two columns give the country a row of its own, so the middle row holds the address alone.
        private fun hasMiddleRow(profile: ProxyEntity): Boolean {
            val showTraffic = profile.rx + profile.tx != 0L
            val middle = if (layoutMode == GroupLayout.TWO_COLUMNS) rowAddress(profile).orEmpty() else rowDetails(profile)
            return !((!showTraffic || profile.status <= 0) && middle.isBlank())
        }

        private fun hasCountryRow(profile: ProxyEntity) = layoutMode == GroupLayout.TWO_COLUMNS && rowCountry(profile) != null

        private fun displaySpanCount() = (layoutManager as? FixedGridLayoutManager)?.spanCount ?: 1

        private fun buildDisplayStamps(
            ids: List<Long>,
            profiles: Map<Long, ProxyEntity>,
            positions: Iterable<Int> = ids.indices,
            reuseKept: Boolean = false,
            restamp: Boolean = true,
            labels: Map<Long, String> = lineLabels,
        ): Map<Long, ProfileRowStamp> {
            // Right after a reload, a profile's kept stamp was made off the main thread a moment ago: reuse it rather
            // than stamp the whole list again on the main thread. Later the country or address a row shows can change
            // without its profile changing, so every other list is stamped afresh, except by a fold: it only adds and
            // removes rows, so each row takes its kept stamp, which every change to the profile or its selection
            // updates, also while a list with that change is still on its way.
            fun base(id: Long): Int {
                val profile = profiles[id]
                return masterStamps[id]?.takeIf { !restamp || (reuseKept && profile != null && masterProfiles[id] === profile) }
                    ?: profile?.let(::profileStateStamp)
                    ?: configurationStamps[id]?.content
                    ?: masterStamps[id]
                    ?: 0
            }
            // A server of Mullvad's tree is a line of its own, outside the grid's pairs.
            val (lines, cells) = positions.partition { ids[it] in labels }
            val lineStamps = lines.associate { position ->
                val id = ids[position]
                id to ProfileRowStamp(Objects.hash(base(id), labels[id]), reserveMiddleRow = false, doubleColumn = false)
            }
            if (cells.isEmpty()) return lineStamps
            val baseStamps = cells.associate { position -> ids[position] to base(ids[position]) }
            // Two columns ask each profile about its neighbor too: one answer per profile.
            val countryRows = HashMap<Long, Boolean>()
            val middleRows = HashMap<Long, Boolean>()
            return lineStamps + buildProfileRowStamps(
                ids,
                baseStamps,
                displaySpanCount(),
                cells,
                hasCountryRow = { id -> countryRows.getOrPut(id) { profiles[id]?.let(::hasCountryRow) == true } },
            ) { id ->
                middleRows.getOrPut(id) { profiles[id]?.let(::hasMiddleRow) == true }
            }
        }

        override fun getItemViewType(position: Int) = configurationIdList[position].let { id ->
            when {
                id < 0 -> SECTION_VIEW
                id in lineLabels -> LINE_VIEW
                else -> layoutMode
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            // The first list takes the rows MainActivity made while it started.
            val warmup = (activity as? MainActivity)?.rowWarmup
            if (viewType == SECTION_VIEW) {
                val view = warmup?.take(R.layout.layout_profile_section) ?: inflater.inflate(R.layout.layout_profile_section, parent, false)
                return SectionHolder(LayoutProfileSectionBinding.bind(view))
            }
            // A server of Mullvad's tree: the compact row on one line, set in under its city.
            if (viewType == LINE_VIEW) {
                val view = warmup?.take(R.layout.layout_profile_compact) ?: inflater.inflate(R.layout.layout_profile_compact, parent, false)
                view.updateLayoutParams<ViewGroup.MarginLayoutParams> { marginStart = resources.getDimensionPixelSize(R.dimen.relay_row_indent) }
                return ConfigurationHolder(LayoutProfileBinding.bind(view), GroupLayout.COMPACT, line = true)
            }
            // The compact row holds the card's views, so the card's binding reads it too.
            val layout = if (viewType == GroupLayout.COMPACT) R.layout.layout_profile_compact else R.layout.layout_profile
            val view = warmup?.take(layout) ?: inflater.inflate(layout, parent, false)
            return ConfigurationHolder(LayoutProfileBinding.bind(view), viewType)
        }

        override fun getItemId(position: Int): Long = configurationIdList[position]

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is SectionHolder) {
                val id = configurationIdList[position]
                sectionRows[id]?.let { holder.bind(id, it) }
                return
            }
            holder as ConfigurationHolder
            try {
                val id = configurationIdList[position]
                val rowStamp = configurationStamps[id] ?: return
                holder.bind(getItemAt(position), rowStamp.reserveMiddleRow, rowStamp.reserveCountryRow, lineLabels[id])
            } catch (ignored: NullPointerException) { // when group deleted
            }
        }

        override fun getItemCount(): Int = configurationIdList.size

        fun refreshProfileState(profileIds: Set<Long>) {
            if (disposed) return
            var changed = false
            profileIds.forEach { profileId ->
                masterProfiles[profileId]?.let { profile ->
                    masterStamps[profileId] = profileStateStamp(profile)
                    changed = true
                }
            }
            if (changed) submitMasterList()
        }

        fun onLayoutModeChanged() {
            if (disposed) return
            layoutMode = DataStore.groupLayoutMode
            ++displayGeneration
            diffJob?.cancel()
            displayPending = false
            submitMasterList()
        }

        fun profileById(profileId: Long): ProxyEntity? {
            if (profileId in pendingRemovalIds) return null
            return masterProfiles[profileId] ?: configurationList[profileId]
        }

        private val updated = HashSet<ProxyEntity>()
        private val pendingRemovalIds = HashSet<Long>()

        fun filter(name: String) {
            if (disposed) return
            filterQuery = name
            hostView.removeCallbacks(filterRunnable)
            ++displayGeneration
            diffJob?.cancel()
            displayPending = false
            if (name.isEmpty()) {
                filterPending = false
                submitMasterList()
            } else {
                filterPending = true
                hostView.postDelayed(filterRunnable, 150L)
            }
        }

        fun move(from: Int, to: Int) {
            if (!dragInProgress || from == to) return

            // In sections the moved rows trade their orders, which leaves the folded ones in place.
            if (layoutMode == GroupLayout.TWO_COLUMNS && sectionRows.isEmpty()) {
                moveDualColumn(from, to)
            } else {
                moveLinear(from, to)
            }
            if (sectionRows.isEmpty()) {
                masterIds.clear()
                masterIds.addAll(configurationIdList)
            } else {
                // Folded sections are not in the list; the new orders put the moved rows in place among them.
                masterIds.sortBy { masterProfiles[it]?.userOrder ?: Long.MAX_VALUE }
            }
            displayedMaster = masterIds.toList()
            rebuildDisplayPositions()
        }

        private fun moveLinear(from: Int, to: Int) {
            val first = getItemAt(from)
            var previousOrder = first.userOrder
            val step = if (from < to) 1 else -1
            for (i in itemMoveIndices(from, to)) {
                val next = getItemAt(i + step)
                val order = next.userOrder
                next.userOrder = previousOrder
                previousOrder = order
                configurationIdList[i] = next.id
                updated.add(next)
            }
            first.userOrder = previousOrder
            configurationIdList[to] = first.id
            updated.add(first)
            notifyItemMoved(from, to)
        }

        private fun moveDualColumn(from: Int, to: Int) {
            val draggedItemId = configurationIdList[from]

            configurationIdList.removeAt(from)
            configurationIdList.add(to, draggedItemId)

            for (i in configurationIdList.indices) {
                val item = getItem(configurationIdList[i])
                val newOrder = (i + 1).toLong()
                if (item.userOrder != newOrder) {
                    item.userOrder = newOrder
                    updated.add(item)
                }
            }

            notifyItemMoved(from, to)
        }

        fun commitMove() {
            val snapshot = updated.toList()
            updated.clear()
            dragInProgress = false
            if (snapshot.isEmpty()) {
                submitMasterList()
                return
            }
            displayPending = true
            runOnDefaultDispatcher {
                try {
                    ProfileDatabase.proxyDao.updateProxy(snapshot)
                } catch (e: Exception) {
                    Logs.w(e)
                }
                try {
                    reloadProfiles()
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        if (!disposed) displayPending = false
                    }
                }
            }
        }

        fun removeById(profileId: Long): Pair<Int, ProxyEntity>? {
            if (disposed || profileId in pendingRemovalIds) return null
            val removed = findItemById(configurationIdList, configurationList, profileId) ?: return null
            reloadGeneration.incrementAndGet()
            pendingRemovalIds.add(profileId)
            masterIds.remove(profileId)
            masterProfiles.remove(profileId)
            masterStamps.remove(profileId)
            submitMasterList()
            return removed
        }

        fun removeProfiles(profileIds: Collection<Long>) {
            if (profileIds.isEmpty()) return
            reloadGeneration.incrementAndGet()
            val ids = profileIds.toHashSet()
            pendingRemovalIds.addAll(ids)
            masterIds.removeAll(ids)
            ids.forEach {
                masterProfiles.remove(it)
                masterStamps.remove(it)
            }
            submitMasterList()
        }

        override fun undo(actions: List<Pair<Int, ProxyEntity>>) {
            reloadGeneration.incrementAndGet()
            for ((_, item) in actions) {
                pendingRemovalIds.remove(item.id)
                masterProfiles[item.id] = item
                masterStamps[item.id] = profileStateStamp(item)
            }
            sortMasterProfiles()
            submitMasterList()
        }

        override fun commit(actions: List<Pair<Int, ProxyEntity>>) {
            val profiles = actions.map { it.second }
            val profileIds = profiles.map { it.id }
            if (isFavorites) {
                // Removing from Favorites unpins the profile; it stays in its group.
                runOnDefaultDispatcher {
                    DataStore.updateFavoriteProfiles { it - profileIds.toSet() }
                    GroupManager.postReload(FAVORITES_GROUP_ID)
                }
                return
            }
            runOnDefaultDispatcher {
                try {
                    for (entity in profiles) {
                        ProfileManager.deleteProfile(entity.groupId, entity.id)
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher { pendingRemovalIds.removeAll(profileIds) }
                    reloadProfiles()
                }
            }
        }

        override suspend fun onAdd(profile: ProxyEntity) {
            if (profile.groupId != proxyGroup.id) return
            onDefaultDispatcher { ServerCountries.lookUp(listOf(ProfileWords.serverIp(profile))) }
            onMainDispatcher {
                if (disposed) return@onMainDispatcher
                reloadGeneration.incrementAndGet()
                masterProfiles[profile.id] = profile
                masterStamps[profile.id] = profileStateStamp(profile)
                scheduleMasterUpdate()
            }
        }

        override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) {
            if (profile.groupId != proxyGroup.id && !isFavorites) return
            onDefaultDispatcher { ServerCountries.lookUp(listOf(ProfileWords.serverIp(profile))) }
            onMainDispatcher {
                if (disposed || profile.id in pendingRemovalIds) return@onMainDispatcher
                if (isFavorites && profile.id !in masterProfiles) return@onMainDispatcher
                reloadGeneration.incrementAndGet()
                val oldProfile = masterProfiles[profile.id] ?: configurationList[profile.id]
                if (noTraffic && oldProfile != null) {
                    profile.rx = oldProfile.rx
                    profile.tx = oldProfile.tx
                }
                masterProfiles[profile.id] = profile
                masterStamps[profile.id] = profileStateStamp(profile)
                scheduleMasterUpdate()
            }
        }

        private fun applyTraffic(data: TrafficData) {
            masterProfiles[data.id]?.let {
                it.rx = data.rx
                it.tx = data.tx
                masterStamps[data.id] = profileStateStamp(it)
            }
            configurationList[data.id]?.let {
                it.rx = data.rx
                it.tx = data.tx
            }
        }

        private fun rebindVisible(position: Int) {
            val expectedId = configurationIdList.getOrNull(position) ?: return
            val profile = configurationList[expectedId] ?: return
            val rowStamp = configurationStamps[expectedId] ?: return
            val holder = layoutManager.findViewByPosition(position)
                ?.let { configurationListView.getChildViewHolder(it) } as? ConfigurationHolder
                ?: return
            if (holder.bindingAdapterPosition != position || !holder.isBoundTo(expectedId)) return
            holder.bind(profile, rowStamp.reserveMiddleRow, rowStamp.reserveCountryRow, lineLabels[expectedId])
        }

        private fun applyTrafficUpdates(updates: List<TrafficData>) {
            val changedIds = HashSet<Long>()
            updates.forEach { data ->
                val master = masterProfiles[data.id]
                val displayed = configurationList[data.id]
                if ((master == null || (master.rx == data.rx && master.tx == data.tx)) &&
                    (displayed == null || (displayed.rx == data.rx && displayed.tx == data.tx))
                ) {
                    return@forEach
                }
                applyTraffic(data)
                changedIds.add(data.id)
            }
            if (changedIds.isEmpty()) return

            if (diffJob?.isActive == true) {
                ++displayGeneration
                diffJob?.cancel()
                displayPending = false
                submitMasterList()
                return
            }

            val positions = HashSet<Int>()
            for (id in changedIds) {
                val position = displayPositions[id] ?: continue
                positions.addAll(profileRowRange(configurationIdList, position, displaySpanCount()))
            }
            val newStamps = buildDisplayStamps(configurationIdList, configurationList, positions)
            for ((id, stamp) in newStamps) {
                val oldStamp = configurationStamps.put(id, stamp)
                if (id in changedIds || oldStamp != stamp) {
                    rebindVisible(displayPositions.getValue(id))
                }
            }
        }

        override suspend fun onUpdated(data: TrafficData) {
            try {
                onMainDispatcher {
                    if (!disposed) applyTrafficUpdates(listOf(data))
                }
            } catch (e: Exception) {
                Logs.w(e)
            }
        }

        override suspend fun onUpdated(data: List<TrafficData>) {
            try {
                onMainDispatcher {
                    if (!disposed) applyTrafficUpdates(data)
                }
            } catch (e: Exception) {
                Logs.w(e)
            }
        }

        override suspend fun onRemoved(groupId: Long, profileId: Long) {
            if (groupId != proxyGroup.id && !isFavorites) return
            onMainDispatcher {
                if (disposed) return@onMainDispatcher
                reloadGeneration.incrementAndGet()
                pendingRemovalIds.add(profileId)
                masterIds.remove(profileId)
                masterProfiles.remove(profileId)
                masterStamps.remove(profileId)
                submitMasterList()
            }
        }

        override suspend fun groupAdd(group: ProxyGroup) = Unit
        override suspend fun groupRemoved(groupId: Long) = Unit

        // Favorites shows profiles of every group, so any group's update can change it.
        override suspend fun groupUpdated(group: ProxyGroup) {
            if (isFavorites) return reloadProfiles()
            if (group.id != proxyGroup.id) return
            onMainDispatcher { proxyGroup = group }
            reloadProfiles()
        }

        override suspend fun groupUpdated(groupId: Long) {
            if (isFavorites) return reloadProfiles()
            if (groupId != proxyGroup.id) return
            val group = ProfileDatabase.groupDao.getById(groupId) ?: return
            onMainDispatcher { proxyGroup = group }
            reloadProfiles()
        }

        suspend fun reloadProfiles() {
            val generation = reloadGeneration.incrementAndGet()
            // A page restored from saved state lacks what a list keeps besides its name, Mullvad's server list among it.
            if (!isFavorites) {
                // A newer reload may have put a newer group in place meanwhile.
                onDefaultDispatcher { ProfileDatabase.groupDao.getById(proxyGroup.id) }?.let { stored ->
                    onMainDispatcher { if (!disposed && generation == reloadGeneration.get()) proxyGroup = stored }
                }
            }
            val groupId = proxyGroup.id
            val order = proxyGroup.order
            val (newProfiles, newProfileIds, baseStamps) = onDefaultDispatcher {
                var profiles = if (isFavorites) {
                    val ids = DataStore.favoriteProfiles
                    subscriptionGroupIds = ProfileDatabase.groupDao.subscriptions().mapTo(HashSet()) { it.id }
                    ProfileDatabase.proxyDao.getEntities(ids).sortedBy { ids.indexOf(it.id) }
                } else {
                    ProfileDatabase.proxyDao.getByGroup(groupId)
                }
                // The countries of where checked proxies leave from too, so their rows have them on the first bind.
                val exits = DataStore.proxyExits.let { known -> profiles.mapNotNull { known[it.id] } }
                ServerCountries.lookUp(profiles.map(ProfileWords::serverIp) + exits)
                profiles = when (order) {
                    GroupOrder.BY_NAME -> profiles.sortedBy { it.displayName() }

                    GroupOrder.BY_DELAY -> profiles.sortedBy {
                        if (it.status == 1) it.ping else 114514
                    }

                    else -> profiles
                }
                Triple(
                    profiles.associateBy { it.id },
                    profiles.map { it.id },
                    profiles.associate { it.id to contentStamp(it) },
                )
            }
            onMainDispatcher {
                if (disposed || generation != reloadGeneration.get()) return@onMainDispatcher
                hostView.removeCallbacks(masterUpdateRunnable)
                masterUpdatePending = false
                masterIds.clear()
                masterIds.addAll(newProfileIds.filterNot(pendingRemovalIds::contains))
                masterProfiles.clear()
                masterIds.forEach { id ->
                    newProfiles[id]?.let { masterProfiles[id] = it }
                }
                masterStamps.clear()
                masterIds.forEach { id ->
                    baseStamps[id]?.let { stamp ->
                        masterStamps[id] = profileStateStamp(stamp, id)
                    }
                }
                val selectedId = selectedItem?.id ?: DataStore.selectedProxy
                // The open page shows its selected profile, unless its section is folded; others start at the top.
                submitMasterList(if (masterIds.isNotEmpty()) 0 else -1, if (selected) selectedId else 0L, reloaded = true)
            }
        }
    }

    val profileAccess = Mutex()
    val reloadAccess = Mutex()

    // Where a checked proxy left from, on the Proxies screen.
    private fun exitOf(profile: ProxyEntity) = DataStore.proxyExits[profile.id]?.takeIf { (parentFragment as? ConfigurationFragment)?.proxies == true }

    // "exit 203.0.113.7", made once per address: rows ask on every bind and stamp, from more than one thread.
    private val exitLabels = ConcurrentHashMap<String, String>()

    // The country under the name: of the address a checked proxy leaves from, else of the server's address.
    private fun rowCountry(profile: ProxyEntity): String? {
        val exit = exitOf(profile) ?: return ProfileWords.country(profile)
        return ProfileWords.countryOf(exit)
    }

    // The address under the name, when settings ask for it; a nameless profile already shows it as its name. A
    // checked proxy shows the address it leaves from instead.
    private fun rowAddress(profile: ProxyEntity) = exitOf(profile)?.let { exit -> exitLabels.getOrPut(exit) { app.getString(R.string.proxies_exit, exit) } } ?: profile.displayAddress().takeIf {
        (parentFragment as? ConfigurationFragment)?.alwaysShowAddress == true && !profile.requireBean().name.isNullOrBlank()
    }

    // Under the name: the country of the server's address, and the address.
    private fun rowDetails(profile: ProxyEntity) = listOfNotNull(rowCountry(profile), rowAddress(profile)).joinToString(" · ")

    /**
     * A section's header: its title and size; a tap folds or unfolds it. In Mullvad's list it is a country or a city to
     * open, set in by its level, with what a check found: the best latency, and how many answered when some did not.
     */
    private inner class SectionHolder(private val binding: LayoutProfileSectionBinding) : RecyclerView.ViewHolder(binding.root) {
        private var headerId = 0L
        private val context = binding.root.context
        private val countryType = context.resolveStyle(com.google.android.material.R.attr.textAppearanceTitleMedium)
        private val cityType = context.resolveStyle(com.google.android.material.R.attr.textAppearanceBodyLarge)
        private val indent = context.resources.getDimensionPixelSize(R.dimen.relay_level_indent)
        private val edge = context.resources.getDimensionPixelSize(R.dimen.relay_header_edge)

        init {
            binding.root.setOnClickListener { adapter?.toggleSection(headerId) }
            ViewCompat.setAccessibilityHeading(binding.root, true)
        }

        // A country or a city is a row of its own, not a heading over cards: list type, even padding, set in by level.
        private fun styleTreeRow(row: SectionRow) {
            TextViewCompat.setTextAppearance(binding.title, if (row.level == 0) countryType else cityType)
            binding.title.setTextColor(context.getColorAttr(if (row.holdsSelected) R.attr.primaryOrTextPrimary else android.R.attr.textColorPrimary))
            binding.root.updatePaddingRelative(start = binding.root.paddingEnd + row.level * indent, top = edge, bottom = edge)
            val answered = row.answered in 0 until row.count
            binding.count.text = if (answered) context.getString(R.string.proxies_answered, row.answered, row.count) else row.count.toString()
            binding.count.setTextColor(context.getColorAttr(if (row.answered == 0) R.attr.testFailColor else android.R.attr.textColorSecondary))
            binding.best.isVisible = row.answered > 0
            if (row.answered > 0) binding.best.text = context.getString(R.string.available, row.best)
        }

        fun bind(id: Long, row: SectionRow) {
            headerId = id
            binding.title.text = row.title
            binding.count.text = row.count.toString()
            if (row.tree) styleTreeRow(row)
            binding.chevron.setImageResource(if (row.folded) R.drawable.ic_expand_more else R.drawable.ic_expand_less)
            binding.root.contentDescription = listOfNotNull(
                row.spoken,
                // Mullvad's list is proxies; sections elsewhere hold profiles of any kind.
                context.resources.getQuantityString(if (row.tree) R.plurals.proxies_found else R.plurals.group_profiles, row.count, row.count),
                context.getString(R.string.proxies_answered_spoken, row.answered, row.count).takeIf { row.tree && row.answered >= 0 },
                context.getString(R.string.proxies_fastest_spoken, row.best).takeIf { row.tree && row.answered > 0 },
                context.getString(R.string.proxies_holds_selected).takeIf { row.tree && row.holdsSelected },
            ).joinToString(", ")
            ViewCompat.setStateDescription(binding.root, context.getString(if (row.folded) R.string.group_collapsed else R.string.group_expanded))
            ViewCompat.replaceAccessibilityAction(
                binding.root,
                AccessibilityActionCompat.ACTION_CLICK,
                context.getString(if (row.folded) R.string.section_expand else R.string.section_collapse),
                null,
            )
        }
    }

    /** A profile's row in [layout]; a [line] is a server of Mullvad's tree, its label and latency on one line. */
    inner class ConfigurationHolder(val binding: LayoutProfileBinding, private val layout: Int, private val line: Boolean = false) : RecyclerView.ViewHolder(binding.root) {

        val view: View get() = binding.root

        lateinit var entity: ProxyEntity

        private fun showShareMenu(anchor: View, profileId: Long) {
            val profile = adapter?.profileById(profileId) ?: return
            val popup = PopupMenu(requireContext(), anchor)
            popup.menuInflater.inflate(R.menu.profile_share_menu, popup.menu)
            // Favorites is a tab on Home, which keeps to servers.
            if ((parentFragment as? ConfigurationFragment)?.proxies == true) popup.menu.removeItem(R.id.action_favorite)
            popup.menu.findItem(R.id.action_favorite)?.setTitle(
                if (profileId in DataStore.favoriteProfiles) R.string.favorite_remove else R.string.favorite_add,
            )

            when {
                !profile.haveStandardLink() -> {
                    popup.menu.findItem(R.id.action_group_qr).subMenu?.removeItem(R.id.action_standard_qr)
                    popup.menu.findItem(R.id.action_group_clipboard).subMenu?.removeItem(
                        R.id.action_standard_clipboard,
                    )
                }

                !profile.haveLink() -> {
                    popup.menu.removeItem(R.id.action_group_qr)
                    popup.menu.removeItem(R.id.action_group_clipboard)
                }
            }

            if (!profile.canBuild()) {
                popup.menu.removeItem(R.id.action_config_export_clipboard)
                popup.menu.removeItem(R.id.action_config_export_file)
            }
            popup.setOnMenuItemClickListener { handleShareMenu(it, profileId) }
            popup.show()
        }

        val profileName: TextView = binding.profileName
        val profileType: TextView = binding.profileType
        val profileAddress: TextView = binding.profileAddress
        val profileCountry: TextView = binding.profileCountry
        val profileStatus: TextView = binding.profileStatus

        val trafficText: TextView = binding.trafficText
        private val card = view as MaterialCardView
        private val highlight = CardHighlight(card)

        // The one-column card shows its buttons; two columns, compact rows and the Proxies screen keep them under ⋮,
        // so their cards never build them.
        private val actions = if (layout == GroupLayout.CARDS && (parentFragment as? ConfigurationFragment)?.proxies != true) {
            LayoutProfileActionsBinding.bind(binding.actions.inflate())
        } else {
            null
        }
        val editButton: ImageView? = actions?.edit
        val doubleColumnMenuButton: ImageView = binding.doubleColumnMenu
        val shareLayout: ImageView? = actions?.share
        val removeButton: ImageView? = actions?.remove

        init {
            view.setOnClickListener {
                val profileId = entity.id
                if (select) {
                    (requireActivity() as ConfigurationFragment.SelectCallback).returnProfile(profileId)
                } else {
                    selectProfile(profileId)
                }
            }
            profileStatus.setOnClickListener {
                val profile = adapter?.profileById(entity.id) ?: return@setOnClickListener
                if (profile.status == 3) alert(profile.error ?: "<?>").tryToShow()
            }
            profileStatus.isFocusable = false
            editButton?.setOnClickListener { openSettings(it, entity.id) }
            removeButton?.setOnClickListener { requestRemove(entity.id) }
            doubleColumnMenuButton.setOnClickListener { showDoubleColumnMenu(it, entity.id) }
            shareLayout?.setOnClickListener { showShareMenu(it, entity.id) }
        }

        fun isBoundTo(profileId: Long) = ::entity.isInitialized && entity.id == profileId

        private fun openSettings(anchor: View, profileId: Long) {
            val host = parentFragment as? ConfigurationFragment ?: return
            if (host.isRunningProfile(profileId)) return
            val profile = adapter?.profileById(profileId) ?: return
            val subscription = if (isFavorites) profile.groupId in subscriptionGroupIds else proxyGroup.type == GroupType.SUBSCRIPTION
            val intent = profile.settingIntent(anchor.context, subscription) ?: return
            anchor.context.startActivity(intent)
        }

        private fun performRemove(profileId: Long) {
            val host = parentFragment as? ConfigurationFragment ?: return
            if (select || (!isFavorites && host.isRunningProfile(profileId))) return
            val manager = undoManager ?: return
            val removed = adapter?.removeById(profileId) ?: return
            manager.remove(removed)
        }

        private fun requestRemove(profileId: Long) {
            val host = parentFragment as? ConfigurationFragment ?: return
            if (select || (!isFavorites && host.isRunningProfile(profileId))) return
            if (DataStore.confirmProfileDelete && !isFavorites) {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.delete_confirm_prompt)
                    .setPositiveButton(R.string.yes) { _, _ -> performRemove(profileId) }
                    .setNegativeButton(R.string.no, null)
                    .show()
            } else {
                performRemove(profileId)
            }
        }

        private fun showDoubleColumnMenu(anchor: View, profileId: Long) {
            val host = parentFragment as? ConfigurationFragment ?: return
            val popup = PopupMenu(requireContext(), anchor)
            popup.menuInflater.inflate(R.menu.double_column_item_menu, popup.menu)
            if (select) popup.menu.removeItem(R.id.action_delete)
            // A proxy connects at once or becomes a group's exit; Remove comes last, apart.
            if (host.proxies && !select) {
                popup.menu.clear()
                popup.menu.add(0, R.string.proxies_connect, 0, R.string.proxies_connect)
                popup.menu.add(0, R.id.action_edit, 1, R.string.edit)
                popup.menu.add(0, R.string.proxies_use_as_exit, 2, R.string.proxies_use_as_exit)
                popup.menu.add(0, R.id.action_share, 3, R.string.share)
                popup.menu.add(1, R.id.action_delete, 4, R.string.delete)
                MenuCompat.setGroupDividerEnabled(popup.menu, true)
            }
            val running = host.isRunningProfile(profileId)
            popup.menu.findItem(R.id.action_edit)?.isVisible = adapter?.profileById(profileId)?.haveSettings() == true
            popup.menu.findItem(R.id.action_edit)?.isEnabled = !running
            popup.menu.findItem(R.id.action_delete)?.isEnabled = !running
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_edit -> {
                        openSettings(anchor, profileId)
                        true
                    }

                    R.id.action_share -> {
                        showShareMenu(anchor, profileId)
                        true
                    }

                    R.id.action_delete -> {
                        requestRemove(profileId)
                        true
                    }

                    R.string.proxies_connect -> {
                        selectProfile(profileId, connect = true)
                        true
                    }

                    R.string.proxies_use_as_exit -> {
                        host.useAsExit(profileId)
                        true
                    }

                    else -> false
                }
            }
            popup.show()
        }

        private fun selectProfile(profileId: Long, connect: Boolean = false) {
            val host = parentFragment as? ConfigurationFragment ?: return
            val profile = adapter?.profileById(profileId) ?: return
            runOnDefaultDispatcher {
                if (DataStore.serviceState.canStop && runningGroupSelectsAutomatically(profile.groupId)) {
                    onMainDispatcher { if (host.isAdded) host.snackbar(R.string.auto_select_active).show() }
                    return@runOnDefaultDispatcher
                }
                var update: Boolean
                var lastSelected: Long
                profileAccess.withLock {
                    update = DataStore.selectedProxy != profileId
                    lastSelected = DataStore.selectedProxy
                    DataStore.selectedProxy = profileId
                }
                host.refreshProfileState()

                if (connect && !DataStore.serviceState.started) {
                    if (update) ProfileManager.postUpdate(lastSelected, noTraffic = true)
                    onMainDispatcher { (activity as? MainActivity)?.startConnecting() }
                } else if (update) {
                    ProfileManager.postUpdate(lastSelected, noTraffic = true)
                    if (DataStore.serviceState.canStop && reloadAccess.tryLock()) {
                        try {
                            NekoBox.reloadService(profileId)
                        } finally {
                            reloadAccess.unlock()
                        }
                    }
                } else if (!connect && NekoBox.isTv) {
                    if (DataStore.serviceState.started) {
                        NekoBox.stopService()
                    } else {
                        NekoBox.startService()
                    }
                }
            }
        }

        // While urltest drives the running group, a manual pick inside it has nothing to act on.
        private fun runningGroupSelectsAutomatically(groupId: Long): Boolean {
            val running = ProfileDatabase.proxyDao.getById(DataStore.currentProfile) ?: return false
            val group = ProfileDatabase.groupDao.getById(groupId) ?: return false
            return running.groupId == groupId && group.isSelector && group.autoSelect
        }

        private fun statusTarget(): TouchDelegate {
            val bounds = Rect()
            profileStatus.getDrawingRect(bounds)
            card.offsetDescendantRectToMyCoords(profileStatus, bounds)
            val minimum = (48 * card.resources.displayMetrics.density).toInt()
            bounds.inset(-((minimum - bounds.width()) / 2).coerceAtLeast(0), -((minimum - bounds.height()) / 2).coerceAtLeast(0))
            return TouchDelegate(bounds, profileStatus)
        }

        /** [label] names a [line]: its server's number, or its own name when renamed. */
        fun bind(proxyEntity: ProxyEntity, reserveMiddleRow: Boolean, reserveCountryRow: Boolean, label: String? = null) {
            val pf = parentFragment as? ConfigurationFragment ?: return

            // A card shown again for the same profile changes in place: its selection and test result fade.
            val same = ::entity.isInitialized && entity.id == proxyEntity.id
            val shownStatus = profileStatus.text.toString()
            entity = proxyEntity

            profileName.text = label ?: proxyEntity.displayName()
            // A server of Mullvad's tree has its place in the headers above it and SOCKS5 like every other: one line.
            profileType.isGone = line
            if (!line) profileType.text = ProfileWords.connection(proxyEntity)

            val rx = proxyEntity.rx
            val tx = proxyEntity.tx
            val showTraffic = !line && rx + tx != 0L
            trafficText.isVisible = showTraffic
            if (showTraffic) {
                trafficText.text = view.context.getString(
                    R.string.traffic,
                    view.context.byteCount(tx),
                    view.context.byteCount(rx),
                )
            }

            // Read-only lifetime (all-time) totals surfaced as a long-press tooltip on the traffic
            // text (schema v12; accumulated by TrafficLooper). Zero layout change; shown only when
            // there is accumulated history.
            if (proxyEntity.lifetimeRx + proxyEntity.lifetimeTx > 0L) {
                TooltipCompat.setTooltipText(
                    trafficText,
                    view.context.getString(
                        R.string.lifetime_traffic,
                        view.context.byteCount(proxyEntity.lifetimeTx),
                        view.context.byteCount(proxyEntity.lifetimeRx),
                    ),
                )
            } else {
                TooltipCompat.setTooltipText(trafficText, null)
            }

            val country = if (line) null else rowCountry(proxyEntity)
            val address = if (line) null else rowAddress(proxyEntity)
            val details = listOfNotNull(country, address).joinToString(" · ")
            // Two columns give the country a row of its own, so the address keeps the card's width.
            val stacked = layout == GroupLayout.TWO_COLUMNS
            profileAddress.text = if (stacked) address.orEmpty() else details
            // A compact row keeps the traffic on its second line; a card without a test result shows it in the result's place.
            val compact = layout == GroupLayout.COMPACT
            val middle = if (stacked) address.orEmpty() else details
            val trafficRowEmpty = middle.isBlank() && (!showTraffic || (!compact && proxyEntity.status <= 0))
            (trafficText.parent as View).visibility = when {
                !trafficRowEmpty -> View.VISIBLE
                reserveMiddleRow -> View.INVISIBLE
                else -> View.GONE
            }
            // Beside a card that shows a country, one without keeps the row empty, so both keep one height.
            profileCountry.text = country.orEmpty()
            profileCountry.visibility = when {
                stacked && country != null -> View.VISIBLE
                stacked && reserveCountryRow -> View.INVISIBLE
                else -> View.GONE
            }

            if (proxyEntity.status <= 0) {
                if (showTraffic && !compact) {
                    profileStatus.text = trafficText.text
                    profileStatus.setTextColor(requireContext().getColorAttr(android.R.attr.textColorSecondary))
                    trafficText.text = ""
                } else {
                    profileStatus.text = ""
                }
            } else if (proxyEntity.status == 1) {
                profileStatus.text = getString(R.string.available, proxyEntity.ping)
                profileStatus.setTextColor(requireContext().getColorAttr(R.attr.testAvailableColor))
            } else {
                profileStatus.setTextColor(requireContext().getColorAttr(R.attr.testFailColor))
                if (proxyEntity.status == 2) {
                    profileStatus.text = proxyEntity.error
                }
            }

            if (proxyEntity.status == 3) {
                val err = proxyEntity.error ?: "<?>"
                val msg = Protocols.genFriendlyMsg(err)
                profileStatus.text = if (msg != err) msg else getString(R.string.unavailable)
            }
            // A screen reader hears a line's whole name, the country and city it leaves to the headers included.
            card.contentDescription = if (line) listOfNotNull(proxyEntity.displayName(), profileStatus.text?.toString()?.ifEmpty { null }).joinToString(", ") else null
            // Only a failed test has more to show, its error; the short line then answers a 48 dp tap.
            val failed = proxyEntity.status == 3
            profileStatus.isClickable = failed
            card.touchDelegate = null
            if (failed) profileStatus.post { card.touchDelegate = statusTarget() }
            if (same && profileStatus.text.toString() != shownStatus) {
                profileStatus.alpha = 0f
                profileStatus.animate().alpha(1f).setDuration(STATUS_FADE).setInterpolator(EaseOut).start()
            } else if (!same) {
                // A card shown for another profile ends the last one's fade.
                profileStatus.animate().cancel()
                profileStatus.alpha = 1f
            }

            // Two columns and compact rows keep the actions under ⋮.
            if (actions == null) {
                doubleColumnMenuButton.isVisible = true
            } else {
                // A button a profile lacks keeps its slot, so the buttons line up down the list.
                actions.share.visibility = slot(select, proxyEntity.type != ProxyEntity.TYPE_CHAIN)
                actions.edit.visibility = slot(select, proxyEntity.haveSettings())
                actions.remove.isGone = select
                doubleColumnMenuButton.isGone = true
            }

            val selected = pf.isSelectedProfile(proxyEntity.id)
            val running = pf.isRunningProfile(proxyEntity.id)
            editButton?.isEnabled = !running
            removeButton?.isEnabled = !running
            // Screen readers say "selected", and "Connecting…" while the service connects to this profile;
            // the outline is for the eye, and breathes until the connection is up.
            val connecting = pf.isConnectingProfile(proxyEntity.id)
            card.isSelected = selected
            ViewCompat.setStateDescription(card, getString(R.string.connecting).takeIf { connecting })
            highlight.show(selected || connecting, animate = same)
            highlight.breathe(connecting)
        }

        private fun showCode(link: String, name: String) {
            QRCodeDialog(link, name).showAllowingStateLoss(parentFragmentManager)
        }

        private fun export(link: String) {
            val success = NekoBox.trySetPrimaryClip(link)
            (activity as MainActivity).snackbar(
                if (success) R.string.action_export_msg else R.string.action_export_err,
            ).show()
        }

        private fun handleShareMenu(item: MenuItem, profileId: Long): Boolean {
            val profile = adapter?.profileById(profileId) ?: return true
            try {
                val name = profile.displayName().orEmpty()
                when (item.itemId) {
                    R.id.action_favorite -> runOnDefaultDispatcher {
                        DataStore.updateFavoriteProfiles { if (profileId in it) it - profileId else it + profileId }
                        GroupManager.postReload(FAVORITES_GROUP_ID)
                    }

                    R.id.action_standard_qr -> showCode(profile.toStdLink(), name)

                    R.id.action_standard_clipboard -> export(profile.toStdLink())

                    R.id.action_universal_qr -> showCode(profile.requireBean().toUniversalLink(), name)

                    R.id.action_universal_clipboard -> export(
                        profile.requireBean().toUniversalLink(),
                    )

                    R.id.action_config_export_clipboard -> export(profile.exportConfig().first)

                    R.id.action_config_export_file -> {
                        val config = profile.exportConfig()
                        DataStore.serverConfig = config.first
                        startFilesForResult(
                            (parentFragment as ConfigurationFragment).exportConfig,
                            config.second,
                        )
                    }
                }
            } catch (e: Exception) {
                Logs.w(e)
                (activity as MainActivity).snackbar(e.readableMessage).show()
            }
            return true
        }
    }
}

// A new test result fades in over this long.
private const val STATUS_FADE = 150L

// A row button: gone while picking a profile, invisible where this profile has nothing for it.
private fun slot(picking: Boolean, has: Boolean) = when {
    picking -> View.GONE
    has -> View.VISIBLE
    else -> View.INVISIBLE
}
